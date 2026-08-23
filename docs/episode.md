# Episode Domain

## 목적

Episode 도메인은 작품에 속한 회차 원고의 메타데이터와 원문 저장 위치를 관리합니다.

회차 원문 전문은 DB에 직접 저장하지 않고 S3에 저장합니다. DB에는 원문 key, version, hash, 글자 수를 기록합니다. `charCount`와 `char_count`는 Java `String.length()`가 아니라 공백 문자를 제외한 Unicode code point 수입니다.

## 핵심 결정

### 원문 저장

회차 원문 저장 key는 현재 다음 형식을 사용합니다.

```text
works/{workId}/episodes/{episodeNo}/{UUID}/{episodeNo}.txt
```

저장 후 `episodes`에는 다음 메타데이터만 남깁니다.

- `content_s3_key`
- `content_s3_version`
- `content_hash`
- `char_count`
- `content_updated_at`

저장본마다 UUID 경로를 사용하므로 보관된 회차 번호를 재사용해도 기존 원문을 덮어쓰지 않습니다. 마지막 파일명은 `{episodeNo}.txt`로 유지해 S3에서도 회차를 식별할 수 있습니다. `content_updated_at`은 원문 직접 수정이나 파일 교체에만 갱신되고 제목만 수정할 때는 유지됩니다.

### 회차 번호 중복

한 작품 안에서 같은 `episodeNo`를 중복 등록할 수 없습니다.

현재 중복 검사는 DB unique 제약이 아니라 `EpisodeRepository.existsByWorkIdAndEpisodeNo()` 기반 서비스 검증으로 처리합니다.

## 상태 모델

`EpisodeStatus`

| 상태 | 의미 | 전이 시점 |
| --- | --- | --- |
| `UPLOADED` | 원문 저장 완료 | `Episode.create()`로 새 회차를 만들거나 `Episode.updateContent()`로 원문을 다시 저장할 때 설정됩니다. |
| `CHUNKING` | 원문 청킹 중 | `Episode.markChunking()` 상태입니다. 청킹 Worker 또는 내부 API 연결 지점은 아직 확정되지 않았습니다. |
| `CHUNKED` | 청크 저장 완료 | `Episode.markChunked()` 상태입니다. `ManuscriptChunk` 모델 구현 후 호출 위치를 확정합니다. |
| `PREPROCESSING` | LLM 전처리 중 | `Episode.markPreprocessing()` 상태입니다. 전처리 산출물 저장 모델 확정 후 호출 위치를 정합니다. |
| `PREPROCESSED` | LLM 전처리 완료 | `Episode.markPreprocessed()` 상태입니다. 전처리 결과 저장이 끝난 뒤로 예상합니다. |
| `ANALYZING` | AI 분석 중 | `Episode.markAnalyzing()` 상태입니다. 분석 작업 상세에서 회차별 분석 단계로 표시합니다. |
| `ANALYZED` | AI 분석 완료 | `Episode.markAnalyzed()` 상태입니다. 설정 후보 또는 검수 결과 저장 완료 후로 예상합니다. |
| `FAILED` | 처리 실패 | `Episode.markFailed()` 상태입니다. 상세 실패 처리 이력은 후속 모니터링 기능에서 조회합니다. |
| `ARCHIVED` | 원문 파기됨 | 삭제 API가 S3 원문과 파생 원문 데이터를 파기한 뒤 `Episode.archive()`를 호출해 tombstone만 유지한 상태입니다. 활성 목록·중복 검사·최신 회차 계산에서는 제외합니다. |

현재 회차 업로드/수정은 `UPLOADED` 상태로 저장합니다. 이후 청킹, 전처리, 분석 단계에서 상태 전이가 연결됩니다.

청킹/전처리/분석 Worker는 progress 요청에 `episodeStatus`를 명시적으로 전달합니다. `currentStep`은 화면 표시용 자유 형식 문구이며 회차 상태 판정에 사용하지 않습니다.

신규 분석과 재분석은 모두 `AnalysisJob.episode_id`로 단일 회차를 지정합니다. 업로드 배치에 여러 회차가 있어도 회차마다 Job을 따로 생성하므로 같은 배치의 서로 다른 회차는 독립적으로 대기·성공·실패할 수 있습니다. Worker가 작업을 claim·완료·실패 처리할 때 해당 Job의 단일 대상 회차 상태만 함께 전이합니다.

상태 표시 기준:

- 분석 작업 목록과 분석 작업 카드에서는 `AnalysisJob.status`를 대표 상태로 표시합니다.
- 분석 작업 상세에서는 해당 작업에 포함된 각 회차의 `Episode.status`를 단계별 상태로 표시합니다.

## DB 모델

`episodes`

| 필드 | 설명 |
| --- | --- |
| `id` | 회차 UUID |
| `work_id` | 회차가 속한 작품 |
| `source_file_id` | 회차를 만든 `upload_files.id` FK. 직접 생성 등 출처 파일이 없는 경우 nullable |
| `episode_no` | 작품 내 회차 번호 |
| `title` | 회차 제목 |
| `content_s3_key` | 회차 원문 S3 key |
| `content_s3_version` | S3 version ID |
| `content_hash` | 원문 SHA-256 hash |
| `content_updated_at` | 현재 원문 업로드 또는 교체 시각. 제목만 수정할 때는 유지 |
| `char_count` | 공백을 제외한 원문 Unicode code point 수 |
| `status` | 회차 처리 상태 |
| `created_at` | 생성 시각 |
| `updated_at` | 수정 시각 |

## API

모든 Episode API는 `/api/v1/works/{workId}/episodes` 아래에 있고 Bearer access token이 필요합니다.

### 회차 목록 조회

```http
GET /api/v1/works/{workId}/episodes
```

처리 흐름

1. `workId + memberId`로 본인 작품을 확인합니다.
2. 회차 목록을 `episodeNo` 내림차순으로 조회합니다.
3. 원본 파일과 최신 배치/회차 분석 작업 메타데이터는 회차별 재조회하지 않고 ID 목록으로 일괄 조회합니다.
4. 목록 응답은 원문 전체를 포함하지 않는 summary 형태입니다.

완료된 분석의 `unresolvedFindingCount`는 요약에 0 이상의 `unresolvedFindingCount`가 명시되었거나 이전 형식의 `findings` 배열이 있을 때만 숫자로 반환합니다. 요약이 없거나 알 수 없는 구조이거나 JSON 파싱에 실패하면 문제 없음으로 단정하지 않고 `null`을 반환하며, 화면은 이를 `—`로 표시합니다.

### 회차 원고 업로드

```http
POST /api/v1/works/{workId}/episodes
Content-Type: multipart/form-data
```

OpenAPI `operationId`는 여러 회차를 한 번에 만들 수 있다는 의미를 반영한 `uploadEpisodes`입니다.

Parts

| part | 설명 |
| --- | --- |
| `metadata` | `EpisodeUploadRequest` JSON |
| `episodeFiles` | 회차 원고 파일 목록 |
| `settingBookFile` | 선택 설정집 파일 |

단일 회차 `EpisodeUploadRequest`

```json
{
  "uploadType": "SINGLE_EPISODE",
  "singleEpisodeNo": 159,
  "singleEpisodeTitle": "운명의 실타래"
}
```

다회차 `EpisodeUploadRequest`

```json
{
  "uploadType": "MULTI_EPISODE_SINGLE_FILE",
  "episodeConfirmations": [
    {
      "detectionOrder": 0,
      "episodeNo": 159,
      "title": "운명의 실타래"
    },
    {
      "detectionOrder": 1,
      "episodeNo": 160,
      "title": "새로운 동료"
    }
  ]
}
```

회차 API의 `uploadType`은 `EpisodeUploadType`의 세 값만 허용합니다. 단일 회차 최종 업로드는 `singleEpisodeNo`가 필수이고 `episodeConfirmations`를 보내면 거절합니다. 두 다회차 방식에서는 단일 회차 전용 필드를 보내지 않으며, `episodeConfirmations`가 필수이고 사전 감지 응답의 `detectedEpisodes`와 개수·`detectionOrder`가 일치해야 합니다.

회차 원고와 선택 설정집은 파일당 10MB, multipart 요청 전체는 25MB까지 허용합니다. `settingBookFile` part를 생략하는 것은 허용하지만, part를 명시적으로 보내고 내용이 비어 있으면 `UPLOAD_FILE_EMPTY`로 거절합니다.

서버는 최종 업로드에서도 원본 파일을 다시 파싱해 `DetectedEpisode*`를 만들고, `EpisodeUploadProcessor.processEpisodeUpload(...)`이 감지된 본문 경계에는 손대지 않은 채 사용자가 확정한 번호와 제목을 적용해 `FinalizedEpisode*`를 만듭니다. 저장 응답의 영속화된 회차 목록 필드는 `createdEpisodes`입니다. 함께 반환되는 업로드 파일 범위는 `files[].episodeStartNo`, `episodeEndNo`, `episodeCount`로 표시합니다.

