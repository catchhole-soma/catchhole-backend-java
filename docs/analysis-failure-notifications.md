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
원 Job 전체 실패와 후보 실패는 각각 불변 snapshot으로 기록하되, V107부터 동일 실행 시도이면 하나의 발송 묶음에 담는다.

전용 단일 thread는 시작 5초 뒤부터 직전 실행이 끝나고 1초 뒤에 발송 묶음을 하나씩 선점한다.
V107의 DB 전용 `analysis_jobs.failure_notification_attempt_id`는 RUNNING 진입 또는 FAILED에서
벗어날 때 새 UUID로 바뀐다. Worker lease/인증값과 무관하다. 실패 당시의 `execution_attempt_id`와
명시적 실행 링크로만 묶고, 같은 Job의 다른 시도나 실행을 모르는 legacy 후보는 합치지 않는다.

`analysis_failure_notification_deliveries`는 마지막 추가 후 5초(최초 생성부터 최대 30초)를 모은다.
최대 4개 snapshot씩 담고, 본문 2000자 및 embed 합계 6000자 한도 안에서 한 Discord 메시지로 발송한다.
후보 한 snapshot의 `failure_count`는 여러 항목일 수 있다. 후보와 작업은 별도 건수로 집계하며,
각 embed에 처리 결과·실패 후보 수·단계·코드·실제 검사·알림 ID를 표시하고 발생 시각과 재시도 안내를 보존한다. 관측 HTTP·요청/trace·근거 ID는 해당 사건의 embed에 남은 공간만큼 표시한다. 4개 초과 또는 선점 후 늦은 실패는 동일 실행 시도 ID의 후속 묶음이다.

발송자는 `FOR UPDATE SKIP LOCKED`로 묶음을 잠근 뒤 별도 READ COMMITTED statement에서
구성원을 읽고 선점한다. 갱신 statement에서 묶음 전체의 대기 시각을 다시 확인하고 각 행의 유효한 선점도 덮어쓰지 않는다. 실패 저장의 묶음 잠금이 풀린 뒤 커밋된 snapshot을 모두 읽기 위한 순서다.
최초 선점 때 봉인하여 전송 재시도에 새 사건이 섞이지 않게 한다. HTTP는 선점 트랜잭션 커밋 후
호출하고, 성공·재대기는 묶음 ID와 선점 token이 일치하는 구성원 전체를 한 statement로 갱신한다.
프로세스가 종료된 선점은 2분 뒤 복구되며 이전 token의 늦은 응답은 새 선점을 완료하지 못한다.

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

알림에는 환경·추출/재비교 실행/후보 실패 종류·단계·고정 원인·재시도 판단·실제 대상 회차·추적 ID가 포함된다. 실행은 실패 작업 1건, 후보 사건은 실패 항목 수를 표시한다. 실제 미상 단계·원인은 `확인 불가`로 남기되 요청/trace/원천 코드/근거 ID처럼 수집되지 않은 선택 정보는 생략한다. seed 회차로 다회차 범위를 추측하지 않는다.

V90의 nullable JSONB/ID/회차 배열은 실패 당시 snapshot이다. 발송 때 현재 Job·후보·FK로 과거 원인을 다시 만들지 않으며 retry·원인 Job 삭제 뒤에도 이전 알림은 유지한다. 새 시도는 현재 진단과 후보 실행 링크를 DB trigger에서도 비운다. 공개 DTO는 기존 coarse `AnalysisFailureCode`와 안전한 문구를 유지하고, 내부 `failureDiagnostics`의 v1 고정 코드·단계·관측한 HTTP·요청/trace만 추가한다. malformed 부가 진단은 버리며 주 실패 저장을 거절하지 않는다.

