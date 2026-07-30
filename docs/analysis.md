# Analysis Domain

## 목적

Analysis 도메인은 작품의 각 회차를 대상으로 하는 AI 분석 작업의 상태와 결과 메타데이터를 추적합니다. `UploadBatch`는 한 번의 업로드 출처를 묶지만 분석 실행과 실패 격리의 단위는 단일 회차 `AnalysisJob`입니다.

현재 범위에서는 백엔드가 분석 작업을 생성/조회하고 Worker가 내부 API로 작업을 claim/상태 갱신할 수 있는 계약을 제공합니다. 실제 원문 청킹, LLM 설정 후보 추출, quote 위치 보정은 Python AI Worker가 담당하며, Spring은 분석 작업 상태와 사용자 검토 도메인을 관리합니다.

## 핵심 결정

### 원문 저장

분석 작업은 원문 텍스트를 DB에 복사하지 않습니다.

회차 원문은 기존 `Episode`의 S3 저장 구조를 재사용합니다.

- `episodes.content_s3_key`
- `episodes.content_s3_version`
- `episodes.content_hash`
- `episodes.char_count`

`analysis_jobs`에는 분석 상태와 결과 메타데이터만 저장합니다.

### 생성 요청 단위

분석 작업 생성 API는 `batchId`를 필수 입력으로 받고, `episodeId`는 선택적으로 받습니다. 응답은 항상 생성된 Job 목록입니다.

- `episodeId == null`: `batchId`에 연결된 보관되지 않은 각 회차마다 `AnalysisJob`을 하나씩 생성합니다.
- `episodeId != null`: 검증된 해당 회차의 `AnalysisJob` 하나를 생성합니다.

이 범위 지정 규칙은 공개 생성 API가 지원하는 모든 `jobType`에 동일하게 적용됩니다. 신규 Job은 `analysis_jobs.episode_id`와 `analysis_job_episode_targets`에 같은 단일 회차를 저장하고, 이후 조회와 Worker 처리는 이 스냅샷을 사용합니다.

batch 요청의 최초 대상 선정 흐름은 다음과 같습니다.

```text
upload_batches.id
  -> upload_files.batch_id
  -> episodes.source_file_id
```

대상 선정 결과가 비어 있으면 `PENDING` 작업을 저장하지 않고 `ANALYSIS_JOB_TARGET_NOT_FOUND`로 거절합니다. 현재 Worker payload와 처리 흐름은 `Episode`만 분석 입력으로 지원하므로 설정집 파일만 있는 batch도 이 규칙을 적용합니다. 설정집 원본 분석을 도입할 때는 설정집을 `Episode`로 간주하지 않고 별도 분석 입력과 대상 스냅샷을 추가한 뒤 이 검증을 확장합니다.

대상 중 한 회차라도 같은 batch에서 `PENDING` 또는 `RUNNING`이면 batch 생성 요청 전체를 저장 전에 거절합니다. 서로 다른 회차의 Job은 동시에 존재할 수 있고, 한 Job의 실패는 다른 회차 Job의 상태를 바꾸지 않습니다. `episode_id == null`이거나 복수 target을 가진 과거 작업은 조회 이력 호환용으로 유지하며 신규 생성하지 않습니다.

### Worker 연동 방식

Kafka/SQS 없이 내부 API polling 방식을 사용합니다.

Python AI Worker는 주기적으로 내부 claim API를 호출해 `PENDING` 작업을 하나씩 가져갑니다. 백엔드는 claim된 작업을 `RUNNING`으로 변경하고, Worker가 S3에서 원문을 읽을 수 있도록 단일 `episode` 원문 메타데이터, 캐릭터 매칭에 사용할 `knownCharacters`, `attributeName` 해석에 사용할 `characterSettingSchemas`를 내려줍니다. 복수 target인 과거 작업은 단일 회차 계약으로 claim하지 않고 실패 처리합니다.

Worker는 분석 작업 생성과 `AnalysisJob` 상태 전이를 위해 백엔드 DB에 직접 접근하지 않습니다. 다만 현재 설정 후보 생성은 Python Worker가 `setting_candidates`에 직접 저장합니다. Spring은 후보 조회/수정/확정/무시 API와 `AnalysisJob` 상태 전이 API를 담당합니다.

