# Episode Domain

## 목적

Episode 도메인은 작품에 속한 회차 원고의 메타데이터와 원문 저장 위치를 관리합니다.

회차 원문 전문은 DB에 직접 저장하지 않고 S3에 저장합니다. DB에는 원문 key, version, hash, 글자 수를 기록합니다.

## 핵심 결정

### 원문 저장

회차 원문 저장 key는 현재 다음 형식을 사용합니다.

```text
works/{workId}/episodes/{episodeNo}.txt
```

저장 후 `episodes`에는 다음 메타데이터만 남깁니다.

- `content_s3_key`
- `content_s3_version`
- `content_hash`
- `char_count`

이 구조는 분석 도메인이 원문을 DB에서 복사하지 않고 S3 저장 구조를 재사용할 수 있게 합니다.

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
| `ANALYZING` | AI 분석 중 | `Episode.markAnalyzing()` 상태입니다. `AnalysisJob` 진행 단계와 Episode 단위 상태 동기화 정책이 필요합니다. |
| `ANALYZED` | AI 분석 완료 | `Episode.markAnalyzed()` 상태입니다. 설정 후보 또는 검수 결과 저장 완료 후로 예상합니다. |
| `FAILED` | 처리 실패 | `Episode.markFailed()` 상태입니다. 실패 사유 저장 위치는 아직 확정되지 않았습니다. |
| `ARCHIVED` | 보관됨 | `Episode.archive()` 상태입니다. 현재 삭제 API는 hard delete이며 보관/복구 API는 아직 없습니다. |

현재 회차 업로드/수정은 `UPLOADED` 상태로 저장합니다. 이후 청킹, 전처리, 분석 단계에서 상태 전이가 연결됩니다.

정책 미확정 TODO:

- 청킹/전처리/분석 Worker가 상태를 바꿀 때 단계별 상태 변경 API로 나눌지, `EpisodeStatus`를 파라미터로 받는 단일 전이 API로 둘지 검토합니다.
- `AnalysisJob.status`와 `Episode.status`가 서로 어긋날 때 어느 상태를 우선 표시할지 결정해야 합니다.
- 회차 단위 실패 사유를 `Episode`, `AnalysisJob`, 리포트 계층에 둘지, 공통 실패 이력 테이블 분리까지 고려할지 검토합니다.
- `ARCHIVED`를 soft delete로 사용할지, 현재 hard delete 정책을 유지할지 결정해야 합니다.

## DB 모델

`episodes`

| 필드 | 설명 |
| --- | --- |
| `id` | 회차 UUID |
| `work_id` | 회차가 속한 작품 |
| `source_file_id` | 회차를 만든 업로드 파일 ID |
| `episode_no` | 작품 내 회차 번호 |
| `title` | 회차 제목 |
| `content_s3_key` | 회차 원문 S3 key |
| `content_s3_version` | S3 version ID |
| `content_hash` | 원문 SHA-256 hash |
| `char_count` | 원문 글자 수 |
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
3. 목록 응답은 원문 전체를 포함하지 않는 summary 형태입니다.

### 회차 원고 업로드

```http
POST /api/v1/works/{workId}/episodes
Content-Type: multipart/form-data
```

Parts

| part | 설명 |
| --- | --- |
| `data` | `EpisodeUploadRequest` JSON |
| `episodeFiles` | 회차 원고 파일 목록 |
| `settingBookFile` | 선택 설정집 파일 |

`EpisodeUploadRequest`

```json
{
  "uploadType": "SINGLE_EPISODE",
  "episodeNo": 159,
  "title": "운명의 실타래"
}
```

업로드 상세 흐름은 [Upload Episode Workflow](upload-episode-workflow.md)를 기준으로 확인합니다.

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
3. 새 원문을 S3에 저장합니다.
4. 기존 S3 key와 새 key가 다르면 기존 원문을 삭제합니다.
5. `Episode.updateContent()`로 번호, 제목, S3 메타데이터, 글자 수를 갱신합니다.
6. 작품의 `latestEpisodeNo`를 다시 계산합니다.

### 회차 삭제

```http
DELETE /api/v1/works/{workId}/episodes/{episodeId}
```

본인 작품과 회차를 확인한 뒤 S3 원문을 삭제하고 DB row를 삭제합니다.

삭제 후 작품의 `latestEpisodeNo`를 다시 계산합니다.

## 접근 제어

- 모든 API는 먼저 `workId + memberId`로 본인 작품을 확인합니다.
- 다른 회원의 작품이면 `WORK_NOT_FOUND`를 반환합니다.
- 본인 작품 안에 없는 회차면 `EPISODE_NOT_FOUND`를 반환합니다.

## 이후 작업

- DB 레벨 `work_id + episode_no` unique 제약 도입 여부 결정
- 회차 보관 상태(`ARCHIVED`)를 사용하는 soft delete 정책 정의
- 실제 청킹/전처리/분석 Worker 구현과 `EpisodeStatus` 세부 상태 전이 연결
