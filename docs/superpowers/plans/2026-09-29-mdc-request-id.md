# MDC HTTP requestId Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. The user approved the design and requested implementation in the current session.

**Goal:** HTTP 응답과 로그를 UUID requestId로 연결하고 삭제 작업 ID와 구분한다.

**Architecture:** Security 앞의 Servlet Filter 한 곳에서 MDC와 응답 헤더를 관리한다. Boot 기본 로그 패턴의 level만 확장하며 기존 업무 API·DB 계약은 유지한다.

**Tech Stack:** Java 21, Spring Boot 4.0.6, Servlet, SLF4J MDC, Logback, JUnit/MockMvc. 의존성 추가 없음.

**Spec:** https://github.com/catchhole-soma/catchhole-backend-java/issues/207 및 승인된 대화 설계.

## Global Constraints

- 클라이언트 X-Request-ID는 채택하지 않고 서버 UUID를 생성한다.
- MDC key=requestId, 응답 헤더=X-Request-ID, 문맥 밖 표시는 `-`.
- 기존 API 본문의 requestId와 DB 필드는 유지하고 로그만 purgeRequestId/withdrawalRequestId로 변경한다.
- 파기 실패·재시도·S3 권한, 분산 추적, 범용 비동기 MDC 전파는 범위 밖이다.
- 운영 배포는 이번 작업에 포함하지 않는다. 운영 검증은 배포 후 절차로 남긴다.

## Review Focus

- Security의 조기 401/403에서도 헤더가 설정되고 MDC가 정리된다.
- 동시 요청과 스레드 재사용에서 ID가 섞이지 않는다.
- 예외 및 ERROR 재디스패치에서 ID가 유지되고 완료 로그가 중복되지 않는다.
- 쿼리·본문·인증 헤더를 공통 로그로 출력하지 않는다.
- health/prometheus 성공 요청은 완료 로그를 생략하되 실패와 헤더는 유지한다.

### Task 1: 공통 HTTP 로그 연결과 검증

**Files:** global/config/logging/RequestIdFilter.java, RequestLoggingConfig.java; application.yml; CorsConfig.java; GlobalExceptionHandler.java; 원문/작품 파기 및 탈퇴 처리기와 원문 파기 이벤트 리스너; 해당 테스트.

**Interfaces:** Servlet REQUEST/ERROR dispatch, SecurityFilterProperties.getOrder() - 1; request attribute에 UUID 보관; finally에서 자신이 소유한 MDC key만 정리; logging.pattern.level에 MDC 표시.

- [x] 변경 전 `./gradlew test --offline --console=plain` 실행. Expected: 기존 테스트 통과. 로그·XML은 저장소 밖 작업 산출물에 보관.
- [x] 실제 보안 체인을 거치는 MockMvc 테스트를 먼저 작성하고 실행. Expected: 헤더/MDC 누락으로 실패; 변경 전 로그 확보.
- [x] 필터·등록 설정·로그 패턴·CORS·500 예외 로그 및 업무 로그 명칭을 최소 수정.
- [x] 필터 테스트로 동시 요청, 스레드 재사용, 예외 정리, ERROR 재진입을 검증. MockMvc로 성공/400/401/403/500, 두 Security 체인, CORS와 모니터링 제외를 검증.
- [x] `./gradlew test --offline --console=plain` 및 `./gradlew bootJar --offline --console=plain`. Expected: 테스트 통과와 실행 JAR 생성.

### Task 2: 운영 안내·로그 이미지·이슈 반영

**Files:** AGENTS.md; docs/request-logging.md; docs/logging/ 아래 전후 로그 원문과 PNG.

**Interfaces:** Task 1의 테스트 로그와 응답 X-Request-ID; 기존 이슈 #207.

- [x] AGENTS.md에 로그 ID 규칙과 패키지를 추가하고 운영 조회·적용 범위를 문서화.
- [x] 전후 실제 테스트 출력에서 비교할 로그를 추출해 원문과 PNG로 저장. 로컬 테스트라는 출처와 예시 경로를 명시하고 실제 출력값은 보존.
- [x] 이미지 가독성 및 로그 원문과 일치 여부 확인.
- [x] 이슈 #207의 구현 정책·완료 기준을 실제 결과로 갱신하고 PNG를 첨부. 운영 배포 검증은 미완료로 구분.

## Progress

- 2026-09-29: 작업 브랜치를 기존 네이밍에 맞춘 `feat/gh-207-mdc-request-id`로 정리하고, 작업 위치를 기존 `apps/CatchHole-Backend` 폴더로 통일했다. 브랜치 네이밍과 기존 폴더 우선 원칙은 `AGENTS.md`에 명시한다.

- 완료: `./gradlew test bootJar --offline --console=plain` 성공. 1,257개 중 실패 0, 건너뜀 66. 신규 11개.
- 완료: 전후 실제 로그 TXT/PNG 저장 및 시각 확인, 이슈 #207 본문에 첨부·구현 결과 반영. 운영 검증은 미완료로 유지.
- PR 준비: 최신 main `203ad95a`의 별도 관리 포트와 `/healthz`를 반영한다. 실제 prod 프로파일의 두 로컬 HTTP 서버를 검증하며 운영 서버에는 연결하지 않는다. 기존 등록 설정을 ManagementContextConfiguration.imports에서 재사용하고 정상 /healthz 완료 로그를 제외한다.

- PR 최종 검증: 최신 main에서 1,263개 중 실패 0, 건너뜀 66. prod API·관리 서버의 실제 HTTP 요청과 로그 연결을 확인했다. 빌드 산출물·프로젝트 캐시를 /tmp로 분리해 중복 사본 영향을 제거했다.
- 커밋 분리: 운영 코드·필수 설정·AGENTS / 회귀 테스트 / 계획·운영 문서·로그 이미지.
