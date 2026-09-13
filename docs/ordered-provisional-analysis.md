# 다회차 순차·임시 상태 분석 — GH-180

관련 이슈: https://github.com/catchhole-soma/catchhole-backend-java/issues/180

이 문서는 로컬 구현의 설계와 검증 현황이다. 실제 모델 품질과 운영 검증은 별도로 구분한다.
최초 기준은 Java `a4a7f9e`, AI `80d5184`이며 운영 버전을 뜻하지 않는다.

## 변경 목적과 보존 조건

업로드와 파일 준비는 여러 회차를 받고, 같은 작품의 상태 의존 분석은 회차 순서대로 수행한다.
새 다회차 분석은 각 회차 분석 후 정상 판단을 작품 설정에 자동 저장한 뒤 다음 회차를 시작한다.
이전 수동 누적 실행은 검증된 AI 제안을 사용자 미확정 상태로 누적하는 기존 경로를 유지한다.
다른 작품의 병렬 분석은 유지한다.

## 회차별 자동 반영과 업로드 정책

선행 단일 분석 없이 새 작품부터 두 다회차 방식을 허용한다. 정책 조회는 기존 응답 호환을 위해
필요 회차 수 0과 사용 가능 true를 반환하며, 실제 완료 수와 검토 대기 수·분량 제한을 계속 제공한다.
검토 대기 후보는 업로드 화면에서 안내하고 업로드 자체는 허용한다. 모든 원고 업로드는 공백·제목 포함
250,000 Unicode code point까지 허용한다. 설정집은 이 원고 합계에서 제외한다.

새 설정 추출·재분석 요청은 `reviewMode`를 생략하면 자동 반영(`AUTOMATIC`)한다. 단일 회차는 직접 검토
(`MANUAL`)를 명시할 수 있고, 새 다회차는 서버에서 자동 반영과 회차 순서를 고정한다. 회차 검증의 생략
기본값과 기존 작업의 실패 재시도 정책은 바꾸지 않는다. 각 회차는 실제 시작 직전 저장 상태를 `automatic_input_state`로 고정하여
1차의 인물·현재 상태와 2차의 관련 설정 비교가 같은 기준을 사용한다. 같은 작품의 앞 회차에서 보류한
후보는 이전 업로드 묶음까지 별도 참고 문맥으로 수집하며 현재 설정이나 선택 가능한 인물로 승격하지 않는다.

완료 트랜잭션은 검증 기록 봉인 → 캐릭터 자동 반영 → 세계관 자동 반영 → 자동 저장 완료 기록 → Job 성공을
묶는다. 기여자의 실행 순서와 무관하게 두 도메인 모두 성공해야 커밋하며 다음 회차는 이 커밋 이후에만 시작한다.
모호한 연결·검토 필요·외부 변경·작가 수정은 검토 대기로 남긴다. 자동 모드는 개별 비교 묶음 실패도 보류 참고로
남기고 정상 묶음은 저장한다. 이 예외는 비교 문맥을 정상 준비한 묶음의 LLM 응답·호출 실패에만 적용한다.
토큰 부족·lease 상실·입력 변경·Backend 통신 실패·주체 해소나 추출 전체 실패는 중단·재개 경로를 사용한다.
이미 시작한 Job의 재시도는 최초 문맥을 보존하며, 원문이나 작가 수정으로 무효화되면 별도 재분석이 필요하다.

후보의 자동 검토 출처와 실제 저장한 이력을 보존하고 모델에도 작가 확인과 AI 자동 반영을 구분해 준다.
기존 인물에 연결된 새로운 이름의 발견 후보를 유지해 이후 회차에서 별칭과 원문 근거를 다시 참고할 수 있다.
V49는 반영 방식·자동 시작 문맥·자동 반영 완료 시각과 두 후보 도메인의 자동 검토 표시를 추가한다.

자동 처리 뒤 남은 후보는 작가가 직접 수정안을 저장한 다음 확정할 수 있다. 서버가 이 경로의 가능 여부를 제공하고
사용자 결정 없이 실패값을 현재 설정으로 올리지 않는다. 세계관은 원래 비교 실패 이력을 유지한 채 사람의 확정으로 기록한다.

직접 검토 단일 회차는 `CONFIRMED_ONLY`이며 기존 입력·프롬프트·모델·추론 강도와 채점 의미를 유지한다.
새 `ORDERED_PROVISIONAL` 모드는 서버가 생성 시 고정한다. 파일 수, 마지막 남은 Job 수,
추가 업로드 유무로 실행 중 모드를 변경하지 않는다. legacy Job에는 새 모드를 소급 적용하지 않는다.

