> 2026-10-29 GH323 후속: 아래 2절의 일반 인용 신규/기존 구분과 offset 오류 중단 정책은 사용자 승인으로 변경되었습니다. 현재 규칙은 [analysis-continuity.md](../../analysis-continuity.md)의 “GH323 일반 인용 표시 정책”을 따릅니다. 동일인·시점 증명 및 원문 hash/출처 검증은 유지합니다.

# 기존 근거 인용 오류 격리와 Discord 상세 진단 설계

상태: 2026-10-28 사용자 승인. GitHub #304로 추적하며 구현은 gpt-6.1-sol / max로 수행한다. 운영 변경·재실행은 제외한다.

## 목적과 범위

과거 확정 설정의 인용문에서 큰따옴표가 누락돼 기존 캐릭터 문맥 생성이 HTTP 500으로 종료되는 문제를 해결한다. 기존 사실과 근거의 검증 가능 여부를 구분하고, 실제 실행 실패는 Discord에서 단계·원인·대상을 식별할 수 있게 한다.

운영 사용자의 현황은 읽기 전용으로 조사했다. 이 작업은 운영 근거 수정, DB 백필, 재비교/재분석, 후보 상태 변경을 포함하지 않는다. 실제 원고·회원 ID·작품명·캐릭터명은 테스트나 Git 문서에 복사하지 않고 합성 fixture를 사용한다.

## 코드 기준과 작업 공간

- Backend: `4b68365aff7ca58265f43bab0c7f08a276765371`, 새 `fix/gh-304-comparison-evidence-resilience` worktree.
- AI: `fa87321444af3724548f50c73c9694bd88d92817`, 같은 이름의 독립 worktree.
- 두 저장소는 `../../`가 아닌 작업 디렉터리의 형제 `backend/`, `ai/`다. 기존 apps 체크아웃을 변경하지 않는다.
- 앱의 worktree 도구는 상위 빈 Git 저장소에 HEAD가 없어 실패했다. 실제 두 저장소의 Git worktree로 생성했고 상위 `.worktrees/`가 ignore된 것을 확인했다.
- 기준 테스트: Backend 관련 4클래스 72개 성공, AI 관련 3파일 74개 성공. 전체 테스트와 PostgreSQL migration 검증은 구현 단계에서 수행한다.

## 현재 실패 흐름

1. `CharacterCurrentSupportingEvidenceResolver.captureSlot/captureFact`가 현재/과거 확정 Fact의 source candidate와 원 Job을 확인한다.
2. `captureCandidate`가 고정 원문 hash를 검증한 뒤 `SettingBookAutomaticEvidenceValidator.matches`로 인용·offset을 검사한다.
3. 인용 불일치와 작품/Job 연결 오류가 같은 `InvalidEvidenceException`이 된다.
4. 캐릭터 batch context API에서는 generic exception handler가 일반 500으로 응답한다. 첫 ordered claim의 별도 catch에서는 Job 실패·journal 정리까지 수행한다.
5. AI는 Spring HTTP 오류를 `UNEXPECTED_ERROR`로 분류한다. 캐릭터 pipeline이 상세 예외를 실패 코드로 축약하고 Worker가 다시 일반 RuntimeError를 만들므로 Job까지 실제 원인이 전달되지 않는다.
6. V88 trigger는 coarse 실패 코드만 outbox에 저장한다. Mapper는 `ANALYSIS_JOB`을 일괄 ‘회차 분석 실패’로 표시하고, 후보 알림의 원 추출 Job과 실제 재비교 실행 Job을 구분하지 않는다.

## Approaches — 권고와 대안

권고는 새 추출의 제한된 원문 복원과 기존 확정 근거의 명시적 UNAVAILABLE 처리를 함께 적용하는 것이다. 새 결과의 인용 품질을 개선하면서 기존 데이터가 새 비교 전체를 막지 않게 한다.

대안 A는 모든 인용의 따옴표를 제거한 채 VERIFIED로 인정하는 것이다. 실제 원문 인용·offset 계약이 깨지므로 채택하지 않는다.

대안 B는 불일치가 날 때마다 LLM 재요청 또는 운영 DB 자동수정을 하는 것이다. 비용·반복 실행·기존 원고와 사용자 결정 변경이 생기므로 이번 범위에 넣지 않는다.

더 작은 대안은 기존 확정 근거만 UNAVAILABLE로 처리하는 것이다. 장애는 막지만 같은 인용 오차가 새 추출에도 계속 저장된다. 아래 권고안은 기존 공백 복원기와 동일한 ‘원문에서 유일한 구간 확인 후 slice 저장’ 원칙을 재사용한다.

## 1. 새 추출 인용의 제한된 원문 복원