V106은 관계 전용 `RELATIONSHIP_EXTRACTION` 종류와 관계 추출·비교의 2개 단계, 공통 계약의 20개 원인 코드를 outbox 허용목록에 추가한다. 기존 V90과 과거 알림은 수정하지 않는다. 알림은 관계 결과 검증 실패·입력 한도 초과·고정 문맥 검증 실패를 구분하고 고정 원인 코드를 함께 표시한다. 재시도 안내는 원인 확인 후 제한된 복구 여부를 판단하도록 하며, 알림이 자동 복구 대상이나 횟수를 확대하지 않는다. 공통 fixture의 40개 단계/원인 조합은 실제 PostgreSQL 실패 저장→outbox→발송 선점까지 검증한다.

V107은 관계 검증 규칙별 한국어 설명을 제공하고, 세계관 주어 해석 실패와 비교 입력 한도를
`WORLD_CONTEXT`의 `WORLD_SUBJECT_RESOLUTION_FAILED`/`WORLD_COMPARISON_INPUT_TOO_LARGE`로 구분한다.
구 writer도 실패 당시 명시적인 `preparation_failure_stage`/hold 사유가 있으면 같은 고정 진단을 snapshot한다.
FAILED 관계 문맥을 재개할 때 원래 세부 원인이 응답에 없으면 `RELATIONSHIP_RESUME`/
`RELATIONSHIP_PREVIOUS_FAILURE`로 한계를 명시한다. 추출/비교 원인을 추정하지 않는다.
`AI_TOKEN_QUOTA_EXHAUSTED`는 HTTP 오류 진단이 있어도 토큰 부족과 충전 안내를 우선한다.

기존 outbox는 내용과 알림 ID를 유지하고 각각 봉인된 단건 delivery만 추가한다. 운영 배포 전의
Discord 메시지는 수정하지 않는다. Backend/Flyway 먼저, AI 다음 순서이며 분석 재실행을 동반하지 않는다.
구 Backend로 코드 rollback하면 새 묶음을 활용하지 않고 기존 row별로 전송할 수 있으므로 묶음 품질은
보장하지 않지만 기존 outbox 열과 발송 상태를 남겨 전달 경로를 유지한다.

입력 hash가 유효한 숨김 캐릭터 그룹은 첫 비교 묶음이 없어도 후보의 실패 실행 링크를 같은
트랜잭션에서 먼저 flush하고 Job을 FAILED로 바꾼다. 직접 `/fail`과 lease 회수 한도 초과에
같은 순서를 적용하므로 Job 트리거가 전체 대상 회차와 유일한 원본 추출을 읽을 수 있다.
서로 다른 회차의 여러 원본 추출은 단일 출처로 고르지 않고, stale 입력은 현재 후보를
변경하거나 범위를 추정하지 않는다. 나중 Job 저장이 실패하면 먼저 flush한 후보·알림도 함께 롤백한다.

Job의 역할은 구분한다. `execution_job_id`는 실제 실행, outbox `source_analysis_job_id`는 실패 후보의 원본 추출, 진단의 `sourceAnalysisJobId`는 원인이 된 과거 근거 Job이다. 여러 원본 추출이 한 실행에 섞이면 단일 원본 ID를 만들지 않는다. 원문 읽기 단계에서 후보를 특정하지 못했으면 원인 후보는 미상이다. 회원 ID·작품 ID·발생 시각·알림 ID도 함께 표시한다. 회원 연락처·전체 원고·프롬프트·전체 AI 응답·예외 원문은 저장하거나 전송하지 않는다. 사용자가 승인한 짧은 문제 구절과 실제 대상 이름은 V109의 제한된 필드로만 전달한다. 자동 멘션은 모두 비활성화한다.

발송 완료 기록은 30일 뒤 하루 한 번 정리한다. 미발송 기록은 재시도를 위해 보존하며 작품
영구 삭제 시 FK cascade로 함께 삭제한다. 이미 Discord에 발송된 메시지는 이 DB 삭제로
삭제되지 않으므로 운영 채널의 보존·접근 정책은 별도로 관리한다.

## 웹훅 준비와 활성화