## 구현 경계

- 분석 실행 ID는 업로드 batch ID와 분리한다. 기존 AnalysisJob에 모드·실행·순서·원문 버전·입력 hash를 저장한다.
- 수동 누적 실행은 첫 Job의 S0와 선행 SEALED 기록으로 상태를 복원한다. 자동 누적 실행은 회차마다 실제 저장 상태를 고정한다. 두 경로 모두 회차 안에서 검증 완료 decision의 당시 값을 JSON 변경 기록으로 보존한다.
- 캐릭터·세계관 서비스가 기존 projector/operation 검증으로 승인한 변경만 기록한다. 후보 현재값이나 raw 응답은 replay 원본이 아니다.
- claim capability가 없는 구 Worker에는 기존 모드만 전달한다. 새 모드의 Python 후보 교체도 같은 DB 트랜잭션에서 lease·run·입력·원문을 검증한다.
- 임시 대상은 별도의 provisionalSubjectKey로 표현한다. 실제 UUID FK를 가장하거나 정식 대상을 미리 생성하지 않는다.
- claim의 짧은 작품 잠금과 실행 중 Job 조건으로 동시 진입을 막는다. 다음 회차는 predecessor의 SEALED 기록과 자동 반영 모드일 때 저장 완료 시각을 요구한다.
- 첫 구현은 회차 Job 전체를 순차화한다. 같은 작품의 소요 시간은 증가할 수 있으며 준비 단계 분리는 후속 최적화다.
- 사용자 수정·반려/원문 변경은 영향을 받은 이후 기록을 무효화한다. 무효화와 사용자가 요청하는 재실행은 분리한다.
- 과거 회차 S0에 미래 확정 상태가 섞이지 않도록 가능한 시작 기준만 지원한다. 전체 과거 복원을 암묵적으로 구현하지 않는다.

## 이전 업로드 묶음의 미확정 참고

자동 분석의 회차 시작 시 같은 작품에서 현재보다 앞선 회차의 `SETTING_EXTRACTION`을 조회한다.
업로드 묶음·자동/직접 검토 방식으로 범위를 제한하지 않으며, 회차별 가장 최근 생성된 작업 하나를 먼저
선택한다. 생성 시각이 같으면 작업 ID로 순서를 고정한다. 최신 작업이 실패·취소·진행 중이거나 무효화됐다고
오래된 성공 작업의 후보로 돌아가지 않는다. 숨김 재비교 작업은 이 선정에 참여하지 않는다.

선택된 작업의 회차 번호와 원문 hash·key·version이 현재 회차와 같고, 원문이 삭제되지 않았으며,
작업이 성공한 경우에만 대기 후보를 검토한다. 순차 실행의 정상 AI 후보는 기록도 봉인된 상태여야 한다.
후보의 작품·회차가 원분석과 다르거나 별도 원문 key가 현재 manifest와 다르면 제외한다. manifest가 없는
과거 데이터를 현재 원문으로 추정하지 않는다. 후보의 개별 원문 key가 없을 때는 검증된 원분석 manifest를 사용한다.

- 확정·제외된 후보, 아직 비교 중인 후보, 비어 있는 값, 원문 인용이 없는 후보는 제외한다. 인물 발견 후보는 이름과 인용만 있어도 참고할 수 있지만 연결 가능한 실제 인물로 등록하지 않는다.
- 비교 실패는 추출 당시 설정값·원래 범위·인용만 전달한다. 실패한 비교의 제안값·대상 선택·판단 문장은 복사하지 않는다. 정상적인 범위 검토는 비교했던 두 경로와 서버가 정한 자연어 보류 안내를 유지한다.
- 사용자 후보 수정은 해당 분석과 뒤의 분석을 무효화한다. 이 경우에도 원문·인용이 유효하고 현재 대기 중인 **명시적인 사용자 수정**은 참고할 수 있다. 캐릭터는 현재 편집값과 사용자가 연결한 인물 이름, 세계관은 저장된 최신 수정안을 사용한다. 제외 수정안·값을 비운 수정안·보관된 인물은 제외한다. 수정 전 AI 제안으로 되돌리지 않으며, 인용은 수정 전 원문이라는 점도 표시한다.
- 미확정 참고는 확정 설정·대상 목록과 분리한다. 이 정보만으로 사실을 등록할 수 있는 API나 변경 기록을 만들지 않으며, 후속 분석 결과도 현재 회차의 추출 근거와 기존 비교 검증을 거쳐야 한다.

