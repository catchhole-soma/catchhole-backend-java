# #271 6단계 — 지연·재시도·취소·전송 실패와 샘플링 검증

이번 단계는 실제 계측 코드가 서로 다른 실행 상황을 구분하는지 확인하는 로컬 실험이다.
운영 동시성·재시도·sampling·배포 설정은 바꾸지 않는다. 실제 원고·유료 LLM·운영
Spring/DB/S3에 요청하지 않으며 실험 지연은 성능 개선 수치로 사용하지 않는다.
앞 단계의 Python→Spring→JDBC 연결 검증은 [local-tracing.md](local-tracing.md)에 있다.

## 세 가지 실험과 확인 범위

| 실험 | 실제 사용하는 코드 | 합성하거나 축소한 부분 | 확인하는 것 |
| --- | --- | --- | --- |
| Worker 실행 | runner, AnalysisJobWorker, MeteredTextGenerationClient, 메트릭·Span 계측 | 입력/저장소/Spring/LLM 응답, 의도적인 대기, 짧은 재시도 간격 | Span의 부모·시간·결과와 기존 메트릭의 경계 |
| 전송 장애 | WorkerTracingRuntime, BatchSpanProcessor, OTLP HTTP exporter, metered 호출 | loopback 수신기 장애, 작은 queue·짧은 timeout | 업무 완료와 전송 실패의 분리, queue drop, 복구 후 새 전송 |
| 샘플링 | Python SDK sampler, Collector contrib 0.162.0 Tail processor | 고정 Trace ID 표본·합성 Span·1초 결정 시간 | Head 결정 상속, Tail 조건 선택, 늦은 오류를 놓치는 경우 |

## 다시 실행하는 순서

AI 저장소의 기존 Python 3.12 가상환경과 설치된 의존성을 사용한다.
아래 두 경로는 실제 checkout에 맞게 지정한다. 출력은 임시 디렉터리에만 저장하고
기존 결과 파일을 덮어쓰지 않는다.

```bash
AI_CHECKOUT=/absolute/path/catchhole-backend-ai-gh271
JAVA_CHECKOUT=/absolute/path/catchhole-backend-java-gh271
TRACE_LAB_OUTPUT=$(mktemp -d /tmp/catchhole-tracing-stage6.XXXXXX)
```

AI checkout에서 Worker 시나리오를 실행한다. 기본은 메모리 검증이며
`--export-otlp`를 넣으면 기존 **로컬** Collector에도 보낸다.

```bash
cd "$AI_CHECKOUT"
.venv/bin/python -m scripts.tracing.verify_worker_scenarios \
  --export-otlp --output "$TRACE_LAB_OUTPUT/worker.json"
```

Java checkout에서 위 결과에 적힌 모든 Span을 로컬 Grafana의 Tempo datasource proxy로
다시 읽는다. 로컬 비밀번호 파일은 기존 `monitoring/.env.tracing.local`을 사용하고
값을 출력하지 않는다. 브라우저 화면 검증과는 별개다.

```bash
cd "$JAVA_CHECKOUT"
"$AI_CHECKOUT/.venv/bin/python" scripts/tracing/verify_worker_trace_readback.py \
  "$TRACE_LAB_OUTPUT/worker.json"
```

전송 장애 실험은 별도 loopback 수신기로 실행한다. 공유 Collector를 중단하지 않는다.

```bash
cd "$AI_CHECKOUT"
.venv/bin/python scripts/tracing/verify_exporter_outage.py \
  --output "$TRACE_LAB_OUTPUT/outage.json"
```

샘플링 실험은 새 임시 Collector 하나만 실행하고 종료 시 제거한다. 기존 Compose와
볼륨을 변경하지 않고 localhost의 임의 포트를 사용한다. `--head-only`는 Docker 없이
SDK의 Head 실험만 한다.

```bash
cd "$JAVA_CHECKOUT"
"$AI_CHECKOUT/.venv/bin/python" scripts/tracing/verify_sampling.py
```

## Worker 시나리오의 해석

| 조건 | 기대하는 관측 |
| --- | --- |
| 정상 실행 | 한 root 아래 업무 단계와 LLM request/call이 연결되고 active Gauge가 0으로 돌아온다 |
| 느린 LLM | `llm.call`과 기존 LLM Histogram에 호출 지연이 반영된다 |
| 호출 슬롯 대기 | `llm.semaphore.wait`가 길어지고, 그 시간은 기존 LLM Histogram에 포함되지 않는다 |
| 429 뒤 성공 | 실패 call → backoff → 성공 call이 분리되고 retry Counter는 실제 추가 호출 1회를 센다 |
| 분석 Task 취소 | root/request는 canceled, 이미 시작한 shield 정산은 별도 Task에서 실제 종료까지 기록된다 |
| 동시 두 Job | 실제 호출 시간이 겹쳐도 서로 다른 Trace ID와 각자의 부모 관계를 유지한다 |

