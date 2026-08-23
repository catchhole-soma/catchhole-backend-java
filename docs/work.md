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

`WorkLifecycleStatus`는 `ACTIVE`, `PURGING` 두 상태를 가집니다. 영구 삭제 요청이 접수되면 같은 트랜잭션에서 `PURGING`으로 전환하며, 이후 작품 수정·원고 업로드·설정 변경·새 분석 요청을 거절합니다. 조회 API는 삭제 진행 상태를 화면에 표시할 수 있도록 작품이 실제로 지워질 때까지 응답합니다.

## 작품 선택·등록 MVP 계약

- 작품 목록은 로그인한 회원의 작품만 최신 생성순으로 반환합니다.
- 목록 카드에는 `id`, `title`, `genre`, `latestEpisodeNo`를 사용합니다.
- `latestEpisodeNo`가 `0`인 작품도 정상 작품이며 프론트에서는 `등록된 회차 없음`으로 표시합니다.
- 작품 등록은 회차·설정집 업로드와 분리합니다. 제목과 장르만으로 먼저 작품을 만들 수 있습니다.
- 제목은 공백일 수 없고 100자 이하여야 합니다.
- 장르는 필수이며 `WorkGenre` enum에 정의된 `판타지`, `로맨스`, `추리`, `코미디`, `SF`, `스포츠`, `호러`, `무협`, `일상`, `기타` 중 하나여야 합니다.
- 작품 설명은 선택값이며 작품 목록에 한 줄로 표시할 50자 이하의 짧은 소개입니다.
- 작품 설명이 `null`, 빈 문자열 또는 공백뿐이면 저장 시 `null`로 정규화합니다.
- 필수값·길이 검증 실패는 `REQUEST_VALIDATION_FAILED`와 `error.details[]`의 필드명·메시지로 반환합니다.
- 빈 문자열이나 지원하지 않는 문자열처럼 `WorkGenre`로 역직렬화할 수 없는 장르는 `REQUEST_INVALID_ARGUMENT`로 반환합니다.

## DB 모델

`works`

| 필드 | 설명 |
| --- | --- |
| `id` | 작품 UUID |
| `member_id` | 작품 소유 회원 |
| `title` | 작품 제목 |
| `genre` | 작품 장르. `WorkGenre` enum 상수명을 `VARCHAR(50) NOT NULL`로 저장 |
| `description` | 최대 50자의 작품 설명 |
| `latest_episode_no` | 가장 큰 회차 번호. 회차 생성/수정/삭제 시 갱신 |
| `lifecycle_status` | `ACTIVE` 또는 영구 삭제 중인 `PURGING` |
| `created_at` | 생성 시각 |
| `updated_at` | 수정 시각 |

### 장르 표현과 저장

| enum·DB 값 | API 값 |
| --- | --- |
| `FANTASY` | `판타지` |
| `ROMANCE` | `로맨스` |
| `MYSTERY` | `추리` |
| `COMEDY` | `코미디` |
| `SF` | `SF` |
| `SPORTS` | `스포츠` |
| `HORROR` | `호러` |
| `MARTIAL_ARTS` | `무협` |
| `SLICE_OF_LIFE` | `일상` |
| `ETC` | `기타` |

Java와 DB는 다른 도메인 enum과 동일하게 enum 상수명을 사용합니다. JSON만 `@JsonValue`·`@JsonCreator`로 한글 값을 주고받습니다. V4 migration은 기존 한글 값을 enum 상수명으로 변환하고, `NULL`이나 지원 목록 밖의 테스트 값은 `ETC`로 정규화한 뒤 `NOT NULL`과 허용값 `CHECK`를 적용합니다. 기존 작품 설명은 앞 50자까지 보존해 `VARCHAR(50)`로 변경합니다.

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
  "description": "검사 주인공의 성장 로맨스"
}
```

처리 흐름

1. 인증 principal에서 `memberId`를 꺼냅니다.
2. `MemberRepository.getByIdOrThrow(memberId)`로 회원을 조회합니다.
3. `Work.create(member, title, genre, description)`으로 작품을 생성합니다.
4. `latestEpisodeNo`는 `0`으로 초기화합니다.

생성 성공 후 프론트는 응답의 `id`를 사용해 `/dashboard?workId={id}&nav=manuscripts`로 이동합니다.

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
  "description": "수정된 작품의 짧은 소개"
}
```

본인 작품을 조회한 뒤 `Work.updateInfo()`로 제목, 장르, 설명을 변경합니다.

### 내 작품 삭제

```http
DELETE /api/v1/works/{workId}
```

Request

```json
{"confirmation":"영구 삭제"}
```

정확한 확인 문구를 검증한 뒤 비동기 삭제 요청을 만들고 `202 Accepted`로 응답합니다. 처리 상태·재시도와 실제 삭제 순서는 [원고 처리 안내와 작품 영구 삭제](manuscript-processing-and-work-purge.md)를 기준으로 합니다.

```http
GET /api/v1/works/{workId}/purge-request
GET /api/v1/works/purge-requests/{requestId}
POST /api/v1/works/purge-requests/{requestId}/retry
```

작품 또는 요청 ID 상태 조회와 실패 요청 재시도는 요청 회원 본인에게만 허용합니다. 다른 회원의 요청은 존재 여부를 노출하지 않고 `WORK_PURGE_NOT_FOUND`로 응답하며, `FAILED`·`PARTIAL_FAILED`가 아닌 요청의 재시도는 `WORK_PURGE_RETRY_NOT_ALLOWED`로 거절합니다.

## 다른 도메인과의 연결

- `Episode`는 `work_id`로 작품에 속합니다.
- `UploadBatch`는 `work_id`와 `member_id`를 함께 저장합니다.
- Analysis 작업은 batch 기반 설계를 사용할 때 `work_id`와 `batch_id`를 함께 검증합니다.
- `WorldSetting`은 `work_id + category + normalized_subject_name`으로 작품 안의 확정 세계관 대상을 구분합니다.
- `WorldSettingCandidate`는 `work_id`와 회차·분석 작업을 함께 연결하며, 조회·확정·제외 전에 항상 Work 소유권을 먼저 검증합니다.

## 이후 작업

- 작품 보관/복구 API가 필요해지면 영구 삭제와 분리된 `ARCHIVED` 전이 정책 정의
