# Upload Domain

## 목적

Upload 도메인은 회차 업로드 요청의 batch와 개별 파일 메타데이터를 추적합니다.

회차 업로드는 `EpisodeController`와 `EpisodeUploadProcessor`가 처리합니다. 설정집 원본은 회차 업로드의 선택 첨부로도 받을 수 있고, `SettingBookController`를 통해 독립적으로 업로드·조회·원문 수정·soft delete할 수도 있습니다.

## 핵심 결정

### Batch 단위 추적

한 번의 업로드 요청은 하나의 `UploadBatch`입니다.

batch에는 작품, 회원, 업로드 방식, 파일 개수, 전체 처리 상태를 기록합니다. 분석 생성 요청은 `batchId`로 현재 대상 회차를 찾지만, 실제 실행은 회차마다 별도 `AnalysisJob`을 생성합니다. 따라서 `UploadBatch`는 업로드 출처 묶음이지 분석 실패·성공의 원자 단위가 아닙니다.

### File 단위 추적

업로드 요청에 포함된 각 원본 파일은 `UploadFile`로 저장합니다.

- 회차 원고 파일은 `fileRole=EPISODE`
- 설정집 파일은 `fileRole=SETTING_BOOK`

원본 파일 자체는 S3에 저장하고, DB에는 `storage_url`, 원본 파일명, mime type, size, 파싱 결과를 저장합니다.

TXT·DOCX 원본은 파일당 10MB까지 허용하고 multipart 요청 전체는 25MB로 제한합니다. DOCX는 `word/document.xml` 이전 엔트리를 포함한 실제 압축 해제량을 누적해 20MB를 초과하거나 본문 탐색 중 ZIP 엔트리가 256개를 초과하면 `UPLOAD_FILE_TOO_LARGE`로 거절합니다. 선택 part를 생략하는 것과 명시적으로 빈 파일을 첨부하는 것을 구분하며, 빈 파일은 `UPLOAD_FILE_EMPTY`로 거절합니다. multipart 파일의 원본 파일명은 필수이며, 누락되거나 공백이면 서버가 확장자를 추정하지 않고 `UPLOAD_FILE_TYPE_NOT_SUPPORTED`로 거절합니다.

## 상태 모델

`UploadType`

| 유형 | 의미 |
| --- | --- |
| `SINGLE_EPISODE` | 단일 파일이 단일 회차 |
| `MULTI_EPISODE_SINGLE_FILE` | 단일 파일 안에 여러 회차 |
| `MULTI_EPISODE_MULTI_FILE` | 여러 파일이 각각 회차 |
| `SETTING_BOOK` | 설정집 원본 단독 업로드 |
| `INITIAL_IMPORT` | 초기 일괄 가져오기용 예약 값. 회차 API에서는 노출하지 않음 |

회차 감지·최종 업로드 API는 공용 저장 enum과 분리된 `EpisodeUploadType`을 사용하며
`SINGLE_EPISODE`, `MULTI_EPISODE_SINGLE_FILE`, `MULTI_EPISODE_MULTI_FILE` 세 값만 허용합니다.
두 다회차 방식에서 `singleEpisodeNo` 또는 `singleEpisodeTitle`을 보내면 요청을 거절합니다.

`UploadStatus`

| 상태 | 의미 | 전이 시점 |
| --- | --- | --- |
| `PENDING` | batch 생성 직후 | `UploadBatch.create()`로 생성될 때 기본값으로 설정됩니다. 현재 회차 업로드 흐름에서는 파일 파싱과 중복 회차 검증을 통과한 뒤 batch row가 저장됩니다. |
| `PROCESSING` | 파싱/저장 처리 중 | `EpisodeUploadProcessor`가 batch를 저장한 직후 `UploadBatch.startProcessing()`으로 전환합니다. |
| `COMPLETED` | 처리 완료 | 업로드 원본 파일, 회차 원문, `Episode`, 선택 설정집 파일 저장이 끝난 뒤 `UploadBatch.complete()`로 전환합니다. |
| `FAILED` | 처리 실패 | `UploadBatch.fail()` 상태입니다. 현재 동기 업로드 흐름에서는 예외 발생 시 같은 트랜잭션이 rollback되어 `FAILED`가 영속화되지 않을 수 있습니다. |

정책 미확정 TODO:

- 현재 동기 업로드는 실패 시 `UploadBatch` row도 함께 rollback될 수 있으므로, 모니터링 이력을 남길 필요가 있으면 batch 선커밋, 별도 트랜잭션, 비동기 처리 중 적절한 방식을 검토합니다.
- 파싱/중복 검증처럼 batch 생성 전 실패한 요청을 실패 응답만으로 끝낼지, `FAILED` batch로 기록할지 검토합니다.
- batch 생성 후 S3 저장, `UploadFile`, `Episode` 저장 중 실패한 경우 어떤 단위까지 rollback하고 어떤 단위는 `FAILED`로 남길지 검토합니다.
- 실패 처리 이력은 후속 모니터링 기능에서 기록/조회합니다. `UploadBatch`와 `UploadFile`은 현재 처리 상태를 표현하는 역할에 집중합니다.

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
| `member_id` | 회원 |
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
| `content_storage_url` | 설정집에서 추출한 현재 편집용 텍스트 S3 위치. 회차 원본은 null |
| `file_size` | 파일 크기 |
| `detected_episode_start_no` | 최종 생성한 시작 회차 번호. API/Java 이름은 `episodeStartNo` |
| `detected_episode_end_no` | 최종 생성한 마지막 회차 번호. API/Java 이름은 `episodeEndNo` |
| `detected_episode_count` | 파일에서 최종 생성한 회차 수. API/Java 이름은 `episodeCount` |
| `parse_status` | 파일 파싱 상태 |
| `archived_at` | 설정집 soft delete 시각. 활성 원본은 null |
| `created_at` | 생성 시각 |
| `updated_at` | 수정 시각 |

회차 원본은 `UploadFile.markEpisodesParsed(episodeStartNo, episodeEndNo, episodeCount)`로 세 범위 값과 `PARSED` 상태를 함께 기록합니다. 회차 범위가 없는 설정집은 범위 값을 비워 둔 채 `UploadFile.markParsed()`로 상태만 변경합니다. `UploadFileResponse`도 legacy DB 컬럼명이 아니라 `episodeStartNo`, `episodeEndNo`, `episodeCount`를 사용합니다.

## 업로드 파일 저장 key

원본 업로드 파일은 다음 key 형식으로 저장합니다.

```text
upload-batches/{batchId}/{randomUUID}-{originalFilename}
```

설정집의 조회·수정용 텍스트는 원본과 분리해 다음 고정 key로 저장합니다.

```text
works/{workId}/setting-books/{settingBookId}/{normalizedOriginalBasename}.txt
```

편집본 파일명은 원본 파일명의 경로와 확장자를 제거하고 Unicode NFC로 정규화한 뒤 `.txt`를 붙입니다. 설정집 수정은 같은 key를 PUT해 현재 텍스트만 교체하며, 업로드 원본 key와 원본 MIME·크기는 바꾸지 않습니다.

응답에는 현재 `ObjectStorageService.toStorageUrl()`을 통해 `s3://{key}` 형태로 내려갑니다.

## 감지와 최종 업로드 계약

회차 파일은 다음 생명주기 이름으로 구분합니다.

```text
source
→ detected
→ confirmation
→ finalized
→ created/saved
```

- `source`: multipart의 원본 `episodeFiles`
- `detected`: `EpisodeFileParser`가 만든 `DetectedEpisode`/`DetectedEpisodeFile`
- `confirmation`: 사용자가 `detectedEpisodes`를 보고 수정해 보낸 번호·제목
- `finalized`: 감지 본문 경계와 사용자 확정 메타데이터를 합친 `FinalizedEpisode`/`FinalizedEpisodeFile`
- `created`/`saved`: S3와 DB에 영속화된 회차

두 API 모두 JSON part 이름은 `metadata`, 파일 part 이름은 `episodeFiles`입니다.

| API | `metadata` DTO | 핵심 계약 |
| --- | --- | --- |
| `POST /api/v1/works/{workId}/episodes/detect` | `EpisodeDetectionRequest` | 저장 없이 `detectedEpisodes`와 각 항목의 `detectionOrder`를 반환 |
| `POST /api/v1/works/{workId}/episodes` | `EpisodeUploadRequest` | 단일 회차는 명시적 번호, 다회차는 필수 `episodeConfirmations[].detectionOrder`를 검증하고 `createdEpisodes` 반환 |

최종 업로드의 OpenAPI `operationId`는 `uploadEpisodes`입니다. `TextDocumentReader`는 TXT·DOCX 검증과 텍스트 추출을 담당합니다. `EpisodeFileParser`는 원본 파일과 단일 회차 감지 힌트를 `DetectedEpisode*`로 변환하며 confirmation을 알지 못합니다. `EpisodeUploadProcessor.processEpisodeUpload(...)`이 confirmation 검증·적용과 `FinalizedEpisode*` 조립·저장을 조율합니다. 요청 내부 또는 기존 활성 회차와 번호가 중복되면 `EPISODE_UPLOAD_DUPLICATED`를 반환합니다.

감지 응답의 `sourceHeading`은 원본에서 경계로 감지한 회차 제목 행을 그대로 담고, `content`에는 그 제목 행 다음의 회차 본문만 담습니다. 제목 행을 본문 경계로 분리하는 다회차 단일 파일 외에는 `sourceHeading`이 `null`일 수 있습니다. 감지 응답과 저장된 Episode의 `charCount`는 공백 문자를 제외한 Unicode code point 수입니다. 감지 응답의 `totalCharCount`는 `detectedEpisodes[].charCount`의 합입니다.

