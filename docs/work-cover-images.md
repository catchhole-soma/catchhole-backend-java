# 작품 표지 이미지 (GH436)

작품 편집에서 표지 한 장을 등록·교체·삭제한다. 작품 생성 계약과 원고·설정·분석·검수 데이터는 바꾸지 않는다. 표지 변경 자체는 AI 작업이나 관계 입력 버전 갱신을 예약하지 않는다.

## API와 저장 정책

- `WorkResponse.coverImageId`: 현재 표지 UUID, 없으면 null. `coverVersion`: 독립적인 동시 수정 버전, 최초 0.
- `PATCH /api/v1/works/{workId}/cover`: multipart `metadata`(application/json)와 교체 시 `image` 파일. metadata는 `{work:{title,genre,description}, action:"REPLACE"|"REMOVE", expectedCoverVersion:0}`이다. 삭제에는 파일을 보내지 않는다. 작품 정보와 표지 참조는 한 트랜잭션으로 확정한다.
- `GET /api/v1/works/{workId}/cover/{imageId}`: 소유자의 현재 READY 표지만 PNG 바이너리로 제공한다. `Cache-Control: no-store, private`, `X-Content-Type-Options: nosniff`. 공개 S3 URL은 응답하지 않는다.
- 기존 인증과 작품 소유권 검사를 재사용한다. 비로그인 401, 타인·이전 표지 404, 버전 충돌 409, 파일 검증 오류 400. PURGING 작품은 변경할 수 없다.
- 입력은 JPG·PNG·WebP, 5MiB 이하, 각 변 1~4096px. 파일명과 MIME 선언을 신뢰하지 않고 ImageIO로 실제 형식·크기·전체 픽셀을 확인한다. 최대 긴 변 1200px PNG로 정규화하고 메타데이터를 제거한다. WebP 읽기는 TwelveMonkeys `imageio-webp:3.12.0`을 사용한다. 애니메이션 편집은 지원하지 않는다.
- 기존 `ObjectStorage`/비공개 버킷을 재사용한다. 경로는 `works/{workId}/cover-images/{imageId}/cover.png`; 요청마다 UUID가 달라 기존 파일을 덮어쓰지 않는다.

## 실패·동시성·정리

1. 소유권·버전·파일을 검사한 뒤 짧은 작품 잠금 트랜잭션으로 UPLOADING 예약을 만든다. 작품당 추적 이미지 10개를 넘으면 재시도를 안내한다.
2. 파일 I/O는 DB 트랜잭션 밖에서 실행한다.
3. 작품을 다시 잠그고 현재 버전/삭제 상태를 검사한다. OSIV에 남은 이전 엔티티는 clear 후 다시 읽는다. 새 READY·작품 정보·표지 참조·이전 DELETING을 함께 커밋한다.
4. 커밋 뒤 이전 prefix의 모든 S3 버전과 delete marker를 기존 `purgePrefixes`로 제거한다. 정리 실패로 저장 성공을 실패 처리하지 않는다. 업로드/DB 확정 실패는 새 예약만 정리하고 이전 표지는 보존한다.
5. 10초마다 최대 20개 DELETING 또는 1시간 지난 UPLOADING을 재시도한다. 실패한 항목은 마지막 시도 시각 기준 뒤로 보내 기아를 줄인다. 만료 예약 회수 뒤 늦게 끝난 업로드도 고유 prefix를 다시 비운다.
6. 작품 영구 삭제는 UPLOADING 동안 대기하고, 기존 `works/{workId}/` 전체 저장소 정리와 FK 그래프 삭제에 표지를 포함한다. `works.cover_image_id`를 먼저 비워 순환 참조를 해소한다.

클라이언트가 응답을 잃었을 때는 서버 커밋 여부가 불명확할 수 있다. 목록을 다시 조회하여 현재 표지를 확인한다. 버전 검사는 오래된 재시도가 새 표지를 덮어쓰는 것을 막는다.

## 운영 적용

- 백엔드를 먼저 배포한다. Flyway V126이 `works` 컬럼과 `work_cover_images`를 추가한다. 기존 작품은 null/0으로 기본 표지를 유지한다. 이후 프론트를 배포한다.
- 기존 비공개 버킷의 `works/*` get/put/list/delete 및 version/delete-marker 삭제 권한을 재사용한다. 별도 공개 ACL, CDN, AI 키는 필요하지 않다.
- `WORK_COVER_CLEANUP_SCHEDULING_ENABLED` 기본 true, `WORK_COVER_CLEANUP_FIXED_DELAY_MS` 기본 10000. 스케줄러를 끄면 실패/중단 파일 정리가 지연되므로 운영에서는 켜둔다.
- 저장소 장애 로그와 DELETING/오래된 UPLOADING 누적을 확인한다. 백엔드 롤백 시 데이터를 보존하고 새 프론트의 표지 저장 경로도 함께 되돌린다. V126을 수동 삭제하지 않는다.

## 검증

`WorkCoverIntegrationTest`는 실제 Spring 인증·MVC·JPA 경로에서 형식 위장, 크기/해상도/손상, 세 포맷, 소유권, DB 롤백, 저장소 실패, 정리 재시도, 버전 경합, 만료 예약을 검사한다. 저장소만 mock이며 I/O 중 DB 트랜잭션이 없음을 검사한다.

별도 PostgreSQL에서 V1~V126 Flyway와 JPA validate를 실행했다. 프론트 `e2e/work-cover-live.spec.ts`는 실제 로컬 서버·DB·파일 저장소와 Chromium으로 등록/새로고침/재로그인/교체/삭제/취소/파일 쓰기 실패/타인 접근을 확인한다. 프로덕션 S3 자체에 파일을 업로드하지는 않는다.
