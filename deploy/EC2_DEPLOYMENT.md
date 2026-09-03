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
- `BACKEND_IMAGE`에는 발행이 성공한 이미지의 `sha-<short-sha>` 태그를 넣는다. 자동 배포는 이 값을 배포 대상 SHA로 갱신한다.
- 기존 통합 배포의 `.env`, `compose.prod.yml`, PostgreSQL 볼륨은 전환 검증이 끝날 때까지 삭제하지 않는다.
- 보존 기간에는 `docker compose up`에 `--remove-orphans`를 붙이지 않는다.

## 데이터 전환 결정

NVM-317 최초 전환은 기존 로컬 PostgreSQL 데이터를 Amazon RDS로 이전하지 않고 빈 데이터베이스에서 새로 시작한다. 따라서 전환 후에는 기존 회원, 작품, 회차, 분석 작업과 Amazon S3 객체를 참조하던 메타데이터가 새 서비스에 표시되지 않는다. Amazon S3 객체 자체는 삭제하지 않지만 새 데이터베이스에는 이를 가리키는 기존 행이 없다.

이 전환은 기존 운영 데이터를 보존해야 한다면 진행하면 안 된다. 보존 요구가 생기면 `DATABASE_URL`을 변경하기 전에 배포를 중단하고 `pg_dump`, Amazon RDS 복원, 행 수와 주요 엔티티 검증을 포함한 별도 이전 계획을 먼저 승인받는다. 기존 PostgreSQL 볼륨은 현재의 신규 시작 결정과 무관하게 롤백 보존 기간이 끝날 때까지 유지한다.

## 네트워크 계약

- Caddy는 인터넷에서 TCP 80번과 443번 포트를 받는다.
- Spring Backend의 TCP 8080번 포트는 호스트에 게시하지만, API 서버 보안 그룹은 Worker 서버 보안 그룹에서 시작된 요청만 허용한다.
- Amazon RDS의 TCP 5432번 포트는 API 서버 보안 그룹과 Worker 서버 보안 그룹에서 시작된 요청만 허용한다.
- Redis는 Docker 네트워크 안에서만 접근하며 호스트 포트를 게시하지 않는다.

## API 서버 인스턴스 역할과 메타데이터 설정

Spring Backend 컨테이너는 고정 AWS 액세스 키 대신 API 서버용 Amazon EC2 인스턴스 역할로 Amazon S3에 접근한다. API 서버용 Amazon EC2 인스턴스에 Amazon S3 버킷 읽기·쓰기 권한이 있는 역할을 연결한다.

Docker 브리지 네트워크 안의 컨테이너가 Instance Metadata Service Version 2 응답을 받을 수 있도록 API 서버용 Amazon EC2 인스턴스의 메타데이터 응답 홉 제한을 `2`로 설정한다. Amazon EC2 콘솔에서 인스턴스를 선택한 뒤 **작업 → 인스턴스 설정 → 인스턴스 메타데이터 옵션 수정**에서 다음과 같이 설정한다.

- Instance Metadata Service: 활성화
- Instance Metadata Service Version 2: 필수
- 메타데이터 응답 홉 제한: `2`

AWS Command Line Interface로 수정할 때는 아래 인스턴스 ID를 실제 API 서버용 Amazon EC2 인스턴스 ID로 바꾼다.

```bash
aws ec2 modify-instance-metadata-options \
  --instance-id replace-with-api-ec2-instance-id \
  --http-endpoint enabled \
  --http-tokens required \
  --http-put-response-hop-limit 2 \
  --region ap-northeast-2
```

적용 상태를 확인한다.

```bash
aws ec2 describe-instances \
  --instance-ids replace-with-api-ec2-instance-id \
  --region ap-northeast-2 \
  --query 'Reservations[0].Instances[0].MetadataOptions.{Endpoint:HttpEndpoint,Tokens:HttpTokens,HopLimit:HttpPutResponseHopLimit,State:State}' \
  --output table
```

`Endpoint=enabled`, `Tokens=required`, `HopLimit=2`, `State=applied`여야 한다. 호스트에서의 AWS 자격 증명 검증만으로 대체하지 않고, API 서버에서 Docker 컨테이너를 직접 실행해 역할과 Amazon S3 권한을 확인한다. 버킷 이름은 `api.env`의 `AWS_S3_BUCKET` 실제 값으로 바꾼다.

```bash
sudo docker run --rm \
  -e AWS_REGION=ap-northeast-2 \
  public.ecr.aws/aws-cli/aws-cli:latest \
  sts get-caller-identity
```

```bash
sudo docker run --rm \
  -e AWS_REGION=ap-northeast-2 \
  public.ecr.aws/aws-cli/aws-cli:latest \
  s3api get-bucket-location \
  --bucket replace-with-s3-bucket-name \
  --region ap-northeast-2
```

