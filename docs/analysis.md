# Analysis Domain

## 목적

Analysis 도메인은 작품 단위 AI 분석 작업의 상태와 결과 메타데이터를 추적합니다.

이번 범위에서는 실제 Python AI Worker와 LLM 호출을 구현하지 않고, 백엔드가 분석 작업을 생성/조회하고 Worker가 내부 API로 작업을 claim/상태 갱신할 수 있는 계약을 제공합니다.

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

분석 작업 생성 API는 `batchId`를 필수 입력으로 받습니다.

업로드 배치 하나에는 파일이 여러 개 있을 수 있고, 각 파일에서 여러 회차가 생성될 수 있습니다. 백엔드는 이미 batch 기준으로 어떤 업로드 파일과 회차가 연결되는지 추적할 수 있으므로 클라이언트가 `episodeId`를 직접 넘기지 않습니다.

현재 연결 흐름은 다음과 같습니다.

```text
upload_batches.id
  -> upload_files.batch_id
  -> episodes.source_file_id
```

회차별 세부 작업이 필요해지면 워커 또는 내부 서비스가 batch에 속한 episode 목록을 조회해 처리합니다.

### Worker 연동 방식

Kafka/SQS 없이 내부 API polling 방식을 사용합니다.

Python AI Worker는 주기적으로 내부 claim API를 호출해 `PENDING` 작업을 가져갑니다. 백엔드는 claim된 작업을 `RUNNING`으로 변경하고, Worker가 S3에서 원문을 읽을 수 있도록 회차 원문 메타데이터만 내려줍니다.

Worker는 분석 작업 생성과 `AnalysisJob` 상태 전이를 위해 백엔드 DB에 직접 접근하지 않습니다. 다만 청킹, 설정 후보, 리포트 같은 분석 산출물 저장은 데이터 양과 모델 안정성에 따라 내부 API 또는 Worker의 DB 직접 저장 중 선택할 수 있습니다. DB 직접 저장을 선택하면 백엔드 문서와 스키마 변경 규칙을 함께 관리합니다.

### 진행률

`progress` 숫자 필드는 사용하지 않습니다.

실제 분석 진행률을 정확히 계산하기 어렵기 때문에 fake percentage를 저장하지 않습니다. 클라이언트는 `status` enum과 필요한 경우 `currentStep`을 사용해 사용자에게 현재 상태를 보여줍니다.

## 상태 모델

`AnalysisJobStatus`

| 상태 | 의미 | 전이 시점 |
| --- | --- | --- |
| `PENDING` | 작업 생성 후 분석 대기 | 사용자가 분석 작업 생성 API를 호출하면 `AnalysisJob.create()`로 생성됩니다. Worker claim 후보가 됩니다. |
| `RUNNING` | 분석 진행 중 | Python AI Worker가 내부 claim API로 작업을 가져가면 `AnalysisJob.start()`로 전환합니다. |
| `SUCCEEDED` | 분석 성공 | Worker가 완료 API를 호출하면 `AnalysisJob.succeed()`로 전환하고 결과 요약과 token count를 기록합니다. |
| `FAILED` | 분석 실패 | Worker가 실패 API를 호출하거나, claim 후 분석 대상 회차가 없으면 `AnalysisJob.fail()`로 전환합니다. |

정책 미확정 TODO:

- `FAILED` 작업 재시도 시 같은 `AnalysisJob`을 다시 `PENDING`으로 되돌릴지, 새 작업을 만들지 결정해야 합니다.
- 실패 처리 이력은 후속 모니터링 기능에서 별도 기록/조회합니다. `AnalysisJob.errorMessage`는 작업 상세 조회에 보여줄 마지막 실패 사유만 저장합니다.
- 사용자 취소 또는 시스템 취소가 필요해지면 `CANCELED` 상태와 전이 API를 별도 정의합니다.
- Episode 단위 상태와 AnalysisJob 상태를 어디까지 동기화할지 결정해야 합니다.

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
| `episode_id` | 회차별 내부 작업이 필요할 때 사용할 선택 연결 |
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

