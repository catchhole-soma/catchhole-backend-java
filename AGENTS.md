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
| `application-local.yml` | 로컬 개발 (JPA `validate`, SQL 로그). DB 접속은 yml에 두지 않는다 |
| `application-e2e.yml` | 브라우저 E2E (JPA `validate`, S3 대신 임시 로컬 파일 저장소) |
| `application-prod.yml` | 운영 (DB / CORS는 환경변수 주입, JPA `validate`) |
| `src/test/resources/application-test.yml` | 통합 테스트 (H2 인메모리 DB, JPA `create-drop`) |

- 기본 활성 프로파일은 `application.yml`의 `spring.profiles.active: local`. 운영 배포 시 `SPRING_PROFILES_ACTIVE=prod`로 덮어쓴다.
- 운영 환경 설정값(DB 접속 정보, 허용 origin 등)은 `${ENV_VAR}` 플레이스홀더로 두고, yml에 평문으로 적지 않는다.
- 로컬과 운영 PostgreSQL schema의 단일 변경 주체는 Flyway다. JPA `ddl-auto`는 `validate`로 두어 Entity와 migration 결과의 일치 여부만 검사하며, 공유 환경에서 `update`나 `create-drop`으로 schema를 변경하지 않는다.
- 운영 JWT 서명키는 `JWT_SECRET` 환경변수로 주입한다. 최소 32바이트 이상이어야 하며, 로그/응답/테스트 실패 메시지에 노출하지 않는다.
- 운영 Worker 내부 API key는 `INTERNAL_API_KEY` 환경변수로 주입한다. 로컬 기본값은 개발 편의를 위한 값이며 운영에서는 반드시 별도 secret을 사용한다.
- 운영 휴대폰 인증은 `SMS_PROVIDER=solapi`와 `SOLAPI_API_KEY`, `SOLAPI_API_SECRET`, `SOLAPI_SENDER_NUMBER`를 사용한다. local은 `SMS_PROVIDER`가 없거나 `fake`이면 인증번호 `123456`을 쓰고, `solapi`이면 실제 SMS를 발송한다. test/e2e는 항상 Fake provider를 사용하며, prod에서 Fake provider를 선택하면 기동을 실패시킨다.
- `PHONE_VERIFICATION_HASH_SECRET`은 JWT secret과 분리한 최소 32바이트 값으로 주입하고 인증번호·전화번호·IP HMAC에만 사용한다. 원문 인증번호·전화번호·IP·SOLAPI API secret은 로그에 남기지 않는다.
- SOLAPI 자동충전은 사용하지 않고 Redis의 전체 KST 일 20건·월 200건 제한과 선불 충전 잔액으로 SMS 비용 상한을 관리한다.
- 운영 Redis는 `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`로 연결한다. Redis 장애 시 SMS를 보내지 않는 fail-closed 정책을 유지한다.
- 로컬 실행 시 `application.yml`이 `apps/CatchHole-Backend/.env`를 optional import한다. AWS/S3 같은 로컬 비밀값은 `.env`에 둘 수 있지만, `.env`는 커밋하지 않는다.
- E2E는 `SPRING_PROFILES_ACTIVE=e2e`로 활성화하고 운영에서는 사용하지 않는다. 이 프로파일에서만 `LocalFileObjectStorage`를 사용하며 `storage.local.root` 기본값은 `${java.io.tmpdir}/catchhole-e2e-storage`, 명시적 override는 `CATCHHOLE_E2E_STORAGE_ROOT`로 둔다.
- 새로운 설정 키를 추가할 때는 base / local / prod 각 위치를 의식적으로 결정한다.
- 운영 환경은 Caddy `reverse_proxy` 뒤에서 실행되므로 `application-prod.yml`에 `server.forward-headers-strategy: framework`를 둔다. Swagger/OpenAPI server URL과 보안/리다이렉트 처리가 외부 HTTPS scheme/host를 기준으로 동작하게 하기 위함이다.
- **로컬 DB 접속 정보는 `compose.yaml` 단일 출처로 둔다.** `spring-boot-docker-compose` 의존성이 컨테이너에서 호스트/포트/사용자/비밀번호를 자동 추출해 `ServiceConnection` 빈으로 주입한다. yml에 `spring.datasource.*`를 중복 작성하지 않는다 (그림자 설정 방지).

### Database Migration

- PostgreSQL schema 변경은 `src/main/resources/db/migration`의 Flyway SQL로 관리한다.
- migration 파일은 `V{순번}__{snake_case_설명}.sql` 형식으로 작성한다.
- 공유 환경에 한 번이라도 적용된 migration은 수정하거나 삭제하지 않는다. 변경이 필요하면 다음 버전 파일에 `ALTER`, `CREATE`, `DROP`을 누적한다. Flyway checksum과 환경별 schema 이력을 일치시키기 위함이다.
- 새 Entity나 컬럼을 추가하는 작업은 JPA 매핑과 Flyway migration을 같은 PR에서 변경한다.
- Python SQLAlchemy 모델은 schema를 생성하지 않고 Flyway가 만든 공유 테이블을 조회·저장하는 매핑으로만 사용한다. 공유 컬럼을 변경하면 Java migration과 Python 매핑의 이름, 타입, nullable 여부를 함께 확인한다.
- PostgreSQL 전용 migration은 H2 기반 `test` profile에서 실행하지 않는다. migration 자체는 빈 pgvector PostgreSQL에서 실행하고, 이후 JPA `validate` 상태로 애플리케이션 기동까지 확인한다.
- 최초 V1 기준과 운영 적용 절차는 `docs/database-migration.md`를 따른다.

### Docker Deployment

