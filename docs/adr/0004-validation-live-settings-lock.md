# ADR-0004: 검수는 확정 설정을 복사하지 않고 현재 설정을 읽으며, 검수가 끝날 때까지 확정 설정 수정을 막는다

| 항목 | 내용 |
| --- | --- |
| 상태 | **승인** |
| 작성일 | 2026-11-05 |
| 작성자 | mkshie |
| 관련 이슈 | [#381](https://github.com/catchhole-soma/catchhole-backend-java/issues/381), [#379](https://github.com/catchhole-soma/catchhole-backend-java/issues/379)·PR [#380](https://github.com/catchhole-soma/catchhole-backend-java/pull/380), [#383](https://github.com/catchhole-soma/catchhole-backend-java/issues/383), [#345](https://github.com/catchhole-soma/catchhole-backend-java/issues/345), #303·#310·#312 |

> 2026-11-05 소유자가 아래 항목을 모두 결정했다. #381 이슈의 "설계 결정" 댓글과 같은 내용이다. 이 결정은 AGENTS.md #303 정책의 "진단은 … live 현재값으로 보충하지 않는다"와 GH306의 "저장 기준만 읽고 live 현재값으로 보충하지 않는다"를 대체한다.

## 1. 배경

### 1.1 접수 때 기준 전체 복사

#381 전에는 `POST /api/v1/works/{workId}/episode-validations` 요청 안에서 `ValidationBaselineService`가 확정 인물·세계관·세계관 확정 이력 전체를 `validation_reports.baseline_seed`에 복사했다. 이어서 판정용 항목 `baseline_entries`를 만들었다(#303). PLANNER가 읽는 주체 목록(SUBJECTS)과 JUDGE가 읽는 설정값(SETTINGS)은 데이터 API가 이 복사본에서만 만들었다.

운영 사본(102화 작품, 운영과 같은 768MB 힙)에서 잰 값은 다음과 같다. 측정 도구는 #379에서 만든 저장소 밖 도구이며, 운영 데이터는 저장소에 넣지 않았다.

| 시점 | 접수 결과 | live 힙 | SQL 문 수 | 과거 분석 Job 전체 행 |
| --- | --- | --- | --- | --- |
| #380 전 | 500 OOM, 21~23초(2회 모두). 4GB 힙이면 550초 | — | 7,880(파기 확인 3,846) | 100건 |
| #380 뒤 | 200, 25.6~36.1초(3회) | 대기 138MB → 최대 413MB | 2,889 | 0건 |

- **live 힙 413MB의 구성(#380 뒤).** 1초마다 전체 GC를 한 뒤 class histogram으로 쟀다.
  - 후보 로드 2초 동안 +139MB. `CharacterAnalysisStateSource.captureCharacter`가 historyFacts를 만들며 `fact.getSettingCandidate()`의 필드 세 개를 읽었다. 이 때문에 `SettingCandidate` 1,388행 전체(텍스트 22MB, 그중 `raw_comparison_json` 15MB)가 초기화됐다. 실제로 쓴 세 필드는 합쳐 22KB였다.
  - 그 뒤 세계관 정리 +26MB, 근거 확인 +18MB, seed 만들기·검사 +52MB, 저장 문자열 +39MB.
- **결과물 크기.** seed 7.7MB와 entries 7.7MB(텍스트)다. seed 구성은 세계관 이력 2,026행 3.4MB, 인물 113명 2.5MB(historyFacts 675개 포함), 세계관 518개 1.7MB, 출처 101개다.
- **판정에 쓰이지 않던 부분.** 세계관 이력(2,026행)은 SETTINGS로 나간 적이 없다(#349 grid v4, `ValidationDataMapper.CURRENT_DOMAINS`). 인물 historyFacts는 SETTINGS로는 나갔지만 AI 모델 투영(`app/validation/projection.py`)이 버렸다. 그래서 복사량의 큰 부분이 모델에 닿지 않았다.
- **크기에 비례하는 동기 처리.** 복사 비용이 작품의 확정 설정 크기에 비례했다. 요청 안에서 동기로 실행되므로, 실패하면 리포트 없이 접수 자체가 안 됐다(#345).

### 1.2 복사의 목적과 다른 해결

복사본에는 세 가지 목적이 있었다.

1. **실행 도중 값이 바뀌지 않게 한다.** 검수가 끝날 때까지 확정 설정 수정을 막으면 된다(아래 결정).
2. **리포트가 비교한 값을 보여 준다.** 오류 항목에는 이미 대상·설정명 사본이 있다(V102). 근거 문장(`basis_quotes`)의 B 인용 원문도 payload에 저장된다. 남는 것은 인용이 가리킨 대상·설정의 이름표다.
3. **설정이 바뀌면 리포트를 무효로 한다.** 규칙이 이미 있다. `ConfirmedSettingsChanged`가 APPLIED가 아닌 현재 report를 무효화한다.

## 2. 판단 기준

1. 접수 비용이 작품의 확정 설정 크기와 무관하다.
2. Worker가 받는 판정 입력이 지금과 같다. AI 모델 투영 뒤의 SETTINGS 행이 같아야 한다.
3. Worker wire 계약(응답 모양, `projectionVersion=2`, 409 처리)을 바꾸지 않는다.
4. Worker가 죽거나 실행이 깨져도 잠금이 남지 않는다.
5. 기존 무효화·재검수·정정 규칙을 유지한다.
6. 움직이는 부분이 가장 적다.

## 3. 검토한 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| A. **잠금 + 현재 설정 읽기 (채택)** | 접수가 출처 목록과 지문만 쓴다. 판정 입력은 현재 DB에서 요청 주체만 읽는다 | 검수 기간 동안 작가가 설정 화면에서 수정할 수 없다. 잠금 해제 규칙이 필요하다 | 채택 |
| B. 복사 유지 + 비동기 준비(#345) | 접수 요청은 빨리 끝난다 | 비용과 힙이 그대로 작품 크기에 비례한다. 준비 실패를 따로 다뤄야 한다 | 거절 |
| C. 주체 id·version만 복사한 색인(~40KB) | 실행 뒤에도 B·S 번호가 고정된다 | 또 하나의 복사본이라 현재 DB와 맞춰야 한다. 잠금이 있으면 필요 없다 | 거절 |
| D. 오류 항목에 판정에 쓴 설정값 복사 | 작고, APPLIED 뒤에도 값을 보여 줄 수 있다 | B 인용 원문(`basis_quotes`)과 중복된다. 소유자가 "값은 남기지 않고 이름표만"으로 정했다 | 이름표만 채택 |
| E. 지금처럼 전체 복사 | 변경 없음 | 위 배경의 비용 | 거절 |

함께 정한 세부 대안은 다음과 같다.

- **DIAGNOSTIC_ONLY(확정 뒤 재검수)의 기준.** 직전 반영 Job에 기록된 반영 전 입력(`automatic_input_state`·`run_base_state`)을 seed로 쓰는 경로를 이 모드에만 남기는 안이 있었다. Job JSON을 데이터 API가 읽을 때 투영하는 안도 있었다. 소유자는 둘 다 거절했다. 작가가 그렇게 저장했다면 틀린 값이어도 그 값이 기준이라는 이유다. 작가가 확정한 현재 값이 기준이다.
- **지문.** 내용과 무관한 64자리 값(reportId+시각 hash)만 두고 다시 계산하지 않는 안이 있었다. 쿼리는 줄지만 이벤트를 놓친 쓰기를 잡지 못한다. 거절했다.
- **대기(QUEUED) 잠금 시간.** `lease-duration`(운영 60초)을 재사용하는 안이 있었다. 바쁜 단일 Worker의 대기열에서 정상 대기 중에 잠금이 풀린다. 그 사이 수정하면 작가 자신의 검수가 취소된다. 별도 설정 10분을 택했다.
- **잠금 기간.** 이슈 본문은 "대기·실행 중에만"이었다. 소유자가 "접수부터 검토 종료까지"로 늘렸다. 검토 화면에서 보는 값과 판정이 본 값이 같아야 하기 때문이다.
- **같은 작품의 자동 작업이 RUNNING일 때의 접수.** 거절하는 안이 있었다. 지금처럼 받아들이고, 그 작업의 반영이 리포트를 무효화하게 두었다.

## 4. 결정

### 4.1 복사하지 않는다

- 어떤 모드(BEFORE_CONFIRMATION·DIAGNOSTIC_ONLY)에서도 확정 설정을 복사하지 않는다. `ValidationBaselineService.prepare`는 출처 manifest(`validation_baseline_sources`)와 설정 버전 지문만 저장하고 report를 READY로 만든다(`ValidationReport.readyWithoutSeed`).
- V119 뒤 접수한 report의 `baseline_seed`·`baseline_entries`는 NULL이다. `baseline_hash`·`baseline_fingerprint`·`confirmed_state_hash` 세 열은 같은 지문을 담는다. V119는 READY일 때 seed·entries를 요구하던 CHECK를 `baseline_hash`만 요구하도록 바꿨다.
- 판정 입력은 `processor.ValidationLiveSettings`가 현재 DB에서 읽는다.
  - 주체 색인은 scalar 조회 세 번이다. ACTIVE 인물은 생성 역순이고 같은 시각이면 id 순이다. 그다음 확정 인물 발견 별칭, 세계관(id 순)을 읽는다.
  - 전체 상태는 요청 주체(최대 100개)만 id로 읽는다. `SettingCandidate`·`WorldSettingCandidate`·`CharacterFact`·`AnalysisJob` 엔티티는 읽지 않는다.
  - 주체 하나에 행 하나라서 B 번호와 S 번호가 같다.
  - AI 모델 투영 뒤의 행은 legacy seed와 바이트 단위로 같다. `ValidationLiveSettingsIntegrationTest`가 이를 기록으로 남긴다.
- DIAGNOSTIC_ONLY는 검토 관문의 의미만 남는다. AWAITING_REVIEW에서 멈추고 release가 없다. 직전 반영 식별값(kind·id·Job·appliedAt)은 report에 그대로 남긴다.

### 4.2 잠그는 기간

판정은 `processor.ValidationSettingsLock` 하나이고 조건은 `ValidationJobRepository.SETTINGS_LOCK`이다. 현재(`current`)이고 INVALIDATED가 아닌 report의 Job만 본다.

| Job 상태 | BEFORE_CONFIRMATION | DIAGNOSTIC_ONLY |
| --- | --- | --- |
| QUEUED, 마지막 상태 변경(`updated_at`)이 `settings-lock-queued-timeout`(운영 10분) 안 | 잠금 `EXECUTION` | 잠금 `EXECUTION` |
| QUEUED, 그 시간 초과 | 잠그지 않음 | 잠그지 않음 |
| RUNNING, lease 유효 | 잠금 `EXECUTION` | 잠금 `EXECUTION` |
| RUNNING, lease 만료 | 잠그지 않음 | 잠그지 않음 |
| COMPLETED, 검토 종료 receipt 없음 | 잠금 `REVIEW` | 잠그지 않음 |
| COMPLETED, receipt 있음 | 잠그지 않음 | 잠그지 않음 |
| PARTIAL·FAILED·CANCELED | 잠그지 않음 | 잠그지 않음 |

- 무효화되거나 퇴역한 report, 다른 작품의 report는 잠그지 않는다.
- 대기 잠금 시간이 설정에 없으면 QUEUED는 잠그지 않는다. 실행이 꺼진 기동이 이 경우이며, 가져갈 Worker가 없기 때문이다.
- 그래서 Worker가 죽어도 잠금은 lease 만료(운영 60초) 또는 대기 잠금 시간 뒤에 풀린다. 실행이 깨진 PARTIAL·FAILED·CANCELED는 처음부터 잠그지 않는다. PARTIAL은 retry로 다시 QUEUED가 되면 잠근다.
- 검토 종료 receipt와 release 추출 Job은 같은 트랜잭션에서 생긴다(`ValidationReviewCompletionService`). 따라서 release Job이 생기는 순간 그 report의 잠금은 끝난다.

### 4.3 막는 쓰기와 막지 않는 쓰기

- **409로 막는다.** 사람이 검수가 읽는 확정 설정(인물 이름·slot·서사 시점·보호 slot, 확정 발견 별칭, 세계관 대상·분류·속성·보호 경로, 작가 정정 기록)을 바꾸는 쓰기다.
  - 캐릭터 수정·보관·복구, 세계관 생성·대상 수정·속성 추가·속성 수정: `AnalysisRunStateService.assertConfirmedSettingMutationAllowed`
  - 캐릭터·세계관 후보의 사람 확정·승격: `AnalysisCandidateSourceGuard.assertManualWriteAllowed`
  - 다른 회차의 회차 전체 확정 반영(READY): `ValidationSettingsApplicationServiceImpl`. HELD 보류 기록은 설정을 쓰지 않아 막지 않는다.
- **막지 않는다.** 검수 입력을 바꾸지 않는 쓰기는 기존 `assertSettingMutationAllowed`만 쓴다. 관계도 편집, 검토 대기 후보 제외, #369 애매 후보 종료, 결정 초안 저장이 여기에 속한다.
- **같은 리포트의 정정.** REVIEW 잠금만 통과한다. 경로는 `AnalysisRunStateService.assertReportCorrectionAllowed`, `CharacterService.correctCharacterSetting(..., reportId)`, `WorldSettingService.correctWorldSettingProperty(..., reportId)`다.
  - 그 리포트가 실행 중(EXECUTION)이거나 다른 리포트가 잠갔으면 정정도 409다.
  - 정정은 기존 규칙대로 그 리포트를 무효화하므로 잠금도 끝난다. 리포트를 지우지 않고 정정한 설정을 인용한 질문만 다시 판정하는 일은 후속 #383이다.
- **자동 작업.** 같은 작품의 SETTING_EXTRACTION·CHARACTER_FACT_COMPARISON·WORLD_SETTING_COMPARISON은 잠금 동안 claim하지 않는다(`AnalysisJobEligibility.VALIDATION_LOCK`).
  - 조건은 후보 조회와 Work 잠금 뒤 재조회 양쪽에 있다. 검수 접수도 Work 잠금 아래라 둘이 직렬화된다.
  - 접수 전에 이미 RUNNING인 작업은 그대로 끝난다. 그 반영이 `ConfirmedSettingsChanged`로 리포트를 무효화하고 검수 Job을 취소한다(기존 규칙).
  - 다른 Job 종류의 claim 조건은 바뀌지 않는다.
- **잠금 순서.** 모든 검사는 호출자가 Work 행을 잠근 뒤 행 잠금 없이 `validation_jobs`를 읽는다. Work 먼저 규칙을 따른다.

### 4.4 409 `VALIDATION_SETTINGS_LOCKED`

- 문구: `오류 리포트 검수가 끝나지 않아 설정을 바꿀 수 없어요. 오류 리포트에서 수정하거나 검토를 마친 뒤 저장해 주세요.`
- `error.context`: `{reportId, validationJobId, episodeNo, executionStatus, lockReason, leaseExpiresAt}`.
  - `lockReason`은 `EXECUTION` 또는 `REVIEW`다.
  - `leaseExpiresAt`은 RUNNING일 때만 들어간다.
  - 잠금이 여럿이면 가장 먼저 접수된 검수다.
- 기존 `ANALYSIS_REVIEW_WAIT_REQUIRED`(순차 분석 대기)와 다른 코드다. Front는 설정 저장 화면들이 이미 `error.message`를 그대로 보여 주므로 이번 범위가 아니다.

### 4.5 지문과 출처 manifest는 남긴다

- **지문.** `{"fingerprintVersion":1,"characters":[[id,snapshotVersion]…],"worldSettings":[[id,version]…]}`의 `AnalysisStateJournal.hash`다. 각 목록은 id 문자열 순이며 scalar 조회 두 번으로 만든다.
  - 값이 바뀌면 version이 오르고, 보관·복구·생성·삭제는 id 집합을 바꾼다.
  - 다시 계산하는 시점은 queue·retry(`validateLiveBaseline`), 모든 data 호출 전후(`requireCurrentReport`), claim·heartbeat·게시·완료(`ValidationExecutionAccess.currentInput`)다.
  - 다르면 기존 409 `VALIDATION_SOURCE_VERSION_CONFLICT`이고 Worker는 `INPUT_CHANGED`로 멈춘다. 이벤트 없이 version을 바꾼 쓰기를 잡는 안전장치다.
  - 기존 계약(데이터 요청 `expectedBaselineHash`, Job `inputDigest`, 재검수 `expectedBaselineHash`=`baselineFingerprint`, 정정 `expectedBaselineHash`)이 64자리 값을 요구하므로 모양도 그대로다.
  - 알려진 빈틈: 이름·역할만 바꾸는 `WorkCharacter.updateBasicInfo`는 version을 올리지 않아 지문으로 잡지 못한다. 사람의 쓰기는 잠금이 막는다.
- **출처 manifest.** `validation_baseline_sources`를 그대로 쓴다. AI 고정 출처 RAG(`FrozenRetrievalRepository`), 접수 때 색인 채우기의 `currentRevision`, `READ_SOURCE`·`VALIDATE`의 출처 확인, `modelSourceCatalogue`·`manifestHash`, 오류 항목의 출처 표기와 앞뒤 줄 조회가 이 행을 쓴다.
  - R 번호 순서는 `processor.ValidationSourceManifests`가 행 내용으로 다시 만든다. 앞 회차는 회차 번호 내림차순, 그다음 설정집은 (설정집 id, revisionNo) 순이다.
  - 각 행이 legacy seed의 `sources` 원소와 같으므로 `manifestHash`도 같다.

### 4.6 계약 변경

- **데이터 API.** `ENTRIES`(저장 `baseline_entries` 페이지) 연산을 없앴다. 요청하면 400이다. grid v4 Worker는 쓰지 않았다.
  - SUBJECTS·SETTINGS·INPUT의 응답 모양과 `projectionVersion=2`는 그대로다.
  - 공통 fixture에서 `execution.responses.dataEntries`를 지웠다.
- **게시.** 그 게시가 값을 대조하는 주체만 전체 상태로 읽고, 나머지는 색인 행(`state{name, aliases}`)이다. 대상은 질문 `draft.subject_refs`, `basis_quotes[].ref`, `evidence_bundle.settings[].entry_ref`다. 서로 다른 주체가 100개를 넘으면 `VALIDATION_RESULT_INVALID`다. V102 대상 열은 잠금 아래 읽은 이 행으로 한 번 기록한다.
- **`quoted_subjects`(V119).** finding의 B 인용마다 `{ref, subjectType, subjectId, subjectName, settingKey, settingName}`이며 최대 3개다.
  - 이름·key만 담고 설정값은 담지 않는다. B 인용 원문 자체는 이미 payload에 있다.
  - legacy(seed) report와 오탈자 finding은 NULL이다.
- **상세 화면.** 근거 문장의 대상·설정명은 `quoted_subjects`, 없으면 V102 대표 대상에서 정한다.
  - 값 전체(`settingValue`)는 report가 유효하고 회차 전체 확정(APPLIED) 전일 때만 현재 행에서 읽는다. 그 기간에는 잠금과 무효화 때문에 현재 값이 판정 때 값과 같다.
  - APPLIED 뒤에는 설정이 바뀔 수 있어 `settingValue`가 null이다. 인용 원문만 보인다.
- **basis와 정정.** 판정 때의 B·S 번호를 쓰지 않는다.
  - basis는 저장된 대상(V102 대표 대상, `quoted_subjects`의 주체)의 현재 행이다. `entryRef`는 지금 색인 참조, `entryHash`는 지금 행의 hash, `currentVersion`은 지금 version이다.
  - 정정은 대상을 요청 id로 찾는다. `entryRef`·`expectedEntryHash`가 지금 행과 같아야 한다(상세 → 정정 사이의 CAS).
  - 고정 기준과 현재 값이 같은지 slot·속성 전체를 비교하던 검사는 없앴다. 기준 항목이 곧 현재 행이기 때문이다.
  - 이전 정정 scope는 `validation_corrections` 표에서 읽는다.
- **legacy report.** seed가 있는 report는 모든 경로에서 기존 저장 기준을 계속 읽는다(dual path). 접수 중이던 report도 그 경로로 끝까지 간다.

### 4.7 legacy와 달라지는 점

1. 대표 서사 시점(`currentNarrativeTime`)의 근거 연결이 깨져 있어도 접수를 보류(`BASELINE_EVIDENCE_INVALID`)하지 않는다. live 행은 저장된 값을 그대로 쓰고 근거 키는 모델 투영이 버린다.
2. 생성 시각(`createdAt`)이 같은 인물은 id 순으로 정렬한다. legacy 조회는 동률 기준이 없었다.
3. 세계관 확정 이력에만 있던 주체(대상 세계관이 없는 이력)는 SUBJECTS에서 사라진다. 이 주체는 SETTINGS로 나간 적이 없다.
4. `DIAGNOSTIC_BASELINE_UNAVAILABLE` 보류가 더 생기지 않는다. 진단도 현재 설정을 읽는다. 접수 단계의 `BASELINE_SOURCE_UNAVAILABLE`도 더 생기지 않는다. 보류는 legacy seed의 늦은 정규화(`BASELINE_SEED_INVALID`)에만 남는다.

### 4.8 배포

1. **AI를 먼저 배포한다.** AI 고정 출처 검색 SQL(`app/validation/search.py` `REPORT_FROM`)이 `baseline_seed IS NOT NULL AND baseline_entries IS NOT NULL`을 요구했고, 새 AI는 이 조건을 뺐다.
   - 옛 AI가 seed 없는 report를 만나면 검색 readiness가 report를 찾지 못해 판정에 검색을 제안하지 않는다. 그러면 검색이 필요한 질문이 `VECTOR_NOT_READY`로 미완료가 된다.
   - 새 AI는 옛 Backend의 seed 있는 report도 그대로 찾는다.
2. **Backend는 그다음이다.** 새 이미지가 시작되기 전에 운영 `api.env`의 `SPRING_APPLICATION_JSON`에 `validation.execution.settings-lock-queued-timeout`(예시 `10m`, `deploy/validation-execution.prod.example.json`)이 있어야 한다. 실행이 켜진 기동은 이 값이 없으면 `검수 실행 제한을 명시적으로 설정해야 합니다.`로 실패한다.
3. **옛·새 Backend가 동시에 검수 접수를 받지 않게 한다.** 옛 인스턴스는 seed 없는 report에 검수 Job을 만들지 않고, retry와 데이터 읽기는 seed 검사에서 409로 거절한다. 운영 API는 EC2 한 대에서 배포 lock 아래 교체되므로 겹침은 재시작 순간뿐이다.

## 5. 결과

- **좋아지는 점.** 102화 운영 사본(768MB 힙)에서 접수가 200으로 0.53~0.64초(4회)에 끝난다. #380 뒤에는 25.6~36.1초였다.
  - 접수 중 live 힙은 약 120MB다. 대기 138MB와 비슷하고, 이전 최대 413MB의 3분의 1 아래다.
  - report는 seed·entries를 0바이트 저장하고 출처 행 101개만 남긴다.
  - 접수 SQL은 1,162문이다(#380 뒤 2,889, #380 전 7,880). 과거 분석 Job 전체 행은 0건이다.
  - 판정 입력은 legacy와 같다(4.1).
- **대가.** 접수부터 검토 종료까지 작가는 설정 화면에서 확정 설정을 고칠 수 없다.
  - 리포트 안 정정은 허용되지만, 지금은 정정하면 리포트가 무효화되어 재검수가 필요하다(#383 전까지).
  - 검토를 마치지 않고 둔 COMPLETED 리포트는 작가가 검토를 끝내거나 리포트 안에서 정정할 때까지 잠금을 유지한다.
  - 같은 작품의 자동 추출·비교는 그동안 기다린다.
  - APPLIED 리포트의 상세는 인용 원문만 보이고 값 전체를 보이지 않는다.
- **위험.**
  - Worker가 죽으면 lease 만료(60초) 또는 대기 잠금 시간(10분)까지 설정 수정이 막힌다.
  - 지문은 이름만 바꾸는 쓰기를 잡지 못한다.
  - 배포 순서를 어기면 검색이 필요한 질문이 `VECTOR_NOT_READY`로 미완료가 되거나(옛 AI), 새 report에 검수 Job이 생기지 않고 retry·데이터 읽기가 409가 된다(옛 Backend).

## 6. 검증 기준

| 시나리오 | 목표 | 결과 |
| --- | --- | --- |
| 102화 운영 사본 접수, 768MB 힙(#379 측정 도구) | 시간·힙이 작품 크기와 거의 무관 | 200, 0.53~0.64초(4회), live 힙 약 120MB, SQL 1,162, Job 전체 행 0, seed·entries 0바이트, 출처 행 101 |
| 검수 대기·실행 중 설정 화면 수정 | 409 `VALIDATION_SETTINGS_LOCKED`, 쓰기·무효화 0 | `ValidationSettingsLockIntegrationTest` |
| 완료·실패·부분 완료·취소·lease 만료·대기 시간 초과 뒤 | 수정 성공, report는 기존 규칙대로 무효화 | 같은 테스트 |
| 검토 종료 전 COMPLETED | 설정 화면은 `REVIEW` 409, 그 리포트의 정정만 통과 | 같은 테스트 |
| 잠금 중 같은 작품의 추출·비교 | claim하지 않음, 다른 작품·release Job은 claim | `ValidationSettingsLockClaimIntegrationTest` |
| SETTINGS 행 동등성 | AI 투영 뒤 legacy seed와 바이트 단위로 같고 S·B 번호가 같음 | `ValidationLiveSettingsIntegrationTest` |
| live 읽기의 엔티티·쿼리 수 | 후보·Fact·Job 엔티티 0, 조회 수가 작품 크기와 무관 | 같은 테스트 |
| 실제 Python Worker 전체 실행 | seed 없는 report로 INPUT·SUBJECTS·SETTINGS·READ_SOURCE·VECTOR·게시·완료 | `ValidationExecutionHttpPostgresIntegrationTest`(AI 새 검색 SQL) |
| seed 있는 legacy report | 데이터·게시·상세·정정·retry가 기존대로 동작 | `ValidationFindingTargetIntegrationTest` 등 legacy 메서드 |

## 7. 후속

| 항목 | 상태 |
| --- | --- |
| 리포트 안 정정 뒤 정정한 설정을 인용한 질문만 다시 판정 | #383 |
| `baseline_seed`·`baseline_entries` 열 삭제 | 마지막 legacy report가 정리된 뒤 별도 migration |
| Front에서 잠근 리포트로 이동(`error.context.reportId`)·검수 중 저장 버튼 비활성 | 선택 후속, 이번 범위 아님 |

## 8. 참고

- [오류 검사 우선 검수 기반](../episode-validation-staging.md) — 접수와 기준
- [검수 실행·재검수 계약](../validation-execution-v1.md) — 데이터 API, 정정, 실행 설정
- [작가 검토·추출 허가](../validation-review-v1.md) — 상세 화면, basis, 무효화
- [설정 충돌 검사 호출 계약](../setting-validation-v1.md) — Java·AI 공통 문서
- `docs/contracts/validation-execution-v1.json` — Java·AI 공통 fixture
