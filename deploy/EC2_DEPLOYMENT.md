# CatchHole Production Compose

EC2 단일 서버에서 Caddy, Spring Backend, Python AI Worker, PostgreSQL을 Docker Compose로 실행한다.

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

## 실행

```bash
cd /opt/catchhole
docker compose --env-file .env -f compose.prod.yml pull
docker compose --env-file .env -f compose.prod.yml up -d
docker compose --env-file .env -f compose.prod.yml ps
```

시간대 반영은 컨테이너 재시작이 아니라 재생성이 필요하다.

```bash
docker compose --env-file .env -f compose.prod.yml up -d --force-recreate backend ai-worker postgres
docker compose --env-file .env -f compose.prod.yml exec backend date
docker compose --env-file .env -f compose.prod.yml exec ai-worker date
docker compose --env-file .env -f compose.prod.yml exec ai-worker printenv EMBEDDING_GENERATION_ENABLED
docker compose --env-file .env -f compose.prod.yml exec postgres \
  sh -lc 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SHOW timezone; SELECT CURRENT_TIMESTAMP;"'
```

Backend 컨테이너가 시작될 때 Flyway가 미적용 migration을 먼저 실행하고, 이후 Hibernate가 JPA Entity와 schema 일치 여부를 `validate`한다. AI Worker의 SQLAlchemy 모델은 schema를 생성하지 않고 Flyway가 만든 테이블을 사용한다.

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
```

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