- 백엔드 컨테이너 이미지는 루트 `Dockerfile`에서 빌드한다.
- Dockerfile은 Gradle Wrapper로 `bootJar`를 만드는 JDK 21 빌드 스테이지와 JRE 21 런타임 스테이지를 분리한다. 런타임 컨테이너는 non-root 사용자로 실행한다.
- 운영 컨테이너는 `SPRING_PROFILES_ACTIVE=prod`와 외부 환경변수로 설정을 주입한다. AWS/S3 자격 증명은 가능하면 EC2 IAM Role을 사용하고, access key를 이미지나 커밋 파일에 넣지 않는다.
- 운영 Backend, Python AI Worker, PostgreSQL은 `APP_TIMEZONE`을 공통으로 사용하며 기본값은 `Asia/Seoul`이다. 세 writer가 timezone 없는 `TIMESTAMP` 컬럼에 서로 다른 로컬 시각을 저장하지 않도록 JVM `user.timezone`, 컨테이너 `TZ`, PostgreSQL `timezone`을 함께 변경한다.
- 운영 AI Worker의 신규 청크 임베딩 생성은 `EMBEDDING_GENERATION_ENABLED`로 제어하고 Compose 기본값은 `false`로 둔다. MVP에서 사용하지 않는 API 비용을 차단하되 pgvector schema와 재활성화 경로는 유지하며, `true` 전환은 신규 분석·재분석에만 적용되고 기존 `NULL` 벡터를 자동 backfill하지 않는다.
- 운영 캐릭터 Fact·세계관 재비교는 같은 AI 이미지를 각각 `--worker-kind character-comparison`, `--worker-kind world-comparison` command로 실행하는 별도 서비스가 담당한다. 기본 `ai-worker`와 claim Job type을 분리하고 재비교 서비스에서는 임베딩 생성을 항상 끈다.
- 운영 `SETTING_EXTRACTION` 처리량은 `AI_WORKER_PROCESS_COUNT × AI_WORKER_CONCURRENCY`로 계산한다. 현재 검증 rollout은 분석 Worker 2개 × 프로세스당 Job 5개 = 최대 10개다. 50개 Job 부하 테스트에서 Backend·PostgreSQL·LLM 지표를 확인하고 기준 미달이면 프로세스당 3개로 되돌린다. 이 10은 별도 캐릭터·세계관 비교 Worker를 제외한 분석 Job 용량이며 provider 계정 전체의 분산 동시성 상한을 뜻하지 않는다.
- 분석 Worker는 빈 실행 슬롯을 확보한 뒤 Job 하나만 claim하고 즉시 실행한다. 미리 여러 Job을 claim해 프로세스 내부 대기열에 쌓지 않으며, `LLM_MAX_CONCURRENT_REQUESTS`와 `AI_WORKER_BLOCKING_MAX_WORKERS`로 프로세스 안의 provider 호출과 동기 DB/S3 offload를 각각 제한한다. 재비교 Worker는 Job·LLM 동시성을 1로 고정한다.
- Worker는 종료 신호를 받으면 신규 claim을 중단하고 `AI_WORKER_SHUTDOWN_GRACE_SECONDS` 동안 실행 중 Job과 heartbeat를 유지한다. Compose `stop_grace_period`는 내부 grace보다 길게 두어 정리 시간을 보장하며, 현재 운영값은 내부 180초·컨테이너 210초다. grace를 넘긴 Job은 heartbeat가 멈춘 뒤 Spring lease 회수 경로로 재처리한다.
- 운영 AI 모델은 추출 `LLM_EXTRACTION_MODEL=gpt-5.6-terra`, 캐릭터·세계관 주체 해소 `LLM_SUBJECT_RESOLUTION_MODEL=gpt-5.6-luna`, 세계관 비교·재비교 `LLM_COMPARISON_MODEL=gpt-5.6-luna`로 분리하고 `LLM_MODEL`은 개별 설정이 없을 때의 fallback으로 둔다. 공통 추론 강도는 `LLM_REASONING_EFFORT=none`을 사용한다. 실제 비밀값과 override는 `/opt/catchhole/.env`에 두고 Compose가 AI Worker에 명시적으로 전달한다.
- 로컬과 운영 PostgreSQL은 `pgvector/pgvector:0.8.2-pg16` 이미지로 통일한다. `latest`나 major version만 지정한 가변 태그를 사용하지 않고 PostgreSQL/pgvector 버전을 함께 고정해 로컬·운영의 vector extension 실행 환경을 일치시킨다.
- 단일 EC2 운영 배포 파일은 `deploy/` 아래에 둔다. `compose.prod.yml`, `Caddyfile`, `.env.example`을 기준으로 서버의 `/opt/catchhole`에 배치하되, 실제 `.env`는 서버에만 두고 커밋하지 않는다.
- 운영 PostgreSQL의 호스트 포트는 `127.0.0.1:5432`에만 바인딩한다. SSH 터널 기반 운영 점검은 허용하되 EC2 외부에 데이터베이스 포트를 직접 노출하지 않기 위함이다.
- 로컬과 운영 Redis는 `redis:7.4.10-alpine3.21`로 고정한다. 운영 Redis는 호스트 포트를 열지 않고 비밀번호, 64MB `noeviction`, 비영속 정책을 유지한다. 휴대폰 인증 데이터는 모두 단기 상태이므로 재시작 시 초기화를 허용한다.
- `main` 브랜치에 push되면 `.github/workflows/publish-image.yml`이 GHCR에 `ghcr.io/catchhole-soma/catchhole-backend-java:main`과 short SHA 태그를 발행한다.

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
│   ├── aitoken
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
│   │   ├── controller
│   │   ├── dto
│   │   │   ├── request
│   │   │   └── response
│   │   ├── entity
│   │   ├── exception
│   │   ├── mapper
│   │   ├── processor
│   │   ├── repository
│   │   ├── service
│   │   └── type
│   ├── worldsetting
│   │   ├── controller
│   │   ├── dto
│   │   │   ├── request
│   │   │   └── response
│   │   ├── entity
│   │   ├── exception
│   │   ├── mapper
│   │   ├── processor
│   │   ├── repository
│   │   ├── service
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
    │   ├── ai
    │   ├── auth
    │   ├── cors
    │   ├── jpa
    │   ├── phoneverification
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
- 분석 실행·중단·복구처럼 여러 저장소가 공유하는 도메인 용어의 canonical 이름은 루트 `CONTEXT.md`에 짧게 정의하고, 구현 상세와 운영 절차는 `docs/`에 둔다.
- 전역 개발 규칙과 컨벤션은 `AGENTS.md`에 유지하고, 도메인별 설계 의도와 구현 흐름은 `docs/`에 둔다.
- 운영 인프라의 현재 구조, 스케일링 전략, 미결정 선택지와 단계별 전환 계획은 `docs/infrastructure-flow.md`에서 관리한다. 아직 구현되지 않았거나 팀이 결정하지 않은 제품·리소스를 목표 구조로 확정해 표현하지 않아 실제 운영 상태와 제안을 구분하기 위함이다.
- 코드 변경으로 도메인 흐름, DB 모델, 상태 전이, 접근 제어가 바뀌면 관련 문서도 같은 PR에서 갱신한다.
- 상세 워크플로우를 작성할 때는 단순 함수명 나열이 아니라 요청 진입, 권한/상태 검증, 주요 분기, 저장 대상, 부수효과, 응답까지 코드 흐름을 따라갈 수 있게 쓴다.
- Mermaid 노드는 한국어로 처리 의미를 먼저 설명하고, 필요할 때만 메서드명이나 repository명을 보조 정보로 함께 적는다. `supported`, `unsupported`처럼 축약된 표현만 쓰지 말고 어떤 값이나 정책을 매핑/거절하는지 드러낸다.
- 복잡한 정책이 있는 흐름도 아래에는 `상세 처리 기준`을 두고 current 선정, 중복 방지, 부수효과 방지, tie-break, snapshot 갱신 같은 케이스별 규칙을 글로 보완한다.
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
- 클라이언트가 분기해야 하는 구조화된 도메인 충돌 정보는 `AppException`의 context로 전달하고 공통 `ErrorResponse.context`에 노출한다. 일반 오류는 빈 context를 사용하며 클라이언트가 message 문자열을 파싱하게 만들지 않는다.
- 여러 도메인에서 공통으로 쓰는 enum은 `global.common` 아래에 둔다.
- 사용자 계정 도메인은 `member`로 명명한다. Java 도메인은 `Member`, DB 테이블은 `members`, FK는 `member_id`를 사용한다.
- 인증 흐름은 `auth` 도메인에 둔다. JWT 발급/검증, refresh token 발급/폐기, 인증 쿠키, 휴대폰 인증 Redis 흐름과 SMS port/adapter는 `domain/auth` 아래에서 관리한다.

#### Auth and Token Policy

- Access token은 JWT로 발급하고, refresh token은 랜덤 opaque token으로 발급한다.
- Access token 만료 기본값은 30분, refresh token 만료 기본값은 14일이다.
- Refresh token 원문은 저장하지 않는다. `refresh_tokens.token_hash`에 SHA-256 해시만 저장하고, 재발급 시 기존 token은 `revoked_at`으로 폐기한 뒤 새 token을 저장한다.
- Refresh token은 `HttpOnly` 쿠키로 전달한다. 쿠키 path는 `/api/v1/auth`, SameSite 기본값은 `Lax`, 운영 환경에서는 `Secure=true`를 사용한다.
- 회원가입과 로그인은 access token을 응답 body로, refresh token을 HttpOnly 쿠키로 함께 발급한다. 회원가입 후 별도 로그인 요청을 요구하지 않는다.
- 회원가입은 한 화면 체크로 현재 이용약관 동의와 개인정보처리방침 확인을 함께 받되 API에서는 `termsAccepted`, `privacyPolicyAcknowledged`를 각각 `true`로 검증한다. 현재 서비스 화면 문서 버전은 `2026-08-23`이며 서버가 버전과 행위 유형(`AGREED`, `ACKNOWLEDGED`)을 `member_legal_records`에 두 행으로 기록한다. 화면 문구가 바뀌면 Front 표시 버전과 `LegalDocumentType.currentVersion`을 함께 올리며, AI 원고 처리는 별도 가입 동의나 업로드별 동의 이력으로 저장하지 않는다.
- 인증번호 발송 API에서만 하이픈 없는 `010` 시작 11자리 전화번호를 받고, 회원가입 요청에서는 전화번호를 받지 않는다. 회원가입은 10분 TTL의 1회용 `phoneVerificationToken`에서 번호를 조회해 `members.phone_number`에 unique로 저장하고 `phone_verified=true`로 생성한다. 기존 `phone_verified=false` 회원의 로그인은 허용한다.
- 인증번호는 HMAC으로 Redis에 5분, 재전송 대기는 60초, 오입력은 5회, 가입 토큰은 10분으로 고정한다. 재전송은 이전 인증 흐름을 폐기하고 가장 최근 번호만 유효하게 한다.
- 발송 제한은 Redis Lua에서 확인과 증가를 원자 처리한다. 전화번호는 1시간 5건·KST 하루 10건, IP는 1시간 10건·KST 하루 20건, 전체는 KST 하루 20건·월 200건이다. 429 제한 응답에는 `Retry-After`를 포함한다.
- 회원가입은 회원과 refresh token을 DB에 flush한 뒤 Redis `GETDEL`로 가입 토큰을 소비한다. 소비 실패 시 DB 트랜잭션을 rollback하고 이메일·전화번호 unique 제약을 최종 동시성 방어선으로 유지한다.
- SOLAPI SMS 요청은 자동 재시도하지 않는다. timeout 뒤 실제 발송 여부를 알 수 없어 중복 SMS와 비용이 발생할 수 있기 때문이다.
- 회원가입 표시 이름은 20자 이하, 비밀번호는 8~64자이면서 영문과 숫자를 각각 하나 이상 포함하도록 검증한다. 프론트 검증과 OpenAPI schema도 같은 제약을 사용한다.

