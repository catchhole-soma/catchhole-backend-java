# 로컬 트레이스 수신·저장·조회와 Python→Spring 연결 (#271, 2~6단계)

이 문서는 Collector·Tempo·Grafana의 로컬 준비, 임시 Python 예제, 5단계 Python→Spring HTTP·JDBC 연결 검증을 다룬다. 4단계 실제 Worker 업무 Span은 AI 저장소의 `docs/worker-tracing.md`에 정리되어 있다. 6단계의 지연·재시도·취소·전송 장애와 샘플링 실험은 [tracing-validation.md](tracing-validation.md)를 따른다. 운영 활성화·자원 구성·배포는 7단계이며 수집 환경만 실행해도 분석 트레이스가 자동 생성되는 것은 아니다.

## 구성과 주소

```text
Mac에서 실행할 SDK → localhost:4318 → Collector → tempo:4318 → Tempo 볼륨
브라우저 → localhost:3001 → Grafana → tempo:3200 (트레이스 조회)
                                  → prometheus:9090 (메트릭 조회)
```

| 구성 | 버전 / 설정 | 역할 |
| --- | --- | --- |
| Collector contrib | 0.162.0 / `monitoring/otel-collector.yml` | OTLP HTTP 수신, 메모리 제한, 묶음 전송과 재시도 |
| Tempo | 3.1.0 / `monitoring/tempo.yml` | `-target=all` 모놀리식, 로컬 저장, 24시간 블록 보존 |
| Grafana | 13.2.2 / `monitoring/grafana/provisioning/datasources/local.yml` | Prometheus·Tempo 데이터 소스 자동 등록 |
| Prometheus | 기존 `compose.monitoring.yml` 재사용 | 15초 scrape, 7일 보존, 기존 named volume 유지 |

초기 설명에서는 Collector 0.163.0을 제안했으나 실제 Docker Hub·GHCR 정식 태그를 가져올 수 없어, 다운로드와 설정 검증이 가능한 공식 0.162.0으로 고정했다. Tempo는 제안했던 3.1.0을 실제로 내려받아 사용한다. GitHub 릴리스 페이지의 존재와 컨테이너 이미지의 다운로드 가능 여부를 구분한다.

`compose.tracing.yml`은 단독 실행 파일이 아니라 `compose.monitoring.yml` 위에 겹쳐 쓰는 파일이다. 프로젝트 이름 `catchhole-monitoring`을 유지해 기존 `catchhole-monitoring_prometheus_data`를 재사용한다. 다른 프로젝트 이름(`-p`)을 임의로 쓰면 다른 볼륨을 보게 된다. 운영 `catchhole-monitoring-prod`와는 별개다.

Collector 4318, Grafana 3001, Prometheus 9090은 호스트 `127.0.0.1`에만 공개한다. Tempo 4318·3200은 Docker 내부에서만 사용한다. 컨테이너의 `localhost`는 그 컨테이너 자신이므로 Collector와 Grafana는 Tempo를 `tempo`라는 서비스 이름으로 조회한다. `0.0.0.0` 수신은 컨테이너 내부의 모든 인터페이스를 의미하며 호스트 공개 범위는 Compose의 `ports`가 결정한다.

## 최초 준비

아래 명령은 해당 브랜치를 체크아웃한 Java 저장소 루트에서 실행한다. 기존 DB·Redis Compose와 섞이지 않도록 모든 명령에 `-f`를 명시한다.

로컬 비밀번호 파일은 최초 한 번만 만든다. 기존 파일이 있으면 덮어쓰지 않는다.

```bash
(umask 077; cp -n monitoring/.env.tracing.example monitoring/.env.tracing.local)
```

`monitoring/.env.tracing.local`의 예시 비밀번호를 로컬 실습용 값으로 바꾼다. 이 파일은 Git에서 제외하며 `.env.tracing.example`만 커밋한다. 실제 비밀번호는 명령 출력·스크린샷·문서에 남기지 않는다. 환경 파일을 처리하지 않고 비밀번호를 shell 환경변수로 직접 주입해도 된다.

