# 운영 Trace 수신 환경 준비 (#271, 7단계)

이 문서는 **검토된 설정을 운영에 적용할 때의 절차**다. 파일 작성과 로컬 검증만으로
AWS 리소스 생성, 운영 배포, 실제 분석 Trace 저장이 완료됐다고 판단하지 않는다.
현재 메트릭 운영은 [기존 모니터링 배포 안내](MONITORING_DEPLOYMENT.md)를 유지한다.

## 1. 구성과 적용 순서

```text
Worker의 분석/LLM Span ── OTLP HTTP ────────────┐
Worker ── traceparent + 내부 API 인증 ──> Spring │
Spring HTTP/JDBC Span ── OTLP HTTP ────────────┤
                                             v
모니터링 EC2 사설 IPv4:4318 → Collector → Tempo → 전용 S3 버킷/tempo/
                                           │
                                     named volume의 WAL
                                           │
기존 HTTPS Grafana → Tempo 데이터 소스 → Trace 조회
```

- `compose.tracing.prod.yml`은 `compose.monitoring.prod.yml`과 함께 명시한 경우에만
  적용하는 overlay다. 기존 `catchhole-monitoring-prod` 프로젝트, Prometheus/Grafana/
  인증서 볼륨, HTTPS 설정을 유지한다. 별도 profile은 필요 없다.
- Collector contrib `0.162.0`, Tempo `3.1.0`을 고정한다. Tempo는 단일 프로세스
  `-target=all`이며 S3 블록과 `/var/tempo` 전체 named volume을 사용한다.
  이 볼륨에는 `/var/tempo/wal`뿐 아니라 Tempo 3.1의 기본 live-store WAL 경로인
  `/var/tempo/live-store/traces`도 포함되어 컨테이너 재생성 시 보존된다.
- 운영 저장 기간은 초기 **72시간(3일)**이다. 로컬 실험의 local backend/24시간과 다르다.
  block retention은 비동기 정리 기준이며 정확히 72시간이 되는 순간 모든 파일이 지워지거나
  디스크/비용이 일정 크기로 제한된다는 의미는 아니다.
- 순서는 **수신 서버 → Spring → Worker 한 프로세스 canary → 나머지 Worker 확대**다.
  수신 환경 준비만으로 기존 분석·AI 대시보드에 Trace 패널을 자동 추가하지 않는다.
- Tail sampling은 OFF다. Worker의 head 결정과 Java 부모 결정 상속을 유지한다.
  오류 Trace를 항상 보관한다고 보장하지 않는다. 운영 head 비율 변경은 별도 판단한다.

## 2. 서버 용량과 AWS 사전 준비

배포 전 현재 EC2 인스턴스 유형·가용 메모리·CPU/크레딧·디스크 여유·기존 컨테이너 사용량을
확인한다. 과거에 t3.small(2GiB)을 선택했더라도 현재 사양과 여유를 다시 확인한다.
Grafana mount 목록을 안전하게 교체하는 `!override`를 사용하므로 Docker Compose는
**2.24.4 이상**이어야 한다.

```bash
free -m
df -h
docker stats --no-stream
docker system df
docker compose version
```

Collector 상한은 256MiB, Tempo 상한은 512MiB다. 합계 768MiB는 **추가 서비스의 컨테이너
메모리 제한**이며 안정적인 운영에 필요한 전체 용량이나 현재 서버의 여유 메모리가 아니다.
기존 Prometheus·Grafana·Nginx·OS·파일 캐시와 수집/조회 피크까지 감당해야 한다.
상한에 도달하면 OOM 종료와 Trace 손실이 가능하다. 여유가 부족하면 인스턴스 증설 또는
Trace 수신 환경 분리를 먼저 하고, 적은 표본으로 수집·조회 중 실사용량을 확인한다.

AWS 설정은 아래 사항을 별도로 준비한 뒤 결과를 기록한다. 이 문서가 자동으로 변경하지 않는다.

1. **전용 S3 버킷**을 모니터링 EC2와 같은 리전에 만든다. 네 가지 Block Public Access를
   모두 켜고, ACL 비활성(Object Ownership: bucket owner enforced), 기본 암호화 SSE-S3를
   사용한다. HTTPS 아닌 접근을 거부하는
   [버킷 정책 예제](monitoring/tracing/tempo-s3-bucket-policy.json.example)를 적용한다.
   이 정책의 `Deny`는 접근을 부여하지 않으며 실제 허용은 다음 IAM 정책으로 제한한다.
2. 초기 버킷은 versioning/Object Lock을 켜지 않는다. 나중에 versioning을 켜면 Tempo 삭제가
   이전 버전까지 없애지 않으므로 별도의 noncurrent-version 보존 정책이 필요하다.
   SSE-KMS를 선택하면 해당 KMS 키 정책·암복호화 권한도 별도로 검토한다.
