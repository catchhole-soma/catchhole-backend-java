# 검수 후 선택적 자동 반영 복원

> **For agentic workers:** Use superpowers:executing-plans. 사용자 요청에 따라 현재 세션에서 구현한다.

**Goal:** 오류 검수 후 추출도 일반 추출처럼 가능한 후보를 자동 반영하고 나머지만 검토 대기로 남긴다.

**Architecture:** 기존 캐릭터·세계관 contributor를 그대로 재사용한다. 자동 경로는 회차/출처 유효성만 전체 차단 조건으로 삼고, 후보 보류는 자동 단계 완료를 막지 않는다. APPLIED는 자동 처리 완료를 뜻하며 남은 후보는 일반 수동 확정으로 처리한다. 기존 HELD receipt의 명시적 전체 확정과 실제 예외의 트랜잭션 rollback은 유지한다.

**Tech Stack:** Java 21, Spring Boot 4, JPA, JUnit/H2.

**Spec:** 사용자 지시(2026-11-07): “worktree 파서 이전으로 되돌려 일반 설정 추출이랑 똑같이”. 오류 검토 완료 전 추출 차단과 원문/권한 보호는 유지한다.

## Global Constraints

- 기존 자동 반영 규칙을 복제하거나 새로운 후보 수용 규칙을 만들지 않는다.
- 실제 저장 예외는 두 도메인을 함께 되돌린다.
- 기존 운영 데이터·원문을 변경하지 않는다.

## Review Focus

- REVIEW_REQUIRED·값 오류·세계관 범위 미정이 정상 후보 반영을 막지 않는가.
- 자동 단계 완료 뒤 남은 후보를 직접 확정할 수 있는가.
- 원문 변경·미완료 추출과 실제 저장 실패는 계속 막는가.
- 남은 후보가 있어도 다음 회차 등록이 가능한가.
- 구버전 HELD receipt의 명시 반영·멱등성·rollback이 유지되는가.

### Task 1: 자동 경로 복원 및 회귀 검증

**Files:** `ValidationSettingsApplicationServiceImpl`, `AnalysisJobRepository`, 관련 검수 통합 테스트와 계약 문서.

- [x] 기존 관련 테스트가 통과하는지 확인한다.
- [x] 정상 후보와 보류 후보가 섞인 실제 완료 경계 테스트를 먼저 추가하고 실패를 확인한다.
- [x] 자동 경로의 후보 전체 차단/잔여 후보 rollback만 제거하고 APPLIED 뒤 직접 확정을 허용한다.
- [x] 구버전 HELD fixture를 명시적으로 분리해 기존 명시 반영 호환성을 검증한다.
- [x] 문서·AGENTS.md를 변경된 정책과 일치시킨다.
- [x] `./gradlew test bootJar`를 실행하고 결과를 확인한다.

## Verification record

- Baseline: 관련 자동/전체 확정·rollback·출처 guard 테스트 통과(1m 2s).

- Final review: 독립 리뷰에서 APPLIED 뒤 진단 재검수의 잔여 후보 확정 영구 차단을 발견했다. 진단 완료 후 확정 실패를 RED로 확인하고 BEFORE_CONFIRMATION에만 completion 차단을 적용했다. 진단 실행 중 설정 잠금은 유지한다.

- 관련 5개 통합 테스트 클래스: 36 tests, 0 failures/errors/skipped (1m 22s). 정상/보류 혼합, 형식 오류 격리, 직접 확정, 다음 회차 허용, 무효 리포트 차단, 진단 중 잠금과 완료 후 확정, 기존 HELD 명시 복구, 실제 예외 rollback을 확인했다.

- 최종 전체 검증: `./gradlew test bootJar` 성공(10m 12s). 310 suites, 5,204 tests 중 4,867 passed / 337 skipped / 0 failures / 0 errors. bootJar 생성. `git diff --check` 통과. 운영 배포·기존 원문/후보 소급 처리는 하지 않았다.