Grafana 로그인 이름은 `admin`이다. `LOCAL_GRAFANA_ADMIN_PASSWORD`는 **새 Grafana 저장소의 최초 관리자 초기화**에 쓰며, 파일을 바꿔 컨테이너를 재시작하는 것만으로 기존 계정 비밀번호가 바뀌지는 않는다. 기존 계정의 비밀번호 변경은 Grafana UI에서 수행한다.

## 설정 검사와 실행

설정 검사는 비밀번호가 출력되지 않는 `config --quiet`로 실행한다. 일반 `config`나 `config --format json` 결과를 공유하면 환경변수가 펼쳐져 비밀번호가 노출될 수 있다.

```bash
docker compose --env-file monitoring/.env.tracing.local \
  -f compose.monitoring.yml -f compose.tracing.yml config --quiet

docker compose --env-file monitoring/.env.tracing.local \
  -f compose.monitoring.yml -f compose.tracing.yml pull

docker compose --env-file monitoring/.env.tracing.local \
  -f compose.monitoring.yml -f compose.tracing.yml \
  run --rm --no-deps otel-collector validate --config=/etc/otelcol/config.yml

docker compose --env-file monitoring/.env.tracing.local \
  -f compose.monitoring.yml -f compose.tracing.yml \
  run --rm --no-deps tempo -target=all -config.file=/etc/tempo.yml -config.verify=true

docker compose --env-file monitoring/.env.tracing.local \
  -f compose.monitoring.yml -f compose.tracing.yml up -d

docker compose --env-file monitoring/.env.tracing.local \
  -f compose.monitoring.yml -f compose.tracing.yml ps
```

`depends_on`은 시작 순서만 지정하므로 `ps`의 running만으로 연결 검증을 끝내지 않는다. Grafana에 로그인하고 Connections → Data sources에서 두 데이터 소스를 각각 Test한다. Tempo의 실제 트레이스는 이후 Explore → CatchHole Tempo Local에서 조회한다.

- Grafana: <http://localhost:3001>
- Prometheus: <http://localhost:9090>
- 애플리케이션의 향후 OTLP HTTP 트레이스 전송 주소: `http://localhost:4318/v1/traces`
- Collector의 `otlp_http` exporter는 base URL `http://tempo:4318`에 `/v1/traces`를 붙여 전송한다.

Spring을 실행하지 않아도 위 네 서비스와 데이터 소스 연결을 검증할 수 있다. 이 경우 Prometheus의 Spring scrape 대상 `host.docker.internal:8080`은 DOWN일 수 있다. 이는 Prometheus API 연결 성공 여부와 구분한다. Spring·DB·Redis는 이 문서의 명령으로 실행하지 않는다.

## 처리 정책과 한계

- Collector의 `memory_limiter` → `batch` → `otlp_http/tempo` 순서로 처리한다. `batch`는 256 Span에서 전송을 시작하며 최대 512개로 분할하고, 적게 들어와도 1초 timeout으로 전송한다. Trace 완료를 기다리거나 서로 다른 Trace ID를 합치는 기능이 아니다.
- 전송 대기열은 메모리에 요청 묶음 최대 100개를 보관하며 소비자는 2개다. Tempo 전송의 일시적 실패는 최초 1초·최대 간격 5초·최대 경과 30초 정책으로 재시도한다. 모든 오류가 재시도 대상은 아니며 대기열 초과·재시도 소진·Collector 종료 때 데이터가 유실될 수 있다. 영속 감사 원장이 아니다.
- Collector의 컨테이너 메모리 상한은 256MiB, limiter heap hard limit은 160MiB, 예상 spike는 32MiB, Go runtime 메모리 목표는 128MiB다. 이 설정이 OOM이나 유실을 완전히 막는 것은 아니다. Tempo·Grafana의 512MiB 상한도 **작은 로컬 실습의 시작값**이며 운영 최소 사양이나 처리량 보장이 아니다.
- Tail Sampling processor는 아직 없다. 3단계 임시 Python 예제는 `ParentBased(ALWAYS_ON)`으로 새 root trace를 선택하고 자식은 부모의 결정을 따른다. 실제 SDK·Agent의 운영 정책은 장시간 Job·늦게 도착하는 Span·보관량을 확인한 뒤 정한다.
- Tempo의 `overrides.defaults.compaction.block_retention: 24h`는 블록 보존 정책이며 개별 Span의 정확한 24시간 즉시 삭제나 디스크 크기 상한이 아니다. Tempo 3.x에 2.x의 `compactor:`·`ingester:` 설정을 복사하지 않는다.
- `/var/tempo` 전체를 `tempo_data` 볼륨에 연결해 WAL·blocks·기본 live-store 경로를 보관한다. 이미지 UID/GID 10001의 쓰기 권한을 사용하며 기존 host 경로를 연결할 때는 권한을 별도로 확인한다. Grafana는 `grafana_data`에 계정과 UI 설정을 보관한다.
- 원문·prompt·응답 본문·비밀값을 Span 속성에 넣는 계측은 만들지 않는다. 실습 Trace ID·상태·시간의 확인과 실제 사용자 분석 검증은 구분한다.

