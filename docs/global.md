# Global Layer

## 목적

`global` 패키지는 특정 도메인에 속하지 않는 공통 기반을 관리합니다.

현재 책임은 공통 응답 envelope, 예외 처리, 보안 설정, CORS, JPA auditing, Swagger, S3 storage abstraction입니다. 도메인 서비스는 이 기반을 사용하되, 도메인 규칙 자체는 각 `domain/<domain>` 아래에 둡니다.

## 패키지 구조

```text
global
├── common
│   ├── entity
│   └── response
├── config
│   ├── auth
│   ├── cors
│   ├── jpa
│   ├── security
│   └── swagger
├── exception
└── storage
```

## 공통 응답

모든 API 응답은 `CommonResponse<T>` envelope를 사용합니다.

| 필드 | 설명 |
| --- | --- |
| `success` | 요청 처리 성공 여부 |
| `message` | 사용자/개발자가 읽을 응답 메시지 |
| `data` | 성공 응답 데이터. 실패 시 null |
| `error` | 실패 응답 에러 정보. 성공 시 null |
| `timestamp` | 응답 생성 시각. `yyyy-MM-dd'T'HH:mm:ss` 형식 |

성공 응답 예시

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000"
  },
  "error": null,
  "timestamp": "2026-06-14T10:29:00"
}
```

실패 응답 예시

```json
{
  "success": false,
  "message": "요청값이 올바르지 않습니다.",
  "data": null,
  "error": {
    "code": "REQUEST_VALIDATION_FAILED",
    "status": 400,
    "details": [
      {
        "field": "email",
        "message": "이메일 형식이 올바르지 않습니다."
      }
    ]
  },
  "timestamp": "2026-06-14T10:29:00"
}
```

### Validation detail 정책

`FieldErrorResponse`는 `field`, `message`만 내려줍니다.

`rejectedValue`는 응답에 포함하지 않습니다. 비밀번호, 토큰, 개인정보 같은 민감한 값이 검증 실패 응답에 섞일 수 있기 때문입니다.

## 예외 처리

비즈니스 예외는 `AppException`에 `ResultCode`를 담아 던집니다.

도메인별 에러 코드는 각 도메인의 `exception` 패키지에 두고, 공통 에러는 `CommonErrorCode`에 둡니다.

`GlobalExceptionHandler` 처리 흐름

| 예외 | 응답 코드 |
| --- | --- |
| `AppException` | exception이 가진 `ResultCode` |
| `MethodArgumentNotValidException` | `REQUEST_VALIDATION_FAILED` |
| `ConstraintViolationException` | `REQUEST_VALIDATION_FAILED` |
| `HttpMessageNotReadableException` | `REQUEST_INVALID_ARGUMENT` |
| `NoResourceFoundException` | `RESOURCE_NOT_FOUND` |
| 기타 `Exception` | `COMMON_INTERNAL_SERVER_ERROR` |

예상하지 못한 예외는 내부 exception message를 그대로 노출하지 않고 공통 서버 오류 메시지로 응답합니다.

## JPA 공통 Entity

모든 JPA Entity는 `BaseEntity`를 상속합니다.

| 필드 | 설명 |
| --- | --- |
| `created_at` | 생성 시각. 최초 생성 후 수정하지 않음 |
| `updated_at` | 수정 시각 |

`JpaConfig`의 `@EnableJpaAuditing`으로 auditing을 활성화합니다.

## Security

Spring Security는 stateless JWT resource server로 동작합니다.

핵심 설정

- CSRF, form login, HTTP Basic은 비활성화합니다.
- session policy는 `STATELESS`입니다.
- 공개 URL은 `SecurityConstant.PUBLIC_URLS`에서 관리합니다.
- 관리자 URL은 `SecurityConstant.ADMIN_URLS`에서 관리하며 `ROLE_ADMIN`이 필요합니다.
- 인증 실패는 `AUTH_UNAUTHORIZED`, 권한 실패는 `AUTH_FORBIDDEN`을 공통 envelope로 반환합니다.

공개 URL

| 그룹 | 경로 |
| --- | --- |
| Auth | `/api/v1/auth/signup`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout` |
| Swagger | `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` |
| Actuator | `/actuator/health`, `/actuator/prometheus` |