## 파싱 규칙

### `SINGLE_EPISODE`

- `episodeFiles`는 정확히 1개여야 합니다.
- 감지 API에서는 `singleEpisodeNo`가 있으면 사용하고, 없으면 파일명 또는 본문의 명시적인 heading에서 감지합니다.
- 최종 업로드 API에서는 `singleEpisodeNo`가 필수이고 `episodeConfirmations`를 보내면 거절합니다.
- 파일명과 본문에서 서로 다른 번호를 감지하면 `UPLOAD_EPISODE_NO_CONFLICT`로 거절합니다.
- 제목은 요청의 `singleEpisodeTitle` 또는 같은 번호의 명시적인 heading 제목만 사용합니다. 둘 다 없으면 `null`이며 파일명을 제목으로 대체하지 않습니다.

### `MULTI_EPISODE_MULTI_FILE`

- TXT 파일을 두 개 이상 전달해야 하며 DOCX는 지원하지 않습니다.
- 각 파일에서 파일명 또는 내용의 회차 번호를 감지합니다.
- 지원 패턴은 `1화`, `제 1화`, `1회`, `1편`, `1장`, `EP 1`, `Episode 1`, `Chapter 1` 계열입니다.
- 각 파일은 하나의 회차로 저장되며, 한 파일에서 heading이 둘 이상 감지되면 거절합니다.
- 최종 업로드에는 감지 결과 전체와 대응하는 `episodeConfirmations`가 반드시 필요합니다.

### `MULTI_EPISODE_SINGLE_FILE`

- `episodeFiles`는 정확히 1개여야 합니다.
- 파일 본문에서 회차 heading을 찾고 heading 사이의 본문을 개별 회차로 분리합니다.
- 각 감지 결과의 `sourceHeading`에는 경계로 사용한 heading 문자열을 원본 그대로 보존하고, `content`에는 heading 다음 본문만 담습니다.
- 회차 heading은 두 개 이상이어야 하고 번호가 엄격한 오름차순이어야 합니다.
- 첫 heading 앞에 본문이 있거나 heading 사이에 빈 본문이 생기면 업로드를 실패 처리합니다.
- 최종 업로드에서는 필수 `episodeConfirmations`의 개수와 각 `detectionOrder`를 감지 순서와 대조한 뒤 번호·제목만 적용하고 본문 경계는 유지합니다.

## 설정집 원본 API

모든 API는 `/api/v1/works/{workId}/setting-books` 아래에 있고 Bearer access token이 필요합니다.

| 메서드·경로 | 동작 |
| --- | --- |
| `GET /api/v1/works/{workId}/setting-books` | `archived_at IS NULL`인 원본을 최근 업로드 순으로 조회 |
| `POST /api/v1/works/{workId}/setting-books` | TXT 또는 DOCX 원본 한 개를 별도 `SETTING_BOOK` batch로 저장 |
| `GET /api/v1/works/{workId}/setting-books/{settingBookId}` | 편집용 현재 텍스트와 원본 MIME·크기 메타데이터를 반환 |
| `PATCH /api/v1/works/{workId}/setting-books/{settingBookId}` | 작품·설정집·원본 파일명 기반의 고정 key에 전체 원문을 PUT |
| `DELETE /api/v1/works/{workId}/setting-books/{settingBookId}` | `archived_at`만 기록하는 soft delete |

같은 원본 파일명도 덮어쓰지 않고 매 업로드마다 새 설정집 항목과 고유 원본 객체로 누적합니다. 각 설정집은 업로드 원본과 추출된 편집용 UTF-8 텍스트를 분리하며, TXT와 DOCX 모두 화면에서는 편집용 텍스트를 수정합니다. DOCX 바이너리 원본은 변경하지 않습니다. 수정할 때는 동일한 `works/{workId}/setting-books/{settingBookId}/{normalizedOriginalBasename}.txt` key를 교체하므로 수정 횟수만큼 새 key가 생기지 않습니다. 원본 파일명, MIME 타입, 파일 크기와 최초 업로드 시각은 목록 표시값으로 유지합니다. soft delete한 DB row와 저장 객체는 물리 삭제하지 않습니다. 설정집 업로드와 수정은 분석 작업을 생성하지 않습니다.

운영 S3 버킷에 Versioning이 활성화되어 있다면 동일 key PUT도 과거 version을 보관하므로, 보관 기한은 인프라 Lifecycle 정책으로 제한합니다.

## 이후 작업

- 독립적인 업로드 batch 조회 API 필요 여부 결정
- 모니터링 이력을 남길 트랜잭션 경계와 `UploadBatch.fail()`/`UploadFile.markFailed()` 호출 흐름 검토
- 텍스트 붙여넣기 업로드(`TEXT_PASTE`) 지원 시 저장/파싱 규칙 추가