참고는 고유 후보 ID별로 한 번만 담고 기존 정렬 규칙을 사용한다. 첫 claim에서 현재 설정과 함께 고정한 후에는
그 회차의 1차·2차·실패 재시도에 같은 입력과 hash를 사용한다. 사용자 수정·제외·원문 교체/삭제는 기존의
작품 잠금과 회차 이후 무효화 경로로 새 묶음까지 무효화한다. 이미 기록된 입력·hash·변경 기록을 수정 내용으로
덮어쓰지 않으며, 사용자가 새 분석을 요청했을 때 최신 참고를 다시 선택한다. 이미 끝난 1~32화 데이터에
소급 적용하거나 유료 재분석을 자동으로 시작하지 않는다.

검증은 `AnalysisPendingReferenceSourceTest`의 범위·최신 선택·수정 예외·출처 검사와
`AutomaticAnalysisIntegrationTest`의 새 묶음 입력, 수정 뒤 무효화, 고정 입력/hash 보존, 최신 실패 작업의
오래된 후보 차단으로 수행한다. 테스트는 H2 격리 데이터만 사용하고 실제 작품·AI 호출을 사용하지 않는다.

## 공유 변경 기록 계약 v1

상태 root는 `characters`, `worldSettings`, `references` object이다. 각 도메인 안에서 targetRef를 key로 사용한다.
도메인별 값과 provenance는 각 domain state mapper가 정의한다.

`AnalysisStateChange(eventId, path, value, remove, operation, sourceCandidateIds)`는 Backend가 검증한 변경이다.
path는 문자열 배열이며 실제 대상·설정 경로를 완전히 해소한 후 만든다. `remove=false`는 값 저장,
`remove=true`는 존재하는 경로 제거다. 원래 ADD/UPDATE/MERGE/REMOVE 등의 의미는 operation에 보존한다.
같은 eventId의 같은 내용은 한 번만 적용하고 다른 내용은 거절한다. 부모 경로와 제거 경로를 암묵적으로 생성하지 않는다.
회차와 회차 내부 event 순서는 저장된 배열 순서로 고정한다. source 후보를 다시 읽어 당시 값을 변경하지 않는다.

V44~48은 기존 Job·후보·비교 batch/decision 컬럼을 보완한다. 첫 Job의 `run_base_state`가 S0이고,
각 Job의 `state_journal`이 당시 제안값과 source ID를 가진다. run/generation/sequence, 원문
ID·번호·hash·S3 version, 입력 hash, journal 포맷/완성 상태를 함께 검증한다. 동일 이벤트의
동일 내용은 한 번 적용하고 다른 내용은 거절한다. 현재 후보를 편집해도 저장 당시 값은 바뀌지 않는다.

## 현재 코드의 동시성 경계

`AnalysisJobClaimRepository`는 대기 Job을 작품별로 고른 뒤 Work row에 PostgreSQL
`FOR UPDATE SKIP LOCKED`를 적용한다. 잠금을 얻은 뒤 별도 statement로 같은 작품의 실행 중
extraction Job 부재를 다시 확인한다. 누적 Job은 같은 작품·run·generation의 직전 sequence가
`SUCCEEDED`이면서 journal이 `SEALED`여야 한다. 생성 시각 정렬만으로 순서를 보장하지 않는다.
이 잠금은 claim 트랜잭션 동안만 유지하며 LLM 호출 중 유지하지 않는다.

기존 lease·heartbeat·재claim·checkpoint·비교 context token을 재사용한다. Python이 청크/후보를
직접 DELETE→INSERT하는 경계에서도 동일 트랜잭션에서 Job을 잠그고 lease, run/generation,
입력 hash, 원문 manifest와 checkpoint를 검증한다. 늦은 Worker가 새 후보를 지우는 것을 막는다.
완료된 모든 원본 후보 ID와 journal source ID의 coverage가 맞아야 seal하며, 기록 완성과 Job 완료는
하나의 트랜잭션이다. 수동 누적 실행의 비교 실패와 실행 자체를 무효화하는 오류는 journal을 완성하지 않고
뒤 회차를 차단한다. 자동 실행의 분리 가능한 비교 실패는 원문 근거가 있는 미확정 참고 기록으로 coverage를
채우며, 정상 설정 저장과 봉인이 끝난 뒤 다음 회차를 허용한다.