검수 리포트, 추가 전처리 산출물, 캐릭터 매칭 보정 결과를 모두 내부 API로 받을지 Worker DB 직접 저장으로 유지할지는 후속 설계에서 다시 결정합니다.

### 진행률

`progress` 숫자 필드는 사용하지 않습니다.

실제 분석 진행률을 정확히 계산하기 어렵기 때문에 fake percentage를 저장하지 않습니다. 클라이언트는 `status` enum과 필요한 경우 `currentStep`을 사용해 사용자에게 현재 상태를 보여줍니다. Worker progress 요청은 표시 문구인 `currentStep`과 별도로 `episodeStatus`를 보내며, 백엔드는 문자열을 파싱하지 않고 이 enum을 대상 회차에 적용합니다.

## 상태 모델

`AnalysisJobStatus`

| 상태 | 의미 | 전이 시점 |
| --- | --- | --- |
| `PENDING` | 작업 생성 후 분석 대기 | 사용자가 분석 작업 생성 API를 호출하면 `AnalysisJob.create()`로 생성됩니다. Worker claim 후보가 됩니다. |
| `RUNNING` | 분석 진행 중 | Python AI Worker가 내부 claim API로 작업을 가져가면 `AnalysisJob.start()`로 전환합니다. |
| `SUCCEEDED` | 분석 성공 | Worker가 완료 API를 호출하면 `AnalysisJob.succeed()`로 전환하고 결과 요약과 token count를 기록합니다. |
| `FAILED` | 분석 실패 | Worker가 실패 API를 호출하거나, claim 후 분석 대상 회차가 없으면 `AnalysisJob.fail()`로 전환합니다. |

현재 재시도 정책:

- 기존 `FAILED` 작업은 이력으로 유지합니다.
- 재시도 API는 해당 Job의 `Episode.status == FAILED`인 단일 회차에 같은 `jobType`의 새 작업을 만듭니다.
- 과거 형식의 batch-wide 작업이 같은 배치에서 `PENDING` 또는 `RUNNING`이면 중복 분석을 막기 위해 `ANALYSIS_JOB_ALREADY_IN_PROGRESS`로 거절합니다.
- 해당 회차에 이미 `PENDING` 또는 `RUNNING` 재시도 작업이 있으면 새 작업을 중복 생성하지 않고 그 활성 작업을 반환합니다.

정책 미확정 TODO:

- 실패 처리 이력은 후속 모니터링 기능에서 별도 기록/조회합니다. `AnalysisJob.errorMessage`는 작업 상세 조회에 보여줄 마지막 실패 사유만 저장합니다.
- 사용자 취소 또는 시스템 취소가 필요해지면 `CANCELED` 상태와 전이 API를 별도 정의합니다.

상태 표시 기준:

- 분석 작업 목록과 분석 작업 카드에서는 `AnalysisJob.status`를 상위 상태로 표시합니다.
- 분석 작업 응답은 `AnalysisJob.status`, `currentStep`, token count, summary/error metadata와 대상 `episodes` 목록을 반환합니다.
- Worker claim/progress/complete/fail 처리와 함께 대상 `Episode.status`도 갱신합니다.

분석 목록의 배치 집계 기준:

- 페이지 항목 하나는 `UploadBatch` 하나이며, 최근 `AnalysisJob.createdAt`이 바뀐 배치가 먼저 옵니다.
- 같은 배치·분석 목적·회차의 재시도 이력은 가장 최근 Job 하나만 현재 상태와 작업 수에 포함합니다. 이전 실패 Job은 DB 이력으로 남습니다.
- `episode_id == null`인 과거 작업은 보존된 `analysis_job_episode_targets`를 회차별로 펼쳐 같은 최신 작업 규칙을 적용합니다.
- 배치 전체 상태는 진행 중, 전체 실패, 일부 실패, 설정 후보 검토 필요, 완료 순으로 판정합니다.
- `UploadBatch`는 분석 실행 ID가 아니므로 같은 업로드 묶음에서 이루어진 독립 실행을 별도 카드로 분리하지 않습니다. 실행별 이력이 필요해지면 `AnalysisRun` 식별자를 별도 도입합니다.

`AnalysisJobType`

| 유형 | 의미 |
| --- | --- |
| `SETTING_EXTRACTION` | 설정집 추출 |
| `EPISODE_VALIDATION` | 회차 검수 |

## DB 모델

`analysis_jobs`