## 종료와 재시작

```bash
docker compose --env-file monitoring/.env.tracing.local \
  -f compose.monitoring.yml -f compose.tracing.yml stop
```

이 명령은 로컬 모니터링 서비스 네 개를 중지하며 볼륨을 지우지 않는다. 다시 `up -d`하면 된다. 볼륨을 삭제하는 `down -v`는 사용하지 않는다. Spring·DB·Redis나 운영 모니터링을 대상으로 하는 명령이 아니다.

## 검증 범위

2단계의 완료 기준은 설정 파싱, 실제 서비스 실행, Grafana의 두 데이터 소스 연결 성공이다. 3단계는 임시 예제의 Span 생성·전송·Tempo 조회와 부모 관계 확인을 다룬다. API에서 읽은 결과와 사용자가 Grafana 화면에서 확인한 결과를 구분해 기록한다. 운영 배포·샘플링 정책·장애 시 유실 검증은 이 단계의 완료로 간주하지 않는다.

### 2026-10-19 로컬 검증 결과

- Compose `config --quiet`, Collector `validate`, Tempo `-config.verify=true` 모두 종료 코드 0.
- Prometheus·Collector·Tempo·Grafana 네 컨테이너 running, 확인 시 OOMKilled=false. 장시간 부하 검증은 하지 않았다.
- Grafana `/api/health` HTTP 200, Prometheus·Tempo 데이터 소스 health API 모두 HTTP 200·status=OK.
- Grafana 데이터 소스 proxy를 통한 Tempo `/ready` HTTP 200·ready, `/api/search` HTTP 200·`traces: []`. Span을 생성하거나 전송하지 않았다.
- Collector `/v1/traces`에 GET을 보내 405를 확인했다. HTTP 수신 경로가 열렸다는 확인이며 정상 OTLP POST의 저장 성공 검증은 아니다.
- 인증 없는 Grafana 데이터 소스 조회는 HTTP 401. 호스트 공개 포트는 모두 127.0.0.1, Tempo host port binding은 없음.
- 기존에 중지된 Prometheus 컨테이너는 worktree의 설정 파일 경로로 재생성했지만 `catchhole-monitoring_prometheus_data` 볼륨을 그대로 재사용했다. 기존 DB·Redis는 실행하지 않았다.
- 당시 Spring 8080이 실행되지 않아 Prometheus의 `catchhole-backend` scrape target은 connection refused로 DOWN이었다. Grafana→Prometheus API 연결은 성공했으며 실제 애플리케이션 메트릭 수집 성공으로 기록하지 않는다.
- `.env.tracing.local`은 권한 0600·Git 제외 확인. 실제 비밀번호는 이 문서에 기록하지 않는다.
- Java·Python 애플리케이션 코드, 운영 배포 파일, 기존 Prometheus 수집 설정은 변경하지 않았다. Gradle 애플리케이션 테스트 대신 변경 대상인 Compose·Collector·Tempo 설정과 실제 데이터 소스 연결을 검증했다.

## 3단계: 저장소 밖 임시 Python 예제