LLM Histogram과 `llm.call`은 실제 delegate 앞뒤에서 각각 시간을 재므로 계측 오버헤드만큼
작은 차이가 날 수 있다. Histogram의 `_sum`/`_count`를 직접 비교하며 p95의 버킷 보간값을
실제 호출 시간으로 취급하지 않는다.

취소 실험은 LLM 응답을 받은 뒤 정산이 진행되는 순간 Task를 취소한다.
따라서 LLM 호출 자체는 성공, 분석 실행은 취소, 정산은 뒤늦게 성공일 수 있다.
정산 자식 Span이 root보다 늦게 끝난 것을 잘못된 중첩으로 판단하지 않는다.
이는 SIGKILL 뒤 정산 성공이나 lease recovery를 입증하는 시험이 아니다.

## 2026-10-20 Worker 실행과 Tempo 저장 확인

여섯 시나리오에서 **7개 Trace·99개 Span**을 생성했다. 로컬 Collector의 수신 응답에
그치지 않고 Grafana datasource proxy API를 통해 Tempo에서 다시 읽어, 모든 Span의
ID·이름·부모 ID·시작/종료 나노초 시각이 생성 결과와 일치함을 확인했다.
브라우저 패널 표시나 운영 배포를 검증한 것은 아니다.

| 합성 조건 | 실제 관측 | 메트릭과의 관계 |
| --- | --- | --- |
| 정상 | `llm.call` 31.143ms | LLM Histogram 합 31.122ms, 호출 1회 |
| 느린 LLM | `llm.call` 180.940ms | Histogram 합 180.917ms, 호출 1회 |
| 호출 슬롯 대기 | wait 151.241ms, call 31.180ms, request 225.405ms | Histogram 합 31.126ms에는 슬롯 대기가 포함되지 않는다 |
| 429 뒤 성공 | call 2회 합 62.233ms, backoff 151.129ms | Histogram 합 62.102ms, 호출 2회·추가 재시도 1회 |
| 정산 도중 분석 Task 취소 | root 53.380ms 후 취소, 이미 시작한 정산은 총 173.018ms 실행 후 성공 | LLM 호출 1회는 성공, Job 취소 1회, active Gauge는 0으로 복원 |
| 동시 두 Job | 서로 다른 root 2개, 각각 request/call 1개, 호출 구간 중첩 | 호출 2회·재시도 0회, active Gauge는 0으로 복원 |

시간은 의도적으로 넣은 합성 지연의 한 실행 결과다. 운영 성능·개선율이나 실제 LLM의
응답 시간을 뜻하지 않는다. `llm.request`에는 호출뿐 아니라 슬롯 대기·예약·정산 등이
들어가므로 wait와 call 두 값만 더해 전체 request 시간이라고 해석하지 않는다.

| 시나리오 | Trace ID | 저장된 Span |
| --- | --- | --- |
| normal | `03c0b36b52d5ae16e980e3a4dd4cd7bb` | 14 |
| slow_call | `79d78d089bdcf329c1470252af7ff366` | 14 |
| semaphore_wait | `3ad21f9e048ce81b9a13c98632760efa` | 14 |
| retry_429 | `80420c6821257db9ebc34cb8627f6b47` | 19 |
| cancellation_settlement | `1d9cca739836867e8d7ca416eb8c8dfa` | 10 |
| concurrent_roots A | `64b37a2cffb614a5d9bfa342139f9b78` | 14 |
| concurrent_roots B | `e33fc5bfda2a1cd07c1e26bb795cd10a` | 14 |

이 ID는 로컬 Tempo의 24시간 블록 보존 정책에 따라 이후 조회되지 않을 수 있다.
AI의 신규 집중 테스트는 **11 passed**였으며, 외부 네트워크 차단 상태에서 실제 Worker
경로와 계측 경계를 검사하고 환경·인증·프록시 격리 및 기존 결과 파일 보존도 확인했다.

## 2026-10-20 전송 장애 실험 결과

| 조건 | 업무 결과 | 실제 전송 결과 |
| --- | --- | --- |
| 연결 거절 | 예약·LLM·정산 각 1회 완료 | 종료된 Span 5개 전송 실패 |
| HTTP 503 | 동일하게 완료 | Span 5개 전송 실패 |
| 느린 응답 | 약 4.8ms에 합성 업무 완료, exporter는 그때 실행 중 | 배치별 약 402~404ms 후 실패 |
| 각 장애 복구 후 새 업무 | 정상 완료 | 새 root를 포함한 5개 Span 수신 |
| queue 4 / batch 1 초과 | 합성 업무 4개 정상 완료 | 종료 21개 → 전송 시도·수신 5개, 전송 전 탈락 16개 |

