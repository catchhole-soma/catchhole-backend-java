# 계정 최초 분석 안내

작가가 첫 분석 전에 자동 반영과 모든 설정 직접 검토의 차이를 체험하도록, 계정 전체의 분석 경험과 안내 여부를 서버에서 관리한다. 예시 화면은 프론트 메모리에서만 동작하며 이 API는 원고 내용·LLM·설정 확정 로직에 관여하지 않는다.

## API

두 API 모두 본인 인증이 필요하며 표준 `CommonResponse<AnalysisGuideResponse>`의 `data.shouldShow`를 반환한다. 작품 ID·타 회원 ID를 요청으로 받지 않는다.

| API | 동작 |
| --- | --- |
| `GET /api/v1/analysis-mode-guide` | 활성 회원의 안내/최초 분석 기록이 없고 계정의 모든 작품에 분석 Job이 없는지 조회한다. 상태를 변경하지 않는다. |
| `POST /api/v1/analysis-mode-guide/claim` | 회원 행을 잠그고 같은 조건을 재검증한다. 처음 성공한 요청만 안내 시각을 기록하고 true를 반환한다. 재요청·동시 요청·기존 분석 계정은 false다. |

안내 시각은 실제 화면 렌더 확인 시각이 아니라 **노출 선점 시각**이다. 응답 손실·탭 이동으로 화면을 못 봐도 자동 안내를 반복하지 않으며 프론트의 수동 도움말로 다시 볼 수 있다. 자격 조회/선점 실패는 업로드나 분석을 막는 선행 조건이 아니다.

## V64와 분석 시작

- `members.analysis_guide_shown_at`: nullable timestamp. 최초 선점 이후 다시 덮어쓰지 않는다.
- `members.first_analysis_started_at`: nullable timestamp. 최초 분석 Job 생성 트랜잭션에서 회원 잠금을 잡고 한 번 기록한다. 단일/ordered 및 자동/직접 검토 모두 동일하다. 생성 실패 시 함께 롤백한다.
- 모든 상태/종류의 Job이 분석 경험으로 계산된다. 실패·취소한 분석도 다시 신규 사용자로 간주하지 않는다. 작품 전체를 영구 삭제해도 두 회원 필드는 남으므로 자동 안내하지 않는다. 회원 탈퇴의 최종 행 삭제에서는 함께 삭제된다.
- V64는 남아 있는 기존 Job의 가장 이른 `created_at`을 회원별로 집계해 최초 분석 시각을 채운다. 작품·원고·설정은 변경하지 않는다. 적용 전에 모든 분석 이력이 영구 삭제된 계정은 과거 경험을 복원할 수 없어 신규 대상으로 분류될 수 있다.
- `existsByWork_Member_Id`는 이력의 보조 확인이며 두 시각 중 하나라도 있으면 호출하지 않는다. 작품 목록이나 원고 본문을 불러오지 않는다.
- AI Worker/Python 변경과 원고 재분석은 없다. API 프로세스가 기존 Job 생성 경계에서 기록한다.

## 검증

- `AnalysisGuideControllerIntegrationTest`: 읽기 비소비, 계정 1회, 다른 작품 분석도 제외, 회원 격리, 이력 지속/멱등, 인증.
- `AnalysisGuideConcurrencyIntegrationTest`: 같은 회원의 두 동시 선점 중 하나만 true.
- 기존 `AnalysisJobControllerIntegrationTest`: 실제 Job 생성과 최초 분석 시각의 같은 트랜잭션 저장.
- 2026-09-17 전체 Java 테스트 1,131 통과/66 조건부 건너뜀 및 추가 동시성 테스트 1개 통과. `bootJar` 성공.
- 빈 PostgreSQL V1~V64·Hibernate validate 및 기존 V63 백업 복제본의 V64 업그레이드 검증. 신규 회원 메타데이터 이외 업무 데이터를 보존한 뒤 로컬에도 적용했다. 기존 테스트 작품 9개·설정 63개 유지.
- Front↔Java↔PostgreSQL 격리 계정 실습에서 안내 시각만 저장하고 실제 캐릭터·세계관·분석 생성은 없음을 검증했다.

배포 순서는 Java V64/API → Front다. 이 migration은 이미지 자동 매칭의 운영자 보정과 독립적이며 기존 이미지 backfill을 실행하지 않는다.
