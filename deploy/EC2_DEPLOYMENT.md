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
