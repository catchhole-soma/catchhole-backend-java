# Upload Domain

## 목적

Upload 도메인은 회차 업로드 요청의 batch와 개별 파일 메타데이터를 추적합니다.

현재 독립된 UploadController는 없고, `EpisodeController`의 회차 업로드 API에서 `EpisodeUploadProcessor`가 `UploadBatch`와 `UploadFile`을 생성합니다.

## 핵심 결정

### Batch 단위 추적

한 번의 업로드 요청은 하나의 `UploadBatch`입니다.

batch에는 작품, 업로드한 회원, 업로드 방식, 파일 개수, 전체 처리 상태를 기록합니다. 이후 분석 작업은 `batchId`를 기준으로 업로드 대상 회차들을 찾을 수 있습니다.

### File 단위 추적

업로드 요청에 포함된 각 원본 파일은 `UploadFile`로 저장합니다.

- 회차 원고 파일은 `fileRole=EPISODE`
- 설정집 파일은 `fileRole=SETTING_BOOK`

원본 파일 자체는 S3에 저장하고, DB에는 `storage_url`, 원본 파일명, mime type, size, 파싱 결과를 저장합니다.

## 상태 모델

`UploadType`

| 유형 | 의미 |
| --- | --- |
| `SINGLE_EPISODE` | 단일 파일이 단일 회차 |
| `MULTI_EPISODE_SINGLE_FILE` | 단일 파일 안에 여러 회차 |
| `MULTI_EPISODE_MULTI_FILE` | 여러 파일이 각각 회차 |
| `INITIAL_IMPORT` | 초기 일괄 가져오기. 현재 parser에서는 미지원 |

`UploadStatus`

| 상태 | 의미 |
| --- | --- |
| `PENDING` | batch 생성 직후 |
| `PROCESSING` | 파싱/저장 처리 중 |
| `COMPLETED` | 처리 완료 |
| `FAILED` | 처리 실패 |

`UploadFileRole`

| 역할 | 의미 |
| --- | --- |
| `EPISODE` | 회차 원고 파일 |
| `SETTING_BOOK` | 설정집 파일 |

`UploadFileParseStatus`

| 상태 | 의미 |
| --- | --- |
| `PENDING` | 파일 row 생성 직후 |
| `PARSED` | 회차 번호/개수 파싱 완료 |
| `FAILED` | 파싱 실패 |

## DB 모델

`upload_batches`

| 필드 | 설명 |
| --- | --- |
| `id` | 업로드 batch UUID |
| `work_id` | 대상 작품 |
| `uploaded_by_id` | 업로드한 회원 |
| `upload_type` | 업로드 방식 |
| `source_type` | 업로드 소스. 현재 회차 업로드는 `FILE` |
| `status` | batch 처리 상태 |
| `file_count` | 요청에 포함된 파일 수 |
| `completed_at` | 완료 또는 실패 시각 |
| `created_at` | 생성 시각 |
| `updated_at` | 수정 시각 |

`upload_files`

| 필드 | 설명 |
| --- | --- |
| `id` | 업로드 파일 UUID |
| `batch_id` | 소속 batch |
| `file_role` | 파일 역할 |
| `original_filename` | 원본 파일명 |
| `mime_type` | MIME type |
| `storage_url` | 원본 파일 S3 위치 |
| `file_size` | 파일 크기 |
| `detected_episode_start_no` | 감지된 시작 회차 |
| `detected_episode_end_no` | 감지된 끝 회차 |
| `detected_episode_count` | 감지된 회차 수 |
| `parse_status` | 파일 파싱 상태 |
| `created_at` | 생성 시각 |
| `updated_at` | 수정 시각 |

## 업로드 파일 저장 key

원본 업로드 파일은 다음 key 형식으로 저장합니다.

```text
upload-batches/{batchId}/{randomUUID}-{originalFilename}
```

응답에는 현재 `ObjectStorageService.toStorageUrl()`을 통해 `s3://{key}` 형태로 내려갑니다.

## 파싱 규칙

### `SINGLE_EPISODE`

- `episodeFiles`는 정확히 1개여야 합니다.
- `episodeNo`는 필수입니다.
- 제목이 없으면 파일명에서 확장자를 제거해 제목으로 사용합니다.

### `MULTI_EPISODE_MULTI_FILE`

- 각 파일에서 파일명 또는 내용의 회차 번호를 감지합니다.
- 지원 패턴은 `1화`, `제 1화`, `1회`, `EP 1`, `Episode 1` 계열입니다.
- 각 파일은 하나의 회차로 저장됩니다.

### `MULTI_EPISODE_SINGLE_FILE`

- `episodeFiles`는 정확히 1개여야 합니다.
- 파일 본문에서 회차 heading을 찾고 heading 사이의 본문을 개별 회차로 분리합니다.
- 회차 heading이 없거나 빈 본문이 생기면 업로드를 실패 처리합니다.

### `INITIAL_IMPORT`

현재 parser에서는 `UPLOAD_TYPE_NOT_SUPPORTED`를 반환합니다.

## 이후 작업

- 독립적인 업로드 batch 조회 API 필요 여부 결정
- 실패 중간 상태에서 `UploadBatch.fail()`과 `UploadFile.markFailed()`를 호출하는 보상 흐름 정의
- 텍스트 붙여넣기 업로드(`TEXT_PASTE`) 지원 시 저장/파싱 규칙 추가
