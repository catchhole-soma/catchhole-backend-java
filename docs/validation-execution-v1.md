# 검수 실행·재검수 계약 v1 — GH310

이 문서의 첫 구현 단위는 변경 입력의 명시적 재검수와 실제 설정 편집의 기준 정정 연결이다.
검수 Job/lease·진행 결과 저장은 다음 구현 단위가 연결한다. 작가 판단·검토 종료·추출 허가는 GH312,
화면과 다음 회차 등록 제한은 GH314다. 이 단위에서 추출 Job·후보·설정 자동 확정·자동 철회는 생성하지 않는다.
합성 요청 예시는 `docs/contracts/validation-execution-v1.json`이다.

## 이력과 버전

`baselineId = reportId`를 유지한다. report마다 고정 원고와 새 기준을 캡처하며 과거 판단을 복사하지 않는다.
원고만 바뀐 경우에도 이 구현은 접근 가능한 자료에서 새 기준을 캡처한다. 이전 복사본을 복구하지 않는다.

| 필드 | 의미 |
| --- | --- |
| `current` | 회차당 하나인 현재 report 포인터. INVALIDATED여도 새 재검수 전까지 현재 포인터다 |
| `revision` | 회차 안의 새 report 접수 순번, 최초 1 |
| `reportVersion` | 현재 report 상태의 CAS. 조회나 flush만으로 증가하지 않는다 |
| `baselineRevision` | 새로 캡처한 기준 순번, 최초 1. 현재 구현은 report마다 새 기준이므로 두 순번이 함께 증가한다 |
| `targetRevision` | report가 고정한 `Episode.contentRevision` |
| `baselineHash` | 현재 읽을 수 있는 저장 seed hash. 무효화하면 null |
| `baselineFingerprint` | 파기 뒤에도 비교할 수 있는 해시 식별값. 본문 접근·추출 허가가 아니다 |
| `previousReportId` | 바로 앞 report ID. 과거 응답은 요약만 제공한다 |
| `mode` | 서버가 실제 확정/적용 이력으로 정한 BEFORE_CONFIRMATION 또는 DIAGNOSTIC_ONLY |

Work 잠금이 report 쓰기의 직렬화 경계다. 최초 접수·새 재검수는 Work → report/원고/기준 순서,
정정은 Work → report → 설정 대상 순서를 따른다. PostgreSQL partial unique index는 회차당 현재 포인터 하나를,
`(episode_id, revision)` unique index는 같은 이력 순번의 중복을 막는다. 이전 포인터를 flush한 뒤 새 report를 저장한다.

report version은 seed 캡처·정규화 완료·보류·최초 무효화·현재 포인터 퇴역에서 증가한다.
반복 파기와 조회는 증가시키지 않는다. 후속 실행/결과 소유자도 Work 잠금 아래 의미 있는 변경 후
`ValidationReport.advanceVersion()`을 호출해야 한다. JPA `@Version` 대신 명시적인 version을 사용한다.
JSON dirty checking의 flush가 내용 변화 없이 CAS를 바꾸는 현상을 실제 통합 테스트에서 확인했기 때문이다.

원고 revision은 원문 수정·저장 위치 변경·파일 교체·파기마다 증가한다. 제목만 수정하면 유지한다.
동일 hash/key/version으로 돌아온 A→B→A도 이전 report 입력을 되살리지 않는다.
회차 상세 `contentRevision`과 원고 fingerprint를 함께 읽고 재검수에 전달한다.

## 공개 접수 API

기존 `POST /api/v1/works/{workId}/episode-validations`는 신규 단일 append 업로드의 최초 접수다.
동일 START 키는 과거 접수 응답도 요약으로 복구한다. 새 키로 최초 접수를 재검수로 대체할 수 없다.
기존 `GET /{reportId}`는 현재/과거 요약을, `GET /current?episodeId=...`는 현재 report와 CAS version을 반환한다.

`POST /{reportId}/rechecks`의 필수 입력은 `idempotencyKey`, `expectedReportVersion`,
현재 `expectedContentHash/expectedContentS3Key/expectedContentRevision`이다. S3 version은 실제 nullable 값을 보낸다.
`expectedBaselineHash` 또는 `correctionReceiptId`를 제공한다. 둘 다 보내면 서로 같은 기준이어야 한다.
키는 START/RECHECK가 공유하는 작품별 namespace이고 같은 키의 다른 operation/입력은 409다.
같은 입력의 실패 재시도는 새 report를 만들지 않고 `VALIDATION_RECHECK_INPUT_UNCHANGED`로 별도 retry 경계를 안내한다.
오래된 report·version은 `VALIDATION_REPORT_VERSION_CONFLICT`, 원고/기준 불일치는
`VALIDATION_SOURCE_VERSION_CONFLICT`다. 새 접수는 최신 활성 회차에만 허용한다.

