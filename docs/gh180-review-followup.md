# PR 리뷰 후속 처리 — 2026-09-13

연속 분석의 중단 조건을 늘리는 변경은 보류한다는 사용자 결정에 따라 범위를 제한했다.

## 반영

- 원문 파기: 무효화된 journal의 참고 기록에는 감사 식별자·회차·처리 종류만 남긴다. 인용·참고 값·새로 추가된 미지의 payload도 제거한다. 정식 설정 영역의 값과 당시 hash는 유지하며 파기된 journal은 재사용하지 않는다.
- 세계관 진단 조회: 저장된 검증 단계와 전체 비교/복구 구분을 응답에 보존한다. 과거 누락 값이나 알 수 없는 선택적 진단은 null로 반환하여 목록 조회를 막지 않는다.
- 사용자 판단 문장: `SCOPE_MISMATCH`를 공개 응답에서 `적용 범위 차이`로 표현한다. 실제 작품 이름·설정값은 보호한다. AI 응답 거절·재시도·추가 호출을 늘리지 않으며 저장된 판단과 연산도 바꾸지 않는다. AI PR #70의 사용자 문장 리뷰는 이 서버 표시 경계에서 해결한다.

## 보류

- 실패한 임시 대상 참조 거절: 잘못된 의존 가능성은 남는다. 전체 요청을 새로 거절하는 수정 대신, 향후 의존 후보만 보류하고 독립 후보는 계속 처리하는 방식으로 검증해야 한다.
- 신뢰도 저장 정밀도: 소수점 네 자리 반올림은 남는다. 입력 거절 강화 없이 저장 정밀도를 확장하는 migration은 별도 검증 후 진행한다.
- 전용 예외 클래스 정리: 팀 규칙 문제는 인정하지만 현재 실패 상태의 커밋/롤백 동작을 바꾸지 않기 위해 보류한다.
- AI 입력 출처 누락·후보 출처 회차 필수화·응답 schema를 포함한 입력 한도 강화는 새 중단 조건이 될 수 있어 이번에는 반영하지 않는다. 기존 안전 검증을 제거하거나 실패한 입력을 성공으로 변환하지 않는다.

## 검증

`AnalysisStateJournalTest`, `AnalysisExplanationTextTest`, `WorldSettingMapperDiagnosticsTest`, `AnalysisJobOrderedRetryTest`, `AutomaticAnalysisIntegrationTest`로 원문 복사본 파기·표시 정보·연속 자동 저장·동일 작업 재개를 검증한다. 운영 DB나 실제 AI 호출은 사용하지 않는다.

관련 PR: [Java #193](https://github.com/catchhole-soma/catchhole-backend-java/pull/193), [AI #70](https://github.com/catchhole-soma/catchhole-backend-ai/pull/70), [Front #77](https://github.com/catchhole-soma/catchhole-front/pull/77).
