# Auth Domain

## 목적

Auth 도메인은 휴대폰 번호 소유 확인, 이메일/비밀번호 기반 회원가입, 로그인, access token 발급, refresh token 회전, 로그아웃, 현재 사용자 조회를 담당합니다.

회원 계정 자체는 `member` 도메인의 `Member` Entity를 사용하고, 인증 세션은 `auth` 도메인의 `RefreshToken` Entity로 관리합니다.

## 핵심 결정

### Token 저장 정책

- Access token은 JWT로 발급하고 응답 body에 반환합니다.
- Refresh token은 랜덤 opaque token으로 발급하고 `HttpOnly` 쿠키에 담아 전달합니다.
- Refresh token 원문은 DB에 저장하지 않습니다. `refresh_tokens.token_hash`에 SHA-256 해시만 저장합니다.
- Refresh API는 기존 refresh token을 `revoked_at`으로 폐기한 뒤 새 refresh token을 발급합니다.

이렇게 분리한 이유는 access token은 stateless 인증에 쓰고, refresh token은 서버에서 폐기 여부를 추적해야 하기 때문입니다.

### 회원 상태

로그인과 refresh 시 `Member.validateActive()`를 호출합니다.

`MemberStatus.ACTIVE`가 아니면 `MEMBER_INACTIVE`로 인증 흐름을 중단합니다.

### 휴대폰 번호 소유 확인

- 이 기능은 PASS처럼 실명·CI/DI를 확인하는 본인인증이 아니라 인증 시점에 해당 번호로 SMS를 수신할 수 있는지 확인합니다.
- 운영 발송은 SOLAPI를 사용합니다. local은 `SMS_PROVIDER`가 없거나 `fake`이면 인증번호 `123456`을 쓰는 Fake provider를 사용하고, `solapi`이면 실제 SMS를 발송합니다. test/e2e는 항상 Fake provider를 사용하며 `prod`에서 Fake provider를 선택하면 애플리케이션 시작이 실패합니다.
- 인증번호와 가입 토큰, 호출 제한은 Redis에 TTL로 저장합니다. Redis 재시작으로 진행 중 인증이 초기화되는 것은 MVP 운영 제약입니다.
- 인증번호는 원문 대신 `PHONE_VERIFICATION_HASH_SECRET` 기반 HMAC-SHA256으로 저장합니다. 전화번호와 IP는 Redis key와 로그에 원문을 쓰지 않고 같은 비밀키 기반 HMAC 식별자를 사용합니다.
- SOLAPI 요청은 timeout이나 5xx에서도 자동 재시도하지 않습니다. 실제 발송 성공 여부를 알 수 없는 상황에서 SMS와 비용이 중복되는 것을 막기 위함입니다.
- Redis에서 rate limit과 인증 흐름을 먼저 기록한 뒤 SMS를 발송합니다. Redis 장애 시에는 SMS를 보내지 않는 fail-closed 정책입니다.

## 상태/역할 모델

`MemberStatus`

| 상태 | 의미 |
| --- | --- |
| `ACTIVE` | 사용 가능한 회원 |
| `SUSPENDED` | 정지된 회원 |
| `PURGING` | 탈퇴 접수 후 인증을 차단하고 물리 파기를 진행 중인 회원 |
| `DELETED` | 기존 데이터 호환용 상태. 즉시 탈퇴 흐름은 이 상태로 전환하지 않음 |

`MemberRole`

| 역할 | 의미 |
| --- | --- |
| `AUTHOR` | 일반 작가 사용자 |
| `ADMIN` | 관리자 |

## DB 모델

`members`

| 필드 | 설명 |
| --- | --- |
| `id` | 회원 ID |
| `email` | 로그인 이메일, unique |
| `password_hash` | 암호화된 비밀번호 |
| `phone_number` | 하이픈 없는 휴대폰 번호, unique |
| `phone_verified` | 휴대폰 인증 여부. 새 회원가입은 인증 토큰을 요구하므로 `true` |
| `age_requirement_confirmed_at` | 가입 요청에서 만 14세 이상임을 필수 확인한 서버 시각 |
| `display_name` | 화면 표시 이름 |
| `profile_image_url` | 프로필 이미지 URL |
| `status` | 회원 상태 |
| `role` | 회원 역할 |

`refresh_tokens`

| 필드 | 설명 |
| --- | --- |
| `id` | refresh token row ID |
| `member_id` | 토큰 소유 회원 |
| `token_hash` | refresh token SHA-256 해시, unique |
| `expires_at` | 만료 시각 |
| `revoked_at` | 폐기 시각. null이면 아직 폐기되지 않음 |