#### Work Domain Policy

- Work는 로그인한 회원의 개인 작업공간 리소스로 취급한다.
- Work 생성 시 서버에서 인증된 `Member`를 소유자로 연결하며, 요청 DTO에서 소유자 식별값을 받지 않는다.
- Work 생성은 회차/설정집 업로드와 분리하며, 제목과 장르만으로 `latestEpisodeNo=0`인 작품을 먼저 만들 수 있다.
- MVP 작품 장르는 `WorkGenre` enum의 `판타지`, `로맨스`, `추리`, `코미디`, `SF`, `스포츠`, `호러`, `무협`, `일상`, `기타`로 고정하고 생성·수정 요청에서 필수 검증한다.
- Entity와 DB에는 `WorkGenre` enum 상수명을 사용하고 다른 도메인 enum과 같이 `@Enumerated(EnumType.STRING)`으로 저장한다. API는 사용자 화면 계약을 위해 `@JsonValue`·`@JsonCreator`로 한글 장르 값을 유지한다.
- `works.genre`는 `NOT NULL`과 허용 enum 상수 `CHECK`를 적용해 OpenAPI 응답 계약 밖의 값이 저장되지 않게 한다.
- 작품 설명은 목록 한 줄 소개용 선택값으로 최대 50자까지 허용하고, 빈 문자열이나 공백뿐인 값은 엔티티에서 `null`로 정규화한다.
- Work 목록 조회, 수정, 삭제는 `memberId` 기준으로 본인 작품만 허용한다.
- 존재하지 않는 작품과 다른 회원의 작품 접근은 모두 `WORK_NOT_FOUND`로 응답해 리소스 존재 여부를 노출하지 않는다.
- 본인 작품 조회가 필요한 도메인 서비스는 `WorkRepository.getOwnedWork(workId, memberId)`를 사용해 소유권 확인과 `WORK_NOT_FOUND` 응답을 일관되게 처리한다.
- Work 영구 삭제 요청은 `ACTIVE → PURGING`으로 전이해 신규 변경과 분석을 잠근 뒤, 저장소와 DB 파기가 완료되면 Work 행을 물리 삭제한다. 복구 가능한 보관 상태는 제공하지 않는다.

#### Episode / Upload Domain Policy

- 회차 원문 전문은 DB에 저장하지 않고 S3에 저장한다. DB에는 `content_s3_key`, `content_s3_version`, `content_hash`, `char_count`, `content_updated_at`만 둔다.
- 회차 원문 key는 `works/{workId}/episodes/{episodeNo}/{UUID}/{episodeNo}.txt`로 매 저장본을 고유하게 만든다. 보관된 회차 번호를 재사용해도 기존 원문을 덮어쓰지 않으면서 S3에서 회차 번호를 식별하기 위함이다.
- `episodes.content_updated_at`은 원문 직접 수정이나 파일 교체 때만 갱신하고 제목만 바꿀 때는 유지한다.
- 회차 업로드 요청 한 번은 `UploadBatch` 하나로 추적하고, 원본 파일 단위는 `UploadFile`로 추적한다.
- 업로드에서 생성된 회차는 `episodes.source_file_id`로 원본 업로드 파일을 추적한다.
- 같은 작품 안에서 회차 번호는 중복될 수 없다.
- 회차 삭제는 S3 원문·업로드 원본의 모든 version과 delete marker, `episode_chunks`, 검토 전 캐릭터·세계관 후보를 파기한 뒤 `ARCHIVED`로 전이한다. Episode 식별자와 이미 확정한 캐릭터·세계관 설정은 유지하되, 확정·무시 후보의 원문 인용과 raw AI payload는 비워 파기된 근거를 다시 노출하지 않는다. 다회차 단일 파일의 한 회차를 삭제하면 공유 업로드 원본 전체를 파기하고 `UploadFile.storageUrl`을 비우되 형제 회차의 분리 원문은 유지한다. 활성 목록, 최신 회차 번호 계산과 회차 번호 중복 검사는 `ARCHIVED` 회차를 제외한다.
- 회차 파일 교체는 새 원문을 먼저 저장한 뒤 새 content key를 제외한 기존 회차 prefix와 이전 업로드 원본을 완전 파기하고, 삭제와 같은 파생 데이터 정리를 수행한다. 자동 재분석이나 확정 설정 재계산은 하지 않으며 사용자가 경고를 확인한 뒤 해당 회차의 `SETTING_EXTRACTION`을 별도로 요청한다.
- 회차 제목은 사용자 확정값 또는 원문의 명시적 회차 제목 행에서만 가져온다. 감지하지 못하면 `null`로 두며 원본 파일명을 제목으로 대체하지 않는다.
- 회차 원고와 설정집 원본은 TXT·DOCX만 허용하고, 명시적으로 첨부한 빈 파일과 파일당 10MB 초과를 서버에서도 거절한다. multipart 요청 전체 제한은 25MB로 둔다. DOCX는 실제 압축 해제량을 누적해 20MB를 초과하거나 본문 탐색 중 ZIP 엔트리가 256개를 초과하면 거절해 압축 폭탄이 서버 자원을 고갈시키지 않게 한다.
- 설정집은 업로드 원본과 화면 조회·수정용 텍스트를 분리한다. `upload_files.storage_url`에는 최초 TXT/DOCX 원본을 불변으로 보존하고, `content_storage_url`에는 추출한 현재 텍스트의 `works/{workId}/setting-books/{settingBookId}/{normalizedOriginalBasename}.txt` 고정 key를 둔다. 편집본 파일명은 원본 경로·확장자를 제거하고 Unicode NFC로 정규화해 S3에서 작품과 파일을 식별할 수 있게 한다. TXT와 DOCX 모두 텍스트 편집을 허용하되 수정은 같은 key를 PUT하고 원본 MIME·크기를 바꾸지 않는다. S3 Versioning이 활성화된 환경의 과거 version 보관 기한은 Lifecycle 정책으로 제한한다.
- 회차 파일 처리 단계는 `source`(요청 원본) → `detected`(`DetectedEpisode*`) → `confirmation`(사용자 확정 입력) → `finalized`(`FinalizedEpisode*`) → `created`/`saved`(영속화 결과) 용어와 타입으로 구분한다. 서로 다른 단계의 값을 `episodes`, `parsed`처럼 같은 이름이나 타입으로 뭉뚱그리지 않는다.
- 회차 API의 업로드 방식은 공용 `UploadType`이 아니라 `EpisodeUploadType`의 세 값만 노출한다. 사전 감지는 `EpisodeDetectionRequest`, 최종 저장은 `EpisodeUploadRequest`로 DTO를 분리하고 multipart JSON part는 `metadata`로 통일한다.
- 최종 업로드에서 `SINGLE_EPISODE`는 `singleEpisodeNo`가 필수이며 `episodeConfirmations`를 보내지 않는다. 두 다회차 방식은 단일 회차 전용 필드를 보내지 않고, 필수 `episodeConfirmations`의 각 `detectionOrder`를 감지 결과와 일치시킨다.
- `TextDocumentReader`는 TXT·DOCX 형식/크기/빈 파일 검증과 텍스트 추출을, `EpisodeFileParser`는 원본 파일과 단일 회차 감지 힌트에서 회차 경계·번호·제목·본문을 `DetectedEpisode*`로 만드는 일을 담당한다. confirmation은 parser에 전달하지 않으며, 사용자 확정 번호·제목 적용과 `FinalizedEpisode*` 조립은 `EpisodeUploadProcessor`가 담당한다.
- 회차 원본 `UploadFile`은 `markEpisodesParsed(episodeStartNo, episodeEndNo, episodeCount)`로 최종 생성 범위와 파싱 완료를 함께 기록하고, 회차 범위가 없는 설정집은 `markParsed()`만 사용한다. API 응답도 `episodeStartNo`/`episodeEndNo`/`episodeCount`로 노출한다.
- 회차 조회, 수정, 삭제, 업로드는 모두 먼저 작품 소유권을 확인한다.
- Worker의 회차 처리 상태 변경은 단계별 엔드포인트를 나누지 않고, progress 요청의 `episodeStatus`로 명시적으로 전달한다. 자유 형식 표시 문구인 `currentStep`에서 상태를 추론하지 않는다.