| 필드 | 설명 |
| --- | --- |
| `id` | 분석 작업 ID |
| `work_id` | 분석 대상 작품 ID |
| `batch_id` | 분석 대상 업로드 배치 ID |
| `episode_id` | 신규 작업의 단일 대상 회차. null인 과거 batch-wide 작업은 이력 호환용 |
| `job_type` | 분석 작업 유형 |
| `status` | 분석 작업 상태 |
| `current_step` | 워커가 기록하는 현재 처리 단계 |
| `model_name` | 사용한 AI 모델명 |
| `input_token_count` | 입력 토큰 수 |
| `output_token_count` | 출력 토큰 수 |
| `summary_json` | 분석 결과 요약 JSON |
| `error_message` | 마지막 실패 사유. 실패 처리 이력은 모니터링 기능에서 별도 관리 |
| `started_at` | 분석 시작 시각 |
| `completed_at` | 분석 완료 시각 |
| `created_at` | 생성 시각 |
| `updated_at` | 수정 시각 |

`analysis_job_episode_targets`

| 필드 | 설명 |
| --- | --- |
| `analysis_job_id` | 분석 작업 ID |
| `episode_id` | 생성 시점에 확정한 실제 대상 회차 ID |

두 필드가 복합 PK입니다. 회차의 `source_file_id`가 바뀌거나 상태가 `ARCHIVED`가 되어도 과거 작업의 대상 목록은 변경하지 않습니다.

## API

### 분석 작업 생성

```http
POST /api/v1/works/{workId}/analysis-jobs
```

Request

```json
{
  "jobType": "EPISODE_VALIDATION",
  "batchId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111",
  "episodeId": null
}
```

`episodeId`를 생략하거나 `null`로 보내면 batch의 각 회차별 Job을 생성하고, 같은 배치의 회차 ID를 보내면 해당 회차 Job만 생성합니다.

Response

```json
{
  "success": true,
  "message": "분석 작업이 생성되었습니다.",
  "data": [{
    "id": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333",
    "workId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d444",
    "workTitle": "내 작품",
    "batchId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111",
    "target": {
      "batchId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111",
      "uploadType": "INITIAL_IMPORT",
      "sourceType": "FILE",
      "status": "COMPLETED",
      "fileCount": 2,
      "episodeStartNo": 1,
      "episodeEndNo": 10,
      "episodeCount": 10
    },
    "episodeId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d555",
    "episodes": [
      {
        "id": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d555",
        "episodeNo": 1,
        "title": "첫 번째 회차",
        "status": "UPLOADED",
        "errorMessage": null,
        "updatedAt": "2026-06-14T10:29:00"
      }
    ],
    "jobType": "EPISODE_VALIDATION",
    "status": "PENDING",
    "currentStep": null,
    "modelName": null,
    "inputTokenCount": null,
    "outputTokenCount": null,
    "summaryJson": null,
    "errorMessage": null,
    "startedAt": null,
    "completedAt": null,
    "createdAt": "2026-06-14T10:29:00",
    "updatedAt": "2026-06-14T10:29:00"
  }],
  "error": null,
  "timestamp": "2026-06-14T10:29:00"
}
```

### 분석 작업 목록 조회

```http
GET /api/v1/works/{workId}/analysis-jobs
```

로그인한 사용자의 해당 작품에 생성된 분석 작업을 최신 생성순으로 조회합니다. 목록 화면에서는 `AnalysisJob.status`를 분석 작업의 대표 상태로 표시합니다.

### 분석 작업 상세 조회

```http
GET /api/v1/works/{workId}/analysis-jobs/{analysisJobId}
```

로그인한 사용자의 해당 작품에 속한 특정 분석 작업 상태와 결과 메타데이터를 조회합니다. 응답은 분석 작업 단위 상태, 대상 업로드 batch 요약과 대상 회차별 `Episode.status` 목록을 반환합니다. `episodes[].errorMessage`는 회차별 실패 사유 저장 모델이 추가되기 전까지 `null`입니다.

### 실패 작업 재시도

```http
POST /api/v1/works/{workId}/analysis-jobs/{analysisJobId}/retry
```