배포 기본값은 AI 저장소 `deploy/compose.worker.prod.yml`의 분석 프로세스 5개 × 프로세스당
Job 10개다. 현재 Java 기준 checkout에는 별도 Worker Compose가 없어 과거의 2개 × 5개 설정을
현재 코드라고 설명하지 않는다. 이 작업은 배포 설정·모델·추론 강도를 변경하지 않았으며 실제 운영
동시성은 서버 환경변수를 조회하지 않아 확인하지 않았다.

## 각 분석 단계에 전달되는 정보

| 단계 | 누적 모드의 입력·검증 |
| --- | --- |
| 1차 | 현재 회차 원문/청크, 그 시점의 실제·임시 캐릭터 이름/별칭, 관련 활성 상태, 항목별 출처 회차·확정 여부 |
| 캐릭터 주체 해소 | 임시 인물의 발견 근거와 기존 식별 정보. 실제 UUID와 임시 key를 분리하고 동명이인을 이름만으로 병합하지 않음 |
| 캐릭터 2차 | 같은 projected state의 관련 canonical FactType/slot, 활성 상태, 후보 근거, 임시 의존성과 입력 버전 |
| 세계관 주체 해소·2차 | 같은 projected state의 실제·임시 대상과 전체 범위/설정 경로, 관련 속성·근거·항목별 provenance |
| 완료 | 문맥에 노출한 ref와 입력 hash를 재검증하고 ADD/UPDATE/MERGE/REMOVE를 같은 projected state에 검증한 뒤 journal로 고정 |

EXCLUDE/HISTORY_ONLY/REVIEW_REQUIRED는 현재 slot을 바꾸지 않고 별도 참고 기록으로 보존한다.
미해결 대상을 임의의 실제 대상과 연결하지 않는다. 실패값을 현재 설정으로 누적하지 않는다. 자동 실행에서
보류한 실패 후보는 `UNCONFIRMED` 참고 정보로만 다음 회차에 전달한다. 다른 run/generation의 변경
기록은 replay하지 않는다. 다만 앞 회차의 유효한 대기 후보는 아래 기준으로 현재 행을 조회하여 참고할 수 있다.
미완료 분석·다른 작품·미래 회차의 후보는 참고하지 않는다. 사용자 반려의 내부 fingerprint는 prompt나 평가의 현재 사실로 변환하지 않는다.
참고 목록은 고정된 참조 식별자 순서로 전달한다. PostgreSQL jsonb 재조회 시 객체 키 순서가 달라져도
회차 claim과 후속 비교 문맥이 같아야 하며, 순서 변화만으로 입력 변경 오류가 나지 않도록 한다.
원문 전문을 과거 회차에서 반복 복사하지 않고 최근 N화 기준으로 유효한 상태를 잘라내지 않는다.
필수 문맥은 임의 top-k로 버리지 않으며 기존 batch 분할·입력 한도와 ordered 요청의 토큰 상한을
검증한다. 단일 후보의 필수 문맥도 한도를 넘으면 명시적으로 실패시켜 뒤 회차 진행을 막는다.

ordered 안내는 미확정 상태를 비교 기준으로 사용하되 원문의 명확한 변화 근거를 억제하지 않도록
한다. 원문·후보·상태의 지시는 데이터로 취급한다. 안내와 provenance는 명시적 모드 분기에만
추가하며 단일 회차의 prompt/model/reasoning 경로는 유지한다. Job summary의 입력 hash·정책
버전·임시 인물 수, 저장된 비교 context, 기존 purpose/model별 token usage로 입력과 사용량을 추적한다.

## 요청·실패 재개·사용자 변경

기존 생성 API에서 `jobType: SETTING_EXTRACTION`, `batchId`, `analysisMode: ORDERED_PROVISIONAL`을
명시하면 선택된 회차를 회차 번호 순으로 고정한다. `episodeId`를 생략하면 그 시점 batch의 전체
회차가 대상이고 지정하면 해당 회차만 대상이다. 새 모드의 명시적 선택은 호출 측에서 해야 한다.
모드를 생략한 기존 요청은 여러 회차를 포함해도 `CONFIRMED_ONLY`의 입력 조건을 유지한다.
첫 구현에서 같은 작품의 extraction Job 전체를 직렬화하므로 기존 요청도 대기 시간은 바뀔 수 있다.

