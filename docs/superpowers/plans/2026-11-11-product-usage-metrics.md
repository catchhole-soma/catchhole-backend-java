# 제품 사용 지표 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 설정 추출 완료 회원·반복 완료·작품 생성의 삭제에 보존되는 날짜/월 합계를 기존 Grafana에서 확인한다.
**Architecture:** 서비스 DB에 생성 receipt, 완료 receipt, 현재 회원별 중복 제거 보조 기록과 익명 날짜/월 합계를 저장한다. 하나의 DB 직렬화 경계에서 합계를 갱신하고 기존 Prometheus 캐시 수집으로 제공한다. 원문 무효화/작품 파기는 기존 Job 잠금 뒤 최종 정산한다.
**Tech Stack:** Java 21, Spring Boot 4.0.6, JPA/JDBC, PostgreSQL 16/Flyway, Micrometer, Grafana/Prometheus.
**Spec:** ../specs/2026-11-11-product-usage-metrics-design.md (2026-11-11 사용자 승인)

## Global Constraints
- 한국 시간 달력 기준 오늘/오늘 포함 7일/30일/이번 달. 새 집계 이후 최초 접수분만 기간 합계.
- SETTING_EXTRACTION의 회차/설정집만 포함. 대상 전체 준비, success/partial_success, ordered SEALED, 자동 반영 완료.
- 첫 완료 이후 요청이 반복. 기존 회원의 첫 관측에 과거 증거가 없으면 분류 불명.
- 회원/작품/요청 ID를 외부 metric label에 넣지 않는다. 탈퇴 후 익명 합계만 보존한다.
- Member → Work → Job → 집계 잠금 순서. polling은 집계 잠금만 사용하며 business FK 잠금을 만들지 않는다.
- 기존 GA와 요청 기준 지표, 공개 API, #423/#425/#426 상태를 변경하지 않는다.
- 새 migration만 추가. 기존 dependency와 데이터 소스를 재사용한다.
- 시작 전/실패/180초 이상 지연은 정상 0으로 표시하지 않는다.

## Review Focus
- 완료 직후 원문 무효화가 먼저 실행되어도 완료 사실 유지: Task 3.
- 서로 다른 작품의 늦게 도착한 더 이른 완료로 최초 분류 날짜 변경: Task 1.
- 탈퇴 후 7/30일 창이 자연 만료되어도 식별 기록 없이 정확한 인원 유지: Task 1.
- 생성 성공 직후 삭제되어 정기 수집보다 빨라도 생성 합계 유지: Task 3.
- 구버전 writer와 첫 활성화 시각/DB 잠금이 충돌하지 않는 배포: Task 5.

## File responsibilities
- `domain/productusage/entity/`: State, DailyTotal, MonthlyTotal, MemberSummary, MemberActivity, AnalysisReceipt, WorkReceipt JPA 저장 형태.
- `domain/productusage/service/ProductUsageService.java`: 영속 원자 집계, 생성 receipt, 소유 기록 파기.
- `domain/productusage/repository/ProductUsageSourceRepository.java`: 완료 요청을 엄격히 판정하는 읽기 전용 SQL.
- `domain/productusage/repository/ProductUsageRepository.java`: EntityManager 기반 잠금/기간 조회/쓰기.
- `global/monitoring/ProductUsageMetrics.java`, `ProductUsageMetricsScheduler.java`: 캐시 Gauge와 수집 신선도.
- `domain/analysis/service/AnalysisResultReadyTracker.java`: 기존 판정 재사용, 현재 tx 내 최종 정산 entry.
- 기존 WorkServiceImpl, WorkPurgeDataRepository/Processor, MemberWithdrawalDataRepository, AnalysisRunStateServiceImpl: 소유 lifecycle 연결.
- 새 V124 migration, 지표 문서/AGENTS, overview JSON과 신규 product usage JSON, PromQL fixture.

### Task 1: 영속 집계와 중복 제거
**Interfaces:** ProductUsageService.initialize(LocalDateTime); recordWorkCreated(Work); collect(LocalDateTime); settleWork(UUID); purgeWork(UUID); purgeMember(Long). Repository는 singleton 상태를 pessimistic lock하고 사업 Entity의 FK 없이 receipt/요약을 다룬다.
- [ ] 실패 테스트: 같은 회원 3회 완료 → 완료 3, 반복 2, 회원 1; 다른 날짜/월 → 월별 중복 제거.
- [ ] 실패 테스트: 과거 회원의 첫 관측 → 불명 1, 다음 → 반복 1; 늦은 최초 완료 → 이전 날짜 반복 증가/불명 감소.
- [ ] 실패 테스트: 활동 D의 투영은 D..D+6/D+29에만 회원 1; 탈퇴 후 식별 행 0, 모든 익명 합계 동일.
- [ ] 최소 JPA 구조와 V124, 원자 집계 구현. timestamp+request UUID tie-break로 최초 완료를 결정한다.
- [ ] 대상 테스트 실행, PostgreSQL migration/validate 및 두 인스턴스 동시 멱등성 검증.
- [ ] 기능과 최소 테스트, 별도 설계/문서 커밋 규칙 준수.

