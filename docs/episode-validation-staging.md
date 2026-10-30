# 오류 검사 우선 검수 기반 (#303)

현재 순서는 **원고 저장 → 원문·과거 확정 설정/출처로 오류 검사 → 작가 판단·수정·재검사 → 검토 완료 → 최종 원고 설정 추출·비교 → 확정**이다. 오류 검사를 위해 설정 후보를 먼저 만들지 않는다.

이번 구현은 접수, 고정 기준, 검사·검토 대기, 우회 차단, 기본 무효화/파기까지다. 실제 오류 검사 Worker/findings, 판단 저장, 검토 완료 release, 이후 추출과 확정 연결, UI 공개는 아직 구현하지 않는다. 기준 READY와 findings가 없다는 사실은 검사 완료/의심 0건을 뜻하지 않는다. 현재는 gate를 열 수 없다.

## 현재 구현된 API/DTO 계약

`POST /api/v1/works/{workId}/episode-validations`

```json
{
  "idempotencyKey": "opaque-client-request-id",
  "batchId": "업로드 배치 UUID",
  "episodeId": "회차 UUID",
  "expectedContentHash": "현재 원고 SHA-256 소문자 64자리",
  "expectedContentS3Key": "현재 원고 key",
  "expectedContentS3Version": "현재 원고 version 또는 null"
}
```

먼저 원고를 업로드하고 회차 상세의 hash/key/version을 사용한다. 일반 분석을 먼저 시작하면 신규 검수 접수 자격이 없어진다. 서버가 기록한 신규 단일 append 업로드 자격, 최신 회차/소유권/배치, 현재 원문 버전, 이전 Job 유무(legacy target-only 관계 포함)를 검증한다. 첫 회차 번호는 1이고 활성 인물/세계관 설정 또는 설정집 revision 등의 비교 자료가 있어야 한다. 기존 원고에는 자격을 소급 부여하지 않는다.

Work 잠금 안에서 report와 대상의 **접수 당시 batch/episodeNo/hash/key/version**을 저장하고, 기존 확정 state 캡처를 읽기 전용으로 재사용해 seed/세계관 이력/과거 source 목록과 정규화된 기준을 확보한다. `AnalysisJob`, 추출 run, 후보, provisional journal, 확정 설정/Fact/출처/서사 시점은 생성하지 않는다. 합성 Job도 만들지 않는다.

같은 작품·키·입력은 같은 report/baseline을 반환한다. 새 키의 같은 입력도 같은 원장에 연결한다. 같은 키나 회차에 다른 입력은 `VALIDATION_IDEMPOTENCY_CONFLICT`(409)로 거절한다. 재전송 때 현재 DB로 기준을 다시 캡처하지 않는다. 원고 교체 후 새 세대 재검수 전체는 후속 범위다.

`GET /api/v1/works/{workId}/episode-validations/{reportId}`는 접수와 같은 `CommonResponse.data`를 반환한다.

| 현재 실제 필드 | 의미/값 |
|---|---|
| `reportId`, `baselineId` | 같은 검수 원장 UUID |
| `batchId`, `episodeId` | 접수 당시 업로드와 대상 회차 |
| `validationJobId` | `null` — 오류 검사 Worker 미구현 |
| `extractionJobId`, `candidateGeneration`, `candidateDigest` | 모두 `null` — 설정 추출을 시작하지 않음 |
| `extractionStatus`, `candidateStatus`, `errorReviewStatus` | 모두 `NOT_STARTED` |
| `baselineStatus` | `PENDING`, `READY`, `FAILED`, `INVALIDATED` |
| `status` | `PREPARING_BASELINE`, `WAITING_FOR_ERROR_REVIEW`, `HELD_INCOMPLETE`, `INVALIDATED` |
| `holdReason` | `BASELINE_EVIDENCE_INVALID`, `BASELINE_SOURCE_UNAVAILABLE`, `BASELINE_SEED_INVALID`, `SOURCE_EVIDENCE_PURGED` 등 |
| `confirmedStateHash`, `baselineHash` | 고정 확정 state/기준 seed hash |
| `sourcePreparationStatus` | `NOT_PREPARED` — 검색/원문 전체 검사 완료를 주장하지 않음 |

현재 JSON의 `reviewComplete`/`approved`나 빈 findings 같은 클라이언트 값은 승인 증거가 아니며 추출을 시작하지 않는다. release API도 없다. 미래 상태/버튼 이름은 아래 제품 원칙이며 현재 DTO 필드나 실행 가능한 계약으로 오해하지 않는다.