Job 응답의 optional `analysisRun`은 모드·실행 ID·generation·순서·선행 Job·journal 상태·무효화
이유를 제공한다. `SUCCEEDED`와 journal `SEALED`는 구분하며 후속 회차 허용에는 둘 다 필요하다.
실행 도중 새 회차를 업로드하거나 별도 단일 회차 분석을 생성해도 원래 run 목록에 편입하지 않는다.

수동 누적 실행은 비교 묶음에 실패가 남으면 기존처럼 다음 비교를 중단한다. 캐릭터 묶음에서 독립적으로 검증한 정상 비교를 실패 후보와 함께 보존할 수 있지만, 이는 다음 묶음·회차를 진행하거나 작품 설정에 자동 반영하는 승인이 아니다. 자동 누적 실행은 아래 기준으로
후보만 보류할 수 있는 실패와 전체 실행을 중단해야 하는 실패를 구분한다.

| 실패 상황 | 자동 실행의 처리 |
| --- | --- |
| 정상 비교 문맥을 받은 묶음의 LLM 응답 해석·형식 검증 실패, 응답 잘림, provider/LLM 연결 실패 | 해당 묶음 후보를 직접 검토 대상으로 보존하고 다음 묶음 계속 |
| 토큰 부족, lease 상실, 예상하지 못한 오류 | 실행 중단, 후보를 임의로 참고 기록으로 바꾸어 완료하지 않음 |
| Backend가 반환한 저장·권한·문맥 오류 | 실행 중단. 같은 비교 실패 코드라도 원본 서버 오류가 있으면 보류 대상으로 허용하지 않음 |
| 비교 문맥을 준비하지 못한 필수 입력 상한·canonical 경로 검증 실패 | 기존 중단 유지 |
| 추출 전체·주체 해소 단계 실패 | 기존 중단 유지 |

캐릭터와 세계관 모두 다음 묶음 claim, 완료 직전 검사와 변경 기록 봉인에 같은 후보 판정을 사용한다.
실패 상태가 있다는 이유만으로 정상 후속 묶음을 막지 않으며, 반대로 분리할 수 없는 실패를 마지막 완료
요청으로 건너뛰어 자동 저장하거나 다음 회차를 열지 못한다. 기존 완료 API가 결과 수신을 `SUCCEEDED`로
기록하더라도 `INCOMPLETE` 변경 기록과 자동 저장 미완료 상태를 유지하므로 다음 회차가 열리지 않는다.
Worker의 정상 오류 전달 경로는 실패 API를 호출하여 `FAILED`로 종료한다. 실패 요청이나 정상 완료 요청이 중복 도착해도 후보·설정·변경 기록을 중복 생성하지 않는다.
실패한 세계관 묶음 전체를 보류하는 방식이며, 잘못된 응답에서 임의로 일부 설정을 골라 자동 저장하지 않는다.

기존 retry API로 사용자 재시도를 요청하면 같은 Job의 S0·입력 hash·원문 manifest·완료
prefix·checkpoint를 보존한다. 청크와 이미 저장된 1차를 재사용하고 실패·대기 비교만 다시 진행한다.
기존 단일 모드의 부분 성공·후보별 hidden 재비교 의미는 유지한다.

원문·사용자 후보·정식 설정이 바뀌면 영향 회차 이후는 `INVALIDATED`로 표시하고 실행 중/대기 중
Job의 lease를 해제한다. 변경 자체로 LLM을 다시 호출하지 않는다. 무효화된 Job은 같은 입력 retry로
되돌리지 않으며, 기존 생성 API에 새 ordered 요청을 명시해 새 run을 시작해야 한다. 새 S0에 시작
회차 이후의 알려진 확정 출처가 있다면 과거 복원을 지원하지 않는다는 오류로 거절한다. 예를 들어
1화 수정값을 확정하고 2화부터 다시 분석하려면 2화 이후를 새 요청 범위로 선택해야 한다. 이전
미확정 journal을 새 run에 자동 합치거나 사용자 수정값을 검증 없이 임시 사실로 옮기지 않는다.

새 분석 생성은 이전 ordered 후보·비교 기록을 보존한다. 일반 사용자 변경으로 과거 journal 내용을
고치지 않는다. 원문 파기 요청은 기존 근거 삭제 정책에 따라 복사된 근거/비교 context도 정리하는
별도 경로다. 이 경우 `sourceEvidencePurged`와 당시 hash를 남기며 해당 기록의 replay는 거절한다.

