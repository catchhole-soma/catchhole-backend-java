# CatchHole API 서버 운영 배포

이 문서는 API 서버용 Amazon EC2 인스턴스에서 Caddy, Spring Backend, Redis만 실행하는 절차를 설명한다. PostgreSQL은 Amazon RDS를 사용하고 인공지능 작업 프로세스는 별도의 Worker 서버용 Amazon EC2 인스턴스에서 실행한다.

## 서버 파일

API 서버에는 다음 파일을 둔다.

```text
/opt/catchhole
├── compose.api.prod.yml
├── Caddyfile
└── api.env
```

- `compose.api.prod.yml`과 `Caddyfile`은 백엔드 저장소에서 내려받는다.
- `api.env`는 `deploy/api.env.example`을 기준으로 서버에서 직접 작성하고 커밋하지 않는다.
- 기존 통합 배포의 `.env`, `compose.prod.yml`, PostgreSQL 볼륨은 전환 검증이 끝날 때까지 삭제하지 않는다.
- 보존 기간에는 `docker compose up`에 `--remove-orphans`를 붙이지 않는다.

## 네트워크 계약

- Caddy는 인터넷에서 TCP 80번과 443번 포트를 받는다.
- Spring Backend의 TCP 8080번 포트는 호스트에 게시하지만, API 서버 보안 그룹은 Worker 서버 보안 그룹에서 시작된 요청만 허용한다.
- Amazon RDS의 TCP 5432번 포트는 API 서버 보안 그룹과 Worker 서버 보안 그룹에서 시작된 요청만 허용한다.
- Redis는 Docker 네트워크 안에서만 접근하며 호스트 포트를 게시하지 않는다.

## 환경변수 계약

`api.env`의 데이터베이스 항목은 다음 형식을 사용한다.

```dotenv
DATABASE_URL=jdbc:postgresql://replace-with-rds-endpoint:5432/catchhole?sslmode=require
DATABASE_USERNAME=catchhole_admin
DATABASE_PASSWORD=replace-with-strong-rds-password
DATABASE_POOL_MAXIMUM_SIZE=10
DATABASE_POOL_MINIMUM_IDLE=2
```

`INTERNAL_API_KEY`는 Worker 서버의 `SPRING_INTERNAL_API_KEY`와 정확히 같은 값이어야 한다. 실제 데이터베이스 비밀번호, 내부 API 키, JSON Web Token 서명키, 문자 발송 자격 증명은 GitHub와 저장소에 올리지 않는다.

Spring Backend의 HikariCP 최대 연결 수는 10개다. Worker 서버의 SQLAlchemy 연결 수 17개와 합쳐 애플리케이션이 사용하는 최대 데이터베이스 연결 수는 27개다.

## 최초 전환 전 확인

다음 항목을 먼저 기록한다.

1. 현재 실행 중인 백엔드 이미지의 정확한 SHA 태그
2. 현재 실행 중인 인공지능 작업 이미지의 정확한 SHA 태그
3. 현재 Flyway 버전
4. `PENDING`과 `RUNNING` 상태의 분석 작업 수
5. 기존 PostgreSQL Docker 볼륨 이름
6. API 서버용 Amazon EC2 인스턴스 ID와 사설 IPv4 주소
7. Worker 서버용 Amazon EC2 인스턴스 ID와 사설 IPv4 주소
8. Amazon RDS 엔드포인트, 포트, 데이터베이스 이름, 사용자 이름

API 서버의 기존 통합 배포 디렉터리에서 실행 중인 작업을 확인하고, `RUNNING` 작업이 0이 된 다음 기존 Worker를 종료한다.

```bash
cd /opt/catchhole
```

```bash
sudo docker compose --env-file .env -f compose.prod.yml stop -t 210 ai-worker ai-character-comparison-worker ai-world-comparison-worker
```

종료 상태를 확인한다.

```bash
sudo docker compose --env-file .env -f compose.prod.yml ps ai-worker ai-character-comparison-worker ai-world-comparison-worker
```

기존 Worker가 멈추기 전에는 API 서버를 Amazon RDS로 전환하지 않는다. 기존 Worker가 로컬 PostgreSQL을 사용하고 새 API 서버가 Amazon RDS를 사용하면 한 작업의 상태와 결과가 서로 다른 데이터베이스에 기록될 수 있다.

## API 서버 배포 파일 검증

배포 파일을 준비한 뒤 API 서버에서 다음 명령을 실행한다.

```bash
cd /opt/catchhole
```

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml config --quiet
```

오류가 없으면 이미지를 내려받는다.

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml pull
```

API 서버 구성만 실행한다.

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml up -d
```

실행 상태를 확인한다.

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml ps
```

다음 세 서비스가 실행 중이어야 한다.

```text
caddy
backend
redis
```

## Amazon RDS 초기화 검증

