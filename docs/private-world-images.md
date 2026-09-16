# 개인 세계관 이미지 — GH194

번호 안내: 2026-09-16 main 통합으로 미배포 GH194 migration을 V55~V60에서 **V57~V62**로 이동했다. 아래 계약은 최종 번호로 표기하며, 이전 검증 실행 당시에는 각 번호가 2씩 작았다. SQL 내용과 기존 사용자 선택은 보존했다.

세계관 대상의 대표 이미지 선택창에서 공용 도감 또는 작품별 `내 이미지`를 선택한다. 개인 원본·썸네일·파일명은 **브라우저에서 암호화한 다음** 서버에 전송한다. 서버에 복호화 키를 저장하지 않는다. 원고 업로드·AI 분석 경로는 변경하지 않았다.

## 보호 범위와 한계

- 정상 배포된 클라이언트에서 업로드한 이미지에 대해 DB/S3/백업만 가진 운영자는 이미지·파일명을 복호화할 수 없다. TLS와 S3 서버 측 암호화만으로 달성하는 보호와 다르다.
- 서비스 운영자가 전달하는 JavaScript를 악의적으로 바꾸거나 XSS로 브라우저에 접근하는 공격까지 막는 구조는 아니다. 작가의 기기, 확장 프로그램, 복구키 파일 유출도 별도 위협이다. 사용자에게 절대적인 운영자 접근 불가능을 약속하지 않는다.
- 회원/작품/이미지 UUID, 선택 관계, 등록 시각, 암호문 크기는 서버에 남는다. 키 검증용 암호문도 저장하지만 비밀번호나 키가 아니다.
- 이미지가 아닌 기존 원고, DB의 추출 설정, AI 입력은 이번 보호 범위에 포함하지 않는다. 원고 보호 변경은 사용자 요청에 따라 보류했다.

## 키와 암호문 계약

Web Crypto의 AES-256-GCM을 사용한다. 계정당 보관함 하나와 무작위 256-bit 키 하나를 생성한다. 복구키 표현은 `CHI1-` + padding 없는 base64url 43자이다. 로그인 비밀번호에서 키를 유도하지 않는다.

키는 `extractable: false`인 `CryptoKey`로 탭 메모리에서만 유지한다. 원시 키·복구키를 localStorage, sessionStorage, IndexedDB, API, 로그에 저장하지 않는다. 사용자가 복구키 파일을 직접 다운로드할 수 있으며, 보관 확인 후 보관함을 생성한다. 새로고침·로그아웃·다른 탭의 계정 변경·명시적 잠금 후 다시 입력해야 한다. 잃어버린 복구키는 운영자도 재발급할 수 없다. 키 교체/복구/기기 기억 기능은 제공하지 않는다.

각 암호화 작업에서 독립적인 `crypto.getRandomValues` 12-byte nonce를 만든다. 인증 태그는 128-bit다. 동일 파일을 다시 업로드해도 새 이미지 UUID와 nonce를 사용한다.

```text
envelope = ASCII("CHI1") || nonce[12] || AES-GCM-ciphertext || tag[16]
AAD      = UTF8(JSON.stringify(["CHI1", vaultId, imageId, part]))
part     = "check" | "metadata" | "image" | "thumbnail"
```

`check`는 imageId가 빈 문자열이며 평문이 `Catchhole private image vault v1`이다. `metadata`의 평문 JSON은 `{name, mime, width, height}`다. 체크·메타데이터 envelope는 표준 base64로 DB에 저장한다. 원본/썸네일 envelope는 octet-stream으로 저장한다. 각 envelope는 최소 33 bytes다. 서버는 header와 크기만 확인하며 GCM 태그 인증/이미지 디코딩은 브라우저에서만 가능하다.

원본은 파일 bytes를 그대로 암호화한다. PNG/JPEG/WebP signature·브라우저 디코딩을 검사하고, 8 MiB 이하·3,200만 화소 이하·가로/세로 최대 16,384를 허용한다. 썸네일은 브라우저 canvas에서 긴 변 480px, WebP quality 0.85로 만든 후 별도 암호화한다. 서버는 원본 envelope 8 MiB + 32 bytes, 썸네일 envelope 512 KiB, 작품당 50개를 제한한다. 복호화 결과 Blob URL은 컴포넌트에서 관리하고 잠금/전환/언마운트 시 해제한다. Query cache에는 암호문만 들어간다.

## 저장과 API

V59는 아래 테이블과 개인 이미지 선택 FK를 추가한다. 기존 V57/V58 및 기존 대상 선택값은 수정하지 않는다.

| 테이블 | 컬럼/역할 |
| --- | --- |
| `private_image_vaults` | client UUID PK, unique member FK, key_check, optimistic version, 생성/수정 시각 |
| `private_world_images` | client UUID PK, work FK, vault FK, encrypted_metadata, image_bytes, thumbnail_bytes, version, 생성/수정 시각 |
| `world_setting_images` | nullable private_image_id 추가. catalog/private 둘 중 하나만 선택 가능. 개인 선택 출처 `PRIVATE` |

