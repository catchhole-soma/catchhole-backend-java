# 세계관 설정 내용 비교 묶음 — Backend 착수

작업 이슈: [#267](https://github.com/catchhole-soma/catchhole-backend-java/issues/267) · 선행·상위 이슈: #251.

기준 main: `339ef410162b450764cea968bc18c51555dbc8c4`(Java #266 병합).
동반 AI 기준: `ed015dd948d580059020a506a96fb1b0b107b011`(AI #86 병합).
두 저장소 브랜치: `feat/gh-267-world-property-batching`.

통합 계획과 설계 기준은 동반 AI worktree의 같은 `docs/superpowers/` 아래에 둔다.

- 계획: `gh-251-world-property-ai/docs/superpowers/plans/2026-10-16-world-property-batching.md`
- 설계 기준: `gh-251-world-property-ai/docs/superpowers/specs/2026-10-16-world-property-batching.md`
- 로컬 기준/검증 기록: workspace의 `outputs/gh-251-world-property-batching-20261016/`.

목표는 입력·예상 출력 토큰 한도 안에서 함께 처리할 수 있는 비교를 묶는 것이다. 한 원고를 여러 요청으로 나누는 것도 정상이며 회차당 1회를 요구하지 않는다.

## 검증 결과

기존 claim/context/complete API로 독립 대상의 문맥을 먼저 읽고 순서대로 저장할 수 있어 Backend 제품 코드·DTO·migration을 추가하지 않았다. 같은 대상의 후속 비교는 앞선 결과를 저장한 뒤 새 문맥을 읽는다.

- 두 독립 문맥의 원래 contextToken으로 순차 완료하고 동일 완료 재전송이 중복 판단을 만들지 않는지 확인했다.
- version이 같아도 대상의 값이 바뀌면 이전 contextToken으로 저장하지 못하고 새 문맥을 요구하는지 확인했다.
- 전체 Java 테스트 2,738 통과, 236 skip, 실패/오류 0건. `test bootJar` 성공(3분 22초).
- 구현 기준 `7326b5716099ba60bd2c0e5ee974983e86fa7153`. 테스트만 추가했으므로 Java AGENTS.md의 계약·개발 규칙은 변경하지 않았다.
- AI의 묶음/복구/수동 중단 회귀와 실제 모델 결과는 동반 AI의 v0017 기록에 연결한다. 새 live backend 통신 중단·lease 만료 실험을 수행한 것으로 보고하지 않는다.
