# GH271 운영 Spring Trace 활성화

이 문서는 **설정 준비와 실제 활성화를 구분**한다. 현재 브랜치의 파일만 작성했거나
로컬 검증을 통과한 것으로 운영 적용 완료를 선언하지 않는다. 수신 서버·S3·Grafana는
[운영 Trace 수집 배포](TRACING_DEPLOYMENT.md), 기존 API 배포는
[EC2_DEPLOYMENT.md](EC2_DEPLOYMENT.md)를 따른다.

## 이미지와 기본 동작

Docker 이미지에는 Agent 2.32.0, SDK 1.66.0 기반 privacy extension,
`agent-prod.properties`, `backend-entrypoint.sh`를 함께 넣는다. `agent.lock.json`의
공식 SHA-256과 다운로드 바이트를 빌드 때 검증하며, 런타임에 Agent를 다운로드하지 않는다.
기존 non-root 사용자, JAR, `JAVA_OPTS`, Asia/Seoul timezone과 Actuator 메트릭은 유지한다.

`BACKEND_TRACING_ENABLED=false`가 기본이다. OFF면 `-javaagent` 없이 기존 앱을 실행한다.
ON이면 사설 OTLP 주소와 필수 번들을 검사한 후 Agent를 붙인다. 잘못된 ON 설정은
기동 오류이므로 활성화 전 검사와 health 확인이 필요하다. 수신 서버의 일시 장애는
기동 설정 오류와 다르며, 전송 실패 때문에 업무 요청을 재실행하지 않는다.

```dotenv
BACKEND_TRACING_ENABLED=false
BACKEND_TRACING_OTLP_ENDPOINT=
```

활성화 주소는 **실제 확인한 모니터링 EC2의 RFC1918 IPv4**를 사용한다.
`localhost`·`host.docker.internal`·공인 IP·인증 정보·query·fragment는 허용하지 않는다.

```dotenv
BACKEND_TRACING_ENABLED=true
BACKEND_TRACING_OTLP_ENDPOINT=http://<MONITORING_PRIVATE_IPV4>:4318/v1/traces
```

## 수집과 접근 경계

- Java는 `parentbased_always_off`로 새 Root를 수집하지 않고 sampled 부모를 이어받는다.
  HTTP server·JDBC·executor 문맥 계측만 켜며 OTLP metrics/logs는 끈다.
- 부모 결정 상속은 인증이 아니다. 공개 Caddy는 `traceparent`, `tracestate`, `baggage`를
  제거한다. Worker가 API 사설 IP:8080으로 보내는 요청은 Caddy를 거치지 않으므로
  문맥을 유지한다. 기존 API key·lease 검증도 유지한다.
- API SG의 8080이 인터넷에 열려 있으면 Caddy를 우회할 수 있다. 실제 SG에서 Worker
  사설 접근만 허용하는지 확인한다. API의 8081 메트릭 경로와 기존 health 경로는 유지한다.
- Collector 4318은 모니터링 EC2의 사설 IP에만 bind하고 API·Worker SG만 허용한다.
  새 API 인바운드 포트를 열 필요는 없다.
- 전송 전 extension이 SQL·URL·본문·헤더·예외 event/description·자유 resource를 제거한다.
  검토한 HTTP route template·method·status, DB system·operation, ID·부모·시각만 남긴다.
- Resource는 고정 service.name, `local|prod` 환경, UUID 형식 인스턴스만 허용한다.
  운영 entrypoint는 프로세스 시작마다 UUID를 생성한다. 환경이 허용 목록에 없으면
  `unknown`이며 원시 값을 노출하지 않는다. instance ID는 재시작 후 바뀐다.
- 완료 Span 큐 1,024개, 배치 256개/1초, export timeout 5초를 사용한다.
  메모리 대기열이며 전체 프로세스 메모리 상한·저장 보장·감사 원장이 아니다.

## 적용 순서

1. 수신 서버의 자원 여유·S3/IAM·SG와 Collector/Tempo 준비 상태를 검증한다.
   합성 Span을 전송하고 Grafana의 Tempo 데이터 소스에서 다시 조회한다.