새 Amazon RDS는 빈 데이터베이스이므로 Spring Backend가 처음 시작될 때 Flyway가 스키마를 생성한다. 백엔드 로그에서 Flyway와 Hibernate 검증 결과를 확인한다.

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml logs --tail=300 backend
```

API 서버에서 PostgreSQL 클라이언트로 접속한다. 아래 명령의 엔드포인트와 사용자 이름은 실제 값으로 바꾸며, 비밀번호는 프롬프트에서 입력한다.

```bash
psql "host=replace-with-rds-endpoint port=5432 dbname=catchhole user=catchhole_admin sslmode=require" -W
```

접속한 PostgreSQL 프롬프트에서 다음 쿼리를 각각 실행한다.

```sql
SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

```sql
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
```

```sql
SELECT current_database(), current_user, inet_server_addr(), inet_server_port();
```

확인 기준은 다음과 같다.

- 가장 높은 Flyway 버전이 저장소의 최신 마이그레이션 버전과 같다.
- `vector` 확장 버전이 `0.8.2`다.
- 현재 데이터베이스가 `catchhole`이다.
- 애플리케이션 기동 로그에 Hibernate 스키마 검증 오류가 없다.

PostgreSQL 프롬프트를 종료한다.

```sql
\q
```

## API 서버 통신 검증

API 서버 안에서 Spring Backend 상태를 확인한다.

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

외부 HTTPS 상태를 확인한다.

```bash
curl -fsS https://api.catchhole.com/actuator/health
```

Worker 서버에서 API 서버의 사설 IPv4 주소로 확인한다. 아래 주소는 실제 API 서버 사설 IPv4 주소로 바꾼다.

```bash
curl -fsS http://replace-with-api-private-ip:8080/actuator/health
```

세 요청 모두 정상 응답을 반환해야 한다. Worker 서버가 사설 8080번 포트에 연결하지 못하면 다음 항목을 확인한다.

1. API 서버에 `catchhole-api-prod-sg` 보안 그룹이 연결되어 있는지 확인한다.
2. Worker 서버에 `catchhole-worker-prod-sg` 보안 그룹이 연결되어 있는지 확인한다.
3. API 서버 보안 그룹의 TCP 8080번 인바운드 소스가 Worker 서버 보안 그룹인지 확인한다.
4. `compose.api.prod.yml`의 `8080:8080` 포트 게시가 적용되었는지 확인한다.

## 기존 로컬 PostgreSQL 보존

새 API 서버와 Worker 서버의 읽기·쓰기 검증이 끝나면 기존 로컬 PostgreSQL 컨테이너만 중지한다.

```bash
sudo docker compose --env-file .env -f compose.prod.yml stop postgres
```

컨테이너와 볼륨은 즉시 삭제하지 않는다. 기존 볼륨 이름을 기록하고 합의한 보존 기간이 끝날 때까지 유지한다.

## GitHub Actions 자동 배포

`.github/workflows/deploy-api-ec2.yml`은 `Publish Backend Image`가 `main` 브랜치에서 성공하거나 사용자가 수동 실행할 때 API 서버용 Amazon EC2 인스턴스만 배포한다.

백엔드 저장소의 GitHub Actions 비밀값은 다음 이름을 사용한다.

```text
AWS_REGION=ap-northeast-2
API_EC2_INSTANCE_ID=replace-with-api-ec2-instance-id
API_EC2_DEPLOY_PATH=/opt/catchhole
API_EC2_DEPLOY_USER=ubuntu
```

AWS OpenID Connect 역할을 사용하는 경우 다음 값도 설정한다.

```text
AWS_ROLE_TO_ASSUME=replace-with-github-actions-deploy-role-arn
```

기존 액세스 키 방식을 임시로 유지하는 경우 다음 두 값이 필요하다.

```text
AWS_ACCESS_KEY_ID=replace-with-access-key-id
AWS_SECRET_ACCESS_KEY=replace-with-secret-access-key
```

GitHub Actions가 사용하는 AWS Identity and Access Management 사용자 또는 역할에는 API 서버용 Amazon EC2 인스턴스를 대상으로 `ssm:SendCommand`를 실행하고 결과를 조회할 권한이 있어야 한다. 대상 인스턴스 권한에 기존 인스턴스 ID만 적혀 있다면 현재 API 서버용 인스턴스 ID에 맞게 갱신해야 한다.

## 이전 백엔드 이미지로 되돌리기

데이터베이스 마이그레이션이 이전 이미지와 호환되는지 먼저 확인한다. 호환되지 않는 마이그레이션이 적용됐다면 이전 이미지를 단순 실행하지 말고 호환 코드를 새 이미지로 배포한다.

호환되는 경우 `api.env`의 `BACKEND_IMAGE`를 기록해 둔 SHA 태그로 변경한다.

```dotenv
BACKEND_IMAGE=ghcr.io/catchhole-soma/catchhole-backend-java:sha-replace-with-previous-short-sha
```

그다음 백엔드만 다시 생성한다.

```bash
cd /opt/catchhole
```

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml pull backend
```

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml up -d --force-recreate backend
```

```bash
curl -fsS https://api.catchhole.com/actuator/health
```

## 종료 신호 전달

백엔드 이미지는 셸 안에서 Java 프로세스를 `exec`로 실행한다. Docker가 보내는 종료 신호가 Java 프로세스에 직접 전달되므로 종료 시 셸 프로세스 때문에 전체 제한 시간을 기다리는 문제를 방지한다.
