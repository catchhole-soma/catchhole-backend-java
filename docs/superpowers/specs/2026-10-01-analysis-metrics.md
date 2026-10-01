# 분석 운영 지표 설계 — GH206

사용자 승인: Python Worker를 Prometheus가 직접 수집하고, Grafana를 운영 현황 / API·서버 상세 / 분석·AI 상세 3개로 구성한다. 로컬 구현은 최신 origin/main을 반영하고 기존 `<type>/gh-<issue>-<description>` 규칙을 따른다.

## 수집 책임

- Java는 DB가 확정한 회차별 분석 접수·결과, 실행 가능한 대기·의존 대기·실행 상태, 가장 오래된 대기 시간, claim 전 대기, 사용자 재시도와 lease 회수를 기록한다.
- Python은 프로세스별 active Job, 실행 시도 시간·결과, 마지막 처리 종료 시각, 개별 LLM 호출 시간·오류·재시도를 기록한다. 기존 호출 usage를 활용한 token 참고 지표도 제공하되 감사 원장을 대체하지 않는다.
- 한 HTTP 요청이 여러 회차 Job을 생성하므로 분석 접수는 회차 Job이 단위다. API 지연과 결과 준비까지의 전체 시간은 구분한다.
- 결과 준비는 추출 이후 해당 회차의 후속 비교까지 끝나 사용자가 검토할 수 있게 된 시점이다. 사람의 검토 시간은 제외한다. 자동 모드에서 실패 후보가 있어도 Job이 SUCCEEDED일 수 있으므로 partial_success를 별도 표시한다.
- ordered 사용자 재시도는 같은 Job을 재사용한다. 기존 createdAt/startedAt만으로 새 접수와 실행 시도를 계산하지 않는다. 필요하면 별도의 durable 측정 timestamp/세대를 추가하고 Flyway로 관리한다.
- DB 변경 사건의 Counter/Timer는 commit 뒤에 기록한다. 중복 완료 요청·rollback·중복 완료 관측이 같은 결과를 다시 기록하지 않게 한다. 계측 오류가 분석 정책/성공 여부를 바꾸지 않는다.
- DB 상태 Gauge는 15초마다 bounded 집계 조회로 갱신한다. DB 전체 관측치를 여러 API 인스턴스에서 합산하지 않는다. 빈 queue의 oldest age는 0, 수집 실패는 정상 0으로 숨기지 않는다.

## Python 메트릭 계약

공통 custom label: `application=catchhole-ai`, `environment=local|prod`, `worker_kind=analysis|character-comparison|world-comparison`.

- `catchhole_worker_jobs_active` Gauge.
- `catchhole_worker_job_attempts_total{job_type,outcome}` Counter, outcome success/failure/canceled/lease_lost/unknown 등 고정 목록.
- `catchhole_worker_job_duration_seconds{job_type,outcome}` Histogram: claim 반환 이후 process 호출부터 종료까지의 monotonic wall-clock, 결과 준비 시간과 구분.
- `catchhole_worker_last_job_finished_timestamp_seconds` Gauge, 아직 종료가 없으면 0.
- `catchhole_llm_calls_total{purpose,model,outcome,error_type}` Counter.
- `catchhole_llm_call_duration_seconds{purpose,model,outcome}` Histogram: delegate 호출 하나, semaphore·예약/정산·재시도 대기 제외.
- `catchhole_llm_retries_total{purpose,model,error_type}` Counter: 실제 추가 provider 시도가 발생할 때.
- `catchhole_llm_usage_tokens_total{purpose,model,token_type}` Counter; input에 cached_input이 포함되므로 input+cached_input을 비용 총량으로 합산하지 않는다.
- `catchhole_llm_usage_unavailable_total{purpose,model}` Counter; usage 없음은 0 token으로 오인하지 않는다.
- 임의 ID·모델 문자열·purpose 문자열·예외 메시지가 새 시계열을 만들지 않도록 고정 값/기동 설정 허용 목록 밖 값은 other로 정규화한다.
- 프로세스당 한 registry/exporter. CLI 시작 시 metrics 서버를 한 번 시작하고 종료 시 정리한다. FastAPI를 추가로 실행하지 않는다. 메트릭 생성/노출 실패는 고정 경고를 남기고 분석 처리를 유지한다.

## 배포와 화면

- 기존 Worker 5×10, 비교 Worker 각 1, graceful shutdown 180/210초, DB pool은 변경하지 않는다.
- 모든 프로세스를 개별 scrape한다. 여러 Worker를 한 로드밸런싱 URL로 수집하지 않는다.
- 기본 metrics bind는 localhost. 운영 노출은 Worker EC2의 사설 주소와 모니터링 SG만 허용하는 경로를 사용한다. 호스트의 9102-9106 port range를 5개의 분석 replica에 할당하고 비교 Worker는 9107/9108을 쓴다. 실제 Docker port mapping으로 file_sd target을 생성해 배포 시 갱신한다. 추가 SG 적용은 코드·테스트·적용 내용이 준비된 뒤 진행한다.
- 운영 현황 UID catlfln을 유지하고 API·서버 상세 / 분석·AI 상세는 새 UID로 생성한다. 모두 한국어 제목·설명·범례, 동일한 datasource UID catchhole-prometheus를 사용한다. 기존 CPU 기준 설명과 peak thread를 보존한다.
- 운영 현황에는 핵심 HTTP 및 분석 요약만, API 상세에는 엔드포인트별 지연·EC2/JVM/Heap/GC/DB/Threads, 분석 상세에는 대기→실행→최종 결과→LLM 원인을 배치한다.
- overview에서 상세로 시간 범위가 이어지는 dashboard 링크를 제공한다. worker_kind/instance 필터를 제공하고 DB 전체 Gauge는 max로 집계한다. 정상 유휴·분모 없는 성공률·수집 실패는 구분한다.
- 운영 코드 배포는 각 저장소 main CI를 따른다. 로컬 구현·검증과 실제 배포·운영 정상 분석 표본 검증을 구분해 #206에 기록한다. 운영 장애 주입/유료 LLM 부하 실험은 하지 않는다.
- 단계별 내부 stage 시간과 금액 환산/알림은 후속 확장으로 남긴다.