원본 작업이 `FAILED`인 경우에만 사용할 수 있습니다. 같은 batch의 과거 batch-wide 작업이 이미 `PENDING` 또는 `RUNNING`이면 409로 거절합니다. 그렇지 않으면 서버가 원본 작업의 대상 중 `Episode.status == FAILED`인 회차를 찾고, 같은 `jobType`과 `batchId`를 사용하는 단일 회차 작업을 생성해 목록으로 반환합니다. 기존 실패 작업 자체의 상태는 변경하지 않습니다.

## Internal Worker API

내부 API는 Python AI Worker 전용입니다.

모든 내부 API는 `X-Internal-Api-Key` 헤더가 필요합니다. 값은 서버 설정 `internal.api-key`와 일치해야 합니다.

### 분석 작업 claim

```http
POST /api/internal/v1/analysis-jobs/claim
X-Internal-Api-Key: {internalApiKey}
```

Request body는 선택입니다.

```json
{
  "modelName": "gpt-4.1-mini",
  "currentStep": "원문 청킹"
}
```

claim할 `PENDING` 작업이 없으면 `204 No Content`를 반환합니다.

claim할 작업이 있으면 가장 오래된 `PENDING` 작업 하나를 `RUNNING`으로 바꾸고 다음 payload를 반환합니다.

```json
{
  "success": true,
  "message": "분석 작업을 claim했습니다.",
  "data": {
    "analysisJobId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333",
    "jobType": "EPISODE_VALIDATION",
    "workId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d444",
    "workTitle": "내 작품",
    "batchId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111",
    "modelName": "gpt-4.1-mini",
    "currentStep": "원문 청킹",
    "characterSettingSchemas": [
      {
        "schemaKey": "stats.physique",
        "displayName": "육체",
        "attributePattern": null,
        "aliases": ["육체", "physical", "physique"],
        "valueType": "NUMBER"
      },
      {
        "schemaKey": "statuses.condition",
        "displayName": "상태",
        "attributePattern": "status.*",
        "aliases": [],
        "valueType": "JSON"
      }
    ],
    "knownCharacters": [
      {
        "characterId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d666",
        "name": "아리아"
      }
    ],
    "episode": {
      "episodeId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d555",
      "episodeNo": 1,
      "title": "첫 번째 회차",
      "contentS3Key": "works/{workId}/episodes/1.txt",
      "contentS3Version": "s3-version-id",
      "contentHash": "sha256-hash",
      "charCount": 12345
    }
  },
  "error": null,
  "timestamp": "2026-06-19T15:20:00"
}
```

원문 본문은 응답에 포함하지 않습니다. Worker는 `contentS3Key`, `contentS3Version`을 사용해 S3에서 원문을 직접 읽습니다.
`characterSettingSchemas`는 job type과 관계없이 활성 전역 schema와 현재 작품의 활성 추가 schema를 `schemaKey` 오름차순으로 내려줍니다. registry row가 없으면 빈 배열입니다. Worker에는 canonical key 해석에 필요한 5개 필드만 공개하며 source와 merge 정책은 포함하지 않습니다.
`knownCharacters`는 Python Worker가 `setting_candidates`의 `matched_character_id`, `match_status`를 계산할 때 사용하는 기존 캐릭터 목록입니다. 현재는 `characters.id`, `characters.name`을 내려줍니다.

### 진행 단계 갱신

```http
PATCH /api/internal/v1/analysis-jobs/{analysisJobId}/progress
```

```json
{
  "currentStep": "LLM 전처리",
  "episodeStatus": "PREPROCESSING"
}
```

`RUNNING` 작업에만 사용할 수 있습니다.

`currentStep`은 표시용 문구로 저장하고, 대상 회차 상태는 명시적인 `episodeStatus` 값으로 갱신합니다.

### 작업 완료

```http
POST /api/internal/v1/analysis-jobs/{analysisJobId}/complete
```

```json
{
  "summaryJson": "{\"status\":\"ok\"}",
  "inputTokenCount": 1200,
  "outputTokenCount": 300
}
```

대상 회차를 `ANALYZED`로 변경하고 `RUNNING` 작업을 `SUCCEEDED`로 변경합니다.

### 작업 실패

```http
POST /api/internal/v1/analysis-jobs/{analysisJobId}/fail
```

```json
{
  "errorMessage": "LLM 응답 스키마 오류"
}
```

아직 `ANALYZED`가 아닌 대상 회차를 `FAILED`로 변경하고 `RUNNING` 작업을 `FAILED`로 변경합니다.

## API Workflow

시각적인 흐름도는 [Analysis Workflow](analysis-workflow.md)에서 확인합니다.