## 고정 기준과 실패

캐릭터 현재값과 과거 Fact의 효력 회차·조건/valueJson·사실/관측 서사 시점·실제 근거를 보존한다. 세계관은 현재 상태 외에 CONFIRMED 후보의 최종 값·행위·전체 경로·history-only·효력 시점·조건/비교 기록을 별도로 저장한다. 현재 경로에서 사라진 과거 값도 보존한다.

직접 Fact 근거 유무와 관계없이 모든 접근 가능한 앞선 활성 회차의 UUID/고정 번호/hash/key/version을 허용 원문 목록에 넣는다. 대상 원고와 후속 회차, 보관/파기 대기 원문은 과거 근거 목록에서 제외한다. 대상 원고는 report의 별도 고정 fingerprint로 식별한다. 설정집은 실제 불변 revision UUID/hash/key/version/기준 시점을 보존하고 과거 근거를 최신 포인터로 바꾸지 않는다. 접근 가능한 보관 revision도 기존 출처 계약에 따라 유지한다.

정규화는 저장한 seed만 사용한다. 과거 원문을 읽거나 hash를 검증할 수 없는 typed 실패는 report와 대상 fingerprint를 보존한 `HELD_INCOMPLETE`로 조회된다. DB 저장 오류는 정상 보류로 삼키지 않고 접수 트랜잭션을 롤백한다. 빈 확정 설정이나 근거 부족을 오류 finding으로 만들지 않으며, 실제 조회/검사 실패를 정상 0건으로 표시하지 않는다.

## 추출 차단과 수명

권위는 extraction Job 존재가 아니라 검수 report의 회차다. 현재 report가 있으면 상태가 READY이거나 INVALIDATED여도 일반 create/재분석/retry로 추출을 열 수 없다. Worker claim 선정 SQL은 직접 회차와 target-only legacy 관계를 모두 제외해 다른 정상 작업을 굶기지 않는다. 늦은 lease callback, 후보 반영/신규 대상/Fact·이력·서사 시점 경로도 같은 report를 확인한다. 차단 오류는 `VALIDATION_ERROR_REVIEW_PENDING`(409)이다.

추출 전 원고 수정/파일 교체는 가능하다. 변경 접수 트랜잭션에서 원고 또는 기준 출처에 의존하는 seed/entries/source 복사본을 비우고 report를 INVALIDATED로 만든다. 작가의 기존 확정 설정 직접 수정은 허용하되 기준을 무효화해 재검사를 요구한다. 실제 설정집 편집/보관도 무효화하고 동일 revision 재분석만으로는 지우지 않는다. 늦은 정규화는 같은 Work 잠금 뒤 자신의 미반영 변경을 flush하고 원장을 새로 읽어 삭제한 내용을 복원하지 않는다.

작품 파기는 source → 요청 키 → report를 기존 부모 원고/배치보다 먼저 제거한다. 회원 탈퇴는 기존 작품 파기 coordinator를 사용한다. 원문 파기 후에도 고정 대상 fingerprint는 무효 결과를 식별하는 감사 메타데이터이며 삭제된 원문을 복원하거나 승인하는 근거로 쓰지 않는다.

## Job 없는 목록

원고 목록은 report의 얇은 회차별 상태 projection으로 `ERROR_REVIEW_PREPARING/PENDING/HELD/INVALIDATED`와 `validationReportId`를 표시한다. `latestAnalysisJobId`는 null이다. 회차 상세의 `status`는 기존 원고 처리 상태이며 검사 완료를 의미하지 않는다.

분석 배치 목록은 접수 당시 배치의 no-job report도 포함하고 별도 `validationReports`를 반환한다. 가짜 Job/group 또는 추출 성공 건수를 만들지 않는다. 기존 일반 자동/수동/순차/설정집 Job의 완료·반영·metrics 의미와 후행 claim 조건은 유지한다.

## 후속 제품 원칙 — 아직 실행 가능한 release 계약 아님

