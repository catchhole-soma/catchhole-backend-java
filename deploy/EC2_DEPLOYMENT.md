# CatchHole Production Compose

EC2 단일 서버에서 Caddy, Spring Backend, Python AI Worker, PostgreSQL, Redis를 Docker Compose로 실행한다.

PostgreSQL은 로컬과 운영 모두 `pgvector/pgvector:0.8.2-pg16` 이미지를 사용한다. 이 이미지는 pgvector extension 파일을 제공하며, 실제 `CREATE EXTENSION vector`와 vector 컬럼 생성은 Flyway migration에서 관리한다.

## EC2 배치 경로

운영 서버에서는 아래 파일들을 `/opt/catchhole`에 둔다.

```text
/opt/catchhole
├── compose.prod.yml
├── Caddyfile
└── .env
```

`.env`는 `deploy/.env.example`을 기준으로 서버에서 직접 작성하고 커밋하지 않는다.

## 휴대폰 인증과 Redis

운영 Backend는 SOLAPI와 내부 Redis를 사용한다. `.env`에 다음 값을 설정한다.

```dotenv
SMS_PROVIDER=solapi
SOLAPI_API_KEY=replace-with-solapi-api-key
SOLAPI_API_SECRET=replace-with-solapi-api-secret
SOLAPI_SENDER_NUMBER=replace-with-registered-sender-number
PHONE_VERIFICATION_HASH_SECRET=replace-with-at-least-32-byte-random-secret
REDIS_PASSWORD=replace-with-strong-redis-password
```

- SOLAPI 개인 계정에서 본인 명의 발신번호를 사전 등록한다. 개인 번호가 사용자 SMS에 표시되며 문자 본인인증으로 등록한 번호는 6개월마다 갱신한다.
- SOLAPI API key에는 `message:write` 권한을 부여하고 API secret은 로그·이미지·저장소에 남기지 않는다.
- SOLAPI 자동충전은 사용하지 않고 필요한 선불 잔액만 유지한다. 전체 KST 일 20건·월 200건 상한은 Redis rate limit으로 적용한다.
- Redis는 호스트 포트를 공개하지 않고 64MB `noeviction`, 비영속으로 실행한다. 재시작 시 진행 중 인증이 초기화된다.
- `PHONE_VERIFICATION_HASH_SECRET`은 JWT·Redis 비밀번호와 분리한 32바이트 이상 랜덤 값으로 유지한다.
- 운영에서 `SMS_PROVIDER=fake`를 설정하면 Backend가 시작되지 않는다.

## 운영 시간대

Backend, AI Worker, PostgreSQL은 `.env`의 `APP_TIMEZONE`을 함께 사용한다.
한국 서비스 운영 기본값은 `Asia/Seoul`이며, JVM의 `user.timezone`, Python 컨테이너의 `TZ`,
PostgreSQL 서버의 `timezone`을 같은 값으로 맞춰 timezone 없는 `TIMESTAMP` 컬럼과 로그가
서로 다른 기준으로 기록되지 않게 한다.

```dotenv
APP_TIMEZONE=Asia/Seoul
```

EC2 호스트 자체의 표시 시간도 한국 시간으로 맞추려면 최초 한 번 실행한다.

```bash
sudo timedatectl set-timezone Asia/Seoul
```

## AI Worker 임베딩 생성

MVP는 벡터 검색을 사용하지 않으므로 신규 청크 임베딩 생성을 기본적으로 비활성화한다.

```dotenv
EMBEDDING_GENERATION_ENABLED=false
```

오류 리포트나 RAG 검색에서 pgvector가 필요해지면 값을 `true`로 바꾸고 AI Worker를 재생성한다. 이 설정은 이후 생성되는 신규 분석·재분석 청크에만 적용되며 기존 `NULL` 임베딩을 자동 backfill하지 않는다. 과거 원문 벡터가 필요하면 대상 회차의 재분석 Job을 생성하거나 별도 범위 제한 backfill 작업을 먼저 준비한다.

## AI Worker 동시 실행과 종료

운영 `SETTING_EXTRACTION` Worker는 Compose의 `deploy.replicas`와 프로세스 내부 실행 슬롯을 함께 사용한다. 현재 검증 rollout 값은 다음과 같다.