실습 스크립트와 가상환경은 `/private/tmp/catchhole-tracing-demo-nrd6_9hk`에 만들었다. 이 경로는 아래 검증 당시의 로컬 임시 경로이며 다른 환경이나 임시 파일 정리 후에는 존재하지 않을 수 있다. 실습 파일은 PR에 포함하지 않고, 실제 Worker 연결을 확인한 뒤 정리한다. Collector·Tempo·Grafana 설정과 데이터 볼륨은 계속 재사용한다.

- `demo_tracing.py`: Provider 1개, Tracer 1개로 `demo.analysis` 루트와 `demo.prepare`, `demo.llm.call` 자식 2개를 생성한다. 두 자식은 각각 `sleep(0.2)`, `sleep(0.6)`으로 흉내 낸 구간이다. 실제 DB·LLM·Worker는 호출하지 않는다.
- `requirements.txt`: `opentelemetry-sdk==1.45.1`, `opentelemetry-exporter-otlp-proto-http==1.45.1`. Python 3.12.13 전용 가상환경에 설치했으며 애플리케이션의 의존성 파일은 변경하지 않았다.
- `run-output.txt`, `trace-readback.json`, `verification.json`: 최초 실행의 Trace ID, Tempo 원본 응답, 검증 결과를 임시 폴더에 보관한다. 저장소에는 아래 요약만 남긴다.

이미 준비한 실습을 다시 실행하려면 다음 명령을 사용한다. 실행할 때마다 새로운 Trace ID가 출력된다. 기존 `run-output.txt`는 최초 검증 결과이므로 새 실행의 ID와 혼동하지 않는다.

```bash
DEMO_DIR=/private/tmp/catchhole-tracing-demo-nrd6_9hk
"$DEMO_DIR/.venv/bin/python" "$DEMO_DIR/demo_tracing.py"
```

SDK는 `service.name=catchhole-tracing-demo`, `deployment.environment.name=local`을 기록한다. 루트에 `demo.synthetic=true`를 넣어 합성 실행임을 표시한다. OTLP/HTTP 목적지는 `http://localhost:4318/v1/traces`, 요청 timeout은 5초다. BatchSpanProcessor는 주기 1초·큐 1,024 Span·최대 export batch 256 Span을 사용한다. SDK의 배치와 Collector의 배치는 별도 단계다.

짧은 프로그램이 끝나기 전에 모든 Span을 종료하고 `force_flush(timeout_millis=5000)` 후 `shutdown()`을 호출한다. flush 반환값은 Tempo 저장 성공을 보장하지 않으며, 이 timeout 인자가 프로그램 전체 종료 시간을 5초로 제한하지도 않는다. 조회로 전달 결과를 확인한다.

Grafana `http://localhost:3001`에 로그인한 뒤 Explore → **CatchHole Tempo Local** → **TraceQL** 입력란에 출력된 Trace ID를 넣고 Run query를 실행한다. 이 임시 예제는 하나의 서비스에서 만든 Trace이므로 Python→Spring 분산 추적의 검증은 아니다.

### 2026-10-19 16:25 KST 합성 Trace 검증 결과

- Trace ID: `7290fa97f6c78c35bc2426cfd8876aac`.
- Python 예제를 한 번 실행했고 `pip check`에서 의존성 충돌이 없었다. SDK의 전송 오류 출력은 없었다.
- Grafana 데이터 소스 proxy의 `GET /api/datasources/proxy/uid/catchhole-tempo/api/traces/{traceId}`가 HTTP 200으로 3개 Span을 반환했다.

| Span | Span ID | 부모 Span ID | 측정 시간 |
| --- | --- | --- | --- |
| `demo.analysis` | `9323d9114a414067` | 없음 | 809.956ms |
| `demo.prepare` | `90a0bb79601b53e4` | `9323d9114a414067` | 204.543ms |
| `demo.llm.call` | `c538545f9f9e92f8` | `9323d9114a414067` | 605.071ms |