AI `evidence_span_resolver.py`의 현재 exact → 공백 정규화 검색 뒤에 큰따옴표 차이 검색을 추가한다.

- 허용 차이: 기존 공백 정규화와 큰따옴표 `U+0022`, `U+201C`, `U+201D`의 누락/종류 차이만. 작은따옴표·마이너스·숫자·부정 표현·일반 문장부호·한글은 보존한다.
- 정규화는 검색용이다. 실제 저장 quote는 반드시 원문 `chunk_text[start:end]`이고 offset도 동일 구간의 Unicode 문자 기준 전역 offset이다.
- 정규화된 의미 문자열이 비거나 원문에서 두 군데 이상 일치하면 복원하지 않는다. 원래 quote와 null offset을 유지한다. LLM을 추가 호출하지 않는다.
- 기존 exact/공백 경로의 결과를 바꾸지 않는다. 원시 AI payload는 보존하고 새 후보의 정제된 evidence만 기존 저장 경로로 전달한다.
- 실제 원문과 다시 정확히 일치하는지 검증한다. `SettingBookAutomaticEvidenceValidator.matches`의 정확 일치 기준은 완화하지 않는다.

## 2. 기존 확정 설정의 일반 인용 불일치 격리

검증 결과를 `VALID`, `QUOTE_NOT_FOUND`, `MALFORMED_SPANS`, `INVALID_OFFSETS`로 구분한다. `matches`는 기존 boolean 호환 wrapper로 유지한다. 구조와 offset의 유효성을 먼저 확인해 구조 오류를 QUOTE_NOT_FOUND로 숨기지 않는다.

기존 DB Fact/current/history 및 확정 discovery를 읽는 경로에만 명시적 `captureRetainedCandidate`를 사용한다. 신규 후보·ordered journal 기여·시점 승격의 `captureCandidate`는 엄격한 경로로 남긴다. 후보 상태만 보고 전역적으로 관대하게 바꾸지 않는다.

이미 저장된 `currentNarrativeTime`의 source를 읽는 `CharacterAnalysisStateSource` 경로도 retained다. 그 source의 **일반** 인용만 불일치하면 UNAVAILABLE로 둘 수 있지만, 별도 narrativeTime evidence와 저장된 referenceTime의 일치는 계속 엄격히 검증한다. 기존 대표 시점 읽기와 신규 대표 시점 승격을 같은 경로로 취급하지 않는다.

| 조건 | 처리 |
|---|---|
| 정상 일반 인용 | 기존 VERIFIED와 인용 유지 |
| 기존 확정 일반 인용이 원문에 없고 offset 둘 다 없음 | 해당 source를 UNAVAILABLE / QUOTE_NOT_FOUND로 표시, evidenceSpans는 빈 배열, 확정 Fact 값·source 연결은 유지, 비교 계속 |
| 여러 source 중 한 source만 불일치 | 그 source만 UNAVAILABLE, 나머지 정상 source는 유지 |
| 원래 근거 없음·원문 파기 | 기존 reason 및 처리 유지, 삭제한 원문을 복원하지 않음 |
| 신규 후보의 근거 검증 실패 | 기존 엄격한 검증·검토 정책 유지 |
| 잘못된 JSON, 반쪽 offset, 범위 초과, 명시 offset과 인용 불일치 | 구조화된 원인으로 중단 |
| 다른 작품/인물/Job으로 연결됨, manifest 불완전, 원문 hash 불일치 | 구조화된 원인으로 중단 |
| S3 읽기 실패 | 저장소 장애로 분류, 근거 없음으로 바꾸지 않음 |
| narrativeTime 근거 실패 | 시점 승격에 쓰이는 별도 엄격 검증 유지 |

`CharacterAdvisoryCurrentContextMapper`의 reason 허용 목록에 QUOTE_NOT_FOUND를 추가한다. 원문을 찾지 못한 quote 자체를 AI에 VERIFIED로 제공하지 않는다. 기존 frozen input/journal은 재작성하지 않고 새 입력 capture에만 반영한다. 프롬프트에는 ‘근거 확인 불가가 현재값이 틀렸다는 뜻은 아니며, 새 판단에 증거가 부족하면 검토 필요로 남긴다’는 규칙을 명시한다. 인용 불일치 로그는 source IDs·고정 reason·개수만 남기고 quote/설정값은 남기지 않는다. 정상 진행된 실행에는 실패 Discord를 만들지 않는다.

## 3. 실패 진단 계약

기존 `AnalysisFailureCode`는 사용자 공개 문구와 재시도/격리 정책에 사용되므로 유지한다. 상세 원인은 별도 optional `failureDiagnostics`로 전달한다. 진단 추가가 오류의 격리 가능성·lease·토큰·자동 확정 정책을 바꾸면 안 된다.