```dotenv
AI_WORKER_PROCESS_COUNT=2
AI_WORKER_CONCURRENCY=5
LLM_MAX_CONCURRENT_REQUESTS=5
LLM_HTTP_MAX_RETRIES=3
LLM_HTTP_RETRY_BASE_SECONDS=2
AI_WORKER_BLOCKING_MAX_WORKERS=3
AI_WORKER_IDLE_SLEEP_SECONDS=5
AI_WORKER_SHUTDOWN_GRACE_SECONDS=180
```

`AI_WORKER_PROCESS_COUNT`는 Compose가 만드는 분석 Worker 컨테이너 수이고, `AI_WORKER_CONCURRENCY`는 각 컨테이너의 동시 Job 슬롯 수다. 따라서 현재 검증 rollout의 `SETTING_EXTRACTION` 용량은 `2 × 5 = 10`이다. 50개 Job 부하 테스트에서 중복 claim·lease 만료, LLM 429, Backend 지연, PostgreSQL connection, EC2 메모리를 확인하고 기준에 미달하면 두 프로세스는 유지한 채 `AI_WORKER_CONCURRENCY`와 `LLM_MAX_CONCURRENT_REQUESTS`를 3으로 되돌린다.

실행 슬롯은 queue가 아니다. queue는 PostgreSQL의 `analysis_jobs`이고 슬롯은 Worker가 지금 즉시 처리할 수 있는 자리다. Worker는 `슬롯 확보 → Job 하나 claim → 즉시 Task 실행` 순서를 지키며, 슬롯 없이 Job을 미리 claim해 내부 대기열에 쌓지 않는다. 한 Job 안의 청크는 순차 처리한다.

`ai-world-comparison-worker`와 `ai-character-comparison-worker`는 같은 LLM 계정을 사용하지만 각각 숨김 재비교 Job type을 처리하며 Compose에서 Job·LLM 동시성을 1로 고정한다. 따라서 위의 10은 `SETTING_EXTRACTION` Job 처리 용량이지 모든 프로세스를 합친 provider 계정 전체 동시 요청 상한이 아니다. 계정 전체의 엄격한 상한이 필요하면 Redis 같은 프로세스 외부 분산 limiter가 필요하며 이번 MVP 범위에는 포함하지 않는다.

동시성 10 검증 rollout에서 50개 Job 부하 테스트가 다음 기준을 모두 만족해야 운영값으로 유지한다.

- 중복 claim과 정상 처리 중 lease 만료가 각각 0건이다.
- LLM 429가 없거나 `Retry-After`/backoff 재시도로 최종 복구된다.
- 회차당 평균 1분인 입력에서 전체 완료가 약 6~9분 이내다.
- EC2 메모리 지속 사용률이 75% 이하이고 Backend 응답 시간이 눈에 띄게 악화되지 않는다.
- PostgreSQL connection 고갈이 없고 claim lock wait가 처리량 병목이 아니다.
- 한 Job 실패가 다른 Job을 취소하지 않으며, 컨테이너 종료 시 신규 claim 중단과 실행 중 Job drain이 확인된다.

종료 신호를 받은 Worker는 신규 claim을 즉시 중단하고 실행 중 Job과 heartbeat를 내부 grace 180초 동안 유지한다. Compose `stop_grace_period`는 210초로 두어 내부 timeout 뒤 Task 취소와 client/executor 정리에 30초를 더 준다. 210초 뒤에도 종료하지 않으면 Docker가 강제 종료하며, heartbeat가 멈춘 Job은 Spring의 5분 lease 만료와 checkpoint 정책으로 회수된다. 내부 grace를 210초 이상으로 올리지 않는다.

이미 시작된 동기 DB/S3 thread는 Python에서 강제로 중단되지 않으므로 내부 180초 grace는 blocking I/O의 절대 timeout이 아니다. 취소 뒤 critical section 완료를 기다리는 동안 heartbeat가 더 갱신될 수 있고, 강제 종료 직전 heartbeat가 성공했다면 재claim은 마지막 5분 lease 만료까지 추가 지연될 수 있다. staging에서는 DB/S3 지연을 주입한 종료 테스트로 신규 claim 중단, 210초 강제 종료, checkpoint 기반 재회수를 함께 확인한다.

설정을 바꾼 뒤 Compose 렌더링과 실제 replica 수를 확인한다.

```bash
docker compose --env-file .env -f compose.prod.yml config --quiet
docker compose --env-file .env -f compose.prod.yml up -d --force-recreate ai-worker ai-world-comparison-worker ai-character-comparison-worker
docker compose --env-file .env -f compose.prod.yml ps ai-worker ai-world-comparison-worker ai-character-comparison-worker
```