- 세 Span의 Trace ID 일치, 서로 다른 Span ID, 자식 2개의 부모 ID 일치, 자식 구간이 부모 안에 포함됨, 준비 종료 후 모의 LLM 시작 순서를 검사했다. 서비스·환경 속성과 합성 실행 표시도 확인했다.
- Tempo V1 JSON의 `batches[].scopeSpans[].spans[]`를 모두 펼쳐 검증했다. JSON의 ID는 Base64이므로 hex로 변환한 뒤 SDK가 출력한 ID와 비교했다.
- 검증 시 브라우저는 Grafana 로그인 화면이었다. API를 통해 Tempo에서 Span을 읽은 것은 확인했고, 로그인 후 화면의 타임라인 확인은 별도로 진행한다.
- Tempo의 기존 24시간 블록 보존 정책에 따라 나중에 이 ID가 조회되지 않을 수 있다. 그때는 임시 예제를 다시 실행한다.
- 서비스 재시작 후 복구·장애 시 유실·운영 부하·실제 분석 성능은 검증하지 않았다. Java·Python 서비스 코드와 운영 설정은 변경하지 않았다.

## 5단계: Python Worker → Spring HTTP → JDBC 연결

Python의 실제 `SpringWorkerClient._request()`가 Worker 실행 문맥 안에서 CLIENT Span을 만들고, 그 Span이 current인 상태에서 요청별 복사 헤더에 W3C 문맥을 주입한다. 최초 Job claim은 제외하지만 이미 실행 중인 Job의 비교 후보 claim은 포함한다. 요청 재시도·인증·lease·취소 정책은 변경하지 않는다. 같은 HTTP 클라이언트에 HTTPX 자동 계측을 중복 적용하지 않는다.

Java는 `monitoring/java-agent/`의 명시적 로컬 Agent 실행으로 SERVER·JDBC Span을 생성한다. SDK는 Agent가 소유하며 애플리케이션 `build.gradle`에 새 OTel SDK를 추가하지 않았다. Agent 2.32.0 / SDK 1.66.0을 사용하며 공식 배포 파일의 SHA-256을 확인한다. 상세 실행 옵션·전송 전 정제 코드와 제약은 [Java Agent 안내](../monitoring/java-agent/README.md)에 있다.

```text
Python: analysis.job.execute
├─ spring.http.request [CLIENT, reserve]
│  └─ Spring POST /api/internal/v1/ai-token-usages/reserve [SERVER]
│     ├─ DB MERGE [CLIENT]
│     └─ DB SELECT [CLIENT]
└─ spring.http.request [CLIENT, settle]
   └─ Spring POST /api/internal/v1/ai-token-usages/{requestId}/settle [SERVER]
      └─ DB UPDATE [CLIENT]
```

위 DB 작업은 아래 합성 H2 검증의 결과이며 운영 원장 쿼리 구성을 뜻하지 않는다. HTTP CLIENT에는 서버 처리 시간이 포함되며 JDBC 시간은 Java의 DB 호출 경과 시간이다. 부모·자식 시간을 단순 합산하거나 DB 서버 CPU 시간으로 해석하지 않는다.

### 샘플링과 전송 정책

- Java `parentbased_always_off`는 부모 없는 새 trace를 수집하지 않고, sampled 원격 부모를 이어받는다. API 접근 제어나 인증 설정이 아니다.
- Python에서 non-recording이더라도 유효한 Worker 문맥의 sampled=0을 전파한다. 이 경우 Spring도 수집하지 않는다. `is_recording()`만으로 문맥 존재를 판단하지 않는다.
- 실제 SDK의 `traceparent` flags는 sampled 외 비트를 포함할 수 있다. 검증에서는 `flags & 0x01`을 사용하며 헤더 마지막 두 글자를 `01` 또는 `00`으로 고정하지 않는다.
- Java 배치는 1초/최대 256 Span, 큐 1,024 Span, export timeout 5초다. metrics/logs exporter는 `none`이고 기존 Prometheus 수집은 유지한다. Collector와 SDK의 큐는 별개이며 영속 원장이 아니다.
- privacy extension은 **OTLP exporter에 넘기기 전** Span 속성·이름·resource·scope·event·status description을 정제한다. 허용한 HTTP method/route/status와 DB system/operation만 남기고, SQL·URL·인증값·예외 원문을 전송하지 않는다. 알 수 없는 route는 일반 HTTP 이름으로 표시한다.
- 현재 Java extension은 local 전용 resource 정책이다. 운영 적용 시 환경·서비스 식별, 수집 경로, 네트워크, 배포, 보존·sampling 정책을 별도로 구성해야 한다. 이번 변경을 머지하는 것만으로 운영 Grafana에 trace가 추가되지 않는다.

