# CatchHole Agent Guide

이 문서는 팀원이 AI 코딩 도구를 사용할 때 공통으로 지켜야 할 작업 규칙입니다.

## 기본 원칙

- 요청 범위 밖의 리팩터링은 하지 않는다.
- 기존 패키지 구조와 네이밍을 우선 따른다.
- 변경 후에는 관련 테스트를 실행하고 결과를 공유한다.
- 불확실한 제품 정책이나 API 규약은 임의로 정하지 말고 팀에 확인한다.
- 보안상 민감한 값은 로그, 응답, 테스트 실패 메시지에 노출하지 않는다.

## Backend Project

### Package Structure

- base package: `org.monitoring.catchholebackend`
- 도메인별 기능 코드는 `domain` 아래에 둔다.
- 전역 공통 코드는 `global` 아래에 둔다.
- 공통 응답 코드는 `global.common.response` 아래에 둔다.
- 전역 예외 코드는 `global.exception` 아래에 둔다.

예시:

```text
org.monitoring.catchholebackend
├── domain
└── global
    ├── common
    │   └── response
    └── exception
```

### Common Response

- API 응답 Envelope는 `CommonResponse<T>`를 사용한다.
- 컨트롤러는 v1 기준으로 자동 래핑을 사용하지 않고 명시적으로 `CommonResponse.success(...)`를 반환한다.
- 성공/실패 응답은 다음 필드를 유지한다.
  - `success`
  - `message`
  - `data`
  - `error`
  - `timestamp`
- `timestamp`는 `LocalDateTime.now()`로 응답 생성 시각을 기록한다.
- 삭제나 빈 성공 응답은 `data: null`을 허용한다.

### Error Handling

- 비즈니스 규칙 위반은 `AppException`과 `ResultCode`를 사용한다.
- 공통 에러 코드는 `CommonErrorCode`에 둔다.
- 에러 코드는 `도메인_상황` 형식의 enum 이름을 사용한다.
  - 예: `AUTH_UNAUTHORIZED`, `REQUEST_VALIDATION_FAILED`, `RESOURCE_NOT_FOUND`
- validation 실패 응답에는 `rejectedValue`를 넣지 않는다.
- 예상하지 못한 예외는 내부 메시지를 그대로 노출하지 않고 공통 서버 오류 메시지로 응답한다.
- Spring Security의 인증/인가 실패 응답도 이후 같은 `CommonResponse` 규약에 맞춘다.

### Java Style

- Java 21 기준으로 작성한다.
- DTO 성격의 단순 응답/요청 객체는 record 사용을 우선 고려한다.
- enum 생성자와 단순 getter는 Lombok 어노테이션을 사용한다.
  - 예: `@Getter`, `@RequiredArgsConstructor`
- 불필요한 추상화나 미래 대비용 확장 포인트를 만들지 않는다.
- 주석은 복잡한 의도를 설명할 때만 짧게 작성한다.

### Commit Convention

커밋 메시지 형식: `type(scope): 한국어 설명`

| type | 용도 |
|------|------|
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `build` | 의존성 / 빌드 설정 변경 |
| `test` | 테스트 추가 또는 수정 |
| `refactor` | 동작 변경 없는 코드 개선 |
| `docs` | 문서 수정 |
| `chore` | 기타 잡무 (설정 파일 등) |

- `scope`는 변경 영역 (예: `global`, `auth`, `user`)
- 하나의 커밋은 하나의 목적만 담는다 (의존성 추가, 기능 구현, 테스트는 각각 분리)
- 예시
  - `build: swagger-annotations-jakarta 의존성 추가`
  - `feat(global): 공통 응답 구조 및 전역 예외 핸들러 추가`
  - `test(global): GlobalExceptionHandler 통합 테스트 추가`

### Tests

- 백엔드 변경 후 기본 검증 명령은 다음과 같다.

```bash
./gradlew test
```

- 테스트는 `apps/CatchHole-Backend`에서 실행한다.
- API 응답 규약을 바꾸면 MockMvc 테스트도 함께 갱신한다.
- DB 설정이 필요한 통합 테스트는 별도 profile 또는 테스트 설정을 명확히 둔다.
