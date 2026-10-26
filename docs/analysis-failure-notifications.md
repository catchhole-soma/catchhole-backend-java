# 회차 분석 실패 Discord 알림

## 대상

모든 회원의 회차 분석에서 서버에 확정된 다음 실패를 기록한다.

| 실패 | 감지 위치 |
| --- | --- |
| 추출·전처리·완료 처리 실패 | `analysis_jobs.status → FAILED` |
| Worker lease 회수 한도 초과 | 같은 Job 실패 전이 (`WORKER_LEASE_EXPIRED`) |
| 캐릭터 연결 준비·비교의 부분 실패 | `setting_candidates.comparison_status → FAILED` 또는 FAILED로 최초 저장 |
| 세계관 연결 준비·비교의 부분 실패 | `world_setting_candidates.comparison_status → FAILED` 또는 FAILED로 최초 저장 |
| 사용자 재비교 Job 실패 | 숨김 비교 Job의 FAILED 전이와 원본 후보 실패 |
| 토큰 부족으로 분석 중단 | `AI_TOKEN_QUOTA_EXHAUSTED`로 다른 장애와 구별 |

재대기·자동 복구 중인 lease, 사용자 취소, 정상적인 검토 필요, 성공은 발송하지 않는다.
새 회차 분석으로 대체되는 이전 캐릭터·세계관 비교 Job은 `CANCELED`로 마감하고,
기존 토큰 예약 해제와 후보 연결 해제·삭제를 유지하여 정상적인 교체가 장애 알림을 만들지 않게 한다.
현재 범위는 `source_kind=EPISODE`다. 설정집 분석, 분석 접수 전의 HTTP 오류·사용량 사전 거절,
브라우저 네트워크 단절은 포함하지 않는다. 서버 프로세스/DB 전체 장애도 실패 상태를 저장할 수
없으므로 별도의 서비스 가용성 경보가 필요하다.

## 기록과 발송

V88의 PostgreSQL trigger가 실패 상태와 알림 대기를 한 트랜잭션으로 기록한다. Python이 최초
캐릭터 후보를 직접 저장하는 경로와 native SQL 상태 전이도 포함하며, 롤백한 실패는 발송하지 않는다.
이미 FAILED인 row의 단순 갱신은 알림을 추가하지 않는다. 재시도로 상태가 바뀐 뒤 다시 실패하면
새 알림이 생긴다. 같은 트랜잭션·Job·회차·종류·코드의 후보 실패는 건수로 합친다.
원 Job 전체 실패와 후보 실패는 서로 다른 종류이므로 각각 기록될 수 있다.

전용 단일 thread는 시작 5초 뒤부터 직전 실행이 끝나고 1초 뒤에 대기 알림을 하나씩 선점한다. 원자적인
`FOR UPDATE SKIP LOCKED` + `UPDATE RETURNING`으로 여러 API 인스턴스가 같은 row를 동시에
발송하지 않는다. HTTP 호출은 DB statement가 끝난 뒤 실행하므로 분석 트랜잭션이나 DB 연결을
전송 대기 동안 점유하지 않는다. 프로세스가 종료된 선점은 2분 뒤 다시 발송할 수 있다.

Discord에는 `wait=true`로 전송 완료 확인을 받고 성공한 알림만 완료한다. 연결 timeout 3초,
요청 timeout 5초, redirect 비허용이다. 429의 Retry-After와 정상 응답의 잔여 한도·Reset-After를
반영한다. 연결·5xx 오류는 5초부터 최대 1시간으로 backoff한다. 잘못된 웹훅 등의 4xx는 최소
1시간 뒤 다시 시도한다. 오류 응답 본문·예외·웹훅 URL을 로그에 출력하지 않는다.

현재 운영 API 단일 인스턴스에서는 해당 목적지의 대기 시간을 공유한다. 여러 인스턴스의
발송 rate limit 대기는 인스턴스별이며 각 인스턴스가 429를 받아 자체적으로 대기한다.
전송 보장은 **at-least-once**다. Discord가 수신했지만 응답 또는 완료 DB 기록이 유실되면
동일 알림 ID가 중복될 수 있다. Discord 전송 오류는 분석 처리 결과를 바꾸지 않는다.
DB outbox 쓰기는 실패 저장 트랜잭션의 일부이므로 DB 자체의 쓰기 장애까지 분리하는 것은 아니다.