`ai-worker`가 2개, 두 비교 Worker가 각각 1개 실행 중이어야 한다. 배포 Workflow의 SSM 상태 확인은 210초 stop grace와 이후 최대 150초 Backend health 확인보다 긴 15분을 허용한다.

## AI 모델과 기본 토큰 지급량

운영 모델, 추론 강도, 신규 회원의 기본 토큰 지급량은 서버의 `/opt/catchhole/.env`에서 관리한다.

```dotenv
LLM_MODEL=gpt-5.6-terra
LLM_EXTRACTION_MODEL=gpt-5.6-terra
LLM_SUBJECT_RESOLUTION_MODEL=gpt-5.6-luna
LLM_COMPARISON_MODEL=gpt-5.6-luna
LLM_REASONING_EFFORT=none
AI_TOKEN_DEFAULT_GRANT=2000000
AI_TOKEN_CONTACT_EMAIL=aicatchhole@gmail.com
AI_TOKEN_MINIMUM_ANALYSIS_RESERVATION=4256
AI_TOKEN_MINIMUM_COMPARISON_RESERVATION=2256
```

캐릭터 Fact·세계관 후보의 1차 추출은 Terra를 사용하고, 캐릭터·세계관 주체 해소와 캐릭터 Fact/세계관 비교·재비교는 Luna를 사용한다. `LLM_MODEL`은 단계별 값이 없을 때만 사용하는 하위 호환 fallback이다. `LLM_REASONING_EFFORT=none`은 구조화 응답 품질을 별도로 검증하면서 GPT-5.6의 기본 추론 비용이 자동으로 추가되지 않게 하는 MVP 기준값이다.

`AI_TOKEN_DEFAULT_GRANT`는 설정 변경 후 처음 생성되는 토큰 계정에만 적용된다. 기존 회원에게도 정책 차액을 지급할 때는 `docs/ai-token-usage.md`의 운영 추가 지급 절차를 사용해 계정 합계와 지급 이력을 같은 transaction에서 갱신한다.

`AI_TOKEN_CONTACT_EMAIL`은 기본 사용량을 모두 소진한 사용자에게 표시할 피드백 연락처다. 운영에서 바꿀 때는 Backend 컨테이너를 재생성해야 한다.

`AI_TOKEN_MINIMUM_ANALYSIS_RESERVATION`과 `AI_TOKEN_MINIMUM_COMPARISON_RESERVATION`은 각각 최초 분석과 후보 비교를 시작하기 전에 요구하는 최소 예약량이다. `.env`에서 조정한 값은 Backend 컨테이너 재생성 뒤 적용된다.

값을 바꾼 뒤에는 관련 컨테이너를 재생성하고 실제 주입값을 확인한다.

```bash
docker compose --env-file .env -f compose.prod.yml up -d --force-recreate backend ai-worker ai-world-comparison-worker ai-character-comparison-worker
docker compose --env-file .env -f compose.prod.yml exec backend printenv AI_TOKEN_DEFAULT_GRANT
docker compose --env-file .env -f compose.prod.yml exec backend printenv AI_TOKEN_CONTACT_EMAIL
docker compose --env-file .env -f compose.prod.yml exec backend printenv AI_TOKEN_MINIMUM_ANALYSIS_RESERVATION
docker compose --env-file .env -f compose.prod.yml exec backend printenv AI_TOKEN_MINIMUM_COMPARISON_RESERVATION
docker compose --env-file .env -f compose.prod.yml exec ai-worker printenv LLM_EXTRACTION_MODEL
docker compose --env-file .env -f compose.prod.yml exec ai-worker printenv LLM_SUBJECT_RESOLUTION_MODEL
docker compose --env-file .env -f compose.prod.yml exec ai-worker printenv LLM_COMPARISON_MODEL
docker compose --env-file .env -f compose.prod.yml exec ai-worker printenv LLM_REASONING_EFFORT
docker compose --env-file .env -f compose.prod.yml exec ai-world-comparison-worker printenv LLM_SUBJECT_RESOLUTION_MODEL
docker compose --env-file .env -f compose.prod.yml exec ai-world-comparison-worker printenv LLM_COMPARISON_MODEL
docker compose --env-file .env -f compose.prod.yml exec ai-character-comparison-worker printenv LLM_COMPARISON_MODEL
```