## API

### 분석 작업 생성

```http
POST /api/v1/works/{workId}/analysis-jobs
```

Request

```json
{
  "jobType": "EPISODE_VALIDATION",
  "batchId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111"
}
```

Response

```json
{
  "success": true,
  "message": "분석 작업이 생성되었습니다.",
  "data": {
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
    "episodeId": null,
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
  },
  "error": null,
  "timestamp": "2026-06-14T10:29:00"
}
```

### 분석 작업 목록 조회

```http
GET /api/v1/works/{workId}/analysis-jobs
```

로그인한 사용자의 해당 작품에 생성된 분석 작업을 최신 생성순으로 조회합니다.

### 분석 작업 상세 조회

```http
GET /api/v1/works/{workId}/analysis-jobs/{analysisJobId}
```

로그인한 사용자의 해당 작품에 속한 특정 분석 작업 상태와 결과 메타데이터를 조회합니다.

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
    "episodes": [
      {
        "episodeId": "01970c2e-7e6d-7000-8e5d-2a9bc4b6d555",
        "episodeNo": 1,
        "title": "첫 번째 회차",
        "contentS3Key": "works/{workId}/episodes/1.txt",
        "contentS3Version": "s3-version-id",
        "contentHash": "sha256-hash",
        "charCount": 12345
      }
    ]
  },
  "error": null,
  "timestamp": "2026-06-19T15:20:00"
}
```

원문 본문은 응답에 포함하지 않습니다. Worker는 `contentS3Key`, `contentS3Version`을 사용해 S3에서 원문을 직접 읽습니다.

### 진행 단계 갱신

```http
PATCH /api/internal/v1/analysis-jobs/{analysisJobId}/progress
```

```json
{
  "currentStep": "LLM 전처리"
}
```

`RUNNING` 작업에만 사용할 수 있습니다.

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

`RUNNING` 작업을 `SUCCEEDED`로 변경합니다.

### 작업 실패

```http
POST /api/internal/v1/analysis-jobs/{analysisJobId}/fail
```

```json
{
  "errorMessage": "LLM 응답 스키마 오류"
}
```

`RUNNING` 작업을 `FAILED`로 변경합니다.

## API Workflow

시각적인 흐름도는 [Analysis Workflow](analysis-workflow.md)에서 확인합니다.

### `POST /api/v1/works/{workId}/analysis-jobs`

분석 작업 생성 흐름입니다.

1. Controller가 인증된 `MemberPrincipal`에서 `memberId`를 꺼냅니다.
2. Request body의 `jobType`, `batchId`를 validation 합니다.
3. Service가 `workId`, `memberId`로 본인 작품을 조회합니다.
4. 작품이 없거나 다른 회원의 작품이면 `WORK_NOT_FOUND`를 반환합니다.
5. Service가 `batchId`, `workId`로 업로드 배치를 조회합니다.
6. batch가 없거나 해당 작품에 속하지 않으면 `ANALYSIS_JOB_TARGET_NOT_FOUND`를 반환합니다.
7. `AnalysisJob`을 `PENDING` 상태로 생성합니다.
8. 생성된 작업을 저장하고 `AnalysisJobResponse`로 반환합니다.

```text
Client
  -> AnalysisJobController
  -> AnalysisJobService
  -> WorkRepository.getOwnedWork(workId, memberId)
  -> UploadBatchRepository.findByIdAndWorkId(batchId, workId)
  -> AnalysisJobRepository.save(PENDING job)
  -> AnalysisJobResponse