사용자 반려는 고정된 회차 ID·번호·원문 hash, 실제 대상 ID 또는 임시 대상의 원문 근거,
원본 경로·값·구조화 JSON과 절대 근거 위치가 모두 일치하는 주장에만 이어받는다. 새로 추출한
원본 후보는 보존하되 비교 전에 EXCLUDE 참고 기록을 남겨 현재 상태에 재주입하지 않는다.
원문·값·경로·근거·대상이 달라지면 자동으로 같은 주장으로 취급하지 않는다. 이 판정에는
S0 references에 보관한 해시만 사용하고 반려 내용이나 비공개 판정 정보를 LLM에 전달하지 않는다.
불변 원문 manifest가 없는 legacy 반려를 현재 원문 hash로 추정하여 새 정책에 소급하지 않는다.

임시 대상을 최종 확정할 때는 원래 anchor와 실제 대상의 연결, 선행 제안이 실제로 적용됐는지,
현재 설정의 경로·값을 다시 검증한다. 임시 UPDATE/REMOVE가 있다는 이유로 실제 설정의 없는
경로를 우회하지 않는다. 사용자 수정 후보는 `applyEditedValue=true`의 명시적 확정 경로에서
현재 실제 대상/schema에 재검증한다. 이 요청 없이 오래된 제안을 자동 적용하지 않는다.

## 읽는 순서와 초기 제한

1. `AnalysisJobCreateRequest` → `AnalysisJobServiceImpl` → `AnalysisJob` / V44~48: 명시적 모드와 고정 실행 생성.
2. `AnalysisStateJournal` → `AnalysisRunStateServiceImpl` → `AnalysisJobClaimRepository`: 결정적 복원·claim·seal·무효화.
3. `CharacterAnalysisStateService`, `OrderedWorldSettingWorker` 및 각 StateMapper/Confirmation: 실제·임시 문맥, 검증, 최종 확정.
4. AI `analysis_job_worker.py` → `ordered_context.py`, `ordered_character_subjects.py` → comparator/pipeline: 단계별 입력과 실패 전파.
5. AI `ordered_analysis_fence.py`와 각 저장 service: 직접 DB 변경의 lease/원문/입력 경계.
6. AI `docs/ordered-provisional-evaluation.md`: 기존 평가 회귀, A/B/C/D 실행안, 지표·분모·미판정·비용 범위.

초기 범위는 한 요청에서 고정한 회차 Job 전체의 순차 실행이다. 준비 단계 분리, 실행 중 업로드
자동 편입, 전체 과거 시점 복원, 의존 후보만 선택해 자동 재계산은 포함하지 않는다. 원문이나
사용자 결정이 바뀌면 영향 이후를 보수적으로 무효화하고 기존 요청/토큰 승인 경계에서 재실행한다.
S0와 전체 선행 journal replay의 비용은 회차/설정 수에 비례해 증가하므로 checkpoint snapshot과
선택적 과거 기록의 최적화는 실제 측정 후 검토한다. 임의 confidence threshold는 사용하지 않는다.

무료 통합 테스트는 결정적 fake LLM/S3로 실행 흐름을 검증한다. 실제 모델의 정확도 개선, token 비용,
운영 전체 완료 시간은 측정하지 않았다. 로컬 Gold는 DRAFT였으며 Notion FINAL 준비와 실제 B live
평가 runner 연결은 별도 준비 사항이다. 저장 예측의 B adapter는 Java sealed journal을 재현하고
Gold는 채점 단계에서만 사용한다. 유료 명령·Actions 입력과 예상 호출 산식은 AI 평가 문서에 정리한다.

## 무료 검증 현황

### 자동 모드의 비교 실패 분리 회귀

`AutomaticAnalysisIntegrationTest`는 격리 H2 DB와 실제 Spring 서비스·트랜잭션으로 다음 경계를 검증한다.
LLM·운영 S3·사용자 로컬 DB는 호출하지 않는다.