## 실행

```bash
cd /opt/catchhole
docker compose --env-file .env -f compose.prod.yml pull
docker compose --env-file .env -f compose.prod.yml up -d
docker compose --env-file .env -f compose.prod.yml ps
```

시간대 반영은 컨테이너 재시작이 아니라 재생성이 필요하다.

```bash
docker compose --env-file .env -f compose.prod.yml up -d --force-recreate redis backend ai-worker ai-world-comparison-worker ai-character-comparison-worker postgres
docker compose --env-file .env -f compose.prod.yml exec backend date
docker compose --env-file .env -f compose.prod.yml exec ai-worker date
docker compose --env-file .env -f compose.prod.yml exec ai-world-comparison-worker date
docker compose --env-file .env -f compose.prod.yml exec ai-character-comparison-worker date
docker compose --env-file .env -f compose.prod.yml exec ai-worker printenv EMBEDDING_GENERATION_ENABLED
docker compose --env-file .env -f compose.prod.yml exec postgres \
  sh -lc 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SHOW timezone; SELECT CURRENT_TIMESTAMP;"'
docker compose --env-file .env -f compose.prod.yml exec redis \
  sh -lc 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
```

Backend 컨테이너가 시작될 때 Flyway가 미적용 migration을 먼저 실행하고, 이후 Hibernate가 JPA Entity와 schema 일치 여부를 `validate`한다. AI Worker의 SQLAlchemy 모델은 schema를 생성하지 않고 Flyway가 만든 테이블을 사용한다. Redis와 Backend를 먼저 올린 다음 Frontend를 즉시 배포하고, 실제 휴대폰으로 SMS 수신과 회원가입을 1회 확인한다.

## 기존 테스트 DB의 최초 V1 전환

이 절차는 Flyway 도입 전에 JPA `ddl-auto=update`로 생성한 기존 DB를 V1 기준으로 다시 만드는 최초 1회 작업이다. 현재 데이터가 테스트용이고 삭제에 팀 동의가 있을 때만 수행한다.

1. 배포 전에 필요한 데이터가 없는지 팀에 확인하고, 보존이 필요하면 `pg_dump`로 백업한다.
2. Backend와 AI Worker를 중지해 DB 쓰기를 막는다.
3. PostgreSQL 컨테이너와 `postgres_data` volume 이름을 확인한다.
4. 삭제 대상을 다시 확인한 뒤 PostgreSQL volume만 제거한다. Caddy volume은 제거하지 않는다.
5. PostgreSQL을 먼저 시작해 빈 DB가 준비될 때까지 health 상태를 확인한다.
6. Backend를 시작하고 로그에서 Flyway V1 성공과 Hibernate validation 성공을 확인한다.
7. `flyway_schema_history`, pgvector extension, `episode_chunks`의 `vector(1536)` 컬럼을 확인한다.
8. AI Worker를 시작하고 Swagger에서 회원·작품·회차·분석 작업의 기본 동작을 확인한다.

V1이 공유 환경에 적용된 뒤에는 파일을 수정하지 않는다. 이후 schema 변경은 V2 이상의 새 migration으로 추가한다.

## 확인

```bash
curl https://api.catchhole.com/actuator/health
docker compose --env-file .env -f compose.prod.yml logs -f backend
docker compose --env-file .env -f compose.prod.yml logs -f ai-worker
docker compose --env-file .env -f compose.prod.yml logs -f ai-world-comparison-worker
docker compose --env-file .env -f compose.prod.yml logs -f ai-character-comparison-worker
```

`ai-worker`는 `SETTING_EXTRACTION`, `ai-character-comparison-worker`는 `CHARACTER_FACT_COMPARISON`, `ai-world-comparison-worker`는 `WORLD_SETTING_COMPARISON`만 claim한다. 세 Worker가 모두 떠 있어야 최초 분석과 두 종류의 재비교 요청이 처리된다.

## 캐릭터 비교 순차 배포

Java와 AI 이미지는 서로 다른 저장소에서 자동 배포되므로 다음 순서로 전환합니다.