1. Discord의 **서버 설정 → 연동 → 웹훅 만들기**에서 알림을 받을 일반 텍스트 채널을 선택한다.
   이름 예: `CatchHole 분석 알림`. 포럼/미디어 채널의 thread 설정은 현재 지원하지 않는다.
2. 웹훅 URL을 복사해 운영 서버 `/opt/catchhole/api.env`의
   `ANALYSIS_FAILURE_DISCORD_WEBHOOK_URL`에 저장한다. URL은 비밀값이며 커밋·이슈·로그에 넣지 않는다.
3. 같은 파일에서 `ANALYSIS_FAILURE_NOTIFICATIONS_ENABLED=true`로 설정한다.
4. 기존 API 배포 lock과 immutable SHA 배포 절차를 지켜 V108까지의 additive migration과 새 Backend 이미지를 먼저 적용하고 AI 진단 전송 이미지를 뒤에 적용한다.
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


2026-10-28 GH304 최종 flush 수정 전 로컬 전체 검증은 Java `test bootJar` 전체 4,026개 중 3,790 통과·236 skip·
실패/오류 0(4분 22초), Python 전체 4,505 통과·130 skip·실패 0(24.27초)다.
Java의 skip은 기존 선택 DB/HTTP/브라우저 환경 gate이며 당시 GH304 통합 3개와 알림 PG 45개는
모두 실행됐다. 실제 시간 JSON 네 개를 export하여 Python의 해당 네 계약 테스트도 실행했다.
전체 XML을 먼저 보관한 뒤 테스트 fixture의 큰따옴표 누락과 Fact/source UUID 동일성 assertion만
강화하여 당시 통합 3개를 다시 통과했다. 그 뒤 최종 리뷰에서 첫 비교 묶음 전의 flush 순서
누락을 확인해 런타임을 수정했으므로 위 전체 결과를 최종 코드의 전체 통과로 재사용하지 않는다.

최종 제품 수정은 `63d104ccea968e2a645fb419549408b119d43ea7`, 한글 표시명까지 포함한
검증 코드는 `07489d6d1a229b07c651ea15867974ab125354d5`다. 실제 PostgreSQL·HTTP에서
수정 전 직접 실패 2건의 대상 회차 누락과 lease 한도 초과의 일부 회차 누락을 재현했다.
수정 후 Job 실패·lease 복구·첫 claim 정리·안전한 진단·알림/Discord·GH304 HTTP 집중 검증은
**367 통과·0 skip·실패/오류 0(33초)**이다. 이 중 알림 PG 45개와 GH304 HTTP 8개가 모두 실행됐다.
HTTP 8개는 정상 retained-UNAVAILABLE 성공·Python/구 DTO 왕복 외에 첫 묶음 없는 단일/복수
원본, 후속 변경에도 불변인 snapshot, stale 입력 no-op, 강제 저장 실패의 원자 rollback,
lease 회수 한도 초과를 포함한다. Python 런타임은 바꾸지 않아 전체 pytest를 다시 실행하지 않았다.
최종 집중 실행의 WARN 23줄과 Java agent/CDS 경고는 별도 보관했으며 강제 rollback의
check 제약 거절 로그도 포함된다. 이번 집중 실행에서는 지표 관측 경고·deadlock이 없었다.
기존 Netty DNS·SpringDoc·open-in-view·Byte Buddy/CDS 및 비동기 지표 경고와 의도한 실패 경로
로그의 전체 실행 기록은 보존하며 관련 의존성/설정을 변경하지 않았다. 과거 지표 관측 경고
420건과 seed TRUNCATE deadlock은 원인 해결로 주장하지 않는다. 실제 모델·운영 데이터·외부 Discord는 사용하지 않았다.

## GH357 검증 — 2026-11-02