#### Analysis Domain Policy

- AnalysisJob은 작품에 속한 단일 회차 AI 분석 작업의 상태와 결과 메타데이터를 추적한다. `UploadBatch`는 업로드 출처 묶음이며 분석 실행 단위가 아니다.
- 원문 텍스트는 `Episode`의 S3 저장 구조를 재사용하고, `analysis_jobs`에는 상태, 현재 단계, 모델명, 토큰 수, 요약 JSON, 마지막 실패 사유만 저장한다.
- 분석 실패 처리 이력은 `analysis_jobs.error_message`에 누적하지 않고, 후속 모니터링 기능에서 별도 기록/조회한다.
- 화면은 업로드 묶음에 생성된 회차별 `AnalysisJob.status`를 집계하고, 각 Job의 단일 대상 `Episode.status`를 단계별 상태로 보여준다.
- 신규 분석 작업은 정확히 한 회차를 `analysis_job_episode_targets`에 스냅샷으로 저장한다. 이후 회차 원본 교체나 `ARCHIVED` 전이로 과거 작업의 대상이 바뀌지 않게 하며, 과거 batch-wide 작업의 복수 target 연결은 조회 이력 호환용으로만 유지한다.
- 분석 배치 목록은 `UploadBatch`를 페이지 항목으로 사용하고, 같은 분석 목적·회차의 재시도 이력 중 최신 `AnalysisJob`만 현재 상태에 포함한다. `UploadBatch` 자체를 독립 분석 실행 식별자로 해석하지 않는다.
- Worker의 claim·진행·완료·실패 처리는 신규 Job의 단일 대상 `Episode.status`만 전이한다. 한 회차 실패가 다른 회차 Job이나 상태를 변경하면 안 된다.
- 공개 분석 작업 생성 API는 `batch_id`를 필수 입력으로 받고 `episode_id`를 선택 범위 지정자로 허용한다. `episode_id`가 없으면 batch의 현재 회차마다 Job을 하나씩 생성해 목록으로 반환하고, 있으면 해당 회차 Job 하나를 목록으로 반환한다.
- 본인 작품의 분석 작업만 생성/조회할 수 있으며, 다른 회원의 작품이나 다른 작품에 속한 분석 대상은 404로 응답한다.
- Python AI Worker는 작업 claim과 `AnalysisJob` 상태 변경에 `/api/internal/**` 내부 API를 `X-Internal-Api-Key`로 인증해 사용한다. Worker에는 원문 본문을 응답하지 않으며, 단일 `episode`의 S3 key/version/hash/charCount 메타데이터, `ACTIVE` 캐릭터 ID·대표 이름 목록, 활성 캐릭터 설정 schema를 전달한다. `ARCHIVED` 캐릭터는 이후 원고 매칭 대상에서 제외한다.
- Worker claim은 `allowedJobTypes`가 필수이며, claim 성공 시 5분 lease token을 발급한다. progress·heartbeat·complete·fail, 토큰 예약과 세계관 내부 API는 같은 `X-Worker-Lease-Token`을 검증한다. 만료 Job은 다음 claim에서 최대 3회까지 checkpoint부터 재대기시키고, 예약 중 토큰을 해제한 뒤 한도를 넘으면 실패 처리한다.
- 일반 `SETTING_EXTRACTION` Job은 `CHUNKS_READY → CHARACTER_CANDIDATES_SAVED → CHARACTER_COMPARISONS_FINISHED → WORLD_CANDIDATES_PUBLISHED → WORLD_COMPARISONS_FINISHED` checkpoint를 단조 증가시킨다. 캐릭터 또는 세계관 후보 비교가 `PENDING`/`PROCESSING`이면 완료를 거절한다. Java가 신규 checkpoint를 먼저 배포한 호환 구간에는 실제 캐릭터 비교 대기 후보가 없을 때만 구버전 Worker의 checkpoint 누락을 허용한다.
- Worker 실패는 `AnalysisFailureCode`로 분류해 `analysis_jobs.failure_code`와 후보별 비교 실패 코드에 저장한다. `error_message`는 운영 진단용 원문이며 공개 DTO의 실패 문구는 코드별 안전한 사용자 메시지만 사용한다. URL, 내부 API 경로, stack trace가 공개 응답에 섞이지 않게 한다.
- `WORLD_CANDIDATES_PUBLISHED` 이후 `SETTING_EXTRACTION`이 `AI_TOKEN_QUOTA_EXHAUSTED`로 중단되면 완료된 1차 추출·비교와 `Episode.ANALYZED`를 보존하고, 남은 세계관 후보만 같은 코드의 재개 가능한 중단 상태로 표시한다. 미해결 토큰 중단 후보나 해당 후보의 활성 `WORLD_SETTING_COMPARISON` Job이 남아 있는 동안 새 전체 분석 생성과 다른 실패 Job을 통한 우회 재시도를 차단하고 배치 단위 세계관 비교 재개 API로만 복구한다. 후보가 모두 완료·기각되고 활성 복구 Job이 사라지면 새 분석 생성은 다시 허용한다.
- 공개 분석 생성·재시도는 최소 첫 추출 예약량, 세계관 비교 시작·일괄 재개는 최소 첫 비교 예약량을 사전 확인한다. 이 검사는 빠른 사용자 피드백용이며, 동시 실행에서 실제 사용 권한은 Worker 호출 직전의 계정 잠금 기반 원자적 예약이 최종 결정한다.
- `WORLD_SETTING_COMPARISON`은 사용자 재비교 요청 한 건을 처리하는 내부 Job type이다. 공개 분석 목록·진행률·회차 실행 잠금에서 제외하고 동일 후보의 활성 Job은 하나만 허용한다. 후보가 `PENDING_REVIEW`를 벗어나 Job이 obsolete가 되면 후보 상태를 되돌리거나 실패 이력을 만들지 않고 no-op 성공시킨다.
- 세계관 후보 목록의 `activeComparisonJobCount`는 해당 배치의 `PENDING/RUNNING WORLD_SETTING_COMPARISON` Job 수를 반환한다. 프론트는 이 값으로 일괄 재개 중인 `PENDING` 후보와 활성 Job이 없는 복구 대상을 구분한다.
- `CHARACTER_FACT_COMPARISON`도 사용자 수정·매칭 변경 또는 stale proposal 한 건을 재비교하는 내부 Job type이다. `analysis_jobs.setting_candidate_id`로 후보 하나만 연결하고 공개 분석 목록·회차 상태 전이에서 제외한다. 후보가 무시되거나 다시 미매칭 상태가 되어 Job이 obsolete가 되면 실패 이력을 만들지 않고 no-op 성공시킨다.
- Worker는 분석 작업 생성과 상태 전이를 위해 백엔드 DB에 직접 접근하지 않는다. 다만 청킹, 설정 후보, 리포트 같은 분석 산출물 저장은 데이터 양과 모델 안정성에 따라 내부 API 또는 Worker의 DB 직접 저장 중 선택할 수 있으며, DB 직접 저장을 선택하면 관련 스키마/문서 변경을 함께 관리한다.

#### Character Setting Domain Policy