V91에서 이미 파기해 fingerprint가 없거나 최초 캡처 실패로 seed/hash가 없는 현재 report만
`expectedBaselineHash=null`을 받을 수 있다. 소유권·현재 reportVersion·현재 원고 fingerprint/revision·최신 회차를
검증해 새 기준/report를 준비한다. 기존 기준·판단을 복원하는 동일 입력 재시도가 아니다.
서버 fingerprint가 있으면 null 또는 다른 hash를 거절한다. 이전 해시를 추측하거나 backfill하지 않는다.

## 명시적 기준 정정

`POST /{reportId}/corrections`는 `ValidationCorrectionRequest`로 실제 설정 편집과 receipt를 한 트랜잭션에 묶는다.
필수 공통 필드는 reportVersion, baselineId/revision/hash, `entryRef`와 `expectedEntryHash`, 멱등 키다.
키는 정정 endpoint의 별도 작품별 namespace이며 동일 키·다른 report/입력은 409다.
`ENTRIES` 응답의 entryHash는 저장 항목 자체의 hash이며 전송용 entryRef/entryHash를 다시 hash하지 않는다.

`character`와 `worldSetting` 중 정확히 하나만 받는다.

- 캐릭터: targetId, expectedSnapshotVersion, factType/factKey, 기존 전체 CharacterUpdateRequest를 받는다.
  기준의 현재 전체 slot과 실제 authoritative snapshot·현재 version이 같아야 한다.
  기존 CharacterService의 동일 변환/스키마/수동 Fact 경계를 사용하되 지정한 현재 slot 하나 외의
  추가·삭제·값 변경, 이름/역할/첫 등장 변경은 저장 전에 거절한다. 일반 Character PATCH의 기존 계약은 유지한다.
- 세계관: targetId, expectedVersion과 기존 WorldSettingPropertyUpdateRequest를 받는다.
  기준의 대상 identity·전체 properties·version과 실제 현재값을 대조하고 기존 서비스가 current scope/path,
  목적 scope/path·충돌·수동 보호를 검증한다. 영수증은 실제 저장한 before/after와 version을 기록한다.
  old/new 경로는 편집 전후 `WorldSetting.getStoredPropertyPath`가 반환한 실제 저장 이름을 사용한다.
  공백·대소문자·NFC alias 요청 문자열을 그대로 정정 scope에 저장하거나 별도 정규화기로 다시 해석하지 않는다.

현재 baseline과 일치하지 않는 과거 항목, worldHistory, 다른 대상, 모호한 범위와 stale version은
`VALIDATION_CORRECTION_CONFLICT` 또는 report version 충돌로 거절한다. BASELINE_ONLY 철회는 지원하지 않는다.
일반 저장은 기존 report를 무효화할 뿐 직접 정정 receipt를 생성하지 않는다.
DB receipt 저장이 실패하면 실제 캐릭터 Fact/snapshot/provenance·세계관 값·baseline 무효화까지 모두 롤백한다.

새 seed와 해당 항목의 `state.explicitAuthorCorrections`에는 서버가 검증한 target/slot 또는 전체 scope/path,
before/after 값·entityVersion·정정 ID·기준 entry/hash/revision만 전달한다. 클라이언트가 의미 범위를 고르지 않는다.
명시적 정정의 연속 편집은 이미 검증한 이전 정정 scope를 서버에서 연결해 보존한다.
일반 저장이나 출처 파기 뒤에는 이 복사본으로 이전 주장을 되살리지 않는다.
Python의 GH306 단수 `explicitAuthorCorrection`은 자유 문자열 질문 scope였으므로 실행 연결에서
이 typed 경로 metadata를 실제 검사 항목에 매핑해야 한다. 임의의 전체 인물 예외로 확대하면 안 된다.

## 진단 재검수

`previousApplicationReceipt`는 실제 `automaticAppliedAt` Job, CONFIRMED 캐릭터 후보 또는 CONFIRMED 세계관 후보 ID를
구분한다. 단순 SUCCEEDED를 확정으로 추정하지 않는다. 캐릭터 후보에는 별도 확정 시각이 없어 `appliedAt=null`이다.
개별 후보 확정은 해당 이력의 증거이며 회차 전체 APPLIED 완료를 뜻하지 않는다.
Job이 없는 legacy 캐릭터 확정도 직접 회차의 실제 후보 ID를 보존하고 null Job receipt로 진단을 보류한다.
해당 원고/target membership의 PENDING/RUNNING 추출·비교 Job이 있으면 재검수를 거절해 진행 중 실행을 끊지 않는다.