장애 실험의 queue 64/batch 1/timeout 0.4초 및 초과 실험의 queue 4는 빠르고 분명한
재현을 위한 실험값이다. 앱의 queue 1,024/batch 256/timeout 5초를 변경하지 않았다.
위 시간은 성능 측정이나 운영 지연 예상값이 아니다.

`force_flush()` 완료는 대기열 처리가 끝났다는 뜻이며 수신·저장 성공과 다르다.
실제 종료 ID, export 시도 ID, 수신 확인 ID를 구분했다. 복구 후 새 Trace는 전달됐지만
실패한 과거 Span은 복원되지 않았다. 일부 Span만 전달된 불완전한 Trace도 생길 수 있다.
이 실험은 Tempo 저장이나 기존 Prometheus 메트릭 불변을 검사하지 않는다.

## 2026-10-20 샘플링 실험 결과와 초기 정책

Head 실험은 하위 64비트를 균등하게 배치한 **고정 16개 Trace ID**를 사용했다.
Python `TraceIdRatioBased`의 0%/25%/100%에서 각각 0/4/16개 Trace가 선택됐고,
자식은 같은 선택 결정을 따랐다. 같은 ID로 반복한 결정도 같았다.
임의의 실제 요청 16개가 반드시 4개 선택된다는 보장은 아니다.
Head 결정 이후 모든 실행에 합성 오류를 만들었지만 제외된 Span은 exporter에 없었다.
따라서 downstream Tail이 이 오류를 되살릴 수 없다.

별도 Tail Collector는 1초 뒤, ERROR 또는 500ms 이상 조건으로 판단했다.
아래 시간은 직접 만든 OTLP fixture의 속성이며 실제 분석 시간 측정값이 아니다.

| 입력 | 관측 결과 |
| --- | --- |
| 빠른 성공 10ms | 제외 |
| 오류 10ms | 보관 |
| 느린 성공 1,000ms | 보관 |
| 긴 작업의 첫 자식 10ms만 먼저 도착 | 당시 정보로 제외 결정 |
| 해당 작업의 오류 root가 결정 이후 도착 | 앞의 제외 결정을 뒤집지 못해 보관되지 않음 |
| 그 뒤 별도 오류 Trace | 정상 보관 — 파이프라인이 계속 동작함을 확인 |

Collector 자체 메트릭에서 최초 네 Trace의 선택 2/제외 2 결정을 확인한 뒤 늦은 오류를
보냈다. 전체 6개 Span 수신 및 최종 출력 3개(오류·느림·후속 오류)를 대조했다.
단순히 잠깐 기다렸다가 결과가 없다는 이유만으로 제외 성공을 판정하지 않았다.

**이번 로컬 정책은 Python ParentBased all-on, Java 부모 결정 상속, Collector Tail OFF를 유지한다.**
다음 운영 단계에서도 초기에는 이 방향으로 시작하되 보존 기간·메모리·디스크·수신량과
전송 실패 관측을 함께 구성한다. 실제 운영 활성화와 자원 설정은 7단계에서 수행한다.

캐치홀의 분석은 길고 정산이 root 뒤에 끝날 수 있다. 짧은 `decision_wait` 또는 root 도착만으로
완료를 판단하면 오류를 놓칠 수 있으므로 이번 실험의 1초를 운영에 복사하지 않는다.
Tail 도입은 실제 Span 도착 지연·최장 실행·수신량·대기 메모리·동일 Trace의 Collector
배치 경로를 확인한 뒤 결정한다. Head 100%도 저장 성공 100%를 보장하지 않는다.
현재 Python은 sampler를 코드에 명시하므로 `OTEL_TRACES_SAMPLER` 환경변수만 바꿔도
자동으로 정책이 바뀌는 구조가 아니다.

검증 결과 원본은 OS 임시 디렉터리의 JSON에 저장했다. 유효 기간이 보장되지 않으므로
재현 스크립트·위 집계·검증 한계를 함께 보존하며 운영 실험 증거로 확대하지 않는다.

공식 근거:

- [OpenTelemetry sampling](https://opentelemetry.io/docs/concepts/sampling/)
- [고정 버전 Tail processor와 늦은 Span](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/v0.162.0/processor/tailsamplingprocessor/README.md#late-arriving-spans)
- [SDK batch processor](https://opentelemetry.io/docs/specs/otel/trace/sdk/#batching-processor)
- [Collector 데이터 유실 조건](https://opentelemetry.io/docs/collector/resiliency/#circumstances-of-data-loss)