- 캐릭터 설정 저장 토대는 `domain/character`에 둔다. `WorkCharacter`는 작품별 캐릭터 대표/현재 설정을, `SettingCandidate`는 AI가 추출한 사용자 검토 전 후보를 저장한다.
- `SettingCandidate.candidateKind`는 값이 있는 기존 설정 후보 `SETTING`과 이름의 존재만 확인한 `CHARACTER_DISCOVERY`를 구분한다. 발견 후보는 `attributeName`, `attributeValue`, `valueType`, `valueJson`을 모두 `NULL`로 저장하고 이름·원문 표현·근거·신뢰도만 보관한다.
- `CharacterSettingSchema` registry는 실제 캐릭터 능력치 값이나 작품 내용을 저장하지 않고, AI의 `SettingCandidate.attributeName`을 해석하기 위한 canonical key, alias, pattern, 값 타입, 값 의미, merge 정책만 저장한다. 정책과 실제 값을 분리해 같은 해석 기준을 여러 작품과 Worker claim에서 재사용하기 위함이다.
- Character Setting Registry의 정책 enum은 상수별 의미를 Javadoc으로 설명하고, 화면·문서에서 재사용할 한글 표시명을 `toKorean` 필드로 둔다.
- `character_setting_schemas.work_id`가 `NULL`이면 전역 schema, 값이 있으면 해당 작품에 전역에 없는 key를 추가하는 schema로 사용한다. 작품별 override와 같은 key 중복 병합은 별도 우선순위 정책이 정해질 때까지 구현하지 않는다.
- Worker claim에는 활성 전역 schema와 현재 작품의 활성 추가 schema를 모든 분석 job type에 포함하되 `schemaKey`, `displayName`, `attributePattern`, `aliases`, `valueType`만 노출한다. registry 식별자, source, enabled, merge 정책은 내부 정책으로 유지한다.
- 후보 confirm의 schema 해석은 `domain/character/processor/SettingCandidateSchemaResolver`가 담당한다. 활성 전역/현재 작품 schema 전체에서 앞뒤 공백을 제거하고 대소문자를 유지한 채 schemaKey 정확 일치 → 별칭 → 마지막이 `.*`로 끝나는 속성 패턴 순으로 매칭한다. 같은 단계에서 여러 schema가 일치하면 임의 선택하지 않고 오류로 거절한다.
- `aliases_json`에는 분류 경로가 없는 별칭 문자열만 저장한다. 후보 속성명은 별칭 자체 또는 `schemaKey`와 같은 분류 경로를 붙인 값만 정확히 비교하며, 다른 분류 경로·부분 문자열·대소문자 변환·fuzzy/LLM 매칭을 허용하지 않는다.
- exact/alias 매칭은 canonical `schemaKey`, pattern 매칭은 trim한 원본 `attributeName`을 `CharacterFact.factKey`로 사용한다. `CharacterFact.factType`은 matched schema에서 가져온다.
- matched schema와 `SettingCandidate`의 `SettingValueType` enum이 같은지, merge policy가 현재 지원하는 `REPLACE` 또는 `UPSERT_BY_NAME`인지 검증한 뒤에만 캐릭터 조회/생성과 `CharacterFact` 저장을 진행한다. 매칭 없음·복수 매칭·타입 불일치·미지원 정책은 confirm 트랜잭션을 롤백해 후보 상태와 캐릭터 설정에 부수효과를 남기지 않는다.
- 적용된 registry seed의 승격이나 alias 수정이 필요하면 기존 Flyway migration을 수정하지 않고 다음 migration에서 변경한다. 환경별 checksum과 schema 해석 이력을 보존하기 위함이다.
- `SettingCandidate` 생성은 Python AI Worker가 담당하고, Spring API는 사용자 검토를 위한 조회/수정부터 담당한다. 후보 생성 API를 Spring에 추가해야 할 때는 Worker 저장 책임을 함께 재검토한다.
- `SettingCandidate` 수정은 `PENDING_REVIEW` 상태에서만 허용한다. 확정/무시 이후 수정은 `CharacterFact` 반영 정책과 동기화 문제가 생기므로 별도 정책이 정해질 때까지 막는다.
- `CHARACTER_DISCOVERY` 후보는 설정 콘텐츠가 없으므로 일반 설정명·표시값 수정 API에서 거절하고, 캐릭터 연결 해소와 확정/무시만 허용한다.
- 설정 후보 MVP 내용 수정은 사용자용 설정명과 표시값만 받으며, 캐릭터 연결 API와 내용 수정 API는 서로의 필드를 변경하지 않는다.
- 후보 응답의 설정명 편집 가능 여부와 prefix는 현재 작품의 활성 schema를 동일 resolver로 해석한 `attributeNameEditable`, `attributeNamePrefix`로 제공한다. exact/alias는 잠그고 pattern만 열며, 해석 불가 후보 조회는 실패시키지 않고 이름 편집을 잠근다.
- 이름과 값이 실질적으로 바뀌지 않은 후보와 캐릭터 연결만 바뀐 후보는 기존 AI `valueJson`을 그대로 유지한다. 동적 suffix 공백과 표시값 앞뒤 공백의 저장 문자열 정규화는 rich JSON을 축소하지 않고 적용한다.
- `SettingValueType.JSON` 복합 후보의 이름 또는 값이 실제로 바뀌면 기존 prefix를 유지하고 suffix와 `valueJson.name`을 동기화한 뒤, 현재 후보 `valueJson`을 name-only object로 의도적으로 교체한다. 타입 계약이 없는 숨은 속성은 merge·추측·보존하지 않는다.
- 후보 내용 수정은 `valueType`, `evidenceSpans`, `rawAiResultJson`을 변경하지 않는다. `rawAiResultJson`은 최초 AI payload 보관용이며 confirm 시 수정 전 구조화 값을 복원하는 source로 사용하지 않는다.
- confirm은 현재 후보 `valueJson`을 append-only `CharacterFact`에 보존한다. AI 비교가 `ADD`/`UPDATE`/`MERGE`를 제안한 경우 snapshot에는 Worker가 반환한 `proposedFactValue`와 `proposedValueJson`을 반영한다. 복합 후보 수정 전 rich JSON을 최초 AI payload에서 다시 복원하지 않으며 중첩 typed JSON 편집은 후속 범위다.
- `SettingCandidate` 확정/무시는 POST action API로 처리한다. 처음 `CONFIRMED`로 전환되는 `SETTING` 후보는 `CharacterFact`를 항상 새 이력으로 저장하고, 기본 `APPLY_PROPOSAL`은 완료된 비교 제안을 `WorkCharacter` snapshot과 provenance에 반영한다. 사용자가 `HISTORY_ONLY`를 선택하면 Fact만 저장하고 snapshot·provenance·snapshotVersion은 바꾸지 않는다. `EXCLUDE`는 무시 action을 사용하며 `REVIEW_REQUIRED`는 제안 적용 확정을 막되 이력 저장은 허용한다. `CHARACTER_DISCOVERY` 후보는 캐릭터와 최초 등장만 반영하고 Fact를 만들지 않는다.
- 신규 검토 화면은 같은 `batchId + 정규화한 entityName`의 모든 대기 후보를 그룹 전용 confirm API 한 번으로 처리한다. 요청 ID가 서버의 전체 대기 그룹과 정확히 일치하는지 잠금 뒤 검증하고 부분 성공을 허용하지 않는다. row 내용 수정·무시와 단건 캐릭터 연결은 유지하되, 캐릭터 이름은 내용 수정 API에서 받지 않는다. 그룹 전체의 캐릭터 연결·변경은 전용 일괄 연결 API로 처리한다. 동일 신규 이름의 `UNRESOLVED` 그룹은 `WorkCharacter` 하나를 만든 뒤 회차·생성 순으로 Fact와 snapshot을 반영한다.
- `AGE`, `LEVEL` 후보는 확정 전에 구조화 대표값을 우선 확인하고, 값이 없을 때만 표시값을 사용해 0 이상이면서 Java `Integer` 범위의 정확한 정수인지 검증한다. 소수·음수·범위 초과 값은 캐릭터 결정과 Fact 저장 전에 거절해 상세 응답과 전체 수정 계약이 어긋나지 않게 한다.
- 후보 `valueJson` object의 정확한 `value` key는 숨겨진 대표값 envelope로 유지한다. `PROFILE`, `STAT`, `SKILL`, `ITEM`, `STATUS`의 나머지 최상위 공개 property는 상세 응답을 전체 수정 요청으로 그대로 왕복할 수 있도록 key가 공백 없이 100자 이하이고 앞뒤 공백·예약 key 충돌·정규화 후 중복이 없어야 하며, 직접 문자열 값은 비어 있지 않고 앞뒤 공백이 없어야 한다. 공개 property가 있는 scalar schema는 선언 타입과 호환되는 `value` envelope도 가져야 한다. 위반 후보는 캐릭터 결정과 Fact 저장 전에 거절한다.
- `UNRESOLVED` 캐릭터 후보 confirm에서 동명 `ACTIVE` 캐릭터가 이미 있으면 즉시 적용하지 않고 후보를 `MATCHED + PENDING`으로 바꿔 숨김 비교 Job을 생성한 뒤 409로 재조회시킨다. 동명 `ARCHIVED` 캐릭터는 명시적 이름 충돌로 거절한다. 실제로 새 `WorkCharacter`를 만든 빈 snapshot만 deterministic `ADD`를 허용하며, 같은 이름 형제 후보는 `AUTO_MATCHED_BY_NAME + PENDING`으로 연결해 각각 비교한다.
- `NUMBER` 후보의 `attributeValue`는 Java `BigDecimal`로 해석 가능한 숫자 문자열이고 `valueJson.value`는 JSON number이며 두 값은 수치적으로 같아야 한다. `BOOLEAN`은 표시값을 소문자 `true`/`false`로 제한하고 JSON boolean과 일치시킨다. 이 계약은 저장 후보와 Worker proposal에 같게 적용해 표시값과 snapshot 값이 엇갈리지 않게 한다.
- 후보 응답의 `valueValidation`(`VALID`, `INVALID`, `NOT_APPLICABLE`)은 현재 활성 schema와 저장된 값에서 조회 시점에 파생하고 DB에 저장하지 않는다. 값 계약 오류도 수정 API를 사용할 수 있는 `PENDING_REVIEW`에서만 `repairable=true`이며, 확정·무시 후보와 schema 없음·모호성·타입 불일치는 `repairable=false`로 내려 현재 수정 API로 고칠 수 있다고 안내하지 않는다. 두 종류 모두 2차 비교와 단건·그룹 확정 전에 fail-closed로 거절한다.
- 원 분석 또는 hidden 비교 Job이 `INVALID` 후보를 claim하면 비교 제안을 비우고 `NOT_REQUIRED`로 격리한 뒤 다음 후보를 계속 처리한다. `PENDING_REVIEW`는 유지해 무시와 가능한 수정을 허용하고, 수정에 성공하면 기존 내용 변경 상태 전이로 다시 `PENDING` 비교를 요청한다.
- confirm으로 생성하는 `CharacterFact`는 `setting_candidate_id` FK로 원본 `SettingCandidate`를 연결하고, 구체적인 원문 인용은 후보의 `evidence_spans`에서 조회한다. 기존 Fact는 추정 backfill하지 않고 `NULL`로 유지하며, 근거 JSON을 Fact에 중복 저장하지 않는다.
- `SettingCandidate` 확정 반영처럼 후보/요청 데이터를 저장용 Entity로 변환하는 코드는 service에서 `Entity.create()` 파라미터를 직접 조립하지 말고 mapper의 `toEntity` 계열 메서드로 분리한다. service는 권한 확인, 조회, 트랜잭션 흐름, 저장 호출, 도메인 메서드 조율에 집중한다.
- `CharacterFact`는 append-only 타임라인이다. 값·근거·current 상태를 수정하지 않으며 `is_current` 컬럼도 사용하지 않는다. 현재값의 유일한 authority는 `WorkCharacter.currentAge/currentLevel`과 JSON snapshot이다.
- `MATCHED` 또는 `AUTO_MATCHED_BY_NAME` 후보 반영은 `SettingCandidate`와 `WorkCharacter`를 pessimistic write lock으로 조회하고, 새 Fact append, snapshot 변경, `character_snapshot_sources` provenance 변경, `snapshotVersion` 증가를 한 트랜잭션에서 처리한다. 관련 slot의 값 또는 source가 달라질 때만 트랜잭션당 version을 정확히 한 번 증가시킨다.
- JSON snapshot entry는 사용자 `valueJson`을 그대로 유지하면서 표시용 `factValue`를 잃지 않도록 내부 `__catchhole_snapshot` envelope에 둘을 함께 저장한다. API는 envelope를 노출하지 않는다. 기존 raw entry는 그대로 읽고 source Fact의 표시값으로 보완하며, 다음 실제 수정 시 새 envelope로 점진 전환한다.
- `character_snapshot_sources`는 현재 snapshot slot을 만든 Fact를 순서대로 연결한다. `ADD`/`UPDATE`는 source를 새 Fact 하나로 교체하고 `MERGE`는 기존 source 뒤에 새 Fact를 추가하며, 제거 제안은 slot과 source link만 제거한다. Fact 자체와 원문 근거는 항상 타임라인에 남는다.
- 캐릭터 현재 설정 전체 수정은 요청과 `WorkCharacter` snapshot을 비교한다. 변경·추가는 수동 `CharacterFact`를 append하고 source link를 교체하며, 삭제는 snapshot과 source link만 제거한다. `TIME` 레거시 slot은 화면 수정 범위 밖이므로 보존한다.
- 캐릭터 상세에서 새로 추가하는 설정도 후보 확정과 같은 exact → alias → pattern 순서로 해석한다. exact·alias는 canonical `schemaKey`, pattern은 의미 있는 suffix key를 사용하며 canonicalize 후 같은 key는 중복으로 거절한다. `manual_`·미등록 custom key는 기존 저장 데이터와 구버전 요청 호환용으로만 key를 유지한다.
- 캐릭터 상세 설정의 편집 계약은 활성 schema로 해석한다. exact key는 key와 표시명을 모두 잠그고, pattern key는 응답의 `attributeNamePrefix`를 보존한 suffix만 수정하며, 레거시 `manual_`·미등록 custom key는 key를 유지한 채 표시명만 수정한다. pattern suffix의 공백은 저장 key에서 underscore로 정규화하고 화면 표시명과 새 `valueJson.name`은 underscore를 공백으로 바꾼 값을 사용한다.
- 캐릭터 상세 설정 응답은 `attributeNameEditable`, `attributeNamePrefix`, `displayNameEditable`을 제공한다. exact는 `false/null/false`, pattern은 `true/<prefix>/true`, 수동 custom은 `false/null/true`다.
- 같은 key와 `factValue`의 exact·pattern 설정, 또는 key·값·표시명·타입이 같은 custom 설정은 요청에 숨은 property가 빠져도 기존 `valueJson`, Fact ID와 근거를 그대로 유지한다. 실제 변경이면 클라이언트가 다시 보낸 숨은 속성을 신뢰하거나 deep merge하지 않는다. JSON pattern/custom은 `{"name": ...}`, scalar pattern/custom은 typed `value`와 `name`, scalar exact는 typed `value`만 가진 새 Manual Fact를 생성하며 이전 rich JSON은 historical Fact에 남긴다.
- 캐릭터 현재 설정 수정 요청의 property가 `JSON` 타입이면 문자열은 정확히 하나의 완전한 JSON 값이어야 한다. 한 문자열에 여러 JSON 값이 이어진 입력은 `CHARACTER_SETTING_VALUE_INVALID`로 거절하고 캐릭터·Fact 변경을 남기지 않는다. 설정의 최상위 `value`는 사용자용 표시 문자열이므로 설정 자체가 `JSON` 타입이어도 raw JSON으로 파싱하지 않는다.
- 캐릭터 화면의 삭제 액션은 hard delete가 아니다. `DELETE /api/v1/works/{workId}/characters/{characterId}`는 `WorkCharacter.status`를 `ACTIVE`에서 `ARCHIVED`로 바꾸고 Fact와 근거 데이터를 유지한다. 기본 목록·상세·수정은 `ACTIVE` 캐릭터만 대상으로 한다.
- 보관함은 `ARCHIVED` 캐릭터만 페이지 조회하며 기본 페이지 크기는 9다. 복구는 작품 row와 보관 캐릭터를 순서대로 pessimistic write lock으로 조회한 뒤 `ACTIVE` 이름 중복을 검증하고 `ACTIVE`로 전환한다. 보관·복구는 `CharacterFact`, `SettingCandidate`, 원문 근거를 수정하지 않는다.
- 작품 내 캐릭터 이름 중복은 `ACTIVE` 상태끼리만 금지한다. 이름 수정과 복구는 다른 `ACTIVE` 동명이 있을 때만 거절하고, `ARCHIVED` 캐릭터끼리 또는 `ACTIVE`와 `ARCHIVED` 사이의 동명은 허용한다. 후보 확정·이름 수정·복구는 작품 row의 pessimistic write lock 아래에서 검사와 생성을 직렬화해 `(workId, name)`별 `ACTIVE` 캐릭터가 최대 하나만 존재하도록 한다.
- CharacterFact 설정 검색은 설정DB의 MVP 검색 구현이다. `ACTIVE` 캐릭터의 `AGE`, `LEVEL`, `STAT`, `SKILL`, `ITEM`, `STATUS`만 대상으로 하며, trim한 검색어의 `%`, `_`, `\`를 literal로 escape한 대소문자 무시 `LIKE` 부분 일치를 사용한다. `character_snapshot_sources` 존재 여부를 현재 snapshot 기여 여부로 계산하고, 기여 Fact 우선 → 적용 회차 내림차순(`NULL` 마지막) → 생성 시각 내림차순 → Fact ID 오름차순으로 고정 정렬한다.
- 장소·세계관·타임라인·관계 등 다른 설정 모델이 준비되면 CharacterFact 전용 API에 유형을 억지로 추가하지 않고 결과 유형과 식별자를 구분하는 통합 설정 검색으로 확장한다. 작품당 Fact가 1만 건 이상으로 늘거나 검색 p95가 200ms를 넘으면 `pg_trgm` 또는 별도 검색 인덱스를 검토한다.
- CharacterFact 상세 근거는 `setting_candidate_id`로 연결된 후보의 `evidence_spans[*].quote`만 저장 순서대로 제공한다. 출처 회차는 Fact를 우선하고 후보 회차를 fallback으로 사용하며, 보관 캐릭터 Fact와 다른 작품 Fact는 404로 숨긴다.
- 캐릭터 상세가 제공하는 `characterFactId`와 `hasEvidence`는
  `GET /api/v1/works/{workId}/character-facts/{characterFactId}/evidence`의 진입점이다.
  근거 조회는 `CharacterFact.settingCandidate`의 `evidenceSpans`와 분석 당시
  `sourceContentS3Key`를 사용하며 S3 key 자체는 응답하지 않는다. V12 이전 후보는 현재
  `Episode.contentS3Key`로만 fallback한다.
- V20은 기존 `is_current=true` 중 slot별 회차·생성·ID 기준 최신 한 건만 provenance로 backfill하고 `character_facts.is_current`를 제거한다. 중복 current 이상 데이터를 임의 MERGE로 해석하지 않는다. 컬럼 drop 때문에 V20 이후 구버전 애플리케이션 이미지로의 단순 rollback은 지원하지 않으며 forward repair migration이 필요하다.
- `WorkCharacter.firstAppearanceEpisodeId`는 확정 순서가 아니라 가장 이른 업로드 회차 기준으로 유지한다.
- 화면 표시, 검색, 비교에 자주 쓰는 캐릭터 이름, 역할, 현재 나이, 현재 레벨은 일반 컬럼으로 둔다.
- 검토 상태는 `SettingCandidate`에만 둔다. `WorkCharacter`와 `CharacterFact`는 사용자가 후보를 승인한 뒤 저장되는 대표 설정과 설정 이력이므로 별도 review status를 두지 않는다.
- 작품마다 구조가 달라지는 프로필, 스탯, 스킬, 아이템, 상태 상세값과 AI 원본 응답, 근거 span은 `JsonNode` + Hibernate JSON 매핑으로 JSONB 컬럼에 저장한다. 이 구조는 장르별 설정 차이를 수용하면서도 자주 조회하는 핵심 값은 일반 컬럼으로 유지하기 위한 선택이다.
- `setting_candidates.source_chunk_id`는 청킹 Entity가 생기기 전까지 FK 없는 UUID로 저장한다. `ManuscriptChunk` 구현 이후 실제 FK 제약 여부를 다시 결정한다.

#### World Setting Domain Policy

- 세계관 설정은 캐릭터 `SettingCandidate`와 섞지 않고 `domain/worldsetting`의 `WorldSetting`, `WorldSettingCandidate`로 관리한다. MVP 저장 테이블은 `world_settings`, `world_setting_candidates` 두 개이며 `world_setting_facts`나 삭제·보관·복원·전체 변경 로그는 만들지 않는다.
- `WorldSetting` 한 행은 `workId + category + normalizedSubjectName` 대상 하나다. 세부 설정은 루트의 문자열 leaf 또는 선택적 1단계 `scopeName` 아래 문자열 leaf로 JSON object에 저장하고, 생성 후 빈 object 상태는 허용하지 않는다. 이 범위 계층은 세계관 도메인에만 적용하며 캐릭터 `SettingCandidate`/`CharacterFact`의 key 계약을 변경하지 않는다.
- 대상명·범위명·설정명·설정값은 앞뒤 공백만 제거하며 내부 공백은 보존한다. 대상 중복키는 Unicode NFC와 `Locale.ROOT` 소문자로 정규화하고, property 중복은 `scopeName + settingName` 전체 경로를 같은 기준으로 정규화해 검사한다. `1층/출몰 규칙`과 `2층/출몰 규칙`은 동시에 허용하지만 같은 전체 경로는 허용하지 않는다. 동일 루트 key를 문자열 leaf와 scope object로 동시에 쓰는 충돌도 거절한다.
- 직접 생성·수정 API는 JSON 전체를 받지 않는다. 대상 정보 수정, 설정 한 개 추가, 설정 한 개 수정 요청을 분리하고 현재 `version`을 확인한 뒤 실제 변경마다 version을 증가시킨다.
- 사용자 직접 입력의 동일 분류·대상과 동일 대상 내 범위+설정명 전체 경로 중복은 Backend가 전체 DB와 unique 제약을 기준으로 최종 판정한다. Frontend의 현재 페이지나 필터 결과를 최종 중복 근거로 사용하지 않는다.
- 세계관 후보 한 행은 회차에서 추출한 설정 속성 하나다. 1차 추출, 2차 비교 제안, 최종 사용자 결정을 같은 행에 보존하되 LLM은 확정본을 직접 변경하지 않고 Spring의 confirm 트랜잭션만 `WorldSetting`을 변경한다.
- 하나의 분석 Job에는 정규화한 `category + subjectName + scopeName + settingName`이 같은 세계관 후보를 한 건만 게시한다. AI는 여러 1차 추출값을 `SINGLE/MERGED/CONFLICT`로 판정하고 모든 원문 근거를 보존한다. `CONFLICT` 후보는 사용자가 최종값을 수정했다는 명시적 결정 없이는 반영하지 않지만 그룹 제외는 허용한다. Backend는 Worker 게시와 사용자 그룹 확정 양쪽에서 동일 전체 property 경로 중복을 방어한다.
- 작가가 후보의 최종 분류·대상·범위·설정명·반영 방식·값을 저장하면 전용 결정 수정 API가 `PENDING_REVIEW + COMPLETED` 상태를 유지한 채 해당 후보의 `final*` 초안을 즉시 갱신한다. 일반 수정은 한 후보만, 분류·대상 일괄 수정은 같은 현재 그룹의 모든 요청 후보를 한 트랜잭션으로 저장하며, 이후 후보 조회·필터·그룹 key는 `final*` 초안을 우선 사용한다. 이 저장은 2차 LLM 재비교로 보내거나 비교 제안을 초기화하지 않는다. confirm 트랜잭션에서 현재 `WorldSetting`을 기준으로 `ADD`는 전체 경로 부재, `UPDATE/MERGE`는 전체 경로 존재, 루트 key와 범위명 무충돌을 직접 검증하고 구조적 오류를 반환한다. 수정하지 않은 LLM 제안의 외부 stale 문맥만 기존 `RECOMPARISON_REQUIRED` 흐름을 사용한다.
- 세계관 그룹 확정은 화면의 남은 검토 대기 row 전체를 한 번에 적용하는 흐름을 기준으로 한다. 예전 체크박스 부분 확정을 전제로 신규 대상 생성 뒤 선택하지 않은 row를 재비교 상태로 바꾸지 않으며, 재비교는 확정 전 외부에서 대상·속성이 달라진 stale 문맥에만 사용한다.
- 같은 `batchId + category + normalizedSubjectName` 원본 비교 그룹의 선택 후보는 대상 그룹 확정 요청 하나로 처리하되, 작가가 row별 최종 분류·대상을 다르게 정할 수 있다. 최종 대상 key 순서로 각 대상을 잠그고 모든 대상·property를 먼저 검증한 뒤 최종 대상별 생성·변경을 한 트랜잭션으로 반영하며, 같은 최종 대상 안의 앞선 `ADD`를 다음 후보의 재비교 사유로 판단하지 않는다. 여러 최종 대상에 반영하면 그룹 응답의 단일 `worldSettingId`·version은 `null`이고 후보별 연결 대상으로 결과를 표현한다.
- 외부 변경이 관련 key에만 영향을 주면 해당 row, 대상 생성·삭제·identity 변경이면 그룹 전체를 `RECOMPARISON_REQUIRED`로 전환하고 선택 후보를 하나도 부분 반영하지 않는다. 같은 행의 다른 설정 변경으로 version만 달라진 경우는 허용한다.
- 1차 `evidence_spans`·회차는 후보 원본으로 보존하고 2차 비교·재비교가 변경하지 않는다. 원고가 바뀐 경우에만 새 1차 분석 후보와 근거를 생성한다.
- 기존 속성과 의미가 같아 `EXCLUDE`하는 비교 결과는 대상 ID와 실제 속성명을 함께 받아 해당 속성값을 `beforeValue`로 보존한다. 특정 기존 속성과 비교하지 않은 일시적 사건 등의 제외만 `beforeValue`가 없을 수 있으며, 매칭 속성명만 있고 대상이 없는 요청은 거절한다.
- 재비교 충돌은 후보 상태를 먼저 commit한 뒤 HTTP 409로 응답해야 한다. Service는 `WorldSettingCandidateConfirmResult.recomparisonRequired`를 정상 반환하고 Controller가 commit 이후 `AppException`으로 변환한다. 이를 위해 전용 예외 클래스를 추가하거나 `noRollbackFor=AppException.class`로 다른 확정 오류의 rollback 범위를 넓히지 않는다.
- 같은 확정·제외 요청은 멱등 처리하고 `CONFIRMED ↔ DISMISSED` 반대 전이는 충돌로 거절한다. `UPDATE`와 `MERGE`는 DB에서 모두 최종 문자열로 한 property를 교체하되 제안 의미를 기록하기 위해 enum을 구분한다.
- `recompare` API는 비교 제안을 비우고 `PENDING`으로 전환한 뒤 멱등한 `WORLD_SETTING_COMPARISON` Job을 생성한다. 실제 LLM 호출은 별도 AI comparison runner가 claim해 수행하며 HTTP 요청 트랜잭션 안에서 호출하지 않는다.
- 1차 세계관 후보 게시와 2차 비교 상태 전이는 `domain/worldsetting` 내부 Worker API만 수행한다. Backend는 lease·작품·분류·후보 소유권, 최대 3개 비교 문맥 ID·version, exact 대상과 property를 검증하고 `beforeValue`와 base version을 직접 산출한다. Worker는 `world_settings`를 직접 수정하지 않는다.
- 같은 회차를 재분석할 때 이전 Job의 검토 전 세계관 후보만 정리하고 `CONFIRMED`/`DISMISSED` 후보는 보존한다. 같은 Job의 lease 재시도는 checkpoint를 기준으로 이미 게시한 후보를 재생성하지 않는다. 후보별 비교 실패는 후보를 `FAILED`로 남기되 초기 회차 Job의 나머지 후보 비교와 완료는 계속한다.
- 작품 hard delete 시 `world_settings`와 `world_setting_candidates`도 함께 정리되도록 두 테이블의 `work_id` FK와 JPA 매핑에 delete cascade를 유지한다. 이는 개별 세계관 대상 삭제·보관·복원 기능을 허용하는 규칙이 아니다.

#### Service Layer

- Service는 **interface와 구현체를 분리**한다.
  - interface: `<Domain>Service` (예: `UserService`)
  - 구현체: `<Domain>ServiceImpl` (예: `UserServiceImpl`)
  - 같은 `service/` 패키지에 함께 둔다 (flat 구조).
- 구현체는 `@Service`를 붙이고, 의존성은 `@RequiredArgsConstructor`로 생성자 주입한다.
- Controller는 interface에만 의존한다 (`UserService`를 주입받고 `UserServiceImpl`을 직접 참조하지 않는다).
- Service와 Controller의 유스케이스 메서드는 `createWork`, `confirmSettingCandidate`, `sendPhoneVerificationCode`처럼 동작과 대상을 함께 드러낸다. 클래스 문맥만으로 의미를 추론해야 하는 `start`, `confirm`, `process` 같은 단독 이름은 피한다.
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
- Spring Boot 4의 MVC 응답 DTO에 Hibernate JSONB용 Jackson 2 `JsonNode`를 직접 노출하지 않는다. Jackson 3가 이를 JSON 값이 아닌 bean 메타데이터로 직렬화할 수 있으므로, Mapper에서 명시적인 record·`List`·`Map` 응답 타입으로 변환한다. Service가 JSON 직렬화 차이를 보정하지 않게 해 유스케이스 흐름과 계층 경계를 유지하기 위함이다.

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
- 클라이언트 코드 생성에 사용할 수 있도록 operationId는 API별로 고유하고 안정적인 이름을 명시한다.
- OpenAPI 전역 security requirement는 사용하지 않는다. 사용자 JWT 인증이 필요한 Controller 또는 API에는 `@SecurityRequirement(name = "bearerAuth")`를, `/api/internal/**` 내부 API에는 `@SecurityRequirement(name = "internalApiKey")`를 명시한다. `internalApiKey`는 `X-Internal-Api-Key` 헤더를 사용하는 API Key 방식으로 정의하고, signup/login/refresh 같은 공개 Auth API는 security requirement 없이 노출한다.
- Swagger에 노출하지 않을 framework 파라미터(`JwtAuthenticationToken` 등)는 `@Parameter(hidden = true)`로 숨긴다.
- 쿠키/헤더/쿼리 파라미터는 `@Parameter(in = ParameterIn.COOKIE/HEADER/QUERY, name, description)`로 문서화한다.
- request / response DTO record에는 class-level `@Schema(description)`를 붙이고, 주요 필드에는 `@Schema(description, example)`를 작성한다.
- 비밀번호, 토큰, 쿠키처럼 민감한 값은 실제 값이 아닌 더미 예시를 사용한다.
- 실패 응답은 성공 payload schema가 아니라 실제 `CommonResponse<Void>` 형태를 표현하는 오류 schema로 문서화한다.
- 예외 메시지, validation 메시지, OAuth2Error description, Swagger/OpenAPI description처럼 사람이 읽는 문장은 한국어로 작성한다. `accessToken`, `refreshToken`, `JWT`, `Bearer`, enum code처럼 API 필드명이나 기술 식별자는 그대로 사용할 수 있다.

이 방식을 선택한 이유는 Swagger UI만 보고도 프론트엔드와 백엔드 작업자가 요청 값, 인증 방식, 성공/실패 응답을 빠르게 확인하고 테스트할 수 있도록 하기 위함이다.

### Common Response

- API 응답 Envelope는 `CommonResponse<T>`를 사용한다.
- 서버 페이지네이션 응답은 `PageResponse<T>`를 사용하고 `content`, 0부터 시작하는 `page`, `size`,
  `totalElements`, `totalPages`, `hasNext`를 제공한다. 도메인마다 페이지 메타데이터 DTO를 반복하지 않고
  프론트 생성 클라이언트가 같은 구조를 재사용할 수 있게 하기 위함이다.
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
- 운영 로그의 사람이 읽는 문장은 한국어로 작성한다. `SOLAPI`, HTTP status, enum code 같은 기술 식별자는 그대로 사용할 수 있다.
- 불필요한 추상화나 미래 대비용 확장 포인트를 만들지 않는다.
- 주석은 복잡한 의도를 설명할 때만 짧게 작성한다.
- Entity에서 nullable 여부가 전역/작품 범위 같은 도메인 의미를 갖거나, JSON·정책 컬럼의 저장 목적이 이름만으로 명확하지 않으면 필드 위에 한국어 주석으로 의미와 필요한 예시를 남긴다.

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
- 테스트 클래스와 테스트 메서드에는 리뷰어가 의도를 바로 이해할 수 있도록 한글 `@DisplayName`을 작성한다. Java 메서드명은 기존처럼 영어 동작 설명형으로 유지한다.
- 도메인 Entity 상태 전이, Mapper 변환, Service 분기/검증처럼 Spring Context 없이 검증 가능한 로직은 단위 테스트를 우선 추가한다.
- API 인증/인가, 요청/응답 JSON, Repository 쿼리, JPA 매핑처럼 프레임워크 경계가 중요한 흐름은 MockMvc 또는 JPA 통합 테스트로 검증한다.
- API 응답 규약을 바꾸면 MockMvc 테스트도 함께 갱신한다.
- DB 설정이 필요한 통합 테스트는 `test` profile의 H2 인메모리 DB를 기본으로 사용한다.
- 휴대폰 인증의 TTL, Lua rate limit 경계, 이전 코드 폐기, 오입력 잠금과 동시 토큰 소비는 `redis:7.4.10-alpine3.21` Testcontainers 통합 테스트로 검증한다.