- 미궁 설정 4개를 한 묶음으로 준비하고 비교 문맥 조회 → 묶음 실패 저장 → 다음 세계관 묶음 claim → 정상 비교 완료 → 회차 자동 저장 → 다음 회차 claim까지 수행한다.
- 실패 원문·값은 검토 대기와 `UNCONFIRMED` 참고에 보존하고, 성공한 세계관과 캐릭터 스탯만 실제 설정에 저장되는지 확인한다.
- 캐릭터 비교도 한 인물의 실패 뒤 다른 인물의 묶음을 처리해 자동 저장과 다음 회차 진행을 검증한다.
- 수동 누적 모드, 토큰 부족, lease 상실, 예상하지 못한 오류와 Backend 문맥 오류는 후속 claim을 거절하고 완료 요청이 와도 변경 기록 미봉인·자동 저장 없음·다음 회차 차단을 유지하는지 확인한다.
- 실패·완료의 중복 전달, 완료 트랜잭션 rollback과 재시도에서 실제 설정이 중복 저장되지 않는지 확인한다.

실행 명령: `./gradlew test --tests '*AutomaticAnalysisIntegrationTest' --console=plain`.
이 검증은 결정적 fixture의 저장·진행 경계를 다루며 실제 모델의 의미 판단 정확도 측정은 아니다.

### 이전 누적 실행 검증

2026-09-07 21:29 KST Java 전체 테스트는 **759개 실행, 실패·오류·skip 0개**였다.
전용 localhost `gh180_test`의 실제 PostgreSQL 테스트 12개와 `gh180_e2e_test`의 Spring HTTP↔
Python Worker 통합 3개를 포함한다. Flyway V1~46 적용 후 JPA validate 상태에서 실행했다.

실제 PostgreSQL에서는 작품별 동시 claim/다른 작품 병렬, rollback 후 seal, lease 재claim/늦은
결과, stale 무효화 commit, 회차 번호 변경, 영향 이후 무효화, 외부 source coverage 거절, 원문
파기, 토큰 예약·Episode 상태 정리와 미래 Fact/인물 발견 이력의 S0 유입 거절을 확인했다.
HTTP 통합은 실제 청킹·추출·주체 해소·비교·저장·seal 흐름을 사용하고 LLM/S3만 무료 fake로
대체한다. 10회차에서 임시 인물을 연결하고 부상을 반복 ADD/REMOVE하며 세계관을 갱신한다.
동일 속성의 서로 다른 근거 후보 2개를 보존해 한 decision과 journal로 연결하고, 실제 캐릭터·
Fact·세계관 레코드가 생성되지 않음을 검사한다. 캐릭터 사전검증 실패와 세계관 필수 입력 초과는
각각 실패 상태를 commit하고 다음 도메인/회차로 진행하지 않는다.

AI 무료 non-integration **935개 통과 / integration 14개 별도 선택**, 최신 스키마 PostgreSQL
저장 경계 **12개 통과**, `ruff check app evals tests` 통과를 확인했다. 나머지 기존 integration
2개는 이 실행에서 선택하지 않았다. 변경 전 AI `80d5184`의 app을 별도 git archive로 읽어 만든
고정 fixture와 현재 실제 HTTP 요청 JSON을 비교하는 4개 테스트에서 추출·캐릭터 batch·세계관
주체 해소·세계관 batch의 prompt/model/reasoning/schema/cache/output 상한이 같았다.

후속 HTTP 통합 3개를 다시 실행해 실제 Java S0와 SEALED journal 10개를 Python mirror로
복원하고 각 input/output hash와 typed projector의 최종 상태가 일치함을 확인했다. 유료 호출은
없었다. 전체 Gradle XML과 후속 HTTP XML·fake provider 로그, Python/Ruff 로그는 작업 루트
`.codex-artifacts/gh180`에 보존했다. 테스트 통과는 실제 모델 정확도나 운영 처리량 측정 결과가 아니다.

## 원문 요구사항별 완료 감사