### `POST /api/v1/works/{workId}/analysis-jobs`

분석 작업 생성 흐름입니다.

1. Controller가 인증된 `MemberPrincipal`에서 `memberId`를 꺼냅니다.
2. Request body의 `jobType`, `batchId`, 선택 `episodeId`를 validation 합니다.
3. Service가 `workId`, `memberId`로 본인 작품을 조회합니다.
4. 작품이 없거나 다른 회원의 작품이면 `WORK_NOT_FOUND`를 반환합니다.
5. Service가 `batchId`, `workId`로 업로드 배치를 조회합니다.
6. batch가 없거나 해당 작품에 속하지 않으면 `ANALYSIS_JOB_TARGET_NOT_FOUND`를 반환합니다.
7. `episodeId`가 있으면 회차가 같은 작품과 batch에 속하는지 검증합니다.
8. 선정된 각 회차에 같은 batch의 활성 작업이 있는지 모두 검사합니다.
9. 활성 대상이 하나라도 있으면 어떤 Job도 저장하지 않고 요청 전체를 거절합니다.
10. 각 대상 회차를 `episode_id`와 `analysis_job_episode_targets`에 저장한 `PENDING` Job을 회차 번호순으로 생성합니다.
11. 생성된 `List<AnalysisJobResponse>`를 반환합니다. 각 응답의 `episodes`에는 해당 단일 회차만 있습니다.

```text
Client
  -> AnalysisJobController
  -> AnalysisJobService
  -> WorkRepository.getOwnedWork(workId, memberId)
  -> UploadBatchRepository.findByIdAndWorkId(batchId, workId)
  -> optional EpisodeRepository.findByIdAndWorkIdAndStatusNot(episodeId, workId, ARCHIVED)
  -> active job validation
  -> AnalysisJobRepository.saveAll(PENDING jobs per episode)
  -> List<AnalysisJobResponse>
```

이 API는 분석을 즉시 수행하지 않습니다. 현재 범위에서는 “분석해야 할 작업을 등록한다”까지만 담당합니다.

### `GET /api/v1/works/{workId}/analysis-jobs`

분석 작업 목록 조회 흐름입니다.

1. Controller가 인증된 `MemberPrincipal`에서 `memberId`를 꺼냅니다.
2. Service가 `workId`, `memberId`로 본인 작품을 조회합니다.
3. 작품이 없거나 다른 회원의 작품이면 `WORK_NOT_FOUND`를 반환합니다.
4. 해당 작품의 분석 작업을 최신 생성순으로 조회합니다.
5. 각 작업의 `analysis_job_episode_targets`에 저장된 대상 회차 목록을 조회합니다.
6. 각 `AnalysisJob`을 대상 `episodes`가 포함된 `AnalysisJobResponse`로 변환해 반환합니다.

```text
Client
  -> AnalysisJobController
  -> AnalysisJobService
  -> WorkRepository.getOwnedWork(workId, memberId)
  -> AnalysisJobRepository.findAllWithTargetsByWorkIdOrderByCreatedAtDesc(workId)
  -> List<AnalysisJobResponse>
```

프론트엔드는 목록 응답의 `status`, `currentStep`, `createdAt`, `updatedAt`을 사용해 작업 현황을 표시합니다.
`workTitle`과 `target.episodeStartNo`, `target.episodeEndNo`, `target.episodeCount`를 사용하면 추가 조회 없이 분석 대상 표시 문구를 만들 수 있습니다.

### `GET /api/v1/works/{workId}/analysis-jobs/batches`

분석 목록 화면을 위한 업로드 배치 페이지 조회입니다.

```http
GET /api/v1/works/{workId}/analysis-jobs/batches?page=0&size=10
```

- `content` 한 항목은 업로드 배치 하나입니다.
- `jobGroups`는 같은 배치에서 수행한 `SETTING_EXTRACTION`, `EPISODE_VALIDATION`을 각각 집계합니다.
- `currentAnalysisJobIds`는 진행·실패·완료 상세 화면에서 다시 조회할 최신 유효 Job ID입니다.
- `totalCandidateCount`, `reviewedCandidateCount`, `pendingCandidateCount`는 배치에 연결된 설정 후보 검토 현황입니다.
- 페이지 크기는 1~20이며 응답은 공통 `PageResponse` 형식입니다.