2. 추적 OFF 상태로 Java PR을 검토·머지한다. 기존 `main push → image publish 성공 →
   같은 SHA API 배포`를 사용하며 운영 코드를 SSM으로 직접 덮어쓰지 않는다.
   배포 workflow가 같은 SHA의 Caddyfile을 받고 reload하는 기존 경로를 유지한다.
3. 배포된 이미지에 Agent·extension·설정이 존재하는지 확인한다. API 공개/로컬 health와
   기존 Prometheus target·패널을 먼저 확인한다.
4. `/opt/catchhole/api.env`를 비공개 백업하고 **기존 키를 수정**한다. 같은 키를 중복
   추가하지 않는다. 자동배포·챗봇 bootstrap과 같은 `.api-deploy.lock`을 잡은 상태에서
   환경 읽기·수정·Compose 검사·backend 재생성·health 확인을 완료한다.
   이 과정에서 `BACKEND_IMAGE`, JVM heap, DB pool, API 키 등 다른 값을 바꾸지 않는다.
5. 기존 Compose에서 `backend`만 재생성한다. `docker compose config --quiet`는 문법
   검사이고, 이미지 entrypoint가 주소를 검사한다. Collector 연결·SG·S3 저장 성공은
   별도 확인해야 한다. health 실패 시 다음 Worker 단계로 진행하지 않는다.
6. AI 저장소의 Worker 배포 안내에 따라 기존 캐릭터 비교 Worker **한 프로세스**만
   먼저 켠다. 나머지 분석 5개·세계관 비교 1개는 OFF로 유지한다.
7. 승인된 정상 표본에서 Python CLIENT → Java SERVER → JDBC의 Trace ID·부모 관계,
   `prod` 환경·프로세스 UUID·민감값 제거를 확인한다. 자원 사용·수신 실패와 기존
   Prometheus 조회를 대조한 뒤 다른 Worker로 확대한다.

설정 검사는 값을 출력하지 않는 형태로 수행한다.

```bash
docker compose --env-file api.env -f compose.api.prod.yml config --quiet
docker compose --env-file api.env -f compose.api.prod.yml up -d --no-deps backend
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS https://api.catchhole.com/actuator/health
```

위 명령의 실행과 환경 파일 수정은 기존 배포 lock을 소유한 운영 세션 안에서 한다.
API 재기동은 잠깐의 서비스 영향을 줄 수 있으므로 정상 health 확인까지 같은 작업으로
취급한다. `docker inspect` 전체·환경 파일·렌더된 Compose를 채팅이나 로그에 출력하지 않는다.

## 비활성화와 롤백

1. AI의 공통 및 명시한 종류별 tracing flag를 모두 OFF로 만들고 기존 drain 절차로
   반영한다. 공통값만 바꿔도 종류별 true override는 남을 수 있다.
2. API의 `BACKEND_TRACING_ENABLED=false`로 바꾸고 같은 lock·backend 재생성·health
   확인 절차를 따른다. 이 경우 같은 이미지에서 Agent를 로드하지 않는다.
3. 필요한 경우 새 Collector·Tempo만 정지하고 새 SG/IAM 권한만 기록에 따라 회수한다.
   기존 Prometheus·Grafana·인증서·Tempo WAL/S3 데이터는 임의 삭제하지 않는다.
4. 이미지 자체를 되돌릴 때는 기존 API 배포의 동일 SHA 이미지/Compose/Caddyfile 계약을
   따른다. 추적 OFF는 기존 저장 데이터 삭제나 과거 Span 복구를 의미하지 않는다.

## 로컬 검증

```bash
./gradlew --no-daemon -p monitoring/java-agent test jar prepareAgent
python3 scripts/tracing/verify_backend_entrypoint.py
docker compose --env-file deploy/api.env.example -f deploy/compose.api.prod.yml config --quiet
```

entrypoint 검증은 앱이나 AWS를 실행하지 않고 OFF의 Java 인자 보존, 잘못된 flag·주소,
필수 번들 누락의 차단을 확인한다. 실제 이미지 기동과 운영 저장은 각각 별도로 검증한다.

참고: [Java Agent 설정 우선순위](https://opentelemetry.io/docs/zero-code/java/agent/configuration/),
[Caddy upstream header 제어](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#headers).
