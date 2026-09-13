# GH180 최신 main 통합 검증 — 2026-09-13

## 최종 정책

단일·다회차 모두 회차별 자동 반영이 기본이며, 단일만 전체 직접 검토를 선택할 수 있다.
다회차의 선행 10회차 조건은 제거한다. 한 요청의 원고는 공백 포함 250,000자까지 허용한다.
이 정책은 최초 #180의 수동 확정 전제에서 후속 제품 합의로 바뀐 최종 구현이다.
한 회차의 정상 설정 저장을 마친 뒤 다음 회차가 실제 확정 상태를 읽는다. 개별 후보의
격리 가능한 실패는 검토로 보존하며, 사용량·계정·고정 입력·lease·저장 오류는 계속 중단한다.

## 통합 기준

세 PR은 모두 main 대상이다. 2026-09-13에 다음 main을 각 PR 브랜치로 병합했다.

| 저장소 | 반영한 main |
| --- | --- |
| Java #193 | `7bc708d608531fa8c56ab2b1868b78dafec110bb` — 새 인물 그룹 비교 #191 포함 |
| AI #70 | `4ed7e5556a91641c320c9e55dc646d4f17b0648d` — 그룹 비교 #69·세계관 추출 기준 #65 포함 |
| Front #77 | `885aa6d3317c24c0d4f01af8e04f4c68cb727e38` — 공통 비교 revision 대기 #75 포함 |

검증 도중 main에 추가된 AI #65까지 반영했다. AI 로직 기록은 main v0004를 보존하고
GH180을 v0005로 옮겼다. 기존 모드는 후보 입력이 닫힌 뒤 숨김 그룹 비교 Worker에 맡기고, 누적 분석은
원래 회차 Job에서 비교·저장·journal 봉인을 마친다. 누적 회차를 그룹 입력 종료까지
기다리게 하지 않는다. 서버는 누적 Job의 handoff 체크포인트를 거절한다.

Front는 응답의 `analysisMode`에 따라 기존 모드의 공통 revision과 누적 모드의 회차별
고정 문맥을 구별한다. 사용자 수정값의 직접 확정은 잠근 현재 설정에 검증하며, 이전
AI 비교 버전을 새 수동 제안의 버전으로 오인해 거절하지 않는다.

## 검증 결과

| 검사 | 결과 |
| --- | --- |
| Java 전체 + 실제 DB/HTTP `test bootJar` | 1,113 통과, 환경 조건 43 제외, 실패 0; 빌드 성공. 아래 별도 DB/HTTP 수치를 포함한다 |
| 실제 PostgreSQL 상태·순차 처리 | 12 통과 |
| 실제 Spring HTTP ↔ Python Worker | 9 통과; 합성 원문·고정 응답, 외부 LLM 0회 |
| migration 업그레이드 | main V43→V54의 11단계, 이메일 회원과 기존 nullable 새 인물 비교 그룹 보존 확인 |
| AI 오프라인 전체 | 1,883 통과, integration 14 제외; Ruff 통과 |
| AI 실제 PostgreSQL 저장 경계 | 별도 DB에서 12 통과 |
| Front Playwright 전체 | 최종 전체 실행 315 통과, 환경 의존 live 2 제외 |
| Front 정적 검사 | TypeScript·빌드 통과, ESLint 오류 0·기존 경고 2, Knip 통과; React Doctor는 권고 진단을 별도 출력 |

Playwright 중 polling 테스트 1개가 전체 실행에서 시간 조건으로 실패했다. 단독 재실행
2개와 이후 전체 315개 실행은 통과했다. 시간에 의존하는 테스트의 변동 가능성은 남는다.
외부 API를 가로채는 UI 테스트와 실제 서버 연결 검증은 구분한다.

별도 `gh180_browser_test`에서 실제 로그인(access token 저장·HttpOnly refresh cookie),
자동 처리 후 보류된 캐릭터의 직접 확인·그룹 확정을 브라우저로 실행했다. 처음에는 AI
비교 버전 0과 자동 저장 후 실제 버전 2가 달라 409가 재현됐다. 수정 후 HTTP 200,
DB `CONFIRMED`·`user_modified=true`·`reviewed_automatically=false`·비교 버전 2를
확인했다. 단건/그룹 직접 확인 회귀도 추가했고 수정 후 전체 Java 실행이 통과했다.

검증은 작업용 PostgreSQL의 별도 `gh180_test`, `gh180_e2e_test`, `gh180_ai_test`,
`gh180_migration_test` DB를 사용했다. 사용자 작품 DB와 기존 API·워커는 초기화하거나
migration을 적용하지 않았다. 업그레이드 테스트는 실행마다 새 빈 DB가 필요하다.

```bash
GH180_POSTGRES_JDBC_URL=jdbc:postgresql://127.0.0.1:35433/gh180_test \
GH180_E2E_JDBC_URL=jdbc:postgresql://127.0.0.1:35433/gh180_e2e_test \
GH180_MIGRATION_JDBC_URL=jdbc:postgresql://127.0.0.1:35433/gh180_migration_test \
GH180_AI_ROOT=../catchhole-backend-ai-gh180-pr \
./gradlew test bootJar
```

테스트 전용 계정/DB와 pgvector를 준비한다. Python 실행 경로는 `GH180_PYTHON`,
사전 준비한 tokenizer 캐시는 `TIKTOKEN_CACHE_DIR`로 지정할 수 있다. 서로 다른 브랜치의
실행 폴더를 사용하지 않도록 `GH180_AI_ROOT`를 실제 검증 대상 코드로 지정한다.

## 배포와 한계

- main의 이메일 가입 V42와 그룹 비교 V43을 유지하고 GH180의 미배포 migration을 V44~V54로 옮겼다.
  이전 실험 V42~V52 또는 V43~V53을 적용한 로컬 DB에는 바로 실행하지 않는다. 이력 삭제·checksum 덮어쓰기는 하지 않는다.
- 진행 중인 작업을 정리한 뒤 **Java schema/API → 같은 버전의 분석·비교 AI 워커 → Front** 순서로 반영한다.
  서버만 갱신된 동안 이전 워커가 새 분석 모드를 처리한다고 가정하지 않는다.
- 이번 작업에서는 PR만 발행하며 main 머지·운영 배포는 수행하지 않는다.
- 이전 로컬 실제 21~40화와 41~50화는 각각 중단 없이 완료했다. 최신 upstream 통합 후의 새 실제 LLM 실행은 하지 않았다.
  정답지 기반 정확도·재현율은 별도 이슈이며 이번 테스트를 품질 점수로 해석하지 않는다.
- 분석에 필요한 인프라·사용량·입력·저장 오류까지 무시하지 않는다. 모든 원인에서 절대 멈추지 않는다는 보장은 아니다.

설계 읽기: [순차 상태 계약](ordered-provisional-analysis.md) → [자동 반영·보류](automatic-review-reliability.md) → [세계관 비교 복구](world-comparison-recovery.md).