첫 번째 명령은 API 서버용 인스턴스 역할의 ARN을 포함한 응답을 반환해야 하고, 두 번째 명령은 버킷 위치를 오류 없이 반환해야 한다. `Unable to locate credentials`가 나오면 인스턴스 역할 연결과 메타데이터 홉 제한을 다시 확인한다. `AccessDenied`가 나오면 인스턴스 역할의 Amazon S3 정책을 확인한다.

## 환경변수 계약

`api.env`의 데이터베이스 항목은 다음 형식을 사용한다.

```dotenv
APP_TIMEZONE=Asia/Seoul
DATABASE_URL=jdbc:postgresql://replace-with-rds-endpoint:5432/catchhole?sslmode=require
DATABASE_USERNAME=catchhole_admin
DATABASE_PASSWORD=replace-with-strong-rds-password
DATABASE_POOL_MAXIMUM_SIZE=10
DATABASE_POOL_MINIMUM_IDLE=2
```

`INTERNAL_API_KEY`는 Worker 서버의 `SPRING_INTERNAL_API_KEY`와 정확히 같은 값이어야 한다. 실제 데이터베이스 비밀번호, 내부 API 키, JSON Web Token 서명키, 문자 발송 자격 증명은 GitHub와 저장소에 올리지 않는다.

Spring Backend의 HikariCP 최대 연결 수는 10개다. Worker 서버의 SQLAlchemy 연결 수 17개와 합쳐 애플리케이션이 사용하는 최대 데이터베이스 연결 수는 27개다.

## Amazon RDS와 연결 session 시간대

Amazon RDS의 PostgreSQL session 시간대는 `Asia/Seoul`로 고정한다. Amazon RDS 콘솔의 **파라미터 그룹**에서 실제 PostgreSQL 주 버전과 같은 패밀리의 사용자 정의 DB 파라미터 그룹을 만들고 `timezone=Asia/Seoul`로 설정한다. 예를 들어 PostgreSQL 16을 사용하면 `postgres16` 패밀리를 선택한다. 기본 DB 파라미터 그룹은 직접 수정하지 않는다.

생성한 DB 파라미터 그룹을 `catchhole-prod-postgres` Amazon RDS 인스턴스에 연결하고 인스턴스가 `사용 가능` 상태가 될 때까지 기다린다. Amazon RDS 콘솔이 `pending-reboot`를 표시하면 서비스 중단 가능 시간에 재부팅한 뒤 다음 쿼리로 검증한다.

```sql
SHOW timezone;
```

결과는 `Asia/Seoul`이어야 한다. 추가로 Spring Backend는 HikariCP가 새 물리 연결을 만들 때마다 `api.env`의 `APP_TIMEZONE`을 PostgreSQL session에 적용한다. Amazon RDS가 시작될 때의 기본값과 애플리케이션이 만든 연결의 값을 둘 다 `Asia/Seoul`로 유지한다.

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

```sql
SHOW timezone;
```

확인 기준은 다음과 같다.

- 가장 높은 Flyway 버전이 저장소의 최신 마이그레이션 버전과 같다.
- `vector` 확장 버전이 `0.8.2`다.
- 현재 데이터베이스가 `catchhole`이다.
- PostgreSQL session 시간대가 `Asia/Seoul`이다.
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

`.github/workflows/deploy-api-ec2.yml`은 `main` push에서 시작된 `Publish Backend Image`가 성공했을 때만 API 서버용 Amazon EC2 인스턴스를 배포한다. 수동 이미지 발행은 API 배포로 이어지지 않는다.

배포 Workflow는 `Publish Backend Image` 실행의 commit SHA를 기준으로 `compose.api.prod.yml`과 `Caddyfile`을 내려받고 같은 SHA의 `sha-<short-sha>` 이미지 태그를 `api.env`에 기록한다. `main` 태그나 실행 시점의 최신 배포 파일을 사용하지 않으므로 서로 다른 커밋의 이미지와 설정이 섞이지 않는다.

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

롤백 원인을 해결한 뒤에는 GitHub Actions에서 복구할 SHA에 대응하는 성공한 `Deploy API EC2` 실행을 다시 실행한다. 이 Workflow는 해당 publish run의 SHA 태그로 `api.env`를 갱신한 뒤 API 서버를 재배포한다. 이후 새로운 `main` 이미지 발행이 성공해도 같은 방식으로 `BACKEND_IMAGE`가 새 SHA로 자동 갱신되므로 롤백 이미지가 다음 자동 배포에 남지 않는다.

## 종료 신호 전달

백엔드 이미지는 셸 안에서 Java 프로세스를 `exec`로 실행한다. Docker가 보내는 종료 신호가 Java 프로세스에 직접 전달되므로 종료 시 셸 프로세스 때문에 전체 제한 시간을 기다리는 문제를 방지한다.