`AnalysisFailureDiagnostics` / Python `WorkerFailureDiagnostics`의 v1 허용 필드:

| 필드 | 계약 |
|---|---|
| schemaVersion | 정수 1 |
| origin | SPRING / PROVIDER / WORKER |
| stage | CLAIM_INPUT, CHARACTER_CONTEXT, CHARACTER_COMPARE, WORLD_CONTEXT, WORLD_COMPARE, EXTRACTION, FINALIZE, UNKNOWN |
| reasonCode | 서버·클라이언트의 고정 enum. 임의 예외 문자열 금지 |
| sourceErrorCode | 지원하는 Spring/Provider 코드 허용 목록. 미지원 값은 UNKNOWN |
| httpStatus | 실제 응답이 있으면 100~599, 없으면 null |
| requestId / traceId | 실제 수집된 bounded request ID / 32자리 hex trace ID. 없으면 null |
| sourceAnalysisJobId / sourceCandidateId / sourceEpisodeId / sourceEpisodeNo | 원인 근거의 식별자. Backend가 현재 작품 내 연결을 검증한 경우만 보존 |

실제 execution Job ID와 comparisonBatchId는 요청 URL/서버 엔티티에서 결정하고 Worker 입력을 권위로 사용하지 않는다. provider request ID는 `[A-Za-z0-9_-]{1,128}`만, 내부 request ID는 서버 규칙을 따른다. stage/코드/UUID/수치 이외의 자유 텍스트, 예외 메시지, URL, 헤더, 원문·설정값은 진단에 넣지 않는다. retryable 문구는 reason별 Mapper 정책으로 만들고 Worker boolean을 믿지 않는다.

초기 reason은 `CURRENT_EVIDENCE_BINDING_INVALID`, `CURRENT_EVIDENCE_MANIFEST_INVALID`, `CURRENT_EVIDENCE_STRUCTURE_INVALID`, `CURRENT_EVIDENCE_OFFSET_INVALID`, `CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH`, `CURRENT_EVIDENCE_SOURCE_READ_FAILED`, `CURRENT_EVIDENCE_QUOTE_NOT_FOUND`, `INTERNAL_API_HTTP_ERROR`, `INTERNAL_API_TRANSPORT_ERROR`, `LLM_RATE_LIMIT`, `LLM_TIMEOUT`, `LLM_PROVIDER_ERROR`, `LLM_OUTPUT_TRUNCATED`, `COMPARISON_RESULT_INVALID`, `UNKNOWN`으로 고정한다. QUOTE_NOT_FOUND는 엄격한 신규 경로의 실패 또는 기존 source 경고를 표현하며, 후자는 실패 전이를 만들지 않는다.

Backend의 알려진 검증 오류는 AppException + `ANALYSIS_CURRENT_EVIDENCE_INVALID`(409)와 안전한 context로 표현한다. S3 일시 장애는 503, hash/연결 오류는 409로 구분하고 원문을 담은 cause를 응답하지 않는다. 기존 claim의 deterministic-failure 정리 경로는 코드별로 유지하고 transient 저장소 오류를 그 catch에 섞지 않는다. UNKNOWN 500도 단계와 request/trace ID가 있으면 식별 가능해야 한다.

AI는 Spring 응답의 허용 필드만 파싱해 예외에 보관하고 batch/candidate fail과 Job fail까지 전달한다. `first_failure_code`와 같은 실패의 `first_failure_diagnostics`를 함께 운반해 일반 wrapper에서 사라지지 않게 한다. 세 Worker와 캐릭터/세계관 단건·묶음 fail 경로가 동일 계약을 사용한다. 구 Worker가 생략하면 기존 동작을 유지하며 상세 원인을 추정해서 만들지 않는다.

optional diagnostics의 잘못된 타입·미지원 버전·허용되지 않은 필드·교차 작품 ID는 해당 진단 필드 또는 진단 전체를 버리고 원래 실패 상태를 저장한다. 진단 검증 때문에 `/fail`을 400으로 거절하면 안 된다. Java fail 요청은 nullable JsonNode를 받아 nonthrowing sanitizer로 위 record를 만들며, Python은 응답 진단 파싱 실패를 HTTP 실패 자체와 분리한다. lease·Job 권한·필수 failure code 같은 본래 실패 API 검증은 그대로 유지한다. 요청 전체가 JSON 파싱 불가능한 경우까지 정상 처리한다는 의미는 아니다.

## 4. Discord와 outbox