3. [IAM 정책 예제](monitoring/tracing/tempo-s3-policy.json.example)의 버킷 이름을 실제
   이름으로 교체해 모니터링 EC2의 **기존 instance role에 추가**한다. 기존 SSM 정책은
   유지한다. 접근 범위는 해당 버킷의 `tempo/` prefix에 필요한 목록/읽기/쓰기/삭제/tag만
   허용하며 버킷 생성이나 다른 서비스 데이터 권한을 부여하지 않는다.
4. Access key를 env·이미지·설정에 넣거나 `~/.aws`를 mount하지 않는다. Tempo 컨테이너가
   EC2 role의 임시 자격 증명을 사용하도록 IMDS endpoint 활성, IMDSv2 필수와 Docker bridge
   환경의 응답 hop limit 2를 확인한다. 이 role은 EC2에 속하므로 같은 호스트의 신뢰된
   컨테이너도 metadata에 접근할 수 있다는 경계를 고려한다. 자격 증명 응답은 출력하지 않는다.
5. 모니터링 EC2에서 S3 HTTPS 접근 경로(인터넷/NAT 또는 S3 VPC endpoint와 그 정책),
   DNS 및 시간 동기화를 확인한다. `TEMPO_S3_BUCKET`, `AWS_REGION`을 실제 값으로 설정한다.
6. 모니터링 SG의 **TCP 4318 인바운드 소스를 API SG·Worker SG로만** 허용한다. 각 송신
   SG의 아웃바운드도 확인한다. 4318을 인터넷 CIDR에 공개하거나 Nginx HTTPS 경로에
   연결하지 않는다. Tempo 3200/4318, Collector health 13133은 호스트에 열지 않는다.

불완전 multipart upload 정리 규칙은 별도로 둘 수 있다. 완성된 Trace 객체를 Tempo의
보존 기간보다 일찍 지우는 S3 lifecycle expiration은 적용하지 않는다. S3는 Trace 블록의
영속 저장소이지 Collector의 메모리 큐나 아직 업로드되지 않은 WAL의 백업은 아니다.

## 3. 파일 반영과 설정 검사