`legal_documents`

| 필드 | 설명 |
| --- | --- |
| `document_type`, `locale`, `document_version` | 문서 종류·언어·불변 버전. 조합 unique |
| `title`, `content_markdown`, `content_hash` | 게시 제목·장문 Markdown 원문·UTF-8 SHA-256 |
| `status` | `DRAFT`, `PUBLISHED`, `RETIRED`. 종류+locale별 현재 `PUBLISHED`는 한 건 |
| `effective_date`, `published_at`, `retired_at` | 시행·게시·폐기 수명주기 시각 |

`member_legal_records`

| 필드 | 설명 |
| --- | --- |
| `member_id`, `legal_document_id` | 가입 회원과 실제로 표시한 법률 문서 FK. 조합 unique |
| `document_type`, `document_version`, `action_type` | 당시 종류·버전·동의/확인 행위 snapshot |
| `recorded_at` | 두 문서에 공통으로 적용한 Backend 서버 기록 시각 |

## API

휴대폰 인증번호 발송부터 가입 토큰 소비와 자동 로그인 응답까지 실제 클래스·메서드 호출 순서는 [Signup Workflow](signup-workflow.md)에서 확인합니다.

### 회원가입

```http
POST /api/v1/auth/signup
```

Request

```json
{
  "email": "user@example.com",
  "password": "password123!",
  "displayName": "장은호",
  "termsAccepted": true,
  "privacyPolicyAcknowledged": true,
  "age14OrOlderConfirmed": true,
  "termsDocumentId": 3,
  "privacyPolicyDocumentId": 4,
  "phoneVerificationToken": "<one-time-token>"
}
```

처리 흐름

1. 이메일 형식, 영문·숫자를 포함한 8~64자 비밀번호, 20자 이하 표시 이름, 인증 토큰, 두 법률 문서 ID를 validation 하고 세 필수 확인 boolean이 모두 `true`인지 검증합니다.
2. Redis에서 가입 토큰에 연결된 휴대폰 번호를 조회합니다. 클라이언트는 전화번호를 회원가입 body에 보내지 않습니다.
3. 이메일 중복 시 `AUTH_EMAIL_DUPLICATED`, 토큰 번호가 이미 가입된 번호이면 `AUTH_PHONE_NUMBER_DUPLICATED`를 반환합니다.
4. `LegalDocumentService`가 두 ID를 `ko-KR`의 현재 `PUBLISHED` 이용약관·개인정보처리방침과 정확히 대조합니다. 게시본이 교체되었으면 `LEGAL_DOCUMENT_NOT_CURRENT`, 현재 게시본이 없으면 `LEGAL_DOCUMENTS_UNAVAILABLE`을 반환합니다.
5. 비밀번호를 `PasswordEncoder`로 hash 합니다.
6. 한 번 만든 Backend 시각으로 `Member.registerPhoneVerified()`의 `age_requirement_confirmed_at`과 두 `MemberLegalRecord.record()`의 `recorded_at`을 기록합니다.
7. `ACTIVE`, `AUTHOR`, `phoneVerified=true` 회원, 정확한 두 법률 문서 FK·snapshot, refresh token을 같은 DB 트랜잭션에 저장합니다.
8. DB를 flush한 뒤 Redis `GETDEL`로 가입 토큰을 한 번만 소비합니다. 동시 요청에서 소비에 실패하면 DB 트랜잭션을 rollback 합니다.
9. refresh token 원문은 저장하지 않고 SHA-256 hash와 만료 시각을 `refresh_tokens`에 저장합니다.
10. access token은 응답 body로 반환하고, refresh token은 `HttpOnly` 쿠키로 전달합니다. 회원가입 후 별도 로그인 요청은 필요하지 않습니다.

Response

```http
Set-Cookie: refreshToken=<opaque-token>; Path=/api/v1/auth; Max-Age=1209600; HttpOnly; SameSite=Lax
```

```json
{
  "success": true,
  "message": "회원가입이 완료되었습니다.",
  "data": {
    "accessToken": "<access-token>",
    "tokenType": "Bearer",
    "expiresIn": 1800
  },
  "error": null,
  "timestamp": "2026-07-22T13:30:00"
}
```

운영 환경의 refresh token 쿠키에는 `Secure` 속성을 추가합니다.

### 공개 법률 문서

```http
GET /api/v1/legal-documents/current?locale=ko-KR
GET /api/v1/legal-documents/{documentId}
```