### 재현 가능한 격리 검증

Collector·Tempo·Grafana는 위 로컬 Compose로 먼저 실행한다. Java 21과 4~5단계가 적용된 AI checkout의 가상환경이 필요하다. 다음 명령은 Java 저장소 루트에서 실행한다.

```bash
monitoring/java-agent/setup-local.sh

./gradlew --no-daemon -I scripts/tracing/verification.init.gradle \
  writeTraceVerificationClasspath

# 실제 AI checkout 경로를 지정한다.
AI_TRACING_CHECKOUT=/path/to/catchhole-backend-ai
"$AI_TRACING_CHECKOUT/.venv/bin/python" scripts/tracing/verify_worker_spring.py \
  --ai-checkout "$AI_TRACING_CHECKOUT"
```

`setup-local.sh`는 Agent를 내려받아 checksum을 확인하고 별도 extension의 테스트와 JAR 빌드를 수행한다. 다운로드 cache와 빌드 산출물은 Git에서 제외한다. 애플리케이션이나 운영 서비스를 기동하지 않는다.

검증 스크립트는 다음을 수행한다.

1. loopback 임시 포트에 OTLP 검증 수신기를 연다. Python·Java의 exporter는 먼저 이 수신기에 전송한다.
2. 별도 JVM에 테스트 전용 Spring 서버를 띄운다. 실제 `AiTokenWorkerController`·DTO·내부 API-key filter를 사용하고, 토큰 서비스는 합성 H2 JDBC 구현으로 대체한다. DB 이름·HTTP 포트는 실행마다 격리한다.
3. 실제 Python `SpringWorkerClient`로 두 Job의 reserve→settle을 동시에 실행하고, 추가 요청 하나에서 의도적인 HTTP 500을 발생시킨다. 부모 없는 요청과 sampled=0 요청도 실제 실행한다.
4. Collector 도착 전 payload에서 테스트용 비밀 문자열, SQL/URL/예외 속성 등을 검사한다. 통과한 payload만 기존 Collector로 전달한다.
5. 각 CLIENT의 SERVER 자식, 각 SERVER의 JDBC 하위 작업, 두 예약 요청의 실제 시간 겹침, 오류 status, 수집 제외 조건을 검사한다.
6. 로컬 Grafana의 Tempo proxy에서 같은 Trace와 Span ID·부모 관계를 다시 읽어 확인한다. 검증 JVM과 임시 수신기는 종료하며 기존 모니터링 컨테이너·볼륨은 유지한다.

검증 프로세스는 ambient 앱/OTel/proxy 환경변수를 제거한다. Spring fixture도 `application.yml`·`.env`·운영 설정을 읽지 않는다. Grafana 비밀번호만 `monitoring/.env.tracing.local`에서 메모리로 읽고 출력하지 않는다. 결과 JSON과 fixture 로그는 출력된 OS 임시 폴더에 저장한다. 원문·운영 비밀값·실제 사용자 데이터는 사용하지 않는다.

이 검증은 **실제 HTTP 전파·Java Agent·JDBC·Collector·Tempo 연결**을 확인한다. 실제 `AiTokenServiceImpl`의 계정/lease/원장 규칙, PostgreSQL의 실행 계획·잠금, 실제 분석 결과나 운영 성능을 검증한 것은 아니다. fixture는 `src/test`의 별도 패키지이므로 운영 애플리케이션 JAR에 포함되지 않는다.