JWT 설정

- HMAC SHA-256 secret key를 사용합니다.
- `auth.jwt.secret`은 최소 32바이트 이상이어야 합니다.
- Access token 만료 기본값은 `30m`입니다.
- Refresh token 만료 기본값은 `14d`입니다.

보안 실패 응답은 MVC 예외 핸들러가 아니라 `SecurityErrorResponseWriter`에서 직접 JSON으로 작성합니다. Spring Security filter 단계에서 발생하는 실패도 API 응답 형식을 맞추기 위함입니다.

## CORS

`CorsConfig`는 `cors.allowed-origins`를 기준으로 전체 경로에 CORS를 적용합니다.

| 설정 | 값 |
| --- | --- |
| allowed methods | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` |
| allowed headers | `*` |
| credentials | `true` |
| exposed headers | `Authorization` |
| max age | `3600` |

운영에서는 `CORS_ALLOWED_ORIGINS` 환경변수로 허용 origin을 주입합니다.

## Swagger

`SwaggerConfig`는 OpenAPI 문서에 `bearerAuth` 보안 스키마를 등록합니다.

multipart 요청에서 JSON part를 처리하기 위해 `MultipartJackson2HttpMessageConverter`를 둡니다. 이 converter는 `application/octet-stream`으로 들어오는 part를 읽기 용도로만 지원하고, write는 비활성화합니다.

## Storage

스토리지 접근은 `ObjectStorage` 인터페이스와 `ObjectStorageService`를 통해 사용합니다.

`S3ObjectStorage`는 AWS SDK `S3Client`로 실제 S3 put/get/delete를 수행합니다.

### 저장 key 규칙

회차 원문

```text
works/{workId}/episodes/{episodeNo}.txt
```

업로드 원본 파일

```text
upload-batches/{batchId}/{randomUUID}-{originalFilename}
```

### 회차 원문 저장 결과

`ObjectStorageService.putEpisodeContent()`는 다음 값을 계산해 `StoredTextObject`로 반환합니다.

| 필드 | 설명 |
| --- | --- |
| `key` | S3 object key |
| `versionId` | S3 version ID |
| `contentHash` | UTF-8 원문 SHA-256 hash |
| `charCount` | Java 문자열 길이 기준 글자 수 |

도메인은 원문 자체 대신 이 메타데이터를 Entity에 저장합니다.

## 설정 파일과 환경변수

| 설정 | 설명 |
| --- | --- |
| `cors.allowed-origins` | CORS 허용 origin 목록 |
| `storage.s3.bucket` | S3 bucket |
| `storage.s3.region` | S3 region |
| `storage.s3.endpoint-override` | 로컬 S3 호환 스토리지 endpoint |
| `storage.s3.access-key-id` | S3 access key |
| `storage.s3.secret-access-key` | S3 secret key |
| `auth.jwt.secret` | JWT 서명 secret. 32바이트 이상 |
| `auth.jwt.access-token-expiration` | access token 만료 시간 |
| `auth.refresh-token-expiration` | refresh token 만료 시간 |
| `auth.cookie.secure` | refresh token cookie Secure 여부 |
| `auth.cookie.same-site` | refresh token cookie SameSite 값 |
| `internal.api-key` | Python AI Worker 내부 API 인증 key. 운영에서는 `INTERNAL_API_KEY`로 주입 |

프로파일별 기준

| 파일 | 역할 |
| --- | --- |
| `application.yml` | 공통 기본값과 local 개발 기본값 |
| `application-local.yml` | 로컬 JPA update, SQL 로그 |
| `application-prod.yml` | 운영 DB, JWT secret, CORS, cookie secure |
| `src/test/resources/application-test.yml` | H2 PostgreSQL mode 통합 테스트 |

## 이후 작업

- `ADMIN_URLS`가 생기면 경로와 권한 정책을 함께 문서화합니다.
- S3 object 삭제 실패 시 보상/재시도 정책이 필요해지면 storage 문서를 분리합니다.
- Flyway/Liquibase 같은 migration 도구 도입 시 ERD와 auditing 기준을 함께 갱신합니다.
