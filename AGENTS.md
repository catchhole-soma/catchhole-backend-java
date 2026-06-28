# CatchHole Agent Guide

이 문서는 팀원이 AI 코딩 도구를 사용할 때 공통으로 지켜야 할 작업 규칙입니다.

## 기본 원칙

- 요청 범위 밖의 리팩터링은 하지 않는다.
- 기존 패키지 구조와 네이밍을 우선 따른다.
- 변경 후에는 관련 테스트를 실행하고 결과를 공유한다.
- 불확실한 제품 정책이나 API 규약은 임의로 정하지 말고 팀에 확인한다.
- 보안상 민감한 값은 로그, 응답, 테스트 실패 메시지에 노출하지 않는다.

## AGENTS.md 유지 규칙

이 문서는 프로젝트 컨벤션의 **단일 출처(single source of truth)**다. 작업 중 컨벤션이 늘어나면 같은 작업 안에서 함께 갱신한다.

- 다음 변화가 생기면 AGENTS.md를 같이 업데이트한다.
  - 새로운 패키지 / 디렉토리 추가 또는 구조 변경 → 패키지 트리 갱신
  - 새로운 네이밍 / 명명 규칙 결정 (예: Mapper 메서드명, DTO 접미사)
  - 새로운 설정 파일 / 프로파일 / 환경변수 도입
  - 새 라이브러리 도입으로 사용 패턴이 바뀜 (예: `@ConfigurationProperties`, `ServiceConnection` 자동 주입)
  - 도메인 / 레이어 간 규약 변경 (예: Service 분리 방식, 예외 처리 정책)
  - GitHub PR/Issue 템플릿, 리뷰 규칙, 브랜치 운영 방식 등 협업 워크플로우 변경
- 다음 경우는 갱신하지 않는다.
  - 기존 컨벤션을 그대로 따르는 단순 기능 추가
  - 버그 수정 / 동작 변경 없는 리팩터링
  - 일회성 핫픽스
- 컨벤션을 정하거나 바꿀 때는 **왜 그렇게 결정했는지 사유**도 함께 기록해, 이후 작업자(사람 또는 AI)가 맥락을 이해하고 예외 케이스를 판단할 수 있도록 한다.

## Backend Project

### Configuration Profiles

설정은 환경별 YAML 파일로 분리한다.

| 파일 | 용도 |
|------|------|
| `application.yml` | 모든 환경 공통 설정 (앱 이름, 기본 활성 프로파일, CORS 기본값) |
| `application-local.yml` | 로컬 개발 (JPA `update`, SQL 로그). DB 접속은 yml에 두지 않는다 |
| `application-prod.yml` | 운영 (DB / CORS는 환경변수 주입, JPA `validate`) |
| `src/test/resources/application-test.yml` | 통합 테스트 (H2 인메모리 DB, JPA `create-drop`) |

- 기본 활성 프로파일은 `application.yml`의 `spring.profiles.active: local`. 운영 배포 시 `SPRING_PROFILES_ACTIVE=prod`로 덮어쓴다.
- 운영 환경 설정값(DB 접속 정보, 허용 origin 등)은 `${ENV_VAR}` 플레이스홀더로 두고, yml에 평문으로 적지 않는다.
- 운영 JWT 서명키는 `JWT_SECRET` 환경변수로 주입한다. 최소 32바이트 이상이어야 하며, 로그/응답/테스트 실패 메시지에 노출하지 않는다.
- 운영 Worker 내부 API key는 `INTERNAL_API_KEY` 환경변수로 주입한다. 로컬 기본값은 개발 편의를 위한 값이며 운영에서는 반드시 별도 secret을 사용한다.
- 로컬 실행 시 `application.yml`이 `apps/CatchHole-Backend/.env`를 optional import한다. AWS/S3 같은 로컬 비밀값은 `.env`에 둘 수 있지만, `.env`는 커밋하지 않는다.
- 새로운 설정 키를 추가할 때는 base / local / prod 각 위치를 의식적으로 결정한다.
- **로컬 DB 접속 정보는 `compose.yaml` 단일 출처로 둔다.** `spring-boot-docker-compose` 의존성이 컨테이너에서 호스트/포트/사용자/비밀번호를 자동 추출해 `ServiceConnection` 빈으로 주입한다. yml에 `spring.datasource.*`를 중복 작성하지 않는다 (그림자 설정 방지).