1. Java를 먼저 배포해 V20~V22와 내부 API를 준비합니다. 이때 구버전 AI가 `CHARACTER_COMPARISONS_FINISHED` checkpoint를 보내지 않아도 실제 `PENDING/PROCESSING` 캐릭터 후보가 없을 때만 legacy 완료를 허용합니다.
2. 기존 DB의 매칭 후보는 V20에서 `NOT_REQUIRED`로 이관하므로 끝난 분석 Job에 영구 `PENDING`이 생기지 않습니다. 사용자가 비교 시작 또는 confirm을 요청하면 Backend가 hidden 비교 Job으로 전환합니다.
3. AI 이미지를 배포해 신규 분석 후보가 `PENDING`을 명시하고 캐릭터 비교 checkpoint를 보고하도록 합니다. 잠시 구버전 AI 이미지로 뜬 `ai-character-comparison-worker`가 종료되더라도 기본 분석 Job을 claim하지 않으며, AI 배포 후 정상화됩니다.
4. Front를 배포해 비교 상태·proposal·`HISTORY_ONLY` UI를 활성화합니다.

V20은 `character_facts.is_current`를 제거하므로 Java 배포 후 구버전 Java 이미지로 단순 rollback할 수 없습니다. 장애 시 DB를 되돌리지 말고 새 forward repair migration과 호환 코드를 배포합니다. V20의 이전 checksum을 개인 로컬 DB에 이미 적용했다면 테스트 데이터는 재생성하고, 보존 데이터가 있으면 Flyway repair 전에 SQL diff와 현재 schema를 확인합니다.

## GitHub Actions 자동 배포

`Deploy EC2` workflow는 `Publish Backend Image` workflow가 main 브랜치에서 성공하면 이어서 실행된다.
또한 Actions 화면에서 수동 실행할 수 있으므로 AI 이미지만 갱신한 뒤에도 재배포할 수 있다.
AI 이미지 발행 workflow는 `ai-image-published` repository dispatch 이벤트로 이 workflow를 호출할 수 있다.

GitHub Secrets에는 아래 값을 설정한다.

```text
AWS_REGION=ap-northeast-2
EC2_INSTANCE_ID=replace-with-ec2-instance-id
EC2_DEPLOY_PATH=/opt/catchhole
EC2_DEPLOY_USER=ubuntu
```

AWS 인증은 OIDC role을 권장한다.

```text
AWS_ROLE_TO_ASSUME=replace-with-github-actions-deploy-role-arn
```

임시로 access key를 쓰는 경우에는 아래 값을 대신 설정할 수 있다.

```text
AWS_ACCESS_KEY_ID=replace-with-access-key-id
AWS_SECRET_ACCESS_KEY=replace-with-secret-access-key
```

workflow는 SSH를 사용하지 않고 SSM `AWS-RunShellScript`로 EC2 내부에서 배포 명령을 실행한다.
따라서 EC2 보안그룹의 22번 포트를 GitHub Actions에 열 필요가 없다.
자동 배포는 서버의 `/opt/catchhole/.env`를 그대로 사용한다. `.env`는 GitHub에 올리지 않는다.
GHCR package가 private이면 EC2의 deploy user로 `docker login ghcr.io`를 한 번 수행해둔다.

AI repo에서 backend 배포를 호출하려면 AI repo의 Repository Secrets에 아래 값을 추가한다.

```text
BACKEND_DEPLOY_TOKEN=replace-with-token-that-can-create-repository-dispatch
```

이 토큰은 `catchhole-soma/catchhole-backend-java`에 `repository_dispatch` 이벤트를 만들 수 있어야 한다.
classic PAT를 쓰면 `repo` scope가 필요하고, fine-grained PAT를 쓰면 백엔드 repo에 대한 `Contents: write` 권한이 필요하다.

## AWS 권한

대상 EC2는 Systems Manager managed node로 등록되어 있어야 한다. EC2 IAM Role에는 `AmazonSSMManagedInstanceCore`가 필요하다.

GitHub Actions가 사용하는 AWS role 또는 user에는 최소한 아래 권한이 필요하다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ec2:ap-northeast-2:*:instance/*",
        "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"
      ]
    },
    {
      "Effect": "Allow",
      "Action": "ssm:GetCommandInvocation",
      "Resource": "*"
    }
  ]
}
```

S3 접근은 EC2 IAM Role을 사용한다. `.env`에는 `AWS_REGION`, `AWS_S3_BUCKET`만 둔다.

Docker 컨테이너 안에서 IAM Role credential 조회가 실패하면 EC2 Metadata option의 `HttpPutResponseHopLimit` 값을 `2`로 올린다.