상태 판정 우선순위는 다음과 같습니다.

1. 현재 유효 Job 중 `PENDING` 또는 `RUNNING`이 있으면 `IN_PROGRESS`
2. 모든 목적의 현재 Job이 실패했으면 `FAILED`
3. 성공과 실패가 섞였으면 `PARTIALLY_FAILED`
4. 실행이 끝났고 검토 대기 후보가 있으면 `REVIEW_REQUIRED`
5. 그 외에는 `COMPLETED`

### `GET /api/v1/works/{workId}/analysis-jobs/{analysisJobId}`

분석 작업 상세 조회 흐름입니다.

1. Controller가 인증된 `MemberPrincipal`에서 `memberId`를 꺼냅니다.
2. Service가 `workId`, `memberId`로 본인 작품을 조회합니다.
3. 작품이 없거나 다른 회원의 작품이면 `WORK_NOT_FOUND`를 반환합니다.
4. `analysisJobId`, `workId`로 분석 작업을 조회합니다.
5. 작업이 없거나 해당 작품에 속하지 않으면 `ANALYSIS_JOB_NOT_FOUND`를 반환합니다.
6. 작업 상태, 결과 메타데이터와 대상 `episodes`를 `AnalysisJobResponse`로 반환합니다.

```text
Client
  -> AnalysisJobController
  -> AnalysisJobService
  -> WorkRepository.getOwnedWork(workId, memberId)
  -> AnalysisJobRepository.findByIdAndWorkId(analysisJobId, workId)
  -> AnalysisJobResponse
```

상세 조회는 분석 결과 전체 원문을 내려주기보다, 작업 상태와 요약 메타데이터를 확인하는 API로 둡니다.

## Per-Episode Job Workflow

분석 생성 request는 `batchId`와 선택적인 `episodeId`를 받습니다.

`episodeId`가 없으면 작업 생성 시 다음 순서로 batch의 현재 회차를 찾고 회차별 Job으로 나눕니다.

1. `upload_batches.id`로 업로드 배치를 찾습니다.
2. `upload_files.batch_id`로 batch에 속한 업로드 파일들을 찾습니다.
3. `episodes.source_file_id`로 각 업로드 파일에서 생성된 회차들을 찾습니다.
4. `ARCHIVED`가 아닌 회차만 선택합니다.
5. 선정된 각 회차마다 `AnalysisJob`을 생성하고 단일 대상을 `analysis_job_episode_targets`에 저장합니다.
6. Worker claim API가 가장 오래된 Job 하나와 단일 `episode`, `knownCharacters`, 활성 `characterSettingSchemas`를 payload로 내려줍니다.
7. Worker가 해당 회차의 S3 원문만 읽고 분석합니다.
8. Worker가 내부 API로 해당 Job과 단일 대상 회차 상태를 함께 변경합니다. 실패해도 다음 회차 Job은 계속 claim할 수 있습니다.
9. 필요하면 `currentStep`, `modelName`, token count, `summaryJson`, 마지막 실패 사유인 `errorMessage`를 기록합니다.

예상 조회 흐름은 다음과 같습니다.

```text
analysis_jobs.id
  -> analysis_job_episode_targets.analysis_job_id
  -> analysis_job_episode_targets.episode_id
  -> episodes.id
```

`episodeId`가 있으면 batch 전체 탐색 대신 검증된 회차 Job 하나만 생성합니다.

## 접근 제어

- 본인 작품의 분석 작업만 생성하고 조회할 수 있습니다.
- 다른 회원의 작품 접근은 `WORK_NOT_FOUND`로 응답합니다.
- 요청한 `batchId`가 해당 작품에 속하지 않으면 `ANALYSIS_JOB_TARGET_NOT_FOUND`로 응답합니다.
- 요청한 `episodeId`가 해당 작품과 `batchId`에 함께 속하지 않으면 `ANALYSIS_JOB_TARGET_NOT_FOUND`로 응답합니다.
- 같은 대상에 활성 작업이 있으면 `ANALYSIS_JOB_ALREADY_IN_PROGRESS`로 응답합니다.

## 이후 작업

- Python AI Worker의 청킹/LLM 처리 운영 안정화
- `summary_json` 구조와 token count 집계 정책 확정
- 설정 후보 외 검수 리포트 저장 흐름 구현
- 회차별 실패 사유와 재시도 이력 모델 확정
