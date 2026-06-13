# Auth Domain

## 목적

Auth 도메인은 이메일/비밀번호 기반 회원가입, 로그인, access token 발급, refresh token 회전, 로그아웃, 현재 사용자 조회를 담당합니다.

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

## 상태/역할 모델

`MemberStatus`

| 상태 | 의미 |
| --- | --- |
| `ACTIVE` | 사용 가능한 회원 |
| `SUSPENDED` | 정지된 회원 |
| `DELETED` | 탈퇴 또는 삭제된 회원 |

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
| `phone_verified` | 휴대폰 인증 여부. 현재 회원가입 시 `false` |
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

## API

### 회원가입

```http
POST /api/v1/auth/signup
```

Request

```json
{
  "email": "user@example.com",
  "password": "password123!",
  "phoneNumber": "01012345678",
  "displayName": "장은호"
}
```

처리 흐름

1. 이메일, 비밀번호, 휴대폰 번호, 표시 이름을 validation 합니다.
2. 이메일 중복 시 `AUTH_EMAIL_DUPLICATED`를 반환합니다.
3. 휴대폰 번호 중복 시 `AUTH_PHONE_NUMBER_DUPLICATED`를 반환합니다.
4. 비밀번호를 `PasswordEncoder`로 hash 합니다.
5. `Member.register()`로 `ACTIVE`, `AUTHOR`, `phoneVerified=false` 회원을 생성합니다.

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

## 접근 제어

- `/signup`, `/login`, `/refresh`, `/logout`은 인증 없이 호출할 수 있습니다.
- `/me`는 Bearer access token이 필요합니다.
- token, cookie, password 예시는 실제 값이 아닌 더미 값을 사용합니다.

## 이후 작업

- SMS 인증 도입 시 `phone_verified` 전이 정책 추가
- 회원 탈퇴/정지 API가 생기면 refresh token 폐기 범위 정의