### Package Structure

- base package: `org.monitoring.catchholebackend`
- 도메인별 기능 코드는 `domain` 아래에 둔다.
- 전역 공통 코드는 `global` 아래에 둔다.
- 공통 응답 코드는 `global.common.response` 아래에 둔다.
- 전역 예외 코드는 `global.exception` 아래에 둔다.
- 전역 `@Configuration` 클래스는 `global.config.<영역>` 아래에 둔다.
  - 영역별 서브패키지로 분리한다 (예: `global.config.swagger`, `global.config.security`).

예시:

```text
org.monitoring.catchholebackend
├── domain
│   ├── analysis
│   │   ├── controller
│   │   ├── dto
│   │   │   ├── request
│   │   │   └── response
│   │   ├── entity
│   │   ├── exception
│   │   ├── mapper
│   │   ├── repository
│   │   ├── service
│   │   └── type
│   ├── character
│   │   ├── entity
│   │   ├── repository
│   │   └── type
│   └── work
│       ├── controller
│       ├── dto
│       │   ├── request
│       │   └── response
│       ├── entity
│       ├── exception
│       ├── mapper
│       ├── repository
│       ├── service
│       └── type
└── global
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

### Domain Package

도메인 단위로 패키지를 나누고, 그 안은 레이어드 구조를 따른다.

본 프로젝트는 도메인 중심 설계를 지향하되, 현재의 도메인별 레이어드 구조를 유지한다.
Entity는 단순 데이터 보관 객체가 아니라 핵심 상태 변경과 도메인 규칙을 표현하는 객체로 설계한다.
Service는 Entity 조회, 트랜잭션, 저장, DTO 변환 등 유스케이스 흐름을 조율한다.
파일 저장소 키/URL 생성, 해시 계산처럼 여러 도메인에서 재사용되거나 인프라 세부사항에 가까운 로직은 Service에 두지 말고 `global`의 별도 컴포넌트에 둔다.

이 방식을 선택한 이유는 MVP 개발 속도를 유지하면서도, 비즈니스 규칙이 Service나 Mapper에 흩어지는 것을 줄이고 도메인 객체 내부에 일관되게 모으기 위함이다.

### Documentation

- 백엔드 도메인 설계, ERD, API 흐름, 작업 워크플로우는 `docs/` 아래 Markdown으로 관리한다.
- 전역 개발 규칙과 컨벤션은 `AGENTS.md`에 유지하고, 도메인별 설계 의도와 구현 흐름은 `docs/`에 둔다.
- 코드 변경으로 도메인 흐름, DB 모델, 상태 전이, 접근 제어가 바뀌면 관련 문서도 같은 PR에서 갱신한다.
- 로컬 문서 편집기 설정 파일은 커밋하지 않는다. 예: `docs/.obsidian/`

```text
domain/<domain>
├── controller/
├── service/
│   ├── <Domain>Service.java        (interface)
│   └── <Domain>ServiceImpl.java    (구현체)
├── repository/
├── entity/                 (JPA Entity)
├── type/                   (도메인 전용 enum)
├── parser/                 (도메인 전용 입력 파싱 컴포넌트)
├── processor/              (도메인 전용 처리 흐름 컴포넌트)
├── dto/
│   ├── request/
│   └── response/
├── mapper/                 (도메인 전용 Mapper)
└── exception/              (도메인 전용 ErrorCode)
```

- `controller`는 API 진입점만 담당하고, 비즈니스 로직은 `service`에 둔다.
- `entity` 패키지에는 JPA Entity만 둔다.
- 도메인 전용 enum은 `type` 패키지에 둔다 (예: `UserStatus`). Entity와 enum을 분리해 JPA Entity 목록을 빠르게 파악하기 위함이다.
- 모든 JPA Entity는 `global.common.entity.BaseEntity`를 상속한다.
  - `createdAt`, `updatedAt`이 자동 관리된다 (`@CreatedDate`, `@LastModifiedDate`).
  - JPA Auditing은 `global.config.jpa.JpaConfig`의 `@EnableJpaAuditing`으로 활성화되어 있다.
- Entity의 상태 변경은 setter 직접 호출보다 `approve()`, `close()`, `changeTitle()`처럼 의미가 드러나는 메서드로 표현한다.
- Entity 내부 메서드는 자기 필드 기반의 검증, 상태 전이, 계산처럼 해당 도메인 객체가 책임져야 하는 규칙을 담당한다.
- Repository 조회/저장, 외부 API 호출, 파일 처리, 이메일 발송, DTO 변환, 트랜잭션 제어는 Entity에 두지 않고 Service 또는 별도 컴포넌트에서 조율한다.
- `dto`는 `request` / `response`로 명확히 분리한다.
  - request DTO 네이밍: `UserCreateRequest`, `UserUpdateRequest` (목적이 드러나게)
  - response DTO 네이밍: `UserResponse`, `UserDetailResponse`
- **도메인 전용 예외 클래스는 만들지 않는다.** 모든 비즈니스 예외는 `AppException`에 도메인 `ErrorCode`를 담아서 던진다.
  - 예: `throw new AppException(UserErrorCode.USER_NOT_FOUND);`
  - 응답의 `error.code`가 도메인 prefix를 포함하므로 클라이언트에서 도메인 식별이 가능하다.
  - 예외 케이스마다 클래스를 만들지 않아 보일러플레이트를 줄이고, ErrorCode enum 한 곳에서 도메인 에러를 관리한다.
- 도메인 전용 `ErrorCode`는 `exception` 패키지에 두고 `ResultCode`를 구현한다.
  - 예: `UserErrorCode` (`USER_NOT_FOUND`, `USER_EMAIL_DUPLICATED`)
- 여러 도메인에서 공통으로 쓰는 enum은 `global.common` 아래에 둔다.
- 사용자 계정 도메인은 `member`로 명명한다. Java 도메인은 `Member`, DB 테이블은 `members`, FK는 `member_id`를 사용한다.
- 인증 흐름은 `auth` 도메인에 둔다. JWT 발급/검증, refresh token 발급/폐기, 인증 쿠키 생성은 `domain/auth` 아래에서 관리한다.

#### Auth and Token Policy

- Access token은 JWT로 발급하고, refresh token은 랜덤 opaque token으로 발급한다.
- Access token 만료 기본값은 30분, refresh token 만료 기본값은 14일이다.
- Refresh token 원문은 저장하지 않는다. `refresh_tokens.token_hash`에 SHA-256 해시만 저장하고, 재발급 시 기존 token은 `revoked_at`으로 폐기한 뒤 새 token을 저장한다.
- Refresh token은 `HttpOnly` 쿠키로 전달한다. 쿠키 path는 `/api/v1/auth`, SameSite 기본값은 `Lax`, 운영 환경에서는 `Secure=true`를 사용한다.
- 회원가입 시 휴대폰 번호는 하이픈 없는 `010` 시작 11자리 숫자로 받고, `members.phone_number`에 unique로 저장한다. SMS 인증은 별도 기능에서 구현하며, 현재는 `phone_verified=false` 기본값을 유지한다.

#### Work Domain Policy

- Work는 로그인한 회원의 개인 작업공간 리소스로 취급한다.
- Work 생성 시 서버에서 인증된 `Member`를 소유자로 연결하며, 요청 DTO에서 소유자 식별값을 받지 않는다.
- Work 목록 조회, 수정, 삭제는 `memberId` 기준으로 본인 작품만 허용한다.
- 존재하지 않는 작품과 다른 회원의 작품 접근은 모두 `WORK_NOT_FOUND`로 응답해 리소스 존재 여부를 노출하지 않는다.
- 본인 작품 조회가 필요한 도메인 서비스는 `WorkRepository.getOwnedWork(workId, memberId)`를 사용해 소유권 확인과 `WORK_NOT_FOUND` 응답을 일관되게 처리한다.
- 현재 Work 삭제는 hard delete이므로 `WorkStatus`나 `works.status`를 두지 않는다. 보관/복구 기능을 만들 때 상태 컬럼과 전이 메서드를 함께 추가한다.

#### Episode / Upload Domain Policy

- 회차 원문 전문은 DB에 저장하지 않고 S3에 저장한다. DB에는 `content_s3_key`, `content_s3_version`, `content_hash`, `char_count`만 둔다.
- 회차 업로드 요청 한 번은 `UploadBatch` 하나로 추적하고, 원본 파일 단위는 `UploadFile`로 추적한다.
- 업로드에서 생성된 회차는 `episodes.source_file_id`로 원본 업로드 파일을 추적한다.
- 같은 작품 안에서 회차 번호는 중복될 수 없다.
- 회차 조회, 수정, 삭제, 업로드는 모두 먼저 작품 소유권을 확인한다.
- 후속 Worker의 회차 처리 상태 변경은 단계별 엔드포인트를 나누지 않고, `EpisodeStatus`를 파라미터로 받는 단일 내부 전이 API로 구현한다.

#### Analysis Domain Policy

- AnalysisJob은 작품 단위 AI 분석 작업의 상태와 결과 메타데이터를 추적한다.
- 원문 텍스트는 `Episode`의 S3 저장 구조를 재사용하고, `analysis_jobs`에는 상태, 현재 단계, 모델명, 토큰 수, 요약 JSON, 마지막 실패 사유만 저장한다.
- 분석 실패 처리 이력은 `analysis_jobs.error_message`에 누적하지 않고, 후속 모니터링 기능에서 별도 기록/조회한다.
- 화면에서 분석 작업은 `AnalysisJob.status`를 상위 상태로 표시하고, 분석 작업 상세에 들어갔을 때 포함된 각 회차의 `Episode.status`를 단계별 상태로 보여준다.
- 분석 작업 생성 API는 업로드 흐름의 단위인 `batch_id`를 필수 입력으로 받는다. `episode_id`는 회차별 세부 작업이 필요해질 때 내부 작업 모델에서 선택적으로 사용할 수 있다.
- 본인 작품의 분석 작업만 생성/조회할 수 있으며, 다른 회원의 작품이나 다른 작품에 속한 분석 대상은 404로 응답한다.
- Python AI Worker는 작업 claim과 `AnalysisJob` 상태 변경에 `/api/internal/**` 내부 API를 `X-Internal-Api-Key`로 인증해 사용한다. Worker에는 원문 본문을 응답하지 않고 `Episode`의 S3 key/version/hash/charCount 메타데이터만 전달한다.
- Worker는 분석 작업 생성과 상태 전이를 위해 백엔드 DB에 직접 접근하지 않는다. 다만 청킹, 설정 후보, 리포트 같은 분석 산출물 저장은 데이터 양과 모델 안정성에 따라 내부 API 또는 Worker의 DB 직접 저장 중 선택할 수 있으며, DB 직접 저장을 선택하면 관련 스키마/문서 변경을 함께 관리한다.

#### Character Setting Domain Policy

- 캐릭터 설정 저장 토대는 `domain/character`에 둔다. `WorkCharacter`는 작품별 캐릭터 대표/현재 설정을, `SettingCandidate`는 AI가 추출한 사용자 검토 전 후보를 저장한다.
- 화면 표시, 검색, 비교에 자주 쓰는 캐릭터 이름, 역할, 현재 나이, 현재 레벨은 일반 컬럼으로 둔다.
- 검토 상태는 `SettingCandidate`에만 둔다. `WorkCharacter`와 `CharacterFact`는 사용자가 후보를 승인한 뒤 저장되는 대표 설정과 설정 이력이므로 별도 review status를 두지 않는다.
- 작품마다 구조가 달라지는 프로필, 스탯, 스킬, 아이템, 상태 상세값과 AI 원본 응답, 근거 span은 `JsonNode` + Hibernate JSON 매핑으로 JSONB 컬럼에 저장한다. 이 구조는 장르별 설정 차이를 수용하면서도 자주 조회하는 핵심 값은 일반 컬럼으로 유지하기 위한 선택이다.
- `setting_candidates.source_chunk_id`는 청킹 Entity가 생기기 전까지 FK 없는 UUID로 저장한다. `ManuscriptChunk` 구현 이후 실제 FK 제약 여부를 다시 결정한다.

#### Service Layer

- Service는 **interface와 구현체를 분리**한다.
  - interface: `<Domain>Service` (예: `UserService`)
  - 구현체: `<Domain>ServiceImpl` (예: `UserServiceImpl`)
  - 같은 `service/` 패키지에 함께 둔다 (flat 구조).
- 구현체는 `@Service`를 붙이고, 의존성은 `@RequiredArgsConstructor`로 생성자 주입한다.
- Controller는 interface에만 의존한다 (`UserService`를 주입받고 `UserServiceImpl`을 직접 참조하지 않는다).
- 트랜잭션 어노테이션(`@Transactional`)은 **구현체**에 붙인다.
- 읽기 전용 메서드는 클래스 레벨 `@Transactional(readOnly = true)` 후 쓰기 메서드에 `@Transactional`을 덮어쓴다.
- 구현체가 길어질 때는 전체 유스케이스의 소유권은 Service에 남기되, 파싱/업로드 처리/외부 저장소 조작 같은 세부 흐름은 `parser`, `mapper`, 도메인 전용 processor, `global` 컴포넌트로 분리한다.

예시:

```java
public interface UserService {
    UserResponse getUser(Long id);
    UserResponse createUser(UserCreateRequest request);
}

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    public UserResponse getUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_FOUND));
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        User saved = userRepository.save(userMapper.toEntity(request));
        return userMapper.toResponse(saved);
    }
}
```

#### Mapping

- 도메인 객체, DTO, 파싱 결과, 외부 저장소 결과처럼 계층 사이를 오가는 값 변환과 단순 객체 조립은 **별도 Mapper 클래스**를 만들어 처리한다 (MapStruct 사용하지 않는다).
- 매퍼는 `domain/<domain>/mapper/` 아래에 두고 `@Component`로 선언한다.
  - 다른 빈 주입이 필요해질 수 있으므로 일관되게 Spring 빈으로 관리한다.
- 메서드 네이밍은 변환/조립 결과가 드러나도록 통일한다.
  - `toEntity(request)` — Request DTO → Entity
  - `toEntity(parsed, stored)` — 파싱 결과 / 저장소 결과 → Entity
  - `toResponse(entity)` — Entity → Response DTO
  - `toResponseList(entities)` — Entity 목록 → Response DTO 목록
- 매퍼는 값 복사와 단순 조립만 수행하고, 검증/저장/상태 전이 같은 비즈니스 흐름은 `service`, 도메인 전용 processor, Entity에 둔다.

예시:

```java
@Component
public class UserMapper {

    public User toEntity(UserCreateRequest request) {
        return User.builder()
                .email(request.email())
                .name(request.name())
                .build();
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName());
    }

    public List<UserResponse> toResponseList(List<User> users) {
        return users.stream().map(this::toResponse).toList();
    }
}
```

### API URL Convention

- 모든 API는 `/api/v1/<resource>` 형식을 따른다.
- `<resource>`는 복수형 명사를 사용한다 (예: `/api/v1/users`, `/api/v1/orders`).
- 버전이 바뀌면 새 prefix로 분리한다 (`/api/v2/...`). 기존 버전은 deprecate 정책 정해질 때까지 유지한다.

### Swagger / OpenAPI Documentation

- 모든 Controller에는 `@Tag(name, description)`를 붙여 API 그룹의 용도를 설명한다.
- 모든 API 메서드에는 `@Operation(summary, description)`와 주요 `@ApiResponses`를 작성한다.
  - 성공 응답뿐 아니라 validation 실패, 인증 실패, 권한 실패, 중복/존재하지 않음 같은 대표 실패 케이스도 함께 적는다.
- 인증이 필요한 API에는 `@SecurityRequirement(name = "bearerAuth")`를 명시한다.
- Swagger에 노출하지 않을 framework 파라미터(`JwtAuthenticationToken` 등)는 `@Parameter(hidden = true)`로 숨긴다.
- 쿠키/헤더/쿼리 파라미터는 `@Parameter(in = ParameterIn.COOKIE/HEADER/QUERY, name, description)`로 문서화한다.
- request / response DTO record에는 class-level `@Schema(description)`를 붙이고, 주요 필드에는 `@Schema(description, example)`를 작성한다.
- 비밀번호, 토큰, 쿠키처럼 민감한 값은 실제 값이 아닌 더미 예시를 사용한다.
- 예외 메시지, validation 메시지, OAuth2Error description, Swagger/OpenAPI description처럼 사람이 읽는 문장은 한국어로 작성한다. `accessToken`, `refreshToken`, `JWT`, `Bearer`, enum code처럼 API 필드명이나 기술 식별자는 그대로 사용할 수 있다.

이 방식을 선택한 이유는 Swagger UI만 보고도 프론트엔드와 백엔드 작업자가 요청 값, 인증 방식, 성공/실패 응답을 빠르게 확인하고 테스트할 수 있도록 하기 위함이다.

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
- Spring Security의 인증/인가 실패 응답도 `CommonResponse` 규약에 맞춘다.
- 코드에서 직접 던지는 예외 메시지와 OAuth2 인증 실패 description은 한국어로 작성한다. 한국어 프로젝트에서 디버깅과 API 문서 이해 비용을 줄이기 위함이다.

### Java Style

- Java 21 기준으로 작성한다.
- DTO 성격의 단순 응답/요청 객체는 record 사용을 우선 고려한다.
- enum 생성자와 단순 getter는 Lombok 어노테이션을 사용한다.
  - 예: `@Getter`, `@RequiredArgsConstructor`
- 불필요한 추상화나 미래 대비용 확장 포인트를 만들지 않는다.
- 주석은 복잡한 의도를 설명할 때만 짧게 작성한다.

### Commit Convention

커밋 메시지 형식:

```
type(scope): 한국어 제목 (50자 이내)

<본문 — 무엇을, 왜 변경했는지 한 줄 72자 이내>
<- 여러 항목은 bullet으로 정리>

<footer — Breaking Changes, 이슈 참조 등 선택>
```

#### Type

| type | 용도 |
|------|------|
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `build` | 의존성 / 빌드 설정 변경 |
| `test` | 테스트 추가 또는 수정 |
| `refactor` | 동작 변경 없는 코드 개선 |
| `docs` | 문서 수정 |
| `chore` | 기타 잡무 (설정 파일 등) |

#### Scope

- 변경 영역을 명시한다. 예: `global`, `auth`, `user`
- 변경 영역이 명확하지 않거나 전역 설정이면 생략 가능 (`build:`, `chore:`)

#### 원칙

- 하나의 커밋은 하나의 목적만 담는다. 의존성 추가 / 기능 구현 / 테스트는 각각 분리한다.
- 제목은 명령조로, 마침표 없이 작성한다.
- 본문이 필요한 커밋과 불필요한 커밋을 구분한다.

#### 본문 작성 기준

**본문이 필요한 경우 (반드시 작성)**

- `feat`, `refactor` 등 여러 파일 / 개념이 묶인 변경
- 설계 의도, 대안 대비 선택 이유 등 "왜"를 설명해야 하는 변경
- 추가된 컴포넌트 / 클래스가 여러 개라 제목만으로 파악이 어려운 변경

**본문 생략 가능한 경우**

- 단일 의존성 추가 같은 한 줄로 끝나는 `build:` / `chore:`
- 오타 수정, 단순 리네임

#### 예시

본문 생략:

```
build: swagger-annotations-jakarta 의존성 추가
```

본문 포함:

```
feat(global): 공통 응답 구조 및 전역 예외 핸들러 추가

- CommonResponse<T> envelope 추가 (success/message/data/error/timestamp)
- ErrorResponse, FieldErrorResponse 분리하여 검증 실패 응답 표준화
- AppException + ResultCode/CommonErrorCode 도입으로 비즈니스 예외 처리
- GlobalExceptionHandler에서 validation, AppException, 알 수 없는 예외 분기
- 향후 모든 API는 본 envelope를 통해 응답하도록 통일
```

### Pull Request

- PR 본문은 `.github/pull_request_template.md`의 템플릿을 그대로 따른다. `gh pr create`로 만들 때도 템플릿 구조를 본문에 그대로 채워 넣는다.
- 모든 섹션(개요, 작업 내용, Jira 이슈, PR 유형, 확인 사항, 참고 사항)을 작성한다.
  - 해당 없는 섹션이라도 삭제하지 말고 "없음" 또는 "해당 없음"으로 명시한다.
- `작업 내용`은 도메인, API, DB, 테스트, 문서처럼 리뷰어가 변경 흐름을 따라가기 쉬운 단위로 구체적으로 작성한다.
- `PR 유형` / `확인 사항`은 해당 항목을 `[x]`로 체크한다. 체크되지 않은 항목은 `[ ]`로 그대로 둔다.
- `Jira 이슈`는 키(예: `CATCH-123`)와 링크를 함께 적는다. 없으면 "없음"으로 표시한다.
- PR 제목은 커밋 제목 컨벤션(`type(scope): 한국어 설명`)을 그대로 따른다.
- `gh pr create --body`로 본문을 전달할 때는 HEREDOC을 사용해 줄바꿈과 체크박스 마크다운이 정확히 보존되도록 한다.

### GitHub Issue Templates

- GitHub Issue는 `.github/ISSUE_TEMPLATE/` 아래의 Issue Form을 사용한다.
- 버그는 `bug_report.yml`, 기능 요청은 `feature_request.yml`, 일반 작업/문서는 `task.yml`을 사용한다.
- 빈 이슈 생성을 막기 위해 `config.yml`의 `blank_issues_enabled: false`를 유지한다.
- 템플릿을 추가하거나 필드를 바꿀 때는 이 문서의 협업 규칙도 함께 갱신한다.

이 방식을 선택한 이유는 이슈 생성 시 재현 절차, 요구사항, 완료 기준 같은 필수 정보를 누락하지 않도록 하기 위함이다.

### Tests

- 백엔드 변경 후 기본 검증 명령은 다음과 같다.

```bash
./gradlew test
```

- 테스트는 `apps/CatchHole-Backend`에서 실행한다.
- API 응답 규약을 바꾸면 MockMvc 테스트도 함께 갱신한다.
- DB 설정이 필요한 통합 테스트는 `test` profile의 H2 인메모리 DB를 기본으로 사용한다.
