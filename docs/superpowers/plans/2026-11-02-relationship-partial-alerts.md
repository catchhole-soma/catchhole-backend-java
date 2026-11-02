# 관계도 부분 실패 알림 구현 계획

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 관계도만 실패하고 핵심 분석이 계속되는 경우도 알리고, 회차 분석 중단과 확실히 구분한다.

**Architecture:** 기존 관계 실패 API에 optional 진단을 전달하고 관계 이력의 FAILED 전이와 같은 트랜잭션에서 기존 outbox에 RELATIONSHIP_PARTIAL snapshot을 만든다. 일반 SETTING_EXTRACTION만 부분 실패로 알리고, 전용 RELATIONSHIP_EXTRACTION은 기존 Job FAILED 알림을 유지한다. DB 소유 실행 시도·묶음·재시도·파기 규칙을 재사용한다.

**Tech Stack:** Python Worker, Spring/Java, PostgreSQL Flyway, Discord outbox.

**Spec:** 사용자의 2026-11-02 후속 요청 및 `docs/analysis-failure-notifications.md`. 같은 #357 worktree에서 직접 구현한다.

## 제약과 판단
- 후보 보류·분석 실패·재시도 판정은 바꾸지 않는다. worker가 보낸 임의 continue 플래그로 부분 실패를 판정하지 않는다.
- 실패 발생 당시 부분 실패와 나중에 생긴 Job 실패는 독립 snapshot으로 보존한다. 같은 묶음에서는 회차 중단을 상단에 우선 표시한다.
- 전체 FAILED 횟수와 관계 부분 실패 건수를 분리하며 후보 0개를 후보 미저장으로 해석하지 않는다.
- 원문/응답은 기존 승인된 제한 필드만 운반한다. 새 관계 진단 사본도 원문 파기 시 제거한다.
- 신규 V110을 사용하며 기존 migration·운영 알림은 변경하지 않는다. 커밋·배포·실제 Discord 발송은 하지 않는다.

## Task 1: 부분 실패의 진단과 outbox
- [x] Python `relationship_pipeline.py`의 fail 요청에 typed `failureDiagnostics`를 보내는 회귀를 RED→GREEN으로 구현한다.
- [x] Java `RelationshipGraphContract.Fail`, `RelationshipGraphAnalysis`, `RelationshipGraphServiceImpl`에 optional 정제 진단을 보존한다. 기존 4인자 요청과 재요청의 최초 원인 보존을 검증한다.
- [x] V110으로 일반 회차 관계 FAILED 전이만 RELATIONSHIP_PARTIAL outbox에 기록한다. RUNNING Job 유지·전용 Job 제외·rollback·반복 요청·후속 Job 실패 묶음·서버 자체 입력 한도 실패 fallback을 실제 PostgreSQL에서 검증한다.
- [x] 파기 요청 DELETE에서 새 관계 진단의 excerpts도 제거하는 회귀를 추가한다.

## Task 2: 표시와 최종 확인
- [x] `AnalysisFailureNotificationMapper.toPayload`에서 부분 실패 제목·결과·건수를 구분한다. 같은 묶음의 회차 FAILED를 우선하고 서로 모순되는 계속 안내를 막는 회귀를 RED→GREEN으로 구현한다.
- [x] Python 전체 pytest, Java 전체 test bootJar 및 실제 PostgreSQL/HTTP 테스트를 실행한다.
- [x] 독립 리뷰·문서/AGENTS/#357 갱신·실제 formatter 합성 미리보기를 완료한다.

## Review Focus
- 정상 HOLD나 COMPLETED는 실패 알림이 되지 않는다.
- 전용 관계 Job은 부분 실패와 전체 실패로 이중 집계되지 않는다.
- 재전송이나 진단만 수정한 UPDATE는 과거 outbox를 다시 만들지 않는다.
- 동일 실행에서 늦은 Job 실패는 부분 실패 기록을 덮어쓰지 않는다.
- 실패 진단의 구절은 새 저장소에도 파기 정책이 적용된다.

## 검증 진행 기록

- 최초 Python 요청 진단 누락, Java 부분 실패 미기록/잘못된 집계, 서비스 진단 누락을 RED로 확인 후 구현했다.
- 기존 Python 실패 요청 테스트는 새 optional 진단을 검증하되 raw 예외가 없는 계약을 유지하도록 갱신했다. 최종 전체 pytest: 5,111 passed / 143 skipped / 기존 Starlette 경고 1 (28.32초).
- Java 집중 검증은 관계도 서비스/백필, 알림 mapper, 실제 PostgreSQL을 포함해 통과했다. 관계 진단 사본의 원문 파기는 PROCESSING/REQUESTED 두 상태에서 RED→GREEN을 확인했다.
- 독립 리뷰의 구 Worker INPUT_TOO_LARGE 단계 오분류를 PENDING/준비된 문맥 두 경로로 재현·수정했다. 실제 PG 두 테스트 GREEN(12초), 재검토 추가 지적 없음.
- 위 리뷰 보완 때문에 진행 중이던 첫 Java 전체 실행은 중단했으며, 최종 코드의 전체 test bootJar를 새로 실행한다. 중단한 실행을 통과 결과로 사용하지 않는다.

- 최종 전체 Java test bootJar: 4,346 passed / 317 skipped / 실패·오류 0 (5분41초). 실제 PostgreSQL 알림 63개와 Python HTTP 연결 8개 모두 실행·통과.
- 운영 문서·양쪽 관계 계약 문서·AGENTS·GitHub #357을 갱신했다. 실제 formatter의 합성 미리보기는 workspace output/discord-relationship-partial-preview-20261102.txt. 커밋·배포·실제 Discord 발송은 하지 않았다.