`MULTI_EPISODE_SINGLE_FILE`은 업로드 원본 객체 하나를 여러 Episode가 공유하고, 각 회차의 현재 원문은 `works/{workId}/episodes/{episodeNo}/...`에 별도로 저장합니다. 공유 파일에 포함된 한 회차를 삭제하거나 수정·교체하면 대상 회차 원문을 완전히 파기하기 위해 공유 업로드 원본 전체를 삭제합니다. 형제 회차의 분리된 현재 원문과 Episode는 유지하며, `UploadFile`의 파일명·회차 범위 메타데이터는 출처 표시용으로 남기되 삭제된 `storage_url`은 비웁니다.

업로드 상세 흐름은 [Upload Episode Workflow](upload-episode-workflow.md)를 기준으로 확인합니다.

### 회차 원고 사전 감지

```http
POST /api/v1/works/{workId}/episodes/detect
Content-Type: multipart/form-data
```

Parts

| part | 설명 |
| --- | --- |
| `metadata` | `EpisodeDetectionRequest` JSON |
| `episodeFiles` | 감지할 회차 원고 파일 목록 |

```json
{
  "uploadType": "MULTI_EPISODE_SINGLE_FILE"
}
```

이 API는 영구 저장하지 않습니다. `TextDocumentReader`가 TXT·DOCX를 검증하고 텍스트를 추출한 뒤, `EpisodeFileParser`가 회차 경계와 메타데이터를 `DetectedEpisode*`로 변환해 반환합니다. 단일 회차에서는 선택적인 `singleEpisodeNo`와 `singleEpisodeTitle`을 감지 힌트로 사용할 수 있습니다.

```json
{
  "uploadType": "MULTI_EPISODE_SINGLE_FILE",
  "episodeCount": 2,
  "totalCharCount": 13120,
  "detectedEpisodes": [
    {
      "detectionOrder": 0,
      "sourceFileIndex": 0,
      "episodeNo": 159,
      "title": "운명의 실타래",
      "sourceHeading": "제 159화 운명의 실타래",
      "charCount": 6782,
      "content": "감지된 첫 회차 본문"
    },
    {
      "detectionOrder": 1,
      "sourceFileIndex": 0,
      "episodeNo": 160,
      "title": "새로운 동료",
      "sourceHeading": "제 160화 새로운 동료",
      "charCount": 6338,
      "content": "감지된 두 번째 회차 본문"
    }
  ]
}
```

`detectionOrder`는 최종 업로드의 `episodeConfirmations[].detectionOrder`와 연결되는 0부터 시작하는 순서입니다. `sourceHeading`은 원본에서 경계로 감지한 회차 제목 행을 그대로 담으며 다회차 단일 파일 외에는 `null`일 수 있습니다. `content`에는 제목 행 다음의 회차 본문만 담습니다. `charCount`는 공백을 제외한 Unicode code point 수이고 `totalCharCount`는 각 감지 회차 `charCount`의 합입니다. 다회차 단일 파일에서는 `sourceHeading`과 `content`를 함께 사용해 명시적인 heading 사이의 고정 경계를 미리 확인합니다.

### 회차 상세 조회

```http
GET /api/v1/works/{workId}/episodes/{episodeId}
```

처리 흐름

1. 본인 작품을 확인합니다.
2. `episodeId + workId`로 회차를 조회합니다.
3. S3에서 `content_s3_key`의 원문을 읽어 응답에 포함합니다.

### 회차 원문 수정

```http
PATCH /api/v1/works/{workId}/episodes/{episodeId}
```

Request

```json
{
  "episodeNo": 160,
  "title": "수정된 회차 제목",
  "content": "수정된 회차 원문"
}
```

처리 흐름

1. 본인 작품과 회차를 확인합니다.
2. 회차 번호가 바뀌면 같은 작품 안의 중복 번호를 검사합니다.
3. 회차 상태와 연결된 배치/회차 분석 작업을 확인해 `PENDING` 또는 `RUNNING`이면 `EPISODE_ANALYSIS_IN_PROGRESS`로 거절합니다.
4. 새 원문을 S3에 저장합니다.
5. 이전 원문과 업로드 원본을 스냅샷한 `episode_source_purge_requests`를 저장합니다.
6. `Episode.updateContent()`와 작품의 `latestEpisodeNo`를 갱신하고 정리 요청과 함께 커밋합니다.
7. 커밋 후 새 key만 제외하고 기존 회차 S3 prefix와 이전 업로드 원본의 모든 version·delete marker를 파기합니다.
8. 이전 `UploadFile.storage_url`, 기존 `episode_chunks`와 검토 전 후보를 정리하고 확정·무시 후보의 원문 근거를 제거합니다. 실패하면 요청을 보존해 재시도합니다.