| 원문 절 | 증명해야 할 조건 | 상태·근거 |
| --- | --- | --- |
| 1 | 각 체크아웃·AGENTS·구현과 배포 기본값 재확인 | 기준 SHA와 작업 트리 확인, 운영은 미확인 |
| 2 | 1~10화 예측 누적, 사용자 확정 대기 없음, 정식 설정 미변경 | 실제 HTTP 10회차 fake-provider 통합 통과 |
| 3 | 단일 회차 입력·모델·프롬프트/legacy/API 호환, 모드 고정 | 명시 모드·optional DTO/worker capability, 기존 API 회귀 및 Python HEAD HTTP 요청 golden 4개 통과 |
| 4 | 고정 S0+불변 변경 기록, 필요한 migration, LLM 없는 복원 | V42~46, AnalysisStateJournal 단위·실제 DB replay/seal 통과 |
| 5 | 후보/기록 역할 분리, 다중 source·순서·당시 값 보존 | immutable journal와 deterministic 순서 테스트, 실제 다중 source HTTP 통과 |
| 6 | DB 작품별 claim, 다른 작품 병렬, 원자 seal, 실패·stale·중복 방어 | 실제 PostgreSQL 12개·HTTP 실패 2개 통과 |
| 7 | 확정/미확정/비반영/미해결/실패/반려 정책 | domain operation·typed failure, exact 반려·discovery 12개 및 projector 회귀 통과 |
| 8 | 임시 대상 식별·연결·실제 FK·최종 확정 | 10회차 임시 연결·동명이인 거절, Character promotion/World confirmation 테스트 통과 |
| 9 | 1차·2차 문맥, 관련 활성 상태 보존, 안전한 크기 제한·진단 | 각 context mapper/pipeline·입력 한도 테스트, 실제 world 초과 실패 통과 |
| 10 | ordered 전용 항목별 provenance·안내, 원문 지시 방어 | ordered_context 모드 분기 및 항목별 provenance 테스트 통과; 문구의 품질 효과는 미측정 |
| 11 | 입력·주체/ref·operation 검증·replay의 상태 일치 | 임시 부상 ADD/REMOVE·world UPDATE HTTP, stale context 단위·DB fence 통과 |
| 12 | 추가 업로드·재claim·수정·반려·재분석·미래 정보 방지 | 고정 manifest·영향 이후 무효화·same Job retry·source purge·미래 이력 테스트 통과; 과거 전체 복원 제한 명시 |
| 13 | 기존 ORACLE/FIXED/ROLLING 보존, A/B/C/D·Gold 분리·지표/비용 계획 | AI 평가 문서·saved adapter·typed projector 회귀와 실제 Java 10회차 export parity 통과; B live runner·유료 비교는 별도 준비 사항 |
| 14 | 명시된 단위/통합/실제 DB 회귀 테스트와 실행 증거 | Java 759개, Python 935개, 별도 Python PG 12개, 후속 HTTP 3개·Ruff 실행 통과 |
| 15 | 사용자 동작/구조/입력/실행 결과/미검증/평가 실행안/제한 보고 | 본 문서와 AI 평가 문서에 정리, 로컬 구현·무료 검증·유료 평가 계획 범위 완료 |

유료 LLM/semantic judge 평가, 운영 배포, GitHub Actions 실행, Notion 정답지 수정,
원격 push·PR·merge는 별도 요청 전 실행하지 않는다. 과거 DRAFT 실험을 FINAL/운영 품질로 표시하지 않는다.
기존 채점 결과의 단순 문구 차이를 의미 오답으로 단정하지 않고 구조·대상 오류와 구분한다.

### 2026-09-13 PR 통합 검증

main 이메일 가입 V42와 신규 캐릭터 그룹 비교 V43을 보존하며 미배포 migration을 V44~V54로 배치했습니다. 위 과거 검증 표의 V42~V46은 실행 당시 번호입니다. Python HTTP 하네스는 `GH180_AI_ROOT`와 `GH180_PYTHON`으로 검증할 checkout/runtime을 명시합니다. 사용자 로컬 DB 35432는 테스트 대상에서 제외합니다.

`CONFIRMED_ONLY`의 일반 분석은 main #191/#69의 그룹 handoff를 사용합니다. `ORDERED_PROVISIONAL`은 각 회차의 고정 입력을 원 Job 안에서 비교하고 저장해야 하므로 batch 전체 게시 barrier를 기다리지 않습니다. ordered 후보를 legacy 숨김 그룹에 보내지 않고, Worker의 모드 지원과 그룹 지원 capability를 claim에서 각각 확인합니다. 신규 캐릭터도 비교 제안 없이 ADD로 추정하지 않습니다.

검토 응답의 `analysisMode`는 프론트가 그룹 공통 revision을 요구하는 일반 비교와 회차별 고정 문맥을 검증하는 순차 비교를 구분하도록 합니다. 자동 보류 후보는 기존 직접 수정·`applyEditedValue` 확정을 유지합니다. 순차 그룹의 최종 승인은 서버가 실제 상태·선행 결정 의존성과 사용자 수정을 다시 검증합니다.