- 현재 문서 API는 회원가입과 공개 `/terms`, `/privacy`가 함께 사용하는 현재 `PUBLISHED` 이용약관·개인정보처리방침 묶음을 반환합니다.
- ID 조회는 `PUBLISHED`와 과거 `RETIRED`만 공개하고 `DRAFT`는 반환하지 않습니다.
- 응답에는 문서 ID·종류·locale·버전·제목·Markdown 원문·원문 SHA-256·상태·시행일·게시 시각이 포함됩니다.
- `LEGAL_DOCUMENT_NOT_FOUND`는 404, 현재 두 게시본 중 하나라도 없으면 `LEGAL_DOCUMENTS_UNAVAILABLE`은 503입니다.

### 인증번호 발송

```http
POST /api/v1/auth/phone-verifications
```

```json
{
  "phoneNumber": "01012345678"
}
```

```json
{
  "success": true,
  "message": "인증번호를 발송했습니다.",
  "data": {
    "verificationId": "<opaque-id>",
    "expiresInSeconds": 300,
    "resendAfterSeconds": 60
  },
  "error": null,
  "timestamp": "2026-08-01T18:00:00"
}
```

처리 순서:

1. `010`으로 시작하는 11자리 형식과 DB 중복 번호를 확인합니다. 중복이면 SMS를 보내지 않습니다.
2. 전화번호·IP·전체 rate limit을 Redis Lua로 한 번에 확인하고 증가시킵니다.
3. 새 `verificationId`와 6자리 번호를 만들고 인증번호 HMAC만 5분 TTL로 저장합니다.
4. 같은 번호의 이전 활성 인증 흐름을 삭제해 가장 최근 번호만 유효하게 합니다.
5. SOLAPI 또는 Fake sender를 한 번 호출합니다.

발송 제한:

| 기준 | 1시간 | KST 하루 | KST 월 |
| --- | ---: | ---: | ---: |
| 전화번호 | 5 | 10 | - |
| IP | 10 | 20 | - |
| 전체 | - | 20 | 200 |

동일 전화번호 재전송은 60초 뒤 가능하며 429 응답에는 `Retry-After` 초를 포함합니다.

### 인증번호 확인

```http
POST /api/v1/auth/phone-verifications/{verificationId}/confirm
```

```json
{
  "code": "123456"
}
```

정답이면 한 인증 흐름에서 하나의 가입 토큰만 원자적으로 생성합니다. 동시에 확인해도 같은 토큰을 반환하며, 가입 토큰은 10분 동안 유효하고 회원가입에서 `GETDEL`로 한 번만 소비됩니다. 오입력은 Redis hash에서 원자적으로 증가하며 5회째부터 흐름 만료 전까지 잠깁니다.

```json
{
  "success": true,
  "message": "휴대폰 인증이 완료되었습니다.",
  "data": {
    "phoneVerificationToken": "<one-time-token>",
    "expiresInSeconds": 600
  },
  "error": null,
  "timestamp": "2026-08-01T18:01:00"
}
```

### 휴대폰 인증 오류 계약

| 코드 | HTTP | 의미 |
| --- | ---: | --- |
| `AUTH_PHONE_NUMBER_DUPLICATED` | 409 | 이미 가입된 번호 |
| `AUTH_PHONE_VERIFICATION_CODE_INVALID` | 400 | 5회 미만의 잘못된 인증번호 |
| `AUTH_PHONE_VERIFICATION_TOKEN_INVALID` | 400 | 없거나 이미 소비된 가입 토큰 |
| `AUTH_PHONE_VERIFICATION_EXPIRED` | 410 | 존재하지 않거나 만료된 인증 흐름 |
| `AUTH_PHONE_VERIFICATION_RATE_LIMITED` | 429 | 재전송 또는 발송량 제한, `Retry-After` 포함 |
| `AUTH_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED` | 429 | 인증번호 5회 오입력 |
| `AUTH_PHONE_VERIFICATION_UNAVAILABLE` | 503 | Redis 또는 SMS 발송 서비스 장애 |

### 로그인

```http
POST /api/v1/auth/login
```

Request

```json
{
  "email": "user@example.com",
  "password": "password123!"
}
```

처리 흐름

1. 이메일로 회원을 조회합니다.
2. 회원이 없거나 비밀번호가 다르면 `AUTH_INVALID_CREDENTIALS`를 반환합니다.
3. 회원 상태가 active가 아니면 `MEMBER_INACTIVE`를 반환합니다.
4. access token과 refresh token을 발급합니다.
5. refresh token hash를 저장하고 원문 token은 `Set-Cookie`로 내려줍니다.