```

이 API는 분석을 즉시 수행하지 않습니다. 현재 범위에서는 “분석해야 할 작업을 등록한다”까지만 담당합니다.

### `GET /api/v1/works/{workId}/analysis-jobs`

분석 작업 목록 조회 흐름입니다.

1. Controller가 인증된 `MemberPrincipal`에서 `memberId`를 꺼냅니다.
2. Service가 `workId`, `memberId`로 본인 작품을 조회합니다.
3. 작품이 없거나 다른 회원의 작품이면 `WORK_NOT_FOUND`를 반환합니다.
4. 해당 작품의 분석 작업을 최신 생성순으로 조회합니다.
5. 각 `AnalysisJob`을 `AnalysisJobResponse`로 변환해 반환합니다.

```text
Client
  -> AnalysisJobController
  -> AnalysisJobService
  -> WorkRepository.getOwnedWork(workId, memberId)
  -> AnalysisJobRepository.findAllByWorkIdOrderByCreatedAtDesc(workId)
  -> List<AnalysisJobResponse>
```

프론트엔드는 목록 응답의 `status`, `currentStep`, `createdAt`, `updatedAt`을 사용해 작업 현황을 표시합니다.
`workTitle`과 `target.episodeStartNo`, `target.episodeEndNo`, `target.episodeCount`를 사용하면 추가 조회 없이 분석 대상 표시 문구를 만들 수 있습니다.

### `GET /api/v1/works/{workId}/analysis-jobs/{analysisJobId}`

분석 작업 상세 조회 흐름입니다.

1. Controller가 인증된 `MemberPrincipal`에서 `memberId`를 꺼냅니다.
2. Service가 `workId`, `memberId`로 본인 작품을 조회합니다.
3. 작품이 없거나 다른 회원의 작품이면 `WORK_NOT_FOUND`를 반환합니다.
4. `analysisJobId`, `workId`로 분석 작업을 조회합니다.
5. 작업이 없거나 해당 작품에 속하지 않으면 `ANALYSIS_JOB_NOT_FOUND`를 반환합니다.
6. 작업 상태와 결과 메타데이터를 `AnalysisJobResponse`로 반환합니다.

```text
Client
  -> AnalysisJobController
  -> AnalysisJobService
  -> WorkRepository.getOwnedWork(workId, memberId)
  -> AnalysisJobRepository.findByIdAndWorkId(analysisJobId, workId)
  -> AnalysisJobResponse
```

상세 조회는 분석 결과 전체 원문을 내려주기보다, 작업 상태와 요약 메타데이터를 확인하는 API로 둡니다.

## Internal Batch Workflow

분석 생성 request는 `batchId`만 받지만, 실제 분석 처리 단계에서는 batch 안의 회차들을 찾아야 합니다.

현재 코드 기준 연결은 다음 순서로 풀 수 있습니다.

1. `upload_batches.id`로 업로드 배치를 찾습니다.
2. `upload_files.batch_id`로 batch에 속한 업로드 파일들을 찾습니다.
3. `episodes.source_file_id`로 각 업로드 파일에서 생성된 회차들을 찾습니다.
4. Worker claim API가 회차 목록을 payload로 내려줍니다.
5. Worker가 회차 목록을 순회하며 S3 원문을 읽고 분석합니다.
6. Worker가 내부 API로 분석 작업 상태를 `RUNNING`, `SUCCEEDED`, `FAILED`로 변경합니다.
7. 필요하면 `currentStep`, `modelName`, token count, `summaryJson`, 마지막 실패 사유인 `errorMessage`를 기록합니다.

예상 조회 흐름은 다음과 같습니다.

```text
analysis_jobs.batch_id
  -> upload_batches.id
  -> upload_files.batch_id
  -> episodes.source_file_id
```

이 흐름을 사용하면 batch에 회차가 한 개이든 여러 개이든 같은 분석 작업 생성 API를 유지할 수 있습니다.

## 접근 제어

- 본인 작품의 분석 작업만 생성하고 조회할 수 있습니다.
- 다른 회원의 작품 접근은 `WORK_NOT_FOUND`로 응답합니다.
- 요청한 `batchId`가 해당 작품에 속하지 않으면 `ANALYSIS_JOB_TARGET_NOT_FOUND`로 응답합니다.

## 이후 작업

- Python AI Worker 실제 청킹/LLM 처리 구현
- `summary_json` 구조 확정
- 실패 재시도 정책 정의
