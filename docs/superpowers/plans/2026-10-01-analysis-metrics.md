# Analysis Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회차별 분석 결과·대기·Worker·LLM 병목을 정확히 계측하고 3개 한국어 Grafana 화면으로 제공한다.

**Architecture:** Java는 DB 상태와 결과 준비 경계를 소유하고 Python CLI는 개별 프로세스·provider 호출을 소유한다. Prometheus는 기존 Actuator와 모든 Worker /metrics를 각각 수집한다. 메트릭 실패가 업무 처리를 바꾸지 않는다.

**Tech Stack:** Java 21 / Spring Boot / Micrometer / JPA / Flyway, Python 3.11+ / prometheus-client / asyncio, Docker Compose / Prometheus / Grafana.

**Spec:** docs/superpowers/specs/2026-10-01-analysis-metrics.md

## Global Constraints

- 최신 origin/main 반영, branch feat/gh-206-analysis-metrics, 기존 저장소 checkout 사용.
- 원문·ID·예외 문자열을 metric label에 넣지 않는다.
- Worker 동시성 5×10, 비교 각 1, graceful shutdown 180/210초 유지.
- 결과 준비 시간은 후속 비교 포함·사람 검토 제외, partial_success 별도 집계.
- commit 뒤 사건 기록, rollback·재호출 중복 방지, Gauge는 DB snapshot.
- 한국어 제목/설명/범례, datasource UID catchhole-prometheus, overview UID catlfln 유지.
- 운영 배포/SG 저장은 코드와 검증이 구체화된 뒤 수행하고 검증 범위를 사실대로 기록.

## Review Focus

- ordered 동일 Job 재시도와 lease 재claim에서 접수·결과·대기 기준이 초기화/중복되지 않는다.
- 후속 비교 지연·실패 및 자동 부분 실패가 사용자 결과 준비 시간/성공률에서 숨겨지지 않는다.
- 취소·예외·계측 오류에도 active Gauge와 semaphore/ledger 정리가 유지된다.
- process replica별 수집이 누락되지 않고 DB Gauge가 replica 수만큼 중복되지 않는다.
- denominator 없음과 scrape/DB snapshot 실패를 0%/정상으로 표시하지 않는다.

---

### Task 1: Python CLI와 개별 LLM 호출 계측

**Files:** AI repo app/monitoring/worker_metrics.py, app/core/config.py, app/usage/metering.py, scripts/run_analysis_worker.py, pyproject.toml, tests/test_worker_metrics.py 및 관련 scheduler/metering tests.

**Interfaces:** Spec의 Python metric 이름과 label을 그대로 제공한다. WorkerMetrics는 독립 registry 주입과 exporter start/stop을 지원한다. 기본 비활성·localhost, Compose에서 명시적 활성화한다.

- [x] fake delegate/ledger 및 독립 registry로 호출별 histogram, 429→성공 retry, timeout/cancel, usage unavailable, label 정규화, exporter GET /metrics, bind failure 및 Worker active 복귀 테스트를 먼저 작성하고 실패를 확인한다.
- [x] prometheus-client dependency를 추가하고 WorkerMetrics를 구현한다. 실제 provider 경계와 CLI process 경계에 최소 hook을 넣는다.
- [x] editable install, 관련 pytest, 전체 pytest 및 변경 파일 ruff를 실행한다.
- [x] dependency / 기능+최소 테스트 / 설정·문서를 의미별 커밋으로 분리한다.

### Task 2: Java 분석 상태와 결과 준비 계측

**Files:** Backend domain/analysis metrics component·snapshot repository·scheduler, AnalysisJob entity·create/claim/retry/complete/fail/recovery hooks, 후속 comparison coordinator, 다음 Flyway migration, tests/domain/analysis metrics integration/unit tests.

**Interfaces:** 제공 지표는 accepted_total, results_total{outcome}, result_ready_seconds histogram, pending_jobs{queue_state}, oldest_pending_seconds{queue_state}, running_jobs, snapshot_success/last_success_timestamp_seconds, claim_wait_seconds histogram, retries_total/recoveries_total. Prometheus 이름 prefix catchhole_analysis. job_type/analysis_mode/review_mode 등 enum label. task report에서 최종 이름을 명시하여 Task 3이 소비한다.