- 오류 검사가 정상 완료되고 미검사 범위/부분 실패가 없으며 의심 0건이면 자동으로 설정 추출을 시작한다. 단순 0건이나 기준 READY만으로 허용하지 않는다.
- findings가 있으면 모두 NOT_CONFLICT로 판단한 뒤 별도 **검토 완료 후 설정 추출** 버튼을 사용한다. 항목 판단 저장 자체는 추출을 시작하지 않는다.
- 검사 중 발견 항목은 읽기 전용이고 검사 종료/중단 뒤 판단을 저장한다. 부분 중단 결과에 판단을 저장해도 추출은 보류한다.
- 원고/기준 설정을 수정하면 재검사가 먼저다. 같은 대상·충돌 원인은 한 항목과 모든 발생 위치로 묶는다. DB에 설정이 없다는 사실 자체는 finding이 아니며 조회 실패는 검사 미완료다.
- 101화 검토와 설정 반영 완료 후 102화를 등록하는 제품 순서와 서버 업로드 제한 연결은 후속 Task 7 범위다. 이번 변경에 새 업로드 제한 정책을 구현하지 않는다.

## Migration·검증·수동 확인

오류 검사 우선 스키마는 미배포 `V91__add_validation_staging.sql`로 추가한다. 최신 main의 #304가 사용한 V89·V90은 그대로 보존하며, 과거 #303의 V89는 격리 임시 DB에만 적용되었다. 새 검증은 새 빈 DB에서 전체 Flyway/JPA validate로 실행한다. 공유 `analysis_jobs`에 candidate-first 정책 컬럼을 추가하지 않는다. Python 추출 payload/모델/기존 자동 반영 계약은 그대로다.

#304의 출처 검증·진단 계약을 유지하며 일반 인용 표시는 GH323 후속 정책을 따른다. 위치가 없거나 정확히 일치하지 않는 일반 인용만 숨기고 값과 정상 근거를 보존한다. 원문 hash·출처 연결 및 별도 시점 증명의 오류는 기준 보류로 처리한다. 고정 원문 읽기 실패는 기존 `ANALYSIS_CURRENT_EVIDENCE_SOURCE_UNAVAILABLE` 코드일 때만 `BASELINE_SOURCE_UNAVAILABLE`로 보류하며, DB 오류와 다른 예외는 삼키지 않는다.

기본 검증은 `./gradlew test bootJar`. 실제 새 PostgreSQL은 명시적인 새 loopback DB에서만 실행한다.

```bash
GH303_ERROR_FIRST_POSTGRES_JDBC_URL=jdbc:postgresql://127.0.0.1:<disposable-port>/gh303_error_first_test ./gradlew test --tests '*ErrorFirstValidationPostgresIntegrationTest'
```

Swagger/API 수동 확인은 별도 개발 DB와 합성 원고로 한다. 현재 UI가 전체 흐름을 공개하지 않았으므로 API 확인을 실제 검수 완료로 해석하지 않는다.

1. 기존 설정이 있는 테스트 작품에 마지막 회차 + 1을 SINGLE_EPISODE로 저장한다. **일반 분석을 먼저 누르지 않는다.**
2. 회차 상세의 hash/key/version과 upload batch/episode ID로 검수 POST를 호출한다. 기준 READY, status WAITING_FOR_ERROR_REVIEW, NOT_STARTED 세 필드, null Job/후보 세대·digest를 확인한다.
3. 같은 입력을 같은/새 idempotencyKey로 다시 보내 report/baseline ID가 같은지 확인한다.
4. 일반 자동·수동 추출/재분석 POST가 409이고 Job/후보가 생성되지 않는지 확인한다.
5. 추출 전 파일 교체를 호출해 성공하고 기존 report가 INVALIDATED, 복사 기준이 정리되었는지 확인한다. 원고 정정 후 새 검수 세대 전체 동선은 아직 제공하지 않는다.
6. 검수 report가 없는 별도 회차/작품에서 기존 설정 구축 자동·직접 검토·순차·설정집 분석이 정상인지 회귀 확인한다.

오류 Worker와 실제 검토 완료 release가 없으므로 이 수동 확인으로 원문 검사부터 최종 설정 확정까지 완료되었다고 주장하지 않는다.

### 기존 비교 완료와 기준 변경의 직렬화

접수 이후 앞 회차의 기존 singleton/batch 비교가 EXCLUDE로 끝나더라도, 검증된 대표 서사 시점을 실제 변경하면 기준이 바뀐다. 두 완료 callback은 Work → Job → Character 순서로 잠그고, 시점 변경과 같은 트랜잭션에서 검수 기준 복사본을 무효화한다. 중복 완료나 시점을 바꾸지 않는 EXCLUDE는 새 변경으로 취급하지 않는다. Job 없는 배치의 최근 활동 시각에도 원장의 무효화 시각을 포함한다.
