# GH180 최신 main 통합 검증 — 2026-09-13

## 최종 정책

단일·다회차 모두 회차별 자동 반영이 기본이며, 단일만 전체 직접 검토를 선택할 수 있다.
다회차의 선행 10회차 조건은 제거한다. 한 요청의 원고는 공백 포함 250,000자까지 허용한다.
이 정책은 최초 #180의 수동 확정 전제에서 후속 제품 합의로 바뀐 최종 구현이다.
한 회차의 정상 설정 저장을 마친 뒤 다음 회차가 실제 확정 상태를 읽는다. 개별 후보의
격리 가능한 실패는 검토로 보존하며, 사용량·계정·고정 입력·lease·저장 오류는 계속 중단한다.

## 통합 기준

| 저장소 | 기준 |
| --- | --- |
| Java | main `37e6df4975773cfb278bec82aaedef9471fd9324` — 이메일 가입 #183, 인프라 문서 #186 포함 |
| AI | PR65 `6deac0400c7b84a22715d669f30dfb12a7ca2b34` — main `05d9c2d`의 평가·진단 #67 포함 |
| Front | main `7cd479347c4c1f3448c876fe43f6667cb278f95d` — 이메일 가입, 검토 UI #66, 접근성, 영상 Hero 및 최신 문구 #76 포함 |

AI #65는 아직 OPEN이므로 후속 AI PR은 해당 브랜치를 base로 둔다. #65가 머지되면
main으로 변경하고 CI를 확인한다. 새 인물 비교 관련 열린 Java #191·AI #69·Front #75는
이번 통합에 포함하지 않았다. 그 변경과는 후보 생성·비교·검토 경계를 다시 확인해야 한다.

## 검증 결과

| 검사 | 결과 |
| --- | --- |
| Java 전체 `./gradlew test bootJar` | 1,066 통과, 환경 조건 65 제외, 실패 0; 빌드 성공 |
| 실제 PostgreSQL 상태·순차 처리 | 12 통과 |
| 실제 Spring HTTP ↔ Python Worker | 9 통과; 합성 원문·고정 응답, 외부 LLM 0회 |
| migration 업그레이드 | 2 통과: 기존 이메일 가입 검증 + main V42→V53 회원 보존 |
| AI 전체 오프라인 | 1,861 통과, integration 14 제외; Ruff 통과 |
| Front 브라우저 | 전체 실행 305 통과, 2 live 제외; 옛 문구 기대값 1개 수정 후 업로드 전체 18개 재검증 통과. 최종 306개 시나리오 확인 |
| Front 정적 검사 | TypeScript·빌드 통과, ESLint 오류 0·기존 경고 2, Knip 통과 |

위 별도 PostgreSQL 검증은 기본 Java 실행에서 제외되는 테스트를 전용 DB로 실행한 것이다.
사용자 작품 DB와 기존 실행 중인 API·워커는 검증 초기화나 새 migration 대상으로 쓰지 않았다.

```bash
./gradlew test bootJar
# 일회용 PostgreSQL의 gh180_test / gh180_e2e_test를 각각 사용한다.
GH180_POSTGRES_JDBC_URL=jdbc:postgresql://127.0.0.1:35433/gh180_test \
GH180_E2E_JDBC_URL=jdbc:postgresql://127.0.0.1:35433/gh180_e2e_test \
GH180_AI_ROOT=../catchhole-backend-ai \
./gradlew test --tests '*OrderedAnalysisPostgresIntegrationTest' --tests '*OrderedAnalysisHttpWorkerIntegrationTest'
```

두 검증 DB의 계정은 테스트 코드의 로컬 전용 `gh180`을 사용한다. Python 실행 경로는
`GH180_PYTHON`, 사전 준비한 tokenizer 캐시는 `TIKTOKEN_CACHE_DIR`로 지정할 수 있다.
V42 업그레이드는 별도 빈 `gh180_migration_test`와 `GH180_MIGRATION_JDBC_URL`을 사용한다.

## 배포와 한계

- main의 이메일 가입 V42를 유지하고 GH180의 미배포 migration을 V43~V53으로 옮겼다.
  이전 실험 V42~V52를 적용한 로컬 DB에는 바로 실행하지 않는다. 이력 삭제·checksum 덮어쓰기는 하지 않는다.
- 진행 중인 작업을 정리한 뒤 **Java schema/API → 같은 버전의 분석·비교 AI 워커 → Front** 순서로 반영한다.
  서버만 갱신된 동안 이전 워커가 새 분석 모드를 처리한다고 가정하지 않는다.
- 이번 작업에서는 PR만 발행하며 main 머지·운영 배포는 수행하지 않는다.
- 이전 로컬 실제 21~40화와 41~50화는 각각 중단 없이 완료했다. 최신 upstream 통합 후의 새 실제 LLM 실행은 하지 않았다.
  정답지 기반 정확도·재현율은 별도 이슈이며 이번 테스트를 품질 점수로 해석하지 않는다.
- 분석에 필요한 인프라·사용량·입력·저장 오류까지 무시하지 않는다. 모든 원인에서 절대 멈추지 않는다는 보장은 아니다.

설계 읽기: [순차 상태 계약](ordered-provisional-analysis.md) → [자동 반영·보류](automatic-review-reliability.md) → [세계관 비교 복구](world-comparison-recovery.md).
