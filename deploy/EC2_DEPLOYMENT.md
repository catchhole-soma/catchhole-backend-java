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

## AWS 권한

S3 접근은 EC2 IAM Role을 사용한다. `.env`에는 `AWS_REGION`, `AWS_S3_BUCKET`만 둔다.

Docker 컨테이너 안에서 IAM Role credential 조회가 실패하면 EC2 Metadata option의 `HttpPutResponseHopLimit` 값을 `2`로 올린다.