### Task 2: 요청 완료 원천과 수집
**Interfaces:** ProductUsageSourceRepository.completedRequests(LocalDateTime asOf, UUID nullableWorkId) → List<CompletedRequest>(requestId, memberId, memberCreatedAt, workId, requestedAt, completedAt). 모든 적격 미처리 요청을 시간순으로 반환한다.
- [ ] 실패 테스트: 여러 회차 전체 완료, 부분 성공 허용, 자동 반영 대기/INVALIDATED/INCOMPLETE/실패 제외.
- [ ] 실패 테스트: 최신 재시도 선택, 설정집 source 식별, 중복/불일치 metadata 제외, 시작 전 접수는 회원 baseline만 갱신.
- [ ] 완료 SQL과 receipt 기반 멱등 수집 구현. 생성 receipt도 같은 집계 실행에서 반영한다.
- [ ] H2/실제 PostgreSQL 테스트로 검증하고 커밋.

### Task 3: 생성·무효화·파기 경계
**Interfaces:** recordWorkCreated는 생성 tx 참가. settleWork는 기존 Job 잠금 이후 현재 tx에서 준비 판정 후 collect(workId). purgeWork/Member는 소유 식별 기록만 삭제.
- [ ] 실패 테스트: Work 생성 rollback, 수집 전 삭제, 완료 직후 원문 변경, 결과 관측과 삭제 경합.
- [ ] AnalysisResultReadyTracker의 판정을 현재 tx에서도 호출하도록 분리하되 기존 REQUIRES_NEW 비동기 진입을 유지한다.
- [ ] 기존 lifecycle entry에 생성 기록/변경 전 정산/파기 정산과 식별 기록 삭제를 연결한다.
- [ ] 단위/통합 회귀와 PostgreSQL 잠금 경합 테스트를 실행한다.
- [ ] 개인정보 삭제 규약/패키지 AGENTS 변경은 해당 구현과 함께 기록한다.

### Task 4: Gauge와 Grafana
**Interfaces:** 새 snapshot은 today active1/7/30, currentMonth active, 오늘/이번달 completed/repeat/unknown/works, 최근 60일·12개월 aggregate rows, collection success/time/start를 제공한다.
- [ ] 실패 테스트: 시작 전 NaN, 집계 실패와 읽기 성공 구분, stale snapshot, 재시작, 날짜/월 label 순환 및 미래 날짜 비노출.
- [ ] 60초 scheduler와 bounded Gauge 등록. scrape는 atomic cache만 읽는다.
- [ ] overview 제품 Stat과 product usage 상세 JSON, 기존 datasource catchhole-prometheus 사용.
- [ ] PromQL 전체 문법/fixture(신선도, NaN, 다중 인스턴스, 기록 부족) 검증.
- [ ] 대시보드/문서 커밋을 앱 코드와 분리한다.

### Task 5: 전체 검증과 운영 연결
- [ ] 전체 ./gradlew test bootJar, 빈 PostgreSQL migration/validate, diff 검사.
- [ ] 전체 변경을 별도 fresh reviewer가 검토하고 중대한 문제를 수정한다.
- [ ] issue에 기준/검증 반영, draft PR 생성. 기존 #423/#425/#426은 유지.
- [ ] 리뷰 가능한 실제 변경과 검증을 제시한 뒤 사용자 지시에 맞춰 배포한다. 기존 단일 API 교체가 구 writer 중단 → 신규 초기화 순서를 보장하는지 확인한다.
- [ ] API 지표 수집 확인 후 기존 Grafana JSON 백업/가져오기/재조회. 로그인은 사용자에게 맡긴다.
- [ ] 새 대시보드 URL, 실제 수치/수집 시작 시각/남은 제약을 결과에 포함한다.

## Self-review
설계의 완료/기간/초기 이력/익명 보존/무효화/삭제/신선도/운영 확인은 위 다섯 Task에 대응한다. 구현은 같은 세션에서 직접 진행한다. 새 지표는 기존 현재 집계와 분리해 회귀 영향을 줄인다.