전용 Java/AI worktree에서 실행했다. 최종 Java `test bootJar` 성공, XML 집계 4,321 통과·317 건너뜀·실패/오류 0이다.
이 중 실제 pgvector PostgreSQL 알림 51개와 Python→Spring→PostgreSQL→가짜 Discord HTTP 8개는 모두 실행됐다.
전체 Flyway V1~V107 적용과 JPA validate, 실패/재시도 분리, 묶음 크기 상한, 동시 선점,
봉인 후 후속 실패, 오래된 token 거절, 롤백, 메시지 길이와 원인 근거 보존을 포함한다.
Python 전체는 5,100 통과·143 건너뜀·기존 Starlette 경고 1개다.
독립 리뷰에서 찾은 설명 범위·같은 트랜잭션 재시도·묶음의 근거 링크 누락을 RED→GREEN으로 검증했다.
운영 배포 및 실제 Discord 수신은 이 로컬 검증에 포함하지 않는다.

## GH357 추가: 기대값·실제값·처리 결과

v1 `failureDiagnostics`의 optional `checks`는 최대 3개의 고정 `field/rule/expected/actual/itemIndex`를 저장한다.
수치·local 참조·enum·boolean만 허용하며 Python·Java·PostgreSQL 경계에서 정제한다. 예외 메시지와 전체 원문/AI 응답은 넣지 않는다. 후속 사용자 승인에 따른 짧은 문제 구절은 V109 규칙을 따른다.
관계도는 입력 토큰 추정치/상한, 청크·관측·비교 묶음 수, 관측 참조/순서/누락, 자기 관계·중복 변경·종료 상태를 수집한다.
세계관 주체 해소는 예상 후보 순서/판단 수와 실제 응답, 선택 가능한 대상/실제 선택, 허용하지 않는 proof/모호 상태를 수집한다.
긴 참조 목록은 최대 8개이며 가능하면 문제를 일으킨 참조를 우선한다. 원문이나 표시 문구 관련 검사는 값 수집 대상이 아니므로 모든 오류에 상세값이 생기는 것은 아니다.
재시도 종료 시 raw 예외를 제거하더라도 검증된 진단은 유지한다. 구 Worker와 미수집 경로는 실제 값 미기록이라고 표시하며 추정하지 않는다.

V108은 후보의 검토 상태와 실패 후보 UUID(최대 8개)를 outbox에 복사한다. 후보 검토 상태가 다른 사건은 건수로 합치지 않는다.
`PENDING_REVIEW`는 **후보가 저장되어 있고 비교가 실패해 검토 대기 중**이라는 뜻이다. 이 후보 사건만으로 Job 전체의 성공/실패를 판정하지 않는다.
`CONFIRMED`/`DISMISSED`는 각각 실패 당시 확정/제외 상태다. 이후 검토 변경을 과거 알림에 반영하지 않는다.
작업 실패는 **Job FAILED**, 관계 전용 작업은 **관계도 전용 Job FAILED**, 재비교는 **재비교 Job FAILED**로 구별한다.
기존의 정상 `REVIEW_REQUIRED`는 실패 알림 대상이 아니다. 실패 판정·복구·차감 정책은 바꾸지 않는다.

Discord 본문은 묶음과 실행 식별자, embed는 사건별 처리 결과와 검사 값을 표시한다. 예: 기대 `S1,S2` / 실제 `S9`, 기대 최대 `64000` / 실제 `72001`.
이 수치는 형식 설명용 합성 예시이며 과거 운영 사건의 값이 아니다. 과거 진단에 없던 값과 후보 상태를 소급 생성하지 않는다.

