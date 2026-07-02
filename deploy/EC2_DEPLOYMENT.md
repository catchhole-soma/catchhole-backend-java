# CatchHole Production Compose

EC2 단일 서버에서 Caddy, Spring Backend, Python AI Worker, PostgreSQL을 Docker Compose로 실행한다.

## EC2 배치 경로

운영 서버에서는 아래 파일들을 `/opt/catchhole`에 둔다.

```text
/opt/catchhole
├── compose.prod.yml
├── Caddyfile
└── .env
```

`.env`는 `deploy/.env.example`을 기준으로 서버에서 직접 작성하고 커밋하지 않는다.

## 실행

```bash
cd /opt/catchhole
docker compose --env-file .env -f compose.prod.yml pull
docker compose --env-file .env -f compose.prod.yml up -d
docker compose --env-file .env -f compose.prod.yml ps
```

## 확인

```bash
curl https://api.catchhole.com/actuator/health
docker compose --env-file .env -f compose.prod.yml logs -f backend
docker compose --env-file .env -f compose.prod.yml logs -f ai-worker
```

## GitHub Actions 자동 배포

`Deploy EC2` workflow는 `Publish Backend Image` workflow가 main 브랜치에서 성공하면 이어서 실행된다.
또한 Actions 화면에서 수동 실행할 수 있으므로 AI 이미지만 갱신한 뒤에도 재배포할 수 있다.

GitHub Secrets에는 아래 값을 설정한다.

```text
EC2_HOST=replace-with-ec2-host
EC2_USER=ubuntu
EC2_SSH_KEY=replace-with-private-key-content
EC2_DEPLOY_PATH=/opt/catchhole
```

GHCR package가 private이면 EC2에서 `docker login ghcr.io`를 한 번 수행하거나, 아래 Secrets를 추가해 workflow가 매 배포 때 로그인하도록 한다.

```text
GHCR_USERNAME=replace-with-github-username
GHCR_READ_TOKEN=replace-with-read-packages-token
```

자동 배포는 서버의 `/opt/catchhole/.env`를 그대로 사용한다. `.env`는 GitHub에 올리지 않는다.

## AWS 권한

S3 접근은 EC2 IAM Role을 사용한다. `.env`에는 `AWS_REGION`, `AWS_S3_BUCKET`만 둔다.

Docker 컨테이너 안에서 IAM Role credential 조회가 실패하면 EC2 Metadata option의 `HttpPutResponseHopLimit` 값을 `2`로 올린다.
