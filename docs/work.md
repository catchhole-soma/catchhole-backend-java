# Work Domain

## 목적

Work 도메인은 로그인한 회원의 작품 작업공간을 관리합니다.

작품은 회차, 업로드 배치, 분석 작업이 연결되는 최상위 리소스이며 모든 접근 제어는 `workId + memberId` 소유권 확인에서 시작합니다.

## 핵심 결정

### 개인 리소스 모델

Work는 로그인한 회원의 개인 리소스입니다.

- 생성 시 요청 DTO에서 소유자 ID를 받지 않습니다.
- 서버가 인증 principal의 `memberId`로 `Member`를 조회해 연결합니다.
- 목록, 상세, 수정, 삭제는 모두 본인 작품만 허용합니다.
- 없는 작품과 다른 회원의 작품은 모두 `WORK_NOT_FOUND`로 응답합니다.

이렇게 처리하면 다른 회원의 작품 ID를 추측해도 리소스 존재 여부가 노출되지 않습니다.

## 상태 모델

`WorkStatus`

| 상태 | 의미 |
| --- | --- |
| `ACTIVE` | 사용 중인 작품 |
| `ARCHIVED` | 보관된 작품 |

현재 API는 삭제 시 hard delete를 수행합니다. `ARCHIVED`는 이후 보관/복구 흐름을 위한 상태입니다.

## DB 모델

`works`

| 필드 | 설명 |
| --- | --- |
| `id` | 작품 UUID |
| `member_id` | 작품 소유 회원 |
| `title` | 작품 제목 |
| `genre` | 작품 장르 |
| `description` | 작품 설명 |
| `status` | 작품 상태 |
| `latest_episode_no` | 가장 큰 회차 번호. 회차 생성/수정/삭제 시 갱신 |
| `created_at` | 생성 시각 |
| `updated_at` | 수정 시각 |

## API

모든 Work API는 Bearer access token이 필요합니다.

### 작품 생성

```http
POST /api/v1/works
```

Request

```json
{
  "title": "빛나는 검사 로맨스",
  "genre": "로맨스",
  "description": "검사 주인공의 성장과 로맨스를 다룬 웹소설입니다."
}
```

처리 흐름

1. 인증 principal에서 `memberId`를 꺼냅니다.
2. `MemberRepository.getByIdOrThrow(memberId)`로 회원을 조회합니다.
3. `Work.create(member, title, genre, description)`으로 작품을 생성합니다.
4. 초기 상태는 `ACTIVE`, `latestEpisodeNo`는 `0`입니다.

### 내 작품 목록 조회

```http
GET /api/v1/works
```

로그인한 회원의 작품을 최신 생성순으로 조회합니다.

### 내 작품 상세 조회

```http
GET /api/v1/works/{workId}
```

`workId + memberId`로 작품을 조회합니다.

작품이 없거나 타인 작품이면 `WORK_NOT_FOUND`를 반환합니다.

### 내 작품 수정

```http
PATCH /api/v1/works/{workId}
```

Request

```json
{
  "title": "수정된 작품 제목",
  "genre": "판타지",
  "description": "수정된 작품 설명입니다."
}
```

본인 작품을 조회한 뒤 `Work.updateInfo()`로 제목, 장르, 설명을 변경합니다.

### 내 작품 삭제

```http
DELETE /api/v1/works/{workId}
```

본인 작품을 조회한 뒤 삭제합니다.

현재 구현은 soft delete가 아니라 repository delete입니다.

## 다른 도메인과의 연결

- `Episode`는 `work_id`로 작품에 속합니다.
- `UploadBatch`는 `work_id`와 `member_id`를 함께 저장합니다.
- Analysis 작업은 batch 기반 설계를 사용할 때 `work_id`와 `batch_id`를 함께 검증합니다.

## 이후 작업

- 작품 보관/복구 API가 필요해지면 `ARCHIVED` 전이 정책 정의
- 작품 삭제 시 연결된 회차 원문과 업로드 원본 파일 정리 정책 정의