- Job·캐릭터/세계관 후보·묶음에 optional 진단을 같은 실패 트랜잭션에서 저장한다. 재시도 시작 시 그 시도의 진단을 비우고 과거 outbox는 그대로 유지한다.
- 새 Flyway migration으로 V88 trigger를 교체한다. 기존 migration을 수정하지 않는다. outbox에 실패 당시의 진단·job type·실행 Job ID·원본 추출 Job ID·비교 묶음 ID를 복사한다. 발송 시 현재 Job 상태로 과거 원인을 재구성하지 않는다.
- 같은 트랜잭션의 후보를 합칠 때 실행 Job/비교 묶음/stage/reason이 다른 사건을 하나로 합치지 않는다. NULL 진단인 기존 writer는 기존 그룹 기준을 유지한다.
- 제목은 ‘회차 설정 추출 실패’, ‘캐릭터 재비교 실행 실패’, ‘세계관 재비교 실행 실패’, ‘캐릭터 비교 항목 실패’, ‘세계관 비교 항목 실패’로 구분한다.
- 후보 실패와 실행 실패는 유지하되 같은 실행 ID로 연결한다. 후보의 원본 추출 Job을 실제 재비교 실행 Job으로 오인하지 않게 별도 표시한다. 묶음이 여러 회차면 단일 seed 회차를 전체 범위로 표시하지 않는다.
- 항목 수는 후보 사건에만 ‘실패 항목’으로 표시한다. 실행 사건의 기본 1은 ‘실패 작업 1건’이며 영향 후보 수는 실제 계산된 경우만 별도 표시한다.
- HTTP 응답 상태, 고정 코드 설명, 단계, 영향 범위, 재시도 판단, request/trace ID를 표시한다. trace 링크를 만들 경우 고정된 모니터링 host와 검증된 trace ID만 사용한다.
- 자유 형식 error_message/stack trace는 Discord에 복사하지 않는다. allowed_mentions=[]와 payload 상한 2,000자, 기존 at-least-once/backoff/lease/rollback 보장을 유지한다. 정상 복구나 일반 미확정 상태는 실패 알림을 만들지 않는다.

형식 예시(합성, 실제 발생 알림이 아님):

```text
🚨 [운영] 캐릭터 재비교 실행 실패
단계: 기존 캐릭터 비교 문맥 준비
원인: 고정 원문 해시 불일치 (CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH)
영향: 대상 회차 59·60화 / 실패 작업 1건
원인 근거: 24화 / 후보 ID …
재시도: 원문 연결 확인 전 재시도 필요 없음
실행 Job ID: …
원본 추출 Job ID: …
HTTP: 409 / 요청 ID: … / Trace ID: …
회원·작품·회차 ID / 발생 시각 / 알림 ID: …
```

## 검증·배포 경계

합성 fixture로 문장부호 복원, 현재값 보존, 다른 source 정상 비교, strict 시점·신규 입력 검증을 확인한다. 실제 PostgreSQL에서 migration/trigger/동시 선점/rollback/원인별 그룹을 검증한다. 외부 Discord는 mock endpoint로 검사한다.

전체 Backend test/bootJar와 AI pytest를 통과하고 Java ↔ Python 계약 fixture를 왕복 검증한다. AI prompt·추출 처리 변경은 다음 AI logic version 문서에 합성 검증과 실제 모델 미측정을 구분해 기록한다. 실제 작가 원고로 유료 품질 평가를 시작하지 않는다.

Backend의 additive schema/optional 진단 수신을 먼저 배포할 수 있게 하고 AI 진단 전송을 뒤에 둔다. 구 Worker/신 Backend와 신 Worker/신 Backend를 검증한다. rollback은 additive column을 남기고 코드만 되돌릴 수 있도록 설계한다. 운영 적용은 PR/검증 결과가 준비된 뒤 별도 배포 단계로 다루며, 배포 후에도 사용자 작업을 자동 재시작하지 않는다.

## 완료 기준

1. 합성한 과거 확정 인용의 큰따옴표 누락이 새 비교를 500으로 막지 않는다.
2. 원문과 다르거나 확인하지 못한 인용을 VERIFIED로 위장하지 않는다.
3. 신규 근거·원문 hash·출처 연결·시점·offset·lease·토큰 검증은 유지된다.
4. 실제 실패 알림 하나로 종류·단계·구체 원인·실행과 출처·추적 ID를 알 수 있다. 미수집값은 확인 불가로 표시한다.
5. 운영 사용자의 원고·설정·작업 상태는 이 개발 작업에서 변경하지 않는다.

## 독립 설계 검토 기록

별도 builder 검토가 기존 대표 시점 읽기의 경계와 optional 진단 거절 시 실패 기록 누락 위험을 지적했다. 두 항목을 위 정책으로 명시했다. 이는 사용자의 새 제품 요구가 필요한 사항이 아니라 이미 요청한 장애 격리와 오류 가시성을 유지하기 위한 구현 경계다.
