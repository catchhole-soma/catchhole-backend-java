# 분석 운영 지표

관련 작업: [Java #206](https://github.com/catchhole-soma/catchhole-backend-java/issues/206). Python Worker도 같은 이슈에서 관리한다.

## 측정 단위와 책임

HTTP 분석 접수 한 번이 여러 회차 Job을 생성할 수 있다. 분석 접수·결과의 기본 단위는 **회차별 사용자 분석 시도**다. 명시적 사용자 재시도는 새 접수/시도이며, lease 자동 회수는 기존 접수를 유지한 새로운 실행이다. 숨은 캐릭터·세계관 비교 Job은 대기/실행과 claim 시간에 포함하지만 회차 접수·최종 결과를 다시 세지 않는다.

Spring은 DB 상태와 사용자 결과 준비를, Python은 프로세스의 실제 실행과 개별 provider 호출을 기록한다. Prometheus는 Spring `/actuator/prometheus`와 Worker `/metrics`를 각각 수집한다. Python 지표를 Spring으로 전달하거나 기존 token 정산 API에 추가하지 않는다.

| 지표 | 종류·단위 | 의미 |
| --- | --- | --- |
| `catchhole_analysis_accepted_total` | Counter, 회차 | 새 접수 또는 사용자 재시도 |
| `catchhole_analysis_results_total` | Counter, 회차 | success / partial_success / failure / canceled |
| `catchhole_analysis_result_ready_seconds` | Histogram, 초 | 해당 접수부터 후속 비교가 끝나 결과가 준비될 때까지 |
| `catchhole_analysis_pending_jobs` | Gauge, 작업 | eligible 실행 가능 / dependency_blocked 의존 대기 |
| `catchhole_analysis_oldest_pending_seconds` | Gauge, 초 | 해당 대기 구간의 가장 오래된 대기 시간, 빈 대기는 0 |
| `catchhole_analysis_running_jobs` | Gauge, 작업 | DB의 RUNNING 상태 |
| `catchhole_analysis_claim_wait_seconds` | Histogram, 초 | 해당 PENDING 전환부터 claim까지, 의존 대기 포함 |
| `catchhole_analysis_retries_total` | Counter, 회 | 명시적 사용자 재시도 |
| `catchhole_analysis_recoveries_total` | Counter, 회 | lease 회수의 requeued / failed |
| `catchhole_analysis_snapshot_success` | Gauge, 0/1 | 마지막 DB 상태 갱신 성공 여부 |
| `catchhole_analysis_snapshot_last_success_timestamp_seconds` | Gauge, Unix 초 | 마지막 성공한 DB 상태 갱신 시각 |
| `catchhole_worker_jobs_active` | Gauge, 작업 | 해당 Python 프로세스가 실행 중인 Job |
| `catchhole_worker_job_attempts_total` | Counter, 실행 | success / failure / canceled / lease_lost / unknown |
| `catchhole_worker_job_duration_seconds` | Histogram, 초 | claim 반환 후 실제 Worker 처리 wall-clock |
| `catchhole_worker_last_job_finished_timestamp_seconds` | Gauge, Unix 초 | 프로세스의 마지막 실행 종료, 아직 없으면 0 |
| `catchhole_llm_calls_total` | Counter, 호출 | 실제 delegate 호출, 실패와 취소 포함 |
| `catchhole_llm_call_duration_seconds` | Histogram, 초 | 개별 delegate 호출의 monotonic 시간 |
| `catchhole_llm_retries_total` | Counter, 호출 | 기존 transport 재시도 loop에서 실제 추가 호출한 수 |
| `catchhole_llm_usage_tokens_total` | Counter, token | input / cached_input / output; 참고 사용량 |
| `catchhole_llm_usage_unavailable_total` | Counter, 호출 | 응답에서 사용량을 확인할 수 없는 호출 |

## 시간·결과 해석

결과 준비는 추출 이후 실행 가능한 후속 비교가 끝나 사용자가 결과를 볼 수 있는 시점이며 사람의 확인/수정 대기는 제외한다. 자동 모드에서 일부 후보가 실패해도 Job은 SUCCEEDED일 수 있어 별도의 partial_success로 표시한다. 결과 준비 시간 p95는 **success와 partial_success**만 집계하며 실패·취소를 섞지 않는다.

결과 준비 조회는 commit 뒤 별도 작업에서 실행하고, 밀리거나 실패한 조회는 15초 주기 작업에서 보완한다. 현재 시각 대신 저장된 실제 종료 시각으로 시간을 기록하므로 계측 작업의 대기가 분석 시간에 더해지지 않는다. 접수 트랜잭션이 별도의 DB 연결을 기다리지 않도록 큐 용량을 제한한다.

완전 성공률은 `success / (success + partial_success + failure)`다. 부분 성공은 완전 성공 분자에서 제외하고 취소는 분모에서도 제외한다. 종료 결과가 없는 구간은 0%가 아니라 계산 불가다. 후보 수·정확도·사용자 만족도는 이 성공률이 뜻하는 바가 아니다.

명시적 ordered 재시도는 같은 Job을 재사용하지만 측정 접수와 세대를 새로 시작한다. lease 회수는 최초 접수를 보존하고 대기 구간만 새로 시작한다. 기존 createdAt/덮어쓰이는 startedAt으로 사용자 대기 시간을 대신 계산하지 않는다. 도입 전에 끝난 과거 Job을 현재 Counter에 소급 재생하지 않는다.

LLM 시간은 semaphore·token 예약/정산·재시도 sleep을 제외한다. 기존 summary의 providerLatencyMs는 여러 호출의 합이므로 한 호출 histogram에 넣지 않는다. 현재 client가 직접 쓰는 httpx 요청에는 별도 SDK 자동 retry가 없으며, 향후 SDK를 넣으면 delegate 내부 retry는 한 호출 시간에 포함되므로 정의를 재검토한다.

`catchhole_llm_retries_total`은 429/5xx/timeout/network의 기존 transport loop만 센다. 추출·비교의 바깥 schema/출력 절단 재시도는 개별 호출·오류·시간에 보이지만 이 retry Counter에 추정으로 넣지 않는다. 입력 token에는 cached input이 포함되므로 input+cached_input을 총량으로 합산하지 않는다. usage 누락은 0 token과 구분한다. DB token 원장이 quota·정산의 단일 출처이며 프로세스 Counter는 감사 자료가 아니다.

## 라벨과 다중 인스턴스

Java 공통 `application=catchhole-backend`, Python `application=catchhole-ai`, 환경은 `environment=local|prod`다. Java는 enum job_type/analysis_mode/review_mode, Python은 worker_kind/job_type/purpose/기동 설정의 model 허용 목록을 사용한다. 미등록 값은 고정 other로 제한한다. 사용자/작품/Job ID, prompt/응답/원고, 비밀값과 예외 원문은 라벨에 넣지 않는다.

DB snapshot은 15초마다 갱신하고 scrape는 캐시를 읽는다. 여러 API가 같은 DB를 보면 **완전한 label 조합별 max로 중복을 제거한 뒤 다른 mode/type의 count를 sum**한다. mode가 다른 값을 먼저 max로 묶으면 queue를 과소 집계한다. oldest age는 가장 오래된 값의 max를 사용한다. snapshot 실패는 마지막 값을 보존하되 success=0, 마지막 성공 시각을 유지한다. 대시보드는 실패하거나 45초 이상 갱신되지 않은 snapshot을 정상 0으로 표시하지 않는다.

각 Worker 프로세스는 독립 Counter/Histogram을 가진다. 프로세스마다 scrape하고 rate/increase 이후 sum한다. 로드밸런싱 주소 하나를 scrape하면 여러 Counter가 섞인다. Counter는 재시작에 초기화되며 commit 직후 프로세스 중단/수집 간격의 짧은 사건은 유실될 수 있다. 메트릭은 정확히 한 번 전달되는 감사 원장이 아니다.

## 화면과 운영 적용

[대시보드 JSON과 설명](../deploy/monitoring/grafana/dashboards/README.md)은 운영 현황 / API·서버 상세 / 분석·AI 상세로 나눈다. Worker 종류/인스턴스 필터는 Python 패널에 적용하고 Spring의 전체 회차·DB 패널은 전체 서비스 상태를 유지한다.

배포는 Java main CI의 schema/API 배포가 성공한 뒤 AI main CI의 현재 SHA를 배포한다. 로컬 구현/테스트, 운영 scrape 연결, 정상 운영 분석 표본 확인은 서로 다른 완료 항목이다. [운영 모니터링 안내](../deploy/MONITORING_DEPLOYMENT.md)와 AI 저장소 `deploy/WORKER_EC2_DEPLOYMENT.md`의 metrics 절차를 따른다.

## 격리 환경 검증 기록 · 2026-10-01

- Java 최종 `./gradlew clean test bootJar`: 1,436개 통과, 23개 건너뜀(전체 1,459개), 실패 0개 및 실행 JAR 빌드 성공. rollback·중복 완료·사용자 재시도·lease 회수·후속 비교·부분 실패와 작은 DB 연결 풀의 동시 commit, 비동기 큐 경합 및 사람 재비교를 확인했다.
- Python 전체 테스트: 1,910개 통과, 14개 건너뜀. fake provider의 실패·취소·재시도와 실제 로컬 `/metrics` 서버의 시작·종료를 확인했다. 실제 provider나 과금 분석을 실행하지 않았다.
- 별도 PostgreSQL 16/pgvector에서 빈 DB의 V68까지 생성 → V69 적용 → Flyway 69개 검증 및 Spring JPA `ddl-auto=validate` 기동을 확인했다. 기존 개발·공유·운영 DB를 사용하지 않았다.
- 고정한 Prometheus v3.13.3에서 92개 대시보드 쿼리 구문과 5개 집계 시나리오를 검증했다. 다중 API Gauge 중복 제거, 실패·오래된 snapshot 제외, 부분 실패를 포함한 성공률 분모, 결과 없음의 NaN을 확인했다.
- target 생성기 테스트 6개와 실제 Docker 5+1+1 프로세스의 서로 다른 게시 포트·HTTP 수집을 확인했다. 이 포트 검증은 테스트용 exporter로 수행했고 운영 Worker 처리 검증은 아니다.
- Grafana 13.2.2에서 세 JSON을 저장하고 다시 조회해 제목·패널 수·쿼리·설명·대시보드 링크가 보존되는지 확인했다. 운영 현황 14개, API·서버 상세 13개(row 포함), 분석·AI 상세 20개 패널이다.
- 운영 API·Worker 배포, Worker SG 사설 접근, 운영 Prometheus 대상 등록·Grafana 가져오기, 정상 운영 분석 표본 대조는 이 기록에 포함하지 않는다. #206은 운영 수집·조회까지 확인한 뒤 완료한다.