- 후속 요구 구현 완료: 검사별 expected/actual(최대3개), 세계관 subject 최종 실패 진단 운반, V108 후보 상태/ID snapshot, 최대4개 Discord embed의 처리 결과/원인별 수/실제 검사 표시. 정상 검토와 실패 분류·재시도·차감은 유지한다.
- 최종 검증: Java 전체 test bootJar 4,330 passed / 317 skipped / failures0 (5분29초). 이후 표시·선점 경계 수정은 최종 집중 206 passed / 0 skipped와 bootJar로 검증했다(실제 PG55 + Python HTTP8 포함). Python 최종 전체 5,106 passed / 143 skipped / 실패0 (29.44초, 기존 Starlette 경고1).
- 독립 리뷰 중요 지적 모두 해결: 구 알림 기록 Job/회차 ID, 원인별 후보 수. duplicate UNIQUE/오류 참조 우선 표시도 반영했다. 선택 뒤 유효 lease가 생긴 경계에서 덮어쓰는 회귀를 RED→GREEN으로 확인하고 fresh 묶음/행 eligibility 조건을 보완했다. 자연 발생 동시 스케줄 자체의 재현으로 주장하지 않는다.
- production mapper 합성 미리보기는 workspace output/discord-failure-details-preview-20261102.txt. 기대 S1,S2/실제 S9, 한도64000/관측72001은 형식 검증용 값이며 운영 사건의 값이 아니다. 원문/응답 구절은 포함하지 않는다. 운영 배포·커밋·외부 Discord 발송은 수행하지 않았다.

## GH357 후속 승인: 짧은 구절과 이해하기 쉬운 영향 설명

2026-11-02 사용자가 문제 원문·응답 구절을 짧게 포함하도록 승인했다. V109는 `subjectName`(최대60 code point)과 `excerpts`(최대3개, 각각120 code point)를 정제·보존한다.
SOURCE/evidenceQuote는 후보에 저장된 근거이며 원문 저장소에서 새로 검증한 직접 인용이라고 주장하지 않는다. EXPECTED는 실제 제공한 대상 이름 또는 기존 관계 값, RESPONSE는 모델이 반환한 문제 필드다. 헤더·예외 원문·전체 prompt/context를 복사하지 않는다.
긴 MERGE 설명은 기존/응답이 처음 달라진 지점 주변, 내부 참조 노출은 실제 참조 주변을 잘라서 차이가 보이게 한다. 최종 재시도에서 남은 구절만 보고하고 정상적으로 복구한 앞선 실패를 알리지 않는다.
Discord에서는 대상 이름과 이유 한 줄·선택지·AI 선택·결과를 앞에 배치한다. 예: ‘AI가 선택지에 없는 대상을 골랐습니다.’ 코드/응답 JSON은 뒤에 둔다. 발췌와 추가 축약을 표시하고 Markdown/멘션을 이스케이프한다.

`continues_after_candidate_failure=true`는 서버가 자동 회차 분석의 세계관 주체 해소/비교 준비 실패를 후보 보류로 처리한 명시적 경로에서만 기록한다. 단순 PENDING_REVIEW로 추정하지 않는다.
이 경우 ‘이 설정만 자동 반영 중단 · 나머지 분석 계속’으로 오류의 영향을 표시한다. 이는 작업의 최종 성공을 선언하는 값이 아니며, 이후 다른 원인으로 Job이 실패하면 별도 Job 실패 카드가 나온다.
Job FAILED는 회차 분석/관계도 전용/재비교의 범위를 구분해 중단이라고 표시한다. 기존 기록에 없는 계속 진행 여부를 소급 생성하지 않는다.

원문 파기 시 같은 작품의 Job·캐릭터/세계관 후보·캐릭터/세계관 비교 묶음·outbox의 6개 진단 저장소에서 excerpts만 제거한다. 기존 관계 값이 여러 회차에서 유래할 수 있어 작품 단위로 제거하며, 기존 오류 코드·건수는 남긴다. V109의 `episode_source_purge_requests` DELETE 트리거가 완료 정리와 같은 트랜잭션에서 `OLD.work_id`로 처리한다. 느린 원문 파기 중 stale recovery가 PROCESSING을 REQUESTED로 되돌릴 수 있으므로 일시적인 상태를 조건으로 삼지 않는다. 이미 Discord로 전달된 메시지는 기존 외부 채널 보존 범위를 따른다.

최종 검증 (2026-11-02):

