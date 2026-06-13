# CatchHole Backend Docs

백엔드 도메인 설계, ERD, API 흐름, 작업 워크플로우를 정리하는 문서 디렉터리입니다.

전역 개발 규칙과 컨벤션은 `AGENTS.md`를 기준으로 관리하고, 특정 도메인의 설계 의도와 구현 흐름은 이 디렉터리에 둡니다.

## 문서 목록

| 문서 | 내용 |
| --- | --- |
| [ERD](erd.md) | 현재 JPA Entity 기준 테이블, 관계, 주요 제약 |
| [Global](global.md) | 공통 응답, 예외 처리, 보안, 설정, 스토리지 기반 구조 |
| [Auth](auth.md) | 회원가입, 로그인, JWT/refresh token, 세션 API 흐름 |
| [Work](work.md) | 작품 모델, 소유권 정책, 작품 CRUD API 흐름 |
| [Episode](episode.md) | 회차 모델, 원문 S3 저장, 회차 CRUD와 업로드 진입점 |
| [Upload](upload.md) | 업로드 배치/파일 추적 모델, 업로드 유형과 파싱 상태 |
| [Upload Episode Workflow](upload-episode-workflow.md) | 회차 업로드 시 batch/file/episode 생성 Mermaid workflow |
| [Analysis](analysis.md) | 분석 작업 상태 모델, 생성 API, batch 기반 처리 흐름 |
| [Analysis Workflow](analysis-workflow.md) | 분석 API별 Mermaid workflow와 상태 전이 |

## 작성 기준

- 도메인별 문서는 현재 코드와 함께 갱신합니다.
- API 요청/응답, DB 필드, 상태 전이, 접근 제어처럼 구현에 영향을 주는 결정은 이유를 함께 남깁니다.
- Notion에 정리한 ERD나 워크플로우를 코드 기준으로 옮길 때는 현재 백엔드 구현과 다른 부분을 명시합니다.
- 전역 개발 규칙과 컨벤션은 `AGENTS.md`에 두고, 도메인별 설계 의도와 흐름은 `docs/`에 둡니다.