이 API는 기존 범용 수정 계약입니다. 원고 목록 MVP에서는 회차 번호·본문 직접 편집을 노출하지 않고 아래 제목 수정과 파일 변경 API를 사용합니다.

### 회차 제목 수정

```http
PATCH /api/v1/works/{workId}/episodes/{episodeId}/title
```

```json
{
  "title": "수정된 회차 제목"
}
```

앞뒤 공백을 제거해 제목만 갱신합니다. 원문 변경 시점, 원문 메타데이터, 회차 상태와 최신 분석 결과는 유지합니다.

### 회차 원문 파일 변경

```http
PUT /api/v1/works/{workId}/episodes/{episodeId}/file
Content-Type: multipart/form-data
```

`file` part로 TXT 또는 DOCX 한 개를 받습니다. 별도 `metadata` 동의 part는 사용하지 않습니다. 분석 중인 회차는 변경할 수 없습니다. 새 `UploadBatch`와 `UploadFile`, 새 회차 원문을 저장한 뒤 새 content key를 제외한 기존 회차 S3 prefix의 모든 version·delete marker와 이전 업로드 원본을 파기하고 이전 `UploadFile.storage_url`을 비웁니다. 다회차 단일 파일에서 파생된 회차라면 공유 업로드 원본 전체가 삭제되지만 형제 회차의 분리 원문은 유지됩니다. 이어서 기존 `episode_chunks`와 검토 전 캐릭터·세계관 후보를 삭제하고, 확정·무시 후보에서는 원문 표현·인용·비교 사유와 raw AI payload를 제거합니다. 회차 번호·제목·ID와 이미 확정한 캐릭터·세계관 설정은 유지하고 원문 메타데이터, `content_updated_at`, `source_file_id`를 새 파일 기준으로 바꾸며 상태는 `UPLOADED`로 돌아갑니다. 자동 재분석이나 후속 회차 재계산은 시작하지 않습니다.

사용자가 재분석을 요청하면 새 원문으로 이 회차의 `SETTING_EXTRACTION`만 실행합니다. 이후 회차에서 축적된 현재 설정을 비교 문맥으로 사용할 수 있어 중복되거나 시간 순서가 맞지 않는 후보가 생길 수 있으며, 후보 확정 전에는 기존 설정 DB를 자동 변경하지 않습니다.

### 회차 삭제

```http
DELETE /api/v1/works/{workId}/episodes/{episodeId}
```

본인 작품과 활성 회차를 확인하고 활성 분석 작업이 없을 때만 삭제합니다. 원문 key/version/hash를 비운 `ARCHIVED` tombstone, 작품의 새 `latestEpisodeNo`, 이전 저장소 위치를 스냅샷한 정리 요청을 같은 DB 트랜잭션으로 먼저 커밋합니다. 커밋 후 회차 S3 prefix와 업로드 원본의 모든 version·delete marker, `episode_chunks`와 검토 전 캐릭터·세계관 후보를 파기하며 실패하면 요청을 보존해 재시도합니다. 다회차 단일 파일에서 파생된 회차라면 공유 업로드 원본 전체가 삭제되지만 형제 회차의 분리 원문과 Episode는 유지됩니다. 이미 확정한 설정과 이를 참조하는 Episode 행은 유지하되 확정·무시 후보의 원문 표현·인용·비교 사유와 raw AI payload를 비워 근거 원문을 더 이상 제공하지 않습니다.

## 접근 제어

- 모든 API는 먼저 `workId + memberId`로 본인 작품을 확인합니다.
- 다른 회원의 작품이면 `WORK_NOT_FOUND`를 반환합니다.
- 본인 작품 안에 없는 회차면 `EPISODE_NOT_FOUND`를 반환합니다.

## 이후 작업

- DB 레벨 `work_id + episode_no` unique 제약 도입 여부 결정
- 실제 분석 산출물 저장 도메인과 최신 유효 리포트의 미처리 항목 집계 계약 연결