저장 key는 `works/{workId}/private-world-images/{imageId}/image.enc`, `thumbnail.enc`이다. 기존 작품 삭제의 `works/{workId}/` 전체 버전 purge에 포함된다. 공용 도감의 SHA whitelist나 공개 경로에는 등록하지 않는다. 원본 파일명은 multipart filename으로도 보내지 않는다.

| 메서드/경로 | 동작 |
| --- | --- |
| `GET /api/v1/private-image-vault` | 현재 회원의 보관함 `{id,keyCheck}` 또는 null |
| `POST /api/v1/private-image-vault` | `{id,keyCheck}` 생성. 동일 요청 재전송은 허용, 기존 키 체크 교체는 409 |
| `GET /api/v1/works/{workId}/private-world-images` | 본인 작품 목록. page 0부터, size 기본 18/최대 50 |
| `POST /api/v1/works/{workId}/private-world-images` | multipart: metadata JSON `{id,vaultId,encryptedMetadata}`, image·thumbnail 암호문 |
| `GET .../{imageId}/image`, `/thumbnail` | 소유권 확인 후 암호문. octet-stream, `Cache-Control: no-store, private`, nosniff |
| `DELETE .../{imageId}` | 사용 중이면 409. 미사용 이미지의 모든 저장소 버전 삭제 후 DB 삭제 |
| `PATCH /api/v1/works/{workId}/world-settings/{id}/image` | `{privateImageId,version}`으로 선택. catalogId와 동시 선택은 400. 둘 다 null이면 기본 이미지 |

이미지 선택 응답은 기존 필드에 `privateImageId`, `vaultId`를 추가한다. 개인 선택은 catalogId/name이 null이고 URL이 인증된 암호문 API 경로다. `<img src>`로 직접 호출하지 않고 SDK로 암호문을 받아 복호화한다. 개인 이미지는 분류 변경 때에도 유지된다. 공용 도감 선택/기본 복귀는 개인 선택을 해제한다. 설정 내용 버전에는 영향을 주지 않는다.

업로드·삭제·선택은 작품 소유권을 확인하고 작품 잠금으로 직렬화한다. 타인 작품 및 다른 작품의 이미지 ID 접근은 404다. 이미지 선택 자체의 별도 version으로 오래된 덮어쓰기를 거절한다. 최초 이미지 row를 먼저 flush해 ID를 예약하고 저장소 쓰기가 실패하면 트랜잭션 취소 후 두 파일의 모든 버전을 정리한다. 저장소 정리 자체가 실패하면 암호문 orphan이 남을 수 있고 작품 전체 purge에서 재정리된다. 성공 응답 유실 시 목록을 확인한 뒤 재시도한다. 업로드 ID 중복은 409다.

## 배포와 검증

1. V59를 포함한 Java를 먼저 배포한다. 기존 bucket/IAM의 `works/*` 쓰기·읽기·전체 버전 삭제 권한을 사용한다. 새 공개 bucket 정책은 필요 없다.
2. 새 OpenAPI로 생성한 SDK와 Front를 배포한다. HTTPS가 필수이며 개발 localhost는 secure context 예외다.
3. 일회용 계정에서 보관함 생성 → 업로드 → 대표 이미지 저장 → 새로고침 잠금 → 올바른 복구키 복호화 → 기본 복귀/삭제를 확인한다.
4. 이 구현의 로컬 검증은 PostgreSQL migration+Hibernate validate, MockMvc 소유권/선택/크기 테스트, 실제 Chromium Web Crypto 변조/키/AAD 테스트, 실제 API를 사용하는 Front live E2E를 포함한다. 로컬 저장소는 e2e filesystem이며 운영 S3 업로드/운영 DB 변경은 수행하지 않는다.

참고: [AWS client-side encryption](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingClientSideEncryption.html), [Web Crypto AES-GCM](https://developer.mozilla.org/en-US/docs/Web/API/AesGcmParams), [OWASP 암호 저장 가이드](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html).

2026-09-16: Java 전체 1,166개 중 1,101 통과/기존 조건부 65 건너뜀. 로컬 PostgreSQL V57·V58·V59 성공과 Hibernate validate 기동, Front 실제 연결 검증을 완료했다. 운영 DB·원고·S3의 개인 파일은 변경하지 않았다.

## 캐릭터와 함께 사용

같은 작품의 캐릭터 이미지 선택에서도 이 보관함·업로드·조회 API와 CHI1 계약을 재사용한다. 서버 경로/저장 키는 호환성을 위해 유지한다. 개인 이미지 삭제는 세계관과 캐릭터 선택을 모두 확인한다. 자세한 선택 우선순위와 V60은 [캐릭터 이미지](character-images.md)를 참고한다.
