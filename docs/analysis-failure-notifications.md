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

V88에 도입하고 V90에서 확장한 PostgreSQL trigger가 실패 상태와 알림 대기를 한 트랜잭션으로 기록한다. Python이 최초
캐릭터 후보를 직접 저장하는 경로와 native SQL 상태 전이도 포함하며, 롤백한 실패는 발송하지 않는다.
이미 FAILED인 row의 단순 갱신은 알림을 추가하지 않는다. 재시도로 상태가 바뀐 뒤 다시 실패하면
새 알림이 생긴다. 같은 트랜잭션·원본 Job·회차·종류·코드에 실행·묶음·정리된 진단 전체가 같은 후보 실패만 건수로 합친다. 같은 reason이어도 단계·HTTP·원천 코드·원인 출처가 다르면 분리한다. 진단과 실행 링크가 모두 없는 구 writer는 기존 묶음 의미를 유지한다.
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

알림에는 환경·추출/재비교 실행/후보 실패 종류·단계·고정 원인·재시도 판단·실제 대상 회차·추적 ID가 포함된다. 실행은 실패 작업 1건, 후보 사건은 실패 항목 수를 표시한다. 미수집값은 `확인 불가`이며 seed 회차로 다회차 범위를 추측하지 않는다.

V90의 nullable JSONB/ID/회차 배열은 실패 당시 snapshot이다. 발송 때 현재 Job·후보·FK로 과거 원인을 다시 만들지 않으며 retry·원인 Job 삭제 뒤에도 이전 알림은 유지한다. 새 시도는 현재 진단과 후보 실행 링크를 DB trigger에서도 비운다. 공개 DTO는 기존 coarse `AnalysisFailureCode`와 안전한 문구를 유지하고, 내부 `failureDiagnostics`의 v1 고정 코드·단계·관측한 HTTP·요청/trace만 추가한다. malformed 부가 진단은 버리며 주 실패 저장을 거절하지 않는다.

Job의 역할은 구분한다. `execution_job_id`는 실제 실행, outbox `source_analysis_job_id`는 실패 후보의 원본 추출, 진단의 `sourceAnalysisJobId`는 원인이 된 과거 근거 Job이다. 여러 원본 추출이 한 실행에 섞이면 단일 원본 ID를 만들지 않는다. 원문 읽기 단계에서 후보를 특정하지 못했으면 원인 후보는 미상이다. 회원 ID·작품 ID·발생 시각·알림 ID도 함께 표시한다. 회원 연락처·원고·작품명·
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
4. 기존 API 배포 lock과 immutable SHA 배포 절차를 지켜 V90까지의 additive migration과 새 Backend 이미지를 먼저 적용하고 AI 진단 전송 이미지를 뒤에 적용한다.
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

## GH304 호환성과 합성 연결 검증

구 Worker가 진단을 생략해도 신 Backend는 기존 실패를 저장하고 승인된 실행 링크를 기록한다.
첫 claim 내부에서 결정적 근거 오류를 마감하고 204를 반환한 경우 진단의 HTTP는 `null`이다. Worker가 context 오류 응답 409를 실제 받은 경우에만 409를 기록한다. 내부 실패에 관측하지 않은 HTTP를 만들어 넣지 않는다.

신 Worker의 다섯 fail API가 보내는 optional 필드는 변경 전 실제 DTO 계약에서도 수신되지만
구 Backend는 이를 보존하지 않는다. Backend 먼저 배포하며 rollback은 nullable 열을 남기고
코드만 복구할 수 있다. 과거 실패 진단 backfill이나 운영 재분석·재비교·자동 재시작은 하지 않는다.

다음은 production formatter로 생성한 합성 예시의 일부다. 실제 알림·발송 기록이 아니다.

```text
🚨 [운영] 캐릭터 재비교 실행 실패
단계: 기존 캐릭터 비교 문맥 준비
원인: 고정 원문 해시 불일치 (CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH)
실패 코드: UNEXPECTED_ERROR / 분석 중 오류가 발생했습니다.
대상 회차: 59·60화 / 실패 작업: 1건
재시도: 원문 연결 확인 전 재시도 필요 없음
실행 Job ID: 00000000-0000-4000-8000-000000000004
원본 추출 Job ID: 확인 불가
비교 묶음 ID: 00000000-0000-4000-8000-000000000009
진단 경계: SPRING
원천 코드: ANALYSIS_CURRENT_EVIDENCE_INVALID
HTTP: 409
원인 근거 회차: 24화
원인 근거 Job ID: 00000000-0000-4000-8000-000000000006
```

`ComparisonEvidenceHttpPostgresIntegrationTest`는 실제 EPISODE HTTP claim→확정 인용만
UNAVAILABLE인 문맥→context token으로 REVIEW_REQUIRED 완료→PG outbox 0건과 기존 Fact/출처
보존을 확인한다. 별도 실패 경로는 실제 Python client가 Spring의 원문 해시 오류 409를 읽고
후보/묶음과 Job 실패를 보고하여 PG snapshot→production dispatcher→localhost 가짜 Discord까지
실행한다. 변경 전 세 DTO로 신 Worker의 다섯 요청을 수신하는 HTTP 호환 검증도 포함한다.

```sh
GH304_AI_CHECKOUT=/path/to/ai GH304_PYTHON=/path/to/existing-venv/bin/python \
  ./gradlew test --tests '*ComparisonEvidenceHttpPostgresIntegrationTest'
```

Docker와 두 경로를 갖춘 로컬 전용 검증이다. 실제 원고·LLM·Discord webhook을 사용하지 않는다.
실제 모델 정확도·운영 실패율·운영 수신 성공은 이 합성 결과로 평가하지 않는다.


2026-10-28 GH304 로컬 전체 검증은 Java `test bootJar` 전체 4,026개 중 3,790 통과·236 skip·
실패/오류 0(4분 22초), Python 전체 4,505 통과·130 skip·실패 0(24.27초)다.
Java의 skip은 기존 선택 DB/HTTP/브라우저 환경 gate이며 GH304 새 통합 3개와 알림 PG 45개는
모두 실행됐다. 실제 시간 JSON 네 개를 export하여 Python의 해당 네 계약 테스트도 실행했다.
전체 XML을 먼저 보관한 뒤 테스트 fixture의 큰따옴표 누락과 Fact/source UUID 동일성 assertion만
강화하여 최종 통합 3개를 다시 통과했다. full 이후 제품 코드는 변경하지 않았다.
기존 Netty DNS·SpringDoc·open-in-view·Byte Buddy/CDS 및 비동기 지표 경고와 의도한 실패 경로
로그가 관측됐고 관련 의존성/설정을 변경하지 않았다. 과거 seed TRUNCATE deadlock은 이번에
재현되지 않았으며 원인 해결로 주장하지 않는다. 실제 모델·운영 데이터·외부 Discord는 사용하지 않았다.
