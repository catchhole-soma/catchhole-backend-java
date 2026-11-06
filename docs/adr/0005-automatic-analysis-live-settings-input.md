# ADR-0005: 자동 누적 분석은 확정 설정을 복사하지 않고, claim 때 지문과 참고 정보만 고정하며 캐릭터·세계관을 대상별로 현재 DB에서 읽는다

| 항목 | 내용 |
| --- | --- |
| 상태 | **승인** |
| 작성일 | 2026-11-06 |
| 작성자 | mkshie |
| 관련 이슈 | [#387](https://github.com/catchhole-soma/catchhole-backend-java/issues/387), [#381](https://github.com/catchhole-soma/catchhole-backend-java/issues/381)·[ADR-0004](0004-validation-live-settings-lock.md), [#379](https://github.com/catchhole-soma/catchhole-backend-java/issues/379), #374·#365, GH201, GH287 |

> 2026-11-06 소유자가 아래 두 가지를 결정했다. **D1**: claim 입력은 (b) 지문 + 대상별 현재 DB 읽기, 참고 정보(references)만 고정한다. **D2**: 회차 전체 확정의 명시 반영도 순차 분석 중에는 거절한다(G1 닫기). 5절의 동작 변경도 같은 날 받아들였다.
>
> 2026-11-07 배포 방식은 **기본 켬**으로 정했다. 플래그 `analysis.automatic-input.live-settings-enabled`의 기본값을 true로 두어 배포와 함께 켠다. 문제가 생기면 운영자가 서버 `api.env`에 `ANALYSIS_AUTOMATIC_INPUT_LIVE_SETTINGS_ENABLED=false`를 넣는다(6.3).

## 1. 배경

### 1.1 세 곳의 전체 복사

자동 반영 순차 분석(`AUTOMATIC + ORDERED_PROVISIONAL`)은 작품의 확정 설정(인물·세계관·참고) 전체를 세 곳에서 다뤘다. 검수 접수(#381, ADR-0004)에서 없앤 것과 같은 종류의 복사다.

1. **Job 생성.** `AnalysisRunStateServiceImpl.initializeRun`이 실행마다 `stateSources` 세 도메인을 capture해 첫 Job의 `run_base_state`(S0)로 저장했다. 분석 시작·검수 release(검토 완료)·설정집 업로드/재분석이 모두 이 경로다. 자동 누적은 S0를 판정에 읽지 않는다. 읽는 곳은 수동 누적 replay뿐이라, 이 복사본은 생성 때의 미래 출처 검사에만 쓰였다.
2. **claim.** Worker가 회차 Job을 가져갈 때마다 `captureAutomaticInput`이 확정 설정을 다시 전부 읽어 `automatic_input_state`에 고정했다.
3. **lease 확인.** Worker 내부 API 호출마다 큰 JSON 열을 가진 Job 엔티티를 읽고, 고정 입력 전체를 다시 hash했다. heartbeat·progress·토큰 예약처럼 입력을 읽지 않는 호출도 마찬가지였다.

### 1.2 측정

운영 100화 시험 작품의 DB 사본으로 쟀다. ACTIVE 인물 113명, 세계관 518개, 회차 100개이며 운영과 같은 768MB 힙이다. 측정 도구는 저장소 밖에 두었고 운영 데이터는 저장소에 넣지 않았다.

| 시점 | 바꾸기 전 | 바꾼 뒤 |
| --- | --- | --- |
| 검토 완료(release Job 생성) | 13.0초. live 힙 127 → 319MB(+192MB). 그동안 Work 행 잠금을 쥐어 같은 작품의 다른 쓰기가 기다림 | 아래 Job 생성과 같은 경로 |
| Job 생성(분석 시작, 자동 반영) | 16.2초, live 힙 +197MB, 요청 스레드 할당 1,895MB, JDBC 1,472문, `run_base_state` 1,157,984B(1.16MB) | 0.27초, +3MB, 25MB, 30문, 42B |
| Worker claim | 14.9초, live 힙 최대 372MB(대기 약 125MB), JDBC 1,462문, `automatic_input_state` 텍스트 4.0MB(압축 저장 1.16MB) | 2.6초, 최대 164MB, 185문, 47kB(압축 저장 17.9kB) |
| 추출 진행 중 Worker 요청 | 요청마다 live 힙 188~203MB. 호출마다 Job JSON 열을 읽고 1MB 넘는 입력을 hash | 다시 재지 않음. lease 확인은 hash를 하지 않고(4.2) 입력 열이 작다(4.4) |

claim 응답 크기는 바꾸기 전과 거의 같았다(688,643B·688,644B). 내용이 같은지는 테스트로 확인했다(7절).

이번 측정은 요청이 하나뿐이라 768MB 안에서 끝났다. 운영에서 요청이 겹치면 #379와 같은 메모리 부족이 날 수 있었다.

### 1.3 복사가 하던 일

| 목적 | 다른 해결 |
| --- | --- |
| 한 회차의 1차·2차 비교와 재시도가 같은 입력을 본다 | GH201이 실행 중 확정 설정 쓰기를 막는다. 막지 못하던 회차 전체 확정의 명시 반영(G1)은 이번에 막았다(4.3) |
| 실행 도중 입력이 바뀌면 거절한다 | 설정 지문을 claim 때 저장하고 읽을 때마다 비교한다(4.5) |
| 앞 회차 미확정 참고·사람의 반려·설정집 manifest를 고정한다 | 실행 중에도 바뀔 수 있어(G3) 지금처럼 claim 때 고정한다 |
| 새 실행의 미래 출처 검사 | scalar 조회 세 개(4.1) |
| 비교 화면의 "비교 전 값" | 원래 claim 사본이 아니라 후보에 저장된 `rawComparisonJson.backendComparisonBefore`를 읽는다. 바뀌지 않는다 |

G3는 실행 중 참고 후보가 바뀌는 경우다. legacy 비교 재시도, `createJobs`의 이전 대기 후보 정리, 비교 상태 필터가 그 예다.

## 2. 판단 기준

1. Job 생성·claim 비용이 작품의 확정 설정 크기와 무관하다.
2. Worker가 받는 입력(claim 응답·비교 문맥)과 journal 변경·최종 DB가 지금과 같다.
3. Worker 계약을 바꾸지 않는다. Worker는 `inputStateHash` 64자리 문자열만 비교한다.
4. 실행 중 입력이 바뀌면 지금처럼 거절하고 기존 무효화 경로로 닫는다.
5. 배포 전에 claim한 Job과 그 재시도가 그대로 끝난다.

## 3. 검토한 대안

①(생성)과 ③(lease)은 쓰지 않는 복사와 hash를 빼는 일이라 소유자 결정 없이 진행했다. 결정이 필요했던 것은 ②(claim 입력)다.

| 대안 | 효과(추정) | 단점 | 판단 |
| --- | --- | --- | --- |
| (a) claim 사본을 scalar 조회로 싸게 만든다 | claim 15초 → 2~4초, +215MB → +80~120MB. 저장 크기는 그대로 | 위험은 낮고 hash로 바이트 동일성을 확인할 수 있다. 그러나 비용과 저장이 여전히 작품 크기에 비례한다 | 거절 |
| **(b) 지문 + 대상별 현재 DB 읽기, 참고 정보만 고정 (채택)** | claim 1초 안팎, 힙 +20~40MB, 저장 수 kB. ③의 큰 JSON 열도 사라진다 | 호출 지점 약 15곳을 대상별 API로 옮겨야 한다. G1을 닫아야 한다. 완료 트랜잭션의 자동 반영 뒤 읽기는 지문 검사를 따로 설계해야 한다 | 채택 |
| (c) claim 입력을 전부 없앤다 | (b)와 같다 | 참고 정보가 실행 중 바뀔 수 있어(G3) GH287 고정 선택과 Worker 입력 동일성 검사가 깨진다 | 거절 |

함께 정한 세부 대안은 다음과 같다.

- **S0 자리.** 자동 run의 `run_base_state`를 NULL로 두려면 migration(V120)이 필요했다. V77 CHECK가 seq 0에 object를 요구하기 때문이다. H2는 이 CHECK가 없어 NULL이 통과하므로 테스트로 잡히지도 않는다. 작은 표지 object를 저장하기로 했다.
- **지문.** 검수(#381)의 `ValidationLiveSettings.fingerprint`를 넓혀 함께 쓰는 안이 있었다. 그 식은 report에 저장돼 다시 비교되므로 바꾸면 기존 report가 409가 된다. 따로 두었다(`fingerprintVersion` 1).
- **지문이 다를 때.** 그 호출만 거절하고 실행은 두는 안이 있었다. #381의 선례대로 실행을 무효화한다. 다음 claim까지 lease 만료를 기다리지 않게 하기 위함이다.
- **배포.** 처음에는 플래그 기본값을 false로 두고 따로 켜려 했다. 2026-11-07 배포와 함께 켜기로 바꿨다.

## 4. 결정

### 4.1 Job 생성은 S0를 만들지 않는다

- 자동 반영 실행을 만들 때 `initializeRun`은 `stateSources`를 capture하지 않는다. `stateSources` 도메인 등록 검사는 `source.domain()`으로만 유지한다. 한 실행의 모든 Job은 첫 Job과 반영 방식이 같아야 한다.
- 자동 run의 seq 0 `run_base_state`에는 표지 `{"automaticInputCapturedAtClaim":true}`만 저장한다. `characters`·`worldSettings`·`references` key가 없어 잘못 replay하면 변경 경로 검증에서 거절된다. migration은 없다.
- 미래 출처 검사는 scalar `max` 조회 세 개다. 현재 출처 Fact의 추출 Job, ACTIVE 인물의 확정 발견, 이력 전용이 아닌 확정 세계관 후보의 고정 회차(`source_episode_no`)를 본다. 오류는 지금과 같은 400 `ANALYSIS_FUTURE_HISTORY_CONFLICT`이고 context는 비어 있다. 설정집 출처는 시작 회차가 없어 검사하지 않는다(이전 순회도 같았다).
- 수동 누적(`MANUAL + ORDERED_PROVISIONAL`)은 S0 capture와 순회를 그대로 한다.

### 4.2 lease 확인은 입력을 hash하지 않는다

- 자동 누적 Job의 lease 확인(`AnalysisJobLeaseServiceImpl` → `assertValidLeaseInput`)은 scalar 조건만 본다. journal PENDING, 현재 원문 버전, 입력 hash 존재, 고정 입력 존재, 선행 회차다.
- 입력 내용을 읽는 경로는 각자 확인한다. `getProjectedState`·`prepareState`·`appendValidatedChanges`·`seal`·`validateResume`, 관계 문맥 준비, 세계관 주체 대기 목록이다.
- 수동 누적의 lease 확인은 S0 replay와 hash를 그대로 한다.

### 4.3 회차 전체 확정의 명시 반영도 순차 분석 중에는 거절한다 (D2, G1)

- `POST .../settings-application`의 READY 분기가 쓰기 전에 GH201 `assertSettingMutationAllowed`를 부른다. 같은 작품에 진행·대기 중이거나 같은 입력으로 재개할 순차 분석이 있으면 409 `ANALYSIS_REVIEW_WAIT_REQUIRED`다. receipt·report version·보류 표지는 바뀌지 않는다.
- 그동안 검토 요약 `allowedActions`에 `APPLY_SETTINGS`를 넣지 않는다. 그 report의 release Job은 반영할 수 있을 때 SUCCEEDED+SEALED라 조건에서 뺀다.
- 이 경로는 #381 잠금만 보고 확정 설정을 썼다. 그래서 설정집 자동 분석이 RUNNING일 때 그 입력을 바꿀 수 있었다. 4.4가 GH201에 기대므로 먼저 닫았다.

### 4.4 claim 입력 format 2

- claim은 `automatic_input_state`에 `{"formatVersion":2, "fingerprintVersion":1, "fingerprint":"<64 hex>", "references":{…}}`만 저장한다(`processor.AnalysisAutomaticInputFormat`). `input_state_hash`는 이 객체의 hash다.
- `references`는 format 1과 같은 규칙으로 claim 때 고정한다. 사람의 반려 hash(`HUMAN_REJECTION_POLICY`), 앞 회차 미확정 참고, 설정집 manifest다. claim 때의 미래 출처 순회도 4.1의 scalar 검사다.
- 캐릭터·세계관은 실행 중 필요할 때 대상별로 현재 DB에서 읽는다(`processor.AnalysisLiveStateReader`, 도메인별 `service.AnalysisLiveSubjectSource`). 그 대상 경로에 닿는 이 Job의 journal 변경만 저장 순서대로 적용한다. 값은 format 1 사본에서 같은 대상을 잘라낸 값과 같다.
- 호출자는 실행 상태를 `AnalysisRunStateService`의 대상별 읽기로만 읽는다. claim 응답(알려진 인물·semantic 현재 문맥)은 현재값만 읽고 이력 Fact·slot 근거 사본·세계관 경로별 출처는 만들지 않는다. semantic 현재 문맥은 입력 전체 hash 대신 저장 객체 hash와 지문을 비교한다.
- 플래그 `analysis.automatic-input.live-settings-enabled`(환경변수 `ANALYSIS_AUTOMATIC_INPUT_LIVE_SETTINGS_ENABLED`)는 새 claim의 형식만 정한다. 기본 true다. 이미 claim한 Job은 저장된 형식으로 읽는다.

### 4.5 지문

- ACTIVE 인물의 id·이름·snapshotVersion·Fact 수·마지막 Fact 생성 시각·확정 인물 발견 id와, 세계관의 id·version·확정 현재값 후보 수를 scalar로만 읽어 hash한다. 엔티티는 읽지 않는다.
- version만으로는 부족했다. 이름만 바꾸는 `updateBasicInfo`, 현재값을 바꾸지 않는 이력 Fact 추가, 별칭을 늘리지 않는 인물 발견 확정, version 없이 출처만 바뀌는 세계관 확정은 version을 올리지 않지만 대상별 읽기 값을 바꾼다.
- 대상 읽기·변경 추가·seal·재개·재claim은 지문을 먼저 확인한다. 다르면 409 `ANALYSIS_RUN_STATE_CONFLICT`다. 그 Worker 호출이 되돌려진 뒤 새 트랜잭션에서 실행을 기존 `invalidateFrom`으로 닫는다(`AnalysisJobWorkerServiceImpl.invalidateAfterLiveInputChanged`).

### 4.6 seal과 완료

- format 2의 `outputStateHash`는 `{inputStateHash, changes}`의 hash다. 자동 누적 journal은 저장된 시작 상태에서 replay되지 않는다. replay는 수동 누적뿐이고 AI는 이 값을 읽지 않는다.
- seal은 반영 전 인물 식별 색인을 그 Job 인스턴스에만 고정한다(저장하지 않음). 같은 완료 트랜잭션의 자동 반영이 인물을 만든 뒤에도 설정집 동일 인물 검증이 claim 때 이름을 읽게 하기 위함이다.
- 그 밖의 읽기는 자동 반영이 값을 바꿨으면 지문이 달라져 거절된다. 그래서 이 Job의 변경을 두 번 적용한 값을 돌려주지 않는다.

## 5. 받아들인 동작 변경

1. **legacy 단건 EXCLUDE가 format 2 실행을 무효화한다.** 단건 `CHARACTER_FACT_COMPARISON`의 EXCLUDE는 GH201을 거치지 않고 대표 서사 시점을 바꿀 수 있다(snapshotVersion 증가). 새 비교 Job은 모두 그룹 Job이라 남은 옛 대기 행만 해당한다. format 1은 고정 사본만 읽어 이 변경을 모른 채 진행했다. format 2는 지문이 달라져 진행 중인 실행이 무효화된다.
2. **확정 근거 오류는 claim이 아니라 그 대상을 처음 읽을 때 드러난다.** format 2는 실행이 실제로 읽는 대상의 근거만 검증한다. 관련 없는 인물의 근거가 깨져 있어도 실행이 실패하지 않는다. 어떤 대상을 처음 읽다가 `ANALYSIS_CURRENT_EVIDENCE_INVALID`가 나면 그 호출을 되돌리고, 새 트랜잭션에서 Job을 claim 입력 실패와 같은 진단으로 마감한다. `FAILED`, `UNEXPECTED_ERROR`, 진단 단계 `CLAIM_INPUT`, journal `INCOMPLETE`, 예약 해제, 회차 `FAILED`다. 근거를 고친 뒤 기존 재시도로 같은 Job을 다시 시작한다.
3. **G1이 닫혔다(4.3).** 순차 분석이 끝나지 않은 동안 회차 전체 확정의 명시 반영 READY는 409이고 버튼이 꺼진다.
4. **생성 응답의 근거 오류 409가 없어졌다(4.1).** 자동 반영 실행을 만들 때 확정 근거를 검증하지 않는다. 깨진 근거는 첫 claim에서 위 2와 같은 실패로 드러나고, release Job도 재시도할 수 있다.
5. **미래 출처 검사가 넓어졌다(4.1).** 확정 행 전체의 고정 회차를 보므로 S0 순회보다 더 거절할 수만 있다. 차이는 회차를 옮긴 경우에만 생긴다.

## 6. 호환과 되돌리기

### 6.1 format 1은 계속 읽는다

- 입력은 claim 때 비어 있을 때만 만든다. 그래서 배포 전에 claim한 Job은 format 1로 남는다. lease 만료 뒤 재claim, 실패 뒤 재시도, 비교·seal·완료 모두 저장 사본을 읽는다.
- 같은 실행에서 배포 뒤 처음 claim하는 다음 회차는 format 2다. 앞 회차가 format 1이어도 선행 조건(SUCCEEDED+SEALED+자동 반영 완료)만 본다.
- 이미 만든 자동 run의 큰 S0와 format 1 입력은 지우지 않으며 읽지 않는다.
- AI Worker는 바꾸지 않는다.

### 6.2 플래그를 끄면

새 claim만 format 1로 돌아간다. 이미 format 2로 claim한 Job은 format 2로 읽고 끝난다.

### 6.3 운영에서 끄는 방법

- 새 이미지는 플래그가 켜진 채로 뜬다. 기본값이 base YAML(`${ANALYSIS_AUTOMATIC_INPUT_LIVE_SETTINGS_ENABLED:true}`)과 운영 Compose(`:-true`) 양쪽에서 true이기 때문이다.
- 끄려면 자동 배포와 같은 `/opt/catchhole/.api-deploy.lock` 아래에서 `api.env`에 `ANALYSIS_AUTOMATIC_INPUT_LIVE_SETTINGS_ENABLED=false`를 넣는다. 그다음 `docker compose --env-file api.env -f compose.api.prod.yml up -d backend`로 backend를 재생성하고, `exec -T backend printenv ANALYSIS_AUTOMATIC_INPUT_LIVE_SETTINGS_ENABLED`로 확인한다.
- 끄기는 이미지를 되돌리는 것이 아니다. 5절의 1·2는 새 claim부터 이전처럼 돌아가지만 3·4·5(4.1·4.3)는 플래그와 무관하게 남는다.

### 6.4 이 변경 이전 이미지로 되돌리려면

이전 코드는 format 2를 읽지 못한다. 진행·대기 중이거나 재개를 기다리는 format 2 Job이 없어야 한다. 아래 조회가 0이어야 한다. 재개 조건의 원문 일치 검사를 빼서 실제보다 넓게 센다.

```sql
select count(*) from analysis_jobs
 where automatic_input_state->>'formatVersion' = '2'
   and journal_status <> 'INVALIDATED'
   and (status in ('PENDING', 'RUNNING')
        or (status = 'FAILED' and journal_status in ('PENDING', 'INCOMPLETE'))
        or (status = 'SUCCEEDED' and journal_status = 'INCOMPLETE'));
```

0이 아니면 먼저 플래그를 끄고(6.3) 그 Job들이 끝나기를 기다린다. 재개를 기다리는 Job은 작가가 재시도해 끝내거나 새 실행으로 대체해야 한다.

## 7. 검증 기준

| 시나리오 | 목표 | 결과 |
| --- | --- | --- |
| 100화 사본의 Job 생성·claim(768MB 힙, 저장소 밖 측정 도구) | 시간·힙이 작품 크기에 비례하지 않음 | 1.2절 |
| 같은 사본에서 실제 AI Worker로 다음 회차 한 개를 format 2로 끝까지(2026-11-07) | 추출·인물/세계관 비교·관계·seal·자동 반영이 409·무효화 없이 끝남 | 2분 24초, 409·무효화·ERROR 0, 후보 19개 모두 자동 확정. claim 2.2초, 세계관 대상 페이지 중앙값 0.47초(JDBC 131~240, live 힙 +10~20MB), 저장 입력 48kB |
| 같은 3회차 실행을 format 1과 format 2로 | claim 응답 3·Worker 응답 13·journal 2·최종 DB 7개 기록이 같음 | `AutomaticAnalysisIntegrationTest.liveInputMatchesFrozenInputAcrossRun` |
| 플래그를 켜기 전에 claim한 회차를 켠 뒤 재claim·재시도 | 사본과 hash를 그대로 쓰고 비교·seal·자동 반영. 같은 실행의 다음 회차는 format 2, 다시 꺼도 format 2로 끝남 | `AutomaticAnalysisIntegrationTest.frozenInputJobClaimedBeforeFlagOnResumesAfterFlagOn` |
| 지문이 대상별 읽기를 바꾸는 쓰기마다 바뀜 | 쓰기 종류별로 바뀌고, 읽기와 무관한 쓰기로는 그대로 | `AnalysisLiveStateReaderIntegrationTest`(PostgreSQL 하위 클래스 포함) |
| 실행 중 GH201 우회 쓰기 | 409 뒤 실행 무효화 | `AutomaticAnalysisIntegrationTest.liveInputFingerprintMismatchInvalidatesRun` |
| 처음 읽은 대상의 근거 오류 | Job을 `CLAIM_INPUT` 실패로 마감, 재시도 가능 | `AutomaticAnalysisIntegrationTest.liveInputEvidenceErrorOnFirstSubjectReadFailsJobCleanly` |
| lease 확인 | 입력 hash 없이 통과하고, 입력을 읽는 경로는 변조를 거절 | `AutomaticAnalysisIntegrationTest.leaseCallsSkipOnlyAutomaticInputHash` |
| 생성 표지·미래 출처 scalar 검사 | 표지만 저장, 같은 오류·context, 옮긴 회차 거절 | `PreparedAnalysisStateTest`, `AnalysisFutureSourceCheckIntegrationTest`(PostgreSQL 포함) |
| G1 | 순차 분석 중 409, 아무것도 바뀌지 않음, 끝나면 반영 | `ValidationSettingsApplicationIntegrationTest`, `ValidationSettingsApplicationPostgresIntegrationTest` |

기본 `./gradlew test`는 format 2로 돈다. format 1은 위 두 `AutomaticAnalysisIntegrationTest`가 서비스의 플래그를 직접 바꿔 확인한다. `ANALYSIS_AUTOMATIC_INPUT_LIVE_SETTINGS_ENABLED=false`를 주면 같은 테스트 전체를 format 1로 돌린다.

## 8. 후속

| 항목 | 상태 |
| --- | --- |
| heartbeat·progress·토큰 예약의 scalar 행 잠금(③-3b) | 필요할 때만. format 2로 Job 입력 열이 작아져 지금은 하지 않음 |
| 플래그와 format 1 capture 경로 제거 | 운영에서 format 2가 충분히 돈 뒤 따로 결정 |
| legacy 단건 EXCLUDE(G2)에 GH201 검사 추가 | 하지 않음. 새 Job은 그룹 Job이라 옛 대기 행만 해당 |

## 9. 참고

- [순차 누적 분석](../ordered-provisional-analysis.md) — "자동 누적 claim 입력 format 2" 절
- [ADR-0004](0004-validation-live-settings-lock.md) — 검수의 같은 결정(잠금 + 현재 설정 읽기)
- [누적 회차 조회와 입력 복사](../analysis-history-memory.md) — #374의 format 1 claim 입력 조립
- [작가 검토·추출 허가](../validation-review-v1.md) — 명시 반영 경로와 `allowedActions`