## 알림 내용과 보존

알림에는 환경, 실패 종류, 안전한 코드별 문구, 회원 ID, 작품 ID, 회차 번호·ID, Job ID,
발생 시각(서버 시간, 운영 KST), 묶인 실패 건수, 알림 ID가 포함된다. 회원 연락처·원고·작품명·
프롬프트·AI 응답·원시 예외는 저장하거나 전송하지 않는다. 자동 멘션은 모두 비활성화한다.

발송 완료 기록은 30일 뒤 하루 한 번 정리한다. 미발송 기록은 재시도를 위해 보존하며 작품
영구 삭제 시 FK cascade로 함께 삭제한다. 이미 Discord에 발송된 메시지는 이 DB 삭제로
삭제되지 않으므로 운영 채널의 보존·접근 정책은 별도로 관리한다.

## 웹훅 준비와 활성화

1. Discord의 **서버 설정 → 연동 → 웹훅 만들기**에서 알림을 받을 일반 텍스트 채널을 선택한다.
   이름 예: `CatchHole 분석 알림`. 포럼/미디어 채널의 thread 설정은 현재 지원하지 않는다.
2. 웹훅 URL을 복사해 운영 서버 `/opt/catchhole/api.env`의
   `ANALYSIS_FAILURE_DISCORD_WEBHOOK_URL`에 저장한다. URL은 비밀값이며 커밋·이슈·로그에 넣지 않는다.
3. 같은 파일에서 `ANALYSIS_FAILURE_NOTIFICATIONS_ENABLED=true`로 설정한다.
4. 기존 API 배포 lock과 immutable SHA 배포 절차를 지켜 V88과 새 이미지를 적용한다.
   Compose가 `ANALYSIS_FAILURE_NOTIFICATION_ENVIRONMENT=prod`를 주입한다.
5. 합성 테스트 회차의 실패를 승인된 환경에서 발생시켜 실제 채널 수신과 해당 알림의
   `delivered_at`을 확인한다. 로컬 테스트 성공을 실제 수신 완료로 간주하지 않는다.

기본값은 비활성이다. V88 적용 이후의 실패는 비활성 중에도 DB에 기록되며 활성화하면 쌓인
미발송 알림부터 전달한다. V88 이전의 실패 이력은 소급하지 않는다. 비활성화는 발송만 멈추며
미발송 이력을 삭제하지 않는다. 웹훅을 교체하면 미발송 메시지는 새 목적지로 발송된다.

수신되지 않을 때는 비밀 URL을 출력하지 않고 다음 상태를 확인한다.

```sql
SELECT count(*) AS pending, min(occurred_at) AS oldest_pending,
       min(available_at) AS next_attempt, max(attempts) AS max_attempts
FROM analysis_failure_notifications WHERE delivered_at IS NULL;
```

검증 명령:

```sh
./gradlew test --tests '*AnalysisFailureNotification*' --tests '*DiscordWebhookClientTest'
./gradlew test
```

전용 Testcontainers PostgreSQL에서 전체 migration과 JPA validate를 실행하고, 모든 실패 코드,
부분 실패, 롤백, 중복 억제, 재실패, 동시 선점, 선점 만료, 발송 이력 정리를 검증한다.
HTTP 테스트는 로컬 가짜 서버를 사용하며 Discord 실제 채널에는 발송하지 않는다.

2026-10-24 로컬 검증: 정상 재분석 교체를 취소로 분류한 보완까지 포함한
`./gradlew test bootJar` 성공(전체 3,762개 중 3,526개 통과,
환경 조건에 따른 236개 건너뜀, 실패 0개).
알림 관련 PostgreSQL 16개와 HTTP·발송 단위 테스트 10개는 모두 실행되어 통과했다.
Discord 운영 서버와 알림 채널은 준비되어 있다. 웹훅 생성·서버 비밀 설정 등록과 운영 배포·
실제 채널 수신 확인은 아직 완료하지 않았다.

참고: [Discord 웹훅 생성](https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks),
[Execute Webhook](https://docs.discord.com/developers/resources/webhook#execute-webhook),
[Rate Limits](https://docs.discord.com/developers/topics/rate-limits).