검증 가능한 확정 전 입력이 있을 때만 진단 기준을 준비한다. 현재 지원하는 이력은 hash가 일치하는
SEALED ordered 자동 입력 또는 첫 순번 수동 runBaseState다. 현재/후행 회차 provenance가 있으면 거절한다.
Work 잠금 안에서 그 입력에 저장한 출처 ID/hash와 실제 후보·Job의 저장 위치·현재 수명도 검증한다.
참조 회차의 파기가 대기 중이면 retained 원문 key가 있어도 진단을 보류한다. 완료된 후행 Job의 이전 입력도
지연 파기 때 redacted될 수 있기 때문이다. 현재 원문의 manifest로 과거 출처를 다시 연결하지 않는다.
이 진단 전용 검증은 새 원문에서 직접 캡처한 일반 기준의 retained-key 허용을 바꾸지 않는다.
그 입력의 실제 정정 receipt가 있다면 검증한 단일 slot/path만 순서대로 정정하며 다른 live 현재값을 섞지 않는다.
확정 전 자료가 없거나 입력 출처가 파기되어 hash를 검증할 수 없으면
`HELD_INCOMPLETE + DIAGNOSTIC_BASELINE_UNAVAILABLE`로 남긴다. 현재 설정 fallback이나 과거 상태 추측을 하지 않는다.
따라서 일부 legacy 진단은 기준을 준비할 수 없다. report 자체가 없는 기존 분석 회차의 새 진단 입구도 이번 범위가 아니다.
진단은 이전 확정 사실을 보존하며 새 추출·후보·확정·자동 철회를 하지 않는다.

## 조회·무효화·파기 경계

현재 report를 선택하는 소비자는 `ValidationReportRepository.findByEpisodeIdAndCurrentTrue`,
`findStatesByEpisodeIdIn`(Episode 목록), `AnalysisBatchSummaryRepository.readValidations`(배치 요약),
`ValidationDataServiceImpl.requireCurrentReport`(읽기 전후 scalar 검증)다.
호환 `findByEpisodeId`도 현재 report만 반환한다. 배치 요청 이력의 min/max 시각 SQL은 모든 접수를 집계하고,
`AnalysisJobEligibility`·`AnalysisJobRepository.existsValidationStagingForEpisodes`의 추출 차단은 모든 이력을 검사한다.
이력 수를 현재 검수 수로 합치거나 기존 Job 성공 수로 가장하지 않는다.

`ValidationStagingEvents.ReportInvalidated(UUID workId, UUID reportId)`는 Work 잠금 안의 동기 이벤트다.
`ValidationBaselineService`가 seed/entries/sources를 지운 뒤 발행한다. 후속 Job·checkpoint·결과·인용 소유자는
같은 트랜잭션에서 이 이벤트를 소비해 무효화/본문 삭제를 연결해야 한다. source·setting 변경과 퇴역이 같은 경계를 사용한다.
작품/회원 파기는 correction → baseline source/request key → report 순서로 자식을 지운다.

파일 교체 파기 요청의 retainedContentKey는 새 원문을 보존한다. `blocksSource(episodeId,key)`는 삭제/다른 key만 막고,
늦은 정리의 `EpisodeSourceChanged(workId,episodeId,retainedContentKey)`도 보존 key의 새 report를 지우지 않는다.
고정 contentRevision·hash/key/version 검증은 별도로 유지해 같은 key의 과거 report를 복구하지 않는다.

고정 자료 응답은 실제 baselineRevision을 반환한다. GH306 Python client의 `baselineRevision == 1` 고정 검증은
실행 연결에서 신뢰된 claim의 expected revision 검사로 바꿔야 한다. 최초 접수/read fixture의 revision 1은 유지한다.
새 manifest의 회차 contentRevision도 검색·읽기·게시의 동일 입력 검증으로 전달해야 한다.

## 로컬 검증

H2 focused: `./gradlew test --tests '*ValidationRecheckIntegrationTest' --tests '*ValidationCorrectionIntegrationTest'`.
PG 테스트는 새 loopback `gh310_recheck_test` DB에서만 실행하며 .env/공유 DB를 사용하지 않는다.
`GH310_RECHECK_POSTGRES_JDBC_URL=jdbc:postgresql://127.0.0.1:<port>/gh310_recheck_test`를 주입하고
`./gradlew test --tests '*ValidationRecheckPostgresIntegrationTest'`를 실행한다.
사용자는 `gh310`, 암호는 합성 fixture 전용 `gh310-disposable-only`다. 전체 Flyway 적용 후 JPA validate로 기동한다.
테스트는 fixture마다 works/members를 CASCADE TRUNCATE하므로 해당 일회용 DB 외에는 실행하지 않는다.
실제 모델 호출이나 운영 배포 검증이 아니며 전체 Java/AI 통합 gate는 마지막 구현 단위에서 별도로 수행한다.