### Access Token 재발급

```http
POST /api/v1/auth/refresh
Cookie: refreshToken=<opaque-token>
```

처리 흐름

1. refresh token 쿠키가 없으면 `AUTH_REFRESH_TOKEN_NOT_FOUND`를 반환합니다.
2. token hash로 저장된 refresh token을 조회합니다.
3. 저장된 token이 없거나 폐기/만료 상태면 `AUTH_REFRESH_TOKEN_INVALID`를 반환합니다.
4. 기존 refresh token을 폐기합니다.
5. 새 access token과 refresh token을 발급합니다.

### 로그아웃

```http
POST /api/v1/auth/logout
Cookie: refreshToken=<opaque-token>
```

저장된 refresh token이 있으면 폐기하고, 클라이언트에는 refresh token 삭제 쿠키를 내려줍니다.

쿠키가 없거나 이미 폐기된 경우에도 삭제 쿠키를 반환해 브라우저 상태를 정리합니다.

### 현재 사용자 조회

```http
GET /api/v1/auth/me
Authorization: Bearer <access-token>
```

Spring Security가 access token을 검증하고 `MemberPrincipal`을 주입하면, controller가 principal 기반 `MemberResponse`를 반환합니다.

### 회원 즉시 탈퇴

```http
DELETE /api/v1/members/me
Authorization: Bearer <access-token>
```

현재 비밀번호와 정확한 `회원 탈퇴` 확인 문구를 검증합니다. 접수 트랜잭션에서 회원을 `PURGING`으로 전환하고 저장된 모든 refresh token을 폐기하므로, 기존 JWT의 서명이 유효해도 다음 요청부터 인증되지 않습니다. 작품은 기존 WorkPurge 기능으로 비동기 영구 파기하고 모든 작품이 사라진 뒤 회원 행을 hard delete합니다. 전체 상태 전이와 자동 재시도 계약은 [회원 즉시 탈퇴와 영구 파기](member-withdrawal.md)를 따릅니다.

## 접근 제어

- `/phone-verifications`, `/phone-verifications/{verificationId}/confirm`, `/signup`, `/login`, `/refresh`, `/logout`은 인증 없이 호출할 수 있습니다.
- `/me`는 Bearer access token이 필요합니다.
- token, cookie, password 예시는 실제 값이 아닌 더미 값을 사용합니다.

## 운영 설정

- `SMS_PROVIDER=solapi`
- `SOLAPI_API_KEY`, `SOLAPI_API_SECRET`, `SOLAPI_SENDER_NUMBER`
- `PHONE_VERIFICATION_HASH_SECRET` (32바이트 이상 별도 랜덤 비밀값)
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`

운영 Redis는 외부 포트를 열지 않고 64MB `noeviction`, 비영속 구성으로 실행합니다. 애플리케이션에서 KST 일 20건·월 200건으로 전체 발송량을 제한하고, SOLAPI 자동충전은 사용하지 않으며 필요한 선불 잔액만 유지합니다. 개인 계정에 본인 명의 발신번호를 등록하면 사용자 SMS에 그 번호가 표시되고, 문자 본인인증으로 등록한 번호는 6개월마다 갱신합니다. 인증번호·전화번호·IP·API secret은 로그에 기록하지 않고 발송 성공/실패·제한·인증 성공 여부만 기록합니다.

운영 배포 전에는 SOLAPI 개인 계정, `message:write` 권한의 API key, 등록·갱신된 발신번호와 충전 잔액을 확인합니다. Redis와 Backend를 먼저 배포하고 Frontend를 즉시 배포하며, 배포 사이에는 신규 가입만 일시 실패할 수 있고 기존 로그인은 유지됩니다. 배포 후 실제 휴대폰으로 수신과 회원가입을 한 번 검증합니다.

SOLAPI 연동 기준은 [발신번호 가이드](https://solapi.com/guides/senderid), [API Key 인증](https://solapi.com/developers/api/authentication-api-key), [메시지 발송 API](https://solapi.com/developers/api/messages)를 따릅니다.

## 이후 작업

- CAPTCHA 또는 별도 WAF가 필요할지 전체 일일 한도 소진 공격을 관측한 뒤 결정
- PASS·카카오 본인인증처럼 실명·CI/DI가 필요한지 제품 요구가 생기면 별도 도입
- 정지 API가 생기면 정지 기간과 refresh token 폐기·복구 범위 정의