- 최종 Java 전체 `test bootJar`: 4,337 통과 / 317 건너뜀 / 실패·오류 0 (5분 33초). 실제 PostgreSQL 알림 58개 및 Python→Spring→PostgreSQL→가짜 Discord HTTP 8개 모두 실행·통과.
- 최종 Python 전체: 5,110 통과 / 143 건너뜀 / 실패 0 (31.31초, 기존 Starlette 경고 1개).
- 독립 리뷰의 늦은 참조 발췌, 파기 요청 상태 경합, 두 비교 묶음의 발췌 사본 누락을 RED→GREEN으로 수정. 최종 중요/치명 지적 없음. 양 저장소 `git diff --check` 통과.
- 실제 알림 생성 코드의 합성 미리보기: `output/discord-failure-excerpts-preview-20261102.txt`. 실제 운영 사건의 구절·값으로 해석하지 않음.
- #357 전용 worktree의 미커밋 변경이며 운영 배포·실제 Discord 발송·기존 알림 변경은 수행하지 않음.

## GH357 후속: 관계도 부분 실패 알림

V110은 일반 회차 분석 중 관계 이력이 새로 FAILED로 바뀌는 순간 `RELATIONSHIP_PARTIAL`을 저장한다. 소유 Job이 관계 기능을 활성화한 RUNNING/EPISODE/SETTING_EXTRACTION인 경우만 대상이다. 관계도 전용 RELATIONSHIP_EXTRACTION은 기존 Job 실패 알림만 사용한다. 정상 HOLD/COMPLETED, 같은 FAILED 재요청과 진단만 수정한 UPDATE는 알림을 추가하지 않는다.

Worker는 관계 fail 요청의 optional `failureDiagnostics`로 기존 허용 진단을 전송한다. Java는 정제 후 첫 실패에 보존하며, 구 Worker의 4필드 요청도 허용한다. 서버 자체 INPUT_TOO_LARGE는 고정 입력 한도 원인으로 보완하고, 기록되지 않은 세부 원인은 추정하지 않는다. 관계도 실패 API와 기존 상위 Job 성공/실패 판정은 그대로다.

부분 실패 메시지의 상단은 `⚠️ [운영] 관계도 부분 실패`, 결과는 `관계도만 실패 · 나머지 분석 계속`이다. 실패 작업/관계도 부분 실패/비교 실패 후보를 별도로 센다. 동일 실행 묶음에 회차 Job FAILED가 있으면 상단에 `🚨 [운영] 회차 분석 중단`을 표시하고 부분 실패 카드도 `관계도 부분 실패 · 회차 분석은 별도 오류로 중단`으로 표시한다. 전용 Job 실패는 `관계도 분석 중단`이다.

처리 결과는 오류 당시의 영향이다. 발송 묶음이 봉인된 뒤 다른 원인으로 작업이 실패하면 동일 실행 시도 ID의 후속 중단 알림이 오며 이미 전달한 메시지를 수정하지 않는다. 새 관계 이력 진단 사본도 원문 파기 요청 삭제 시 같은 작품의 excerpts를 제거해 총 7개 저장소를 정리한다.

합성 미리보기: `output/discord-relationship-partial-preview-20261102.txt`. 실제 운영 사건이나 실제 Discord 수신 결과가 아니다.

최종 검증 (2026-11-02):

- 최종 Java 전체 `test bootJar`: 4,346 통과 / 317 건너뜀 / 실패·오류 0 (5분 41초). 실제 PostgreSQL 알림 63개 및 Python→Spring→PostgreSQL→가짜 Discord HTTP 8개 모두 실행·통과.
- 최종 Python 전체: 5,111 통과 / 143 건너뜀 / 실패 0 (28.32초, 기존 Starlette 경고 1개).
- 독립 리뷰에서 발견한 구 Worker 단계 오표시를 RED→GREEN으로 수정했고 재검토 추가 지적 없음. 양 저장소 `git diff --check` 통과.
- #357 전용 worktree에 구현했으며 커밋·배포·실제 Discord 발송·과거 알림 변경은 수행하지 않음.
