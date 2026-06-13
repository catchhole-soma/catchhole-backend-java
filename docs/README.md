# CatchHole Backend Docs

백엔드 도메인 설계, ERD, API 흐름, 작업 워크플로우를 정리하는 문서 디렉터리입니다.

전역 개발 규칙과 컨벤션은 `AGENTS.md`를 기준으로 관리하고, 특정 도메인의 설계 의도와 구현 흐름은 이 디렉터리에 둡니다.

## 문서 목록

| 문서 | 내용 |
| --- | --- |
| [Analysis](analysis.md) | 분석 작업 상태 모델, 생성 API, batch 기반 처리 흐름 |
| [Analysis Workflow](analysis-workflow.md) | 분석 API별 Mermaid workflow와 상태 전이 |

## 작성 기준

- 도메인별 문서는 현재 코드와 함께 갱신합니다.
- API 요청/응답, DB 필드, 상태 전이처럼 구현에 영향을 주는 결정은 이유를 함께 남깁니다.
- Notion에 정리한 ERD나 워크플로우를 코드 기준으로 옮길 때는 현재 백엔드 구현과 다른 부분을 명시합니다.