- [x] pending eligible/blocked, 빈 queue 0, 재시도 기준, rollback/duplicate 완료, 후속 비교와 partial_success를 재현하는 테스트를 먼저 작성하고 실패를 확인한다.
- [x] read-only 설계 보고서의 훅을 검증한 뒤 durable 측정 필드·집계 조회·after-commit 기록과 histogram을 구현한다. business query/claim 규칙을 중복 복사하지 않는다.
- [x] 관련 Gradle 테스트 및 전체 test/bootJar, 별도 PostgreSQL migration+JPA validate를 실행한다.
- [x] 코드+최소 테스트, 설정, 문서를 구분해 커밋한다.

### Task 3: 프로세스별 수집과 Grafana 3개 화면

**Files:** AI deploy/compose.worker.prod.yml, deploy/worker.env.example, targets 생성 script·테스트; Backend deploy/monitoring/prometheus.yml, compose.monitoring.prod.yml, targets 예시, grafana/dashboards 3개 JSON·README 및 deployment docs/AGENTS.

**Interfaces:** Task 1 Python 이름과 Task 2 report의 Java 이름을 사용한다. Docker port mapping의 host address/port로 actual target을 생성한다. 기본 host bind localhost, 운영 private bind와 monitoring SG 절차를 설명한다.

- [x] 5 analysis + 2 comparison target, process count 부족/중복/localhost 오설정 검출 및 queue/no-data/ratio PromQL 검증 시나리오를 작성한다.
- [x] 기존 concurrency를 유지한 port mapping과 target 생성기를 추가하고 Prometheus file_sd를 연결한다.
- [x] 기존 패널 설명을 보존하며 overview/API/analysis JSON을 구성하고 dashboard link/filters를 연결한다.
- [x] Compose render, Prometheus promtool check config/rules, local HTTP scrape/PromQL와 Grafana import 호환성을 검증한다.
- [x] 적용·재시작·drain·rollback과 지표 정의/데이터 없음 해석을 docs 및 각 AGENTS에 기록한다.

### Task 4: 통합 검증과 진행 기록

- [x] 최종 diff를 독립 리뷰하고 중요한 발견을 수정·재검증한다.
- [x] 두 저장소 최신 origin/main을 다시 확인하고 필요한 변경을 반영한다.
- [x] 기존 #205 local commits와 #206 변경의 관계, 테스트 결과, 로컬/운영 검증 여부를 기록한다.
- [x] 관련 #206에 최종 진행 댓글을 남긴다. main merge와 실제 운영 배포는 준비된 결과로 사용자에게 리뷰 가능하게 제시한다.

## 구현 기록

- 최종 fetch에서 Java origin/main `99cc192`, AI origin/main `d59496c`가 유지됨을 확인했다. 두 작업 브랜치는 `feat/gh-206-analysis-metrics`다.
- Java의 기존 #205 HTTPS·기본 대시보드 로컬 커밋은 최신 main 위의 `4f2ef5c`와 `e6030c4`로 보존했다. #206은 이 두 커밋 이후의 변경이다. main과 비교할 때 #205 변경도 포함됨을 리뷰에서 구분해야 한다.
- Java 계측 코드와 설정은 `09dbac0` / `de3d625`로 구분했다. 최종 전체 테스트 1,436개 통과·23개 건너뜀, 실행 JAR 빌드가 성공했다.
- Python 계측·포트/타깃 생성·문서는 `ee33905`부터 `43e5d6b`까지 별도 커밋으로 정리했다.
- 검증 근거와 로컬/운영의 구분은 `docs/analysis-metrics.md`에 기록했다. 실제 운영 배포·SG·수집 등록·Grafana 가져오기와 정상 운영 분석 표본 대조는 후속 적용 단계다.
- Java/Python/수집 설정의 독립 리뷰에서 중요한 발견을 수정했다. 커밋 스레드의 DB 연결 대기, 비동기 큐 종료 경합, Python 기본값 후보의 최초 상태와 사람 재비교 경계를 회귀 테스트로 확인했다.

- [#206 진행 댓글](https://github.com/catchhole-soma/catchhole-backend-java/issues/206#issuecomment-5927816863)에 구현·검증·로컬 커밋과 원격 게시/운영 적용 대기를 기록했다. 로컬 Git HTTPS 자격 증명 연결이 없어 push가 실패했으며 검증된 로컬 커밋을 유지했다. GitHub App 댓글 기록은 성공했다.
