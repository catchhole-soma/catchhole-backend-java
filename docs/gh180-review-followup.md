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

## 두 번째 리뷰 반영

- 다회차 자동 반영 강제는 설정 추출에만 적용한다. 다회차 단일 파일·여러 파일의 회차 검수 요청은 기존 수동 모드를 유지하여 잘못된 모드 오류로 거절되지 않는다.
- 원문 파일 교체에도 기존 250,000자 정책을 적용한다. TXT·DOCX를 공백을 보존하여 읽고 Unicode code point로 계산한 뒤, 초과하면 이전 분석 무효화·업로드 생성·저장소 쓰기 전에 거절한다. 실행 중인 분석에 새 중단 조건을 추가한 것은 아니다.
- `AnalysisJobControllerIntegrationTest`, `EpisodeControllerIntegrationTest`, `AutomaticAnalysisIntegrationTest`, `AnalysisJobOrderedRetryTest` 합계 **156개 통과**. 두 업로드 방식의 수동 검수, 교체 제한 초과의 DB/저장소 부작용 없음, 공백과 비 BMP 문자로 정확히 250,000자인 교체 허용, 기존 자동 반영·재개를 포함한다. H2와 모의 저장소를 사용했고 운영 데이터는 변경하지 않았다.
- 위 보류 결정은 유지한다.

## 세 번째 리뷰 반영

- 세계관 대상의 현재 이름·분류를 그대로 재저장하면 기존 순차 실행을 무효화하지 않는다. 도메인의 이름 정리와 version 증가 결과로 실제 변경 여부를 판단하므로 앞뒤 공백만 있는 재전송도 안전하다. 실제 이름·분류 변경의 영향 분석 무효화와 version 검증은 유지한다.
- `AutomaticAnalysisIntegrationTest`, `WorldSettingControllerIntegrationTest`, `AnalysisJobOrderedRetryTest` **64개 통과**. 동일 요청 반복으로 RUNNING·SUCCEEDED/SEALED 및 뒤 회차 PENDING을 보존하고, 실제 변경에서는 영향 분석을 무효화하는 저장 경계를 확인했다.
- 만료 lease 복구와 작품 잠금의 교착 지적은 미해결로 남긴다. 현재 claim의 작품 잠금은 SKIP LOCKED를 사용하므로 리뷰가 지목한 대기 순환을 그대로 단정할 수 없다. 복구 조회의 연관 entity 잠금까지 실제 PostgreSQL 동시 실행으로 확인한 뒤 트랜잭션 경계를 변경해야 한다. 이번에 실제 교착을 재현하거나 해결했다고 주장하지 않는다.
- 캐릭터 전체 실패 응답의 오류 종류 검사 강화는 보류한다. 작업 전체 오류를 후보 실패로 받아들이는 계약의 빈틈은 남지만, 단순 HTTP 거절로 바꾸면 기존 원인을 다른 API 오류로 가리거나 새 중단 조건을 만들 수 있다. 후속 대응은 실제 중단 코드를 부모 작업에 보존하는 실패 경로로 설계한다.