운영 앱과 같은 로컬 Spring 프로세스에 계측을 붙이는 방법은 Java Agent 안내의 `run-local.sh boot-run` 또는 JAR 실행 절차를 따른다. 해당 명령은 앱을 실제로 실행하므로 앱의 기존 local DB 설정이 별도로 필요하다. 위 격리 검증에서는 이 명령을 실행하지 않는다.

### 2026-10-20 5단계 최종 검증 결과

- Java 21 / Spring Boot 4.0.6 / Agent 2.32.0 / SDK 1.66.0으로 실행했다. Agent extension 단위 테스트 5개, fixture 컴파일, `bootJar`가 통과했다. boot JAR에 검증 fixture나 Agent/extension이 포함되지 않은 것도 검사했다.
- 동시 reserve→settle 두 건과 의도적 HTTP 500 한 건에서 총 **3개 Trace / 21개 Span**을 수신하고 Tempo에서 동일한 ID와 부모 관계를 다시 확인했다. 두 예약 SERVER Span의 실행 시간이 실제로 겹치는 것도 확인했다.
- 정상 실행 하나는 Python root 1개 + HTTP CLIENT 2개 + Spring SERVER 2개 + JDBC 3개로 구성됐다. 500 실행은 root 1개 + CLIENT 1개 + SERVER 1개 + JDBC 2개이며 Python/Java HTTP Span 모두 ERROR였다.
- 부모 없는 요청과 sampled=0 요청은 실제 HTTP/JDBC 처리를 수행했지만 수집된 trace 집합에 추가되지 않았다.
- Collector 이전 OTLP payload의 canary 부재, Java 속성 허용 목록, resource/scope/event/status 제한을 검사했다. 원격 부모·link 표시를 보존하는 extension 수정 후 최종 재실행도 통과했다.
- 실제 원장 규칙·PostgreSQL·운영 배포·장시간 부하·Collector 장애 실험은 이번 결과에 포함하지 않는다. Grafana proxy API에서 읽었으며 브라우저 타임라인을 직접 확인한 것으로 기록하지 않는다.

| 합성 시나리오 | Trace ID | Span 수 |
| --- | --- | --- |
| 동시 Job A: reserve→settle | `3b94a7df46895c77075bd2cedc1a80ae` | 8 |
| 동시 Job B: reserve→settle | `5bce868f6bb7aa986bfa00252f071729` | 8 |
| 의도적 HTTP 500 | `ab9ce60e39d8e75b9201a8c9fdc5f1a1` | 5 |

로컬 Grafana `http://localhost:3001/explore`에서 **CatchHole Tempo Local**을 선택하고 위 Trace ID로 조회할 수 있다. 24시간 블록 보존과 로컬 볼륨 상태에 따라 나중에는 조회되지 않을 수 있다. 결과는 당시 OS 임시 디렉터리 `catchhole-gh271-stage5-m1vet4fj`의 `verification.json` 및 Trace JSON에 보관했다.

Python 변경은 신규 HTTP tracing 테스트 36개를 포함한 집중 100개가 통과했고, 최종 전체 검사에서 **4,185 passed / 134 skipped / 기존 경고 1개**를 확인했다. 초기 전체 실행 한 번에서 챗봇 관련 실패 212개가 보고됐으나 최초 traceback을 확보하지 못했다. 이후 같은 production code로 세 번의 전체 실행이 통과했고, 마지막에는 초기와 같은 `pytest -q` 명령의 전체 로그를 `/private/tmp/catchhole-gh271-stage5-pytest-final.log`에 저장했다. 초기 실패 원인이 해결됐다고 단정하지 않으며 현재 재현되지 않은 검증 이력으로 남긴다.

## 공식 참고

- [Collector 설정과 validate](https://opentelemetry.io/docs/collector/configuration/)
- [Collector 0.162.0 OTLP HTTP exporter](https://github.com/open-telemetry/opentelemetry-collector/blob/v0.162.0/exporter/otlphttpexporter/README.md)
- [Tempo 3.1.0 로컬 예제](https://github.com/grafana/tempo/tree/v3.1.0/example/docker-compose/single-binary)
- [Grafana Tempo 데이터 소스](https://grafana.com/docs/grafana/latest/datasources/tempo/configure-tempo-data-source/)
