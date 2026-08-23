# CatchHole Backend Docs

백엔드 도메인 설계, ERD, API 흐름, 작업 워크플로우를 정리하는 문서 디렉터리입니다.

전역 개발 규칙과 컨벤션은 `AGENTS.md`를 기준으로 관리하고, 특정 도메인의 설계 의도와 구현 흐름은 이 디렉터리에 둡니다.

## 문서 목록

| 문서 | 내용 |
| --- | --- |
| [ERD](erd.md) | 현재 Flyway schema와 JPA Entity 기준 테이블, 관계, 주요 제약 |
| [Database Migration](database-migration.md) | Flyway schema 관리 규칙, V1·V2 검증 및 최초 운영 전환 절차 |
| [AI Token Usage](ai-token-usage.md) | AI 요청별 토큰 예약·정산, 사용자 한도와 운영 지급 정책 |
| [Infrastructure Flow](infrastructure-flow.md) | 현재 운영 구조, 스케일링 전략, 미결정 인프라 선택지와 단계별 전환 계획 |
| [Global](global.md) | 공통 응답, 예외 처리, 보안, 설정, 스토리지 기반 구조 |
| [Auth](auth.md) | 회원가입, 로그인, JWT/refresh token, 세션 API 흐름 |
| [Signup Workflow](signup-workflow.md) | 휴대폰 인증부터 회원 저장·가입 토큰 소비·자동 로그인까지의 코드 실행 순서 |
| [Work](work.md) | 작품 모델, 소유권 정책, 작품 CRUD API 흐름 |
| [Manuscript Processing & Work Purge](manuscript-processing-and-work-purge.md) | 외부 AI 처리 안내, 작품 영구 삭제·재시도·보존 정책 |
| [Episode](episode.md) | 회차 모델, 원문 S3 저장, 회차 CRUD와 업로드 진입점 |
| [Upload](upload.md) | 업로드 배치/파일 추적 모델, 업로드 유형과 파싱 상태 |
| [Upload Episode Workflow](upload-episode-workflow.md) | 회차 업로드 시 batch/file/episode 생성 Mermaid workflow |
| [Analysis](analysis.md) | 회차별 분석 작업 상태 모델, 생성 API, 단일 회차 Worker 처리 흐름 |
| [Analysis Workflow](analysis-workflow.md) | 분석 API별 Mermaid workflow와 상태 전이 |
| [Character](character.md) | 캐릭터 설정 저장 모델, CharacterFact 검색·근거 상세 API, Schema Registry, JSONB 기준, AI 설정 후보 저장 구조 |
| [World Setting](world-setting.md) | 세계관 확정본·후보 저장 모델, 2차 비교 경계, 속성 단위 확정·직접 수정과 충돌 정책 |

## 추가 예정 설계 문서

DFD와 유스케이스 다이어그램은 Front나 AI 저장소 한쪽에 종속되지 않는 프로젝트 전체 설계 산출물이므로 Backend `docs/`를 단일 출처로 관리합니다. 실제 문서를 작성하면 아래 상태를 갱신하고 문서 목록에 링크를 추가합니다.

| 산출물 | 예정 위치 | 상태 | 포함 범위 |
| --- | --- | --- | --- |
| 데이터 흐름도(DFD) | `docs/data-flow.md` | 작성 필요 | Level 0 시스템 Context, Level 1 주요 프로세스, AI 분석·후보 확정·재비교 핵심 Level 2, 데이터 저장소와 흐름 목록 |
| 유스케이스 다이어그램 | `docs/use-cases.md` | 작성 필요 | 비회원·회원·AI Worker 액터, CatchHole 시스템 경계, 주요 사용자 목표, `include`·`extend` 관계 |

## 작성 기준

- 도메인별 문서는 현재 코드와 함께 갱신합니다.
- API 요청/응답, DB 필드, 상태 전이, 접근 제어처럼 구현에 영향을 주는 결정은 이유를 함께 남깁니다.
- Notion에 정리한 ERD나 워크플로우를 코드 기준으로 옮길 때는 현재 백엔드 구현과 다른 부분을 명시합니다.
- 전역 개발 규칙과 컨벤션은 `AGENTS.md`에 두고, 도메인별 설계 의도와 흐름은 `docs/`에 둡니다.