검토·머지·CI를 통과한 **같은 버전**을 사용한다. API/Worker 운영 소스나 이미지에 SSM으로
코드를 직접 덧붙이지 않는다. API는 [Java 배포 준비](API_TRACING.md), Worker는
[AI 저장소 운영 안내](https://github.com/catchhole-soma/catchhole-backend-ai/blob/main/deploy/WORKER_EC2_DEPLOYMENT.md)를 따른다.
두 저장소가 준비되기 전에는 계측 플래그를 OFF로 유지한다.

모니터링 서버의 기존 `/opt/catchhole-monitoring`에 다음 파일만 같은 구조로 반영한다.
기존 env·targets·dashboard·Grafana DB·인증서는 앞선 배포 안내대로 보관한다.
실제 env를 예제 파일로 덮어쓰지 않고 필요한 키만 추가한다.

```text
compose.tracing.prod.yml
monitoring/tracing/
  otel-collector.yml
  tempo.yml
  grafana-tempo.yml
  tempo-s3-policy.json.example   # 검토 자료이며 컨테이너가 읽지 않음
  tempo-s3-bucket-policy.json.example
```

`monitoring.env`의 새 키는 아래 세 개다. 실제 파일 권한은 600으로 유지한다.

```dotenv
TRACING_OTLP_BIND_ADDRESS=127.0.0.1
TEMPO_S3_BUCKET=<실제로 준비한 전용 버킷 이름>
AWS_REGION=ap-northeast-2
```

처음에는 localhost 바인딩으로 준비한다. overlay 없이 기존 메트릭 Compose만 사용하면
S3 env를 요구하지 않는다. overlay에서는 S3 버킷과 리전이 비어 있으면 검사부터 실패한다.

```bash
cd /opt/catchhole-monitoring
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml config --quiet
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml pull tempo-volume-init tempo otel-collector
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml run --rm --no-deps otel-collector validate --config=/etc/otelcol/config.yml
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml run --rm --no-deps tempo -target=all -config.file=/etc/tempo.yml -config.expand-env=true -config.verify=true
```

`config --quiet`는 env를 출력하지 않는다. Tempo `config.verify`는 문법/구성 검사이며
실제 S3 쓰기·조회 권한이나 EC2 용량을 증명하지 않는다.

## 4. 수신 환경 실행과 Grafana 연결

```bash
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml up -d tempo-volume-init tempo otel-collector
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml ps -a
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml exec -T grafana wget -qO- http://tempo:3200/ready
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml exec -T grafana wget -qO- http://otel-collector:13133/
```

`tempo-volume-init`은 기존에 고정한 Grafana 이미지의 shell로 **새 Tempo 볼륨만**
준비하는 일회성 root 컨테이너다. 네트워크가 없으며 CHOWN/DAC_OVERRIDE만 허용해
Docker의 초기 소유권 차이를 처리한다. 이후 Tempo와 Collector는 UID/GID 10001로 실행된다.
프로세스에는 쓰기 가능한 root filesystem을 주지 않는다. Tempo WAL과 제한된 `/tmp`만 쓴다.

Tempo 준비까지 시간이 걸리면 잠시 뒤 다시 확인한다. Tempo 이미지에는 shell/curl이 없으므로
가짜 컨테이너 healthcheck를 넣지 않고 같은 네트워크의 기존 Grafana `wget`으로 조회한다.
Collector health 200은 프로세스 준비 상태이며 **Tempo/S3 전달 성공을 뜻하지 않는다**.
계속 실패하면 새 서비스 로그와 AWS 접근을 해결하고 앱 계측을 켜지 않는다.

SG 준비 뒤 `TRACING_OTLP_BIND_ADDRESS`를 모니터링 EC2의 실제 사설 IPv4로 바꾸고
Collector만 재생성한다. `0.0.0.0`이나 공인 IP는 사용하지 않는다.

```bash
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml up -d --no-deps --force-recreate otel-collector
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml up -d --no-deps --force-recreate grafana
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml exec -T nginx nginx -t
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml exec -T nginx nginx -s reload
```

Grafana 재생성은 기존 데이터 볼륨과 Prometheus 파일을 유지하며 Tempo provisioning 파일
하나를 추가한다. read-only 디렉터리 안에 새 mountpoint를 만들지 않도록 `!override`로
두 데이터 소스 파일을 개별 mount한다. 기존 provisioning 디렉터리에 다른 파일을 새로
추가한다면 이 overlay의 mount 목록에도 반영해야 한다.
Prometheus 데이터 소스 `catchhole-prometheus`는 기본값을 유지하고, 새 데이터 소스 UID는
`catchhole-tempo`, URL은 Docker 내부 `http://tempo:3200`이다. 수동으로 만든 대시보드를
덮어쓰지 않는다. Grafana 재생성 뒤 바뀔 수 있는 upstream IP를 Nginx reload로 반영한다.
기존 HTTPS 주소에서 로그인·메트릭 조회와 Explore의 Tempo 선택을 확인한다.

## 5. Spring → Worker canary → 확대

1. 수신 환경 준비와 실제 IAM/S3 접근 확인 뒤 API Java Agent를 켠다. Java는 부모 없는
   일반 사용자 요청을 새 Trace로 수집하지 않는 설정이며 내부 인증은 별도로 유지한다.
2. Worker는 하나의 프로세스에만 켜고, endpoint를
   `http://<모니터링 EC2 사설 IPv4>:4318/v1/traces`로 지정한다. 공유 env의 global
   플래그를 먼저 켜서 모든 Worker가 동시에 전송하지 않도록 AI 배포 안내를 따른다.
3. 승인된 정상 분석 표본으로 같은 Trace 안에 Worker Root → LLM/업무 Span →
   Python HTTP CLIENT → Spring HTTP SERVER → JDBC가 이어지는지 확인한다.
   Trace ID만 같다는 확인으로 끝내지 않고 parent Span ID·오류 상태·시간을 대조한다.
4. Grafana Explore에서 실제 Trace를 열고 원문·프롬프트·응답·토큰·인증/lease header·SQL
   및 식별 가능한 URL이 없는지 확인한다. 계측 오류가 분석 결과나 기존 메트릭을
   바꾸지 않는지, HTTP 오류·DB 연결·CPU·메모리·Collector 전송 실패를 함께 본다.
5. Tempo의 S3 block 업로드가 완료된 뒤 전용 버킷의 `tempo/` 아래 새 block 객체와
   Trace 재조회 성공을 확인한다. `/ready` 200이나 막 수집한 Trace의 메모리 조회만으로
   S3 저장을 입증하지 않는다. 변경 기록에 canary 시각·Trace ID·이미지 SHA·설정 버전·
   접근 통제·S3 확인 근거를 남긴다.
6. 메모리/전송량/분석 상태가 정상인 것을 확인한 뒤 나머지 Worker로 확대한다.
   운영에 고의 장애나 대규모 유료 LLM 호출을 발생시키는 절차는 포함하지 않는다.

데이터 양이 늘면 보존 기간·sampling·서버 자원을 함께 조정한다. batch는 256 Span 또는
1초 기준, 한 batch 최대 512 Span이다. Collector queue는 **100개의 export 요청 묶음**
이며 100개의 Span이라는 의미가 아니다. 재시도 총 시간 30초, 개별 전송 timeout 5초,
병렬 consumer 2개다. 앱 SDK에도 별도 queue가 있으며 앱→Collector→Tempo→S3는 독립 단계다.
큐 포화·재시도 만료·강제 종료에서 일부 Trace가 유실될 수 있고 영구 전송을 보장하지 않는다.

## 6. 롤백과 영속성

- 앱 영향이 있으면 Worker 계측을 끄고 필요하면 Java 계측도 끈 뒤 해당 서비스만
  정상 배포 경로로 재생성한다. Worker graceful shutdown 정책과 실행 중 분석을 존중한다.
- 새 수신 서비스를 중단하려면 overlay를 포함한 Compose의
  `stop otel-collector tempo`만 실행한다. Prometheus·Grafana·Nginx를 함께 내리지 않는다.
- `down -v`, S3 비우기, Tempo/Grafana 볼륨 삭제는 롤백이 아니다. 기존 네 개 볼륨과
  새 `tempo_data`를 유지한다. `tempo_data`에는 아직 S3에 업로드하지 못한 WAL이 있다.
- Tempo 장애 중 Grafana의 기존 메트릭은 계속 조회할 수 있다. Tempo 데이터 소스는
  남겨두면 일시적으로 unavailable이며, 파일 mount를 제거하는 것만으로 Grafana DB의
  provisioning된 데이터 소스가 반드시 삭제된다고 가정하지 않는다.
- S3 backend도 수신 서버 자체를 HA로 만들지는 않는다. 단일 EC2/디스크 장애, WAL 손실,
  전송되지 않은 Span 손실, 백업/복구 요구는 별도로 관리한다.

## 근거와 검증 범위

- [Tempo 구성](https://grafana.com/docs/tempo/latest/configuration/):
  S3·WAL·환경변수 확장·compaction 보관 설정과 query 제한.
- [Tempo S3](https://grafana.com/docs/tempo/latest/configuration/hosted-storage/s3/):
  EC2 instance role, 필요한 S3 권한, multipart 정리.
- [EC2 metadata와 컨테이너](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html):
  IMDSv2와 컨테이너 hop limit.
- [Compose override](https://docs.docker.com/reference/compose-file/merge/#replace-value):
  `!override`와 최소 Compose 버전.

설정 검증과 격리 로컬 smoke는 실제 AWS IAM/SG/S3 및 운영 분석 canary와 구분한다.
로컬 실험에서 backend를 local로 교체했다면 S3 설정을 실행 검증한 것으로 기록하지 않는다.

2026-10-21 로컬 검증: 운영 S3 설정의 Tempo `config.verify`, Collector `validate`,
Compose 병합 검사를 통과했다. 별도 임시 project와 볼륨에 **local backend로만 교체한
smoke**에서 UID 10001의 WAL 쓰기, 두 데이터 소스 공존, 합성 Span의 Collector→Tempo→
Grafana proxy 조회, Tempo 컨테이너 재생성 뒤 Trace 보존을 확인했다. 이 결과는 운영
S3 업로드·IAM·SG, 실제 분석 부하, 72시간 뒤 정리 또는 운영 안정성을 검증한 결과가 아니다.

같은 날 Java 전체 `test bootJar`는 3,276개 중 236개 skip, 실패 0개였으며 개인정보
제거 확장 테스트 6개도 통과했다. `scripts/tracing/verify_production_image.py`는 실제
Dockerfile 이미지의 entrypoint·Agent·확장·운영 설정을 그대로 사용하고 앱 JAR만 합성
HTTP/H2 fixture로 바꾸어 검사했다. 성공·HTTP 500 두 Trace의 SERVER 2개·JDBC 4개
Span, 부모 연결, `prod`·프로세스 UUID와 전송 전 정보 제거를 확인했다.
부모 없는 요청·sampled=0은 전송되지 않았고, OFF는 Agent를 실행하지 않았으며 확장이
빠진 번들은 시작을 거절했다. 운영 DB·실제 Worker 분석·S3 저장의 검증은 아니다.

```bash
# 저장소 루트에서 실제 이미지를 먼저 빌드한다.
docker build -t catchhole-backend-tracing:stage7-local .
./gradlew --no-daemon -I scripts/tracing/verification.init.gradle traceVerificationBootJar
docker pull python:3.12-slim
# OTLP protobuf가 설치된 AI checkout의 Python을 사용한다.
/absolute/path/catchhole-backend-ai/.venv/bin/python scripts/tracing/verify_production_image.py --image catchhole-backend-tracing:stage7-local --output /tmp/catchhole-production-image-check.json
```
