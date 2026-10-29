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
- `SIGNUP_VERIFICATION_METHOD=PHONE`에서 운영 휴대폰 인증은 `SMS_PROVIDER=solapi`와 `SOLAPI_API_KEY`, `SOLAPI_API_SECRET`, `SOLAPI_SENDER_NUMBER`를 사용한다. local은 `SMS_PROVIDER`가 없거나 `fake`이면 인증번호 `123456`을 쓰고, `solapi`이면 실제 SMS를 발송한다. test/e2e는 항상 Fake provider를 사용하며, prod에서 Fake provider를 선택하면 기동을 실패시킨다.
- `PHONE_VERIFICATION_HASH_SECRET`은 JWT secret과 분리한 최소 32바이트 값으로 주입하고 인증번호·전화번호·IP HMAC에만 사용한다. 원문 인증번호·전화번호·IP·SOLAPI API secret은 로그에 남기지 않는다.
- SOLAPI 자동충전은 사용하지 않고 Redis의 전체 KST 일 20건·월 200건 제한과 선불 충전 잔액으로 SMS 비용 상한을 관리한다.
- 운영 Redis는 `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`로 연결한다. Redis 장애 시 SMS를 보내지 않는 fail-closed 정책을 유지한다.
- 작품 영구 삭제는 운영·local에서 `WORK_PURGE_SCHEDULING_ENABLED=true`, `WORK_PURGE_FIXED_DELAY_MS=10000`, `WORK_PURGE_BATCH_SIZE=10`을 기본으로 두어 요청을 빠르게 재시도하되 한 번의 DB 점유를 제한한다. 실행 중 Worker 정리와 장애 회수는 `WORK_PURGE_WORKER_DRAIN=75s`, `WORK_PURGE_STALE_PROCESSING=15m`, 완료 감사 보존과 정리는 `WORK_PURGE_AUDIT_RETENTION=365d`, `WORK_PURGE_CLEANUP_CRON="0 20 3 * * *"`를 기본으로 사용한다. 운영 부하에 따라 환경변수로만 override하며 test 프로파일은 스케줄러를 끈다.
- 회원 탈퇴 조정은 운영·local에서 `MEMBER_WITHDRAWAL_SCHEDULING_ENABLED=true`, `MEMBER_WITHDRAWAL_FIXED_DELAY_MS=10000`, `MEMBER_WITHDRAWAL_RETRY_DELAY=10s`, `MEMBER_WITHDRAWAL_BATCH_SIZE=10`을 기본으로 사용한다. 완료 감사 row는 `MEMBER_WITHDRAWAL_AUDIT_RETENTION=365d` 뒤 `MEMBER_WITHDRAWAL_CLEANUP_CRON="0 30 3 * * *"`에 정리하며 test 프로파일은 스케줄러를 끈다.
- 회차 원문 파기는 운영·local에서 `EPISODE_SOURCE_PURGE_SCHEDULING_ENABLED=true`, `EPISODE_SOURCE_PURGE_FIXED_DELAY_MS=10000`을 기본으로 사용한다. 커밋 직후 정리에 실패한 S3 원문과 파생 데이터를 10초 간격으로 재시도하기 위한 값이며 운영 부하에 따라 환경변수로 override한다. test 프로파일은 스케줄러를 끈다.
- 로컬 실행 시 `application.yml`이 `apps/CatchHole-Backend/.env`를 optional import한다. AWS/S3 같은 로컬 비밀값은 `.env`에 둘 수 있지만, `.env`는 커밋하지 않는다.
- E2E는 `SPRING_PROFILES_ACTIVE=e2e`로 활성화하고 운영에서는 사용하지 않는다. 이 프로파일에서만 `LocalFileObjectStorage`를 사용하며 `storage.local.root` 기본값은 `${java.io.tmpdir}/catchhole-e2e-storage`, 명시적 override는 `CATCHHOLE_E2E_STORAGE_ROOT`로 둔다.
- 새로운 설정 키를 추가할 때는 base / local / prod 각 위치를 의식적으로 결정한다.
- 운영 환경은 Caddy `reverse_proxy` 뒤에서 실행되므로 `application-prod.yml`에 `server.forward-headers-strategy: framework`를 둔다. Swagger/OpenAPI server URL과 보안/리다이렉트 처리가 외부 HTTPS scheme/host를 기준으로 동작하게 하기 위함이다.
- **로컬 DB 접속 정보는 `compose.yaml` 단일 출처로 둔다.** `spring-boot-docker-compose` 의존성이 컨테이너에서 호스트/포트/사용자/비밀번호를 자동 추출해 `ServiceConnection` 빈으로 주입한다. yml에 `spring.datasource.*`를 중복 작성하지 않는다 (그림자 설정 방지).

### Application Metrics

- 회차 분석 실패 알림은 `analysis_failure_notifications`의 transactional outbox를 사용한다. V90의 PostgreSQL trigger는 회차 Job/캐릭터·세계관 후보가 새로 FAILED가 될 때 안전한 진단·실행 종류·실행/원본 추출/비교 묶음·대상 회차를 실패 당시 snapshot으로 기록한다. 발송 때 현재 Job·후보·FK로 과거 원인을 재구성하지 않으며 원시 오류·원문·연락처는 복사하지 않는다.
- 후보 실패는 기존 트랜잭션·원본 Job·회차·종류·코드에 실행·묶음·정리한 진단 전체가 같은 경우만 합친다. reason이 같아도 단계·관측 HTTP·원천 코드·원인 근거 ID가 다르면 나눠 대표 진단이 전체 개수를 설명하는 것처럼 표시하지 않는다. 진단과 서버 실행 링크가 모두 없는 구 writer만 기존 그룹 의미를 유지한다.
- 새 시도의 현재 진단·후보 실행 링크는 애플리케이션과 DB 상태 전이에서 함께 비워 rolling/rollback의 구 SQL writer도 이전 시도를 재사용하지 않게 한다. 과거 outbox는 유지한다. 실행 실패의 기본 수는 실패 작업 1건이며 후보 사건만 실패 항목 수를 쓴다. 다회차 실행은 실제 membership의 회차를 쓰고 여러 원본 추출 Job을 하나로 추정하지 않는다.
- 입력 hash가 유효한 숨김 캐릭터 그룹은 직접 실패와 lease 회수 한도 초과에서 후보 실행 링크를 같은 트랜잭션으로 먼저 flush한 뒤 Job을 FAILED로 바꾼다. 첫 비교 묶음 전에는 이 링크만으로 전체 대상 회차·유일한 원본 출처를 snapshot할 수 있기 때문이다. stale 입력은 현재 후보를 바꾸지 않으며 이후 live 상태로 과거 outbox를 보완하지 않는다.
- `ANALYSIS_FAILURE_NOTIFICATIONS_ENABLED=false`가 기본이며 활성화할 때 `ANALYSIS_FAILURE_DISCORD_WEBHOOK_URL`을 서버 비밀 설정으로 주입한다. 운영 Compose는 알림 환경을 prod로 고정한다. 전용 dispatcher는 DB 선점을 끝낸 뒤 HTTP를 호출하고, 429의 대기 헤더·일시 오류 backoff·재시작 후 미발송 복구를 처리한다. 전송은 at-least-once이며 응답 유실 때 같은 알림 ID가 중복될 수 있다. 비활성 중 대기 기록도 활성화 시 발송하며 완료 이력만 30일 뒤 정리한다. 설정집·접수 전 HTTP 오류·브라우저 연결 오류는 이 알림의 대상이 아니다. 운영 설정과 한계는 `docs/analysis-failure-notifications.md`를 따른다.

- 기본 애플리케이션 지표는 Actuator와 `micrometer-registry-prometheus`로 제공한다. registry 버전은 Spring Boot의 의존성 관리에 맡긴다.
- 메트릭 HTTP 노출은 `application-local.yml`과 `application-prod.yml` 각각의 `management.endpoints.web.exposure.include: health,prometheus`로 설정한다. 필요한 환경에서만 제공하도록 공통·test·e2e 프로파일로 노출 설정을 확장하지 않는다.
- 공통 label은 두 환경 모두 `application=catchhole-backend`를 사용하고, `environment`는 local에서 `local`, prod에서 `prod`로 구분한다. 서로 다른 환경의 지표가 섞이지 않도록 각 프로파일에서 설정한다.
- HTTP 응답 시간은 local·prod 모두 `management.metrics.distribution.percentiles-histogram.http.server.requests=true`로 histogram을 제공한다. API별 p95는 이후 Prometheus에서 bucket을 조회해 계산한다.
- 운영 Actuator는 `management.server.port=8081`로 API·Worker의 8080과 분리한다. API Compose의 `API_METRICS_BIND_ADDRESS` 기본값은 `127.0.0.1`이며, 실제 모니터링 SG에만 8081을 허용한 뒤 API EC2 사설 IPv4로 바꾼다. 운영 메트릭은 애플리케이션 로그인 대신 사설망·SG로 제한하며, 같은 API 서버 관리자와 Docker 내부 네트워크는 신뢰 경계에 포함된다.
- `global.config.monitoring.ProductionHealthWebConfig`는 prod에서 기존 8080의 `/actuator/health`를 전체 health group의 `/healthz`로 내부 전달한다. 관리 포트만 살아 있는 상황을 정상으로 오인하지 않도록 API 포트에서 실제 health를 확인하며 상태 코드·상세 정보 노출 여부는 Actuator가 결정한다.
- Caddy는 `/actuator/health`를 제외한 `/actuator` 경로를 404로 차단한다. 자동 배포는 Caddyfile을 명시적으로 reload해 파일만 바뀌어도 접근 규칙을 반영한다. 기존 API·Worker 인증과 health 확인 경로는 유지한다.
- 사용자·작품·Job ID, 원문, 비밀값과 실제 ID가 포함된 URL은 metric label에 넣지 않는다. HTTP 경로는 framework가 제공하는 route template을 사용한다.
- Security에서 종료된 401·403 중 경로 패턴이 없는 요청은 `SecurityRejectionMetricsFilter`가 등록된 MVC 매핑으로 Observation의 패턴만 보완한다. 컨트롤러·인터셉터는 실행하지 않으며, 미등록 경로·매핑 실패는 raw URI로 대체하지 않고 `UNKNOWN`을 유지한다. 인증 응답과 기존 MVC 계측을 보존하고 실제 HTTP→Prometheus 통합 테스트로 검증한다.
- 기본 API 대시보드의 HTTP 집계에서는 `/actuator.*`와 `/healthz`를 제외해 메트릭 수집·health 확인 요청을 사용자 트래픽에 섞지 않는다.
- API 4xx 응답률은 기존 5xx 서버 오류율과 분리한다. 4xx에는 검증·인증·권한·충돌 등 정상적인 요청 거절도 포함되므로 서버 장애율로 합치지 않는다. 상태 코드·메서드·route template별 선택 기간 증가량을 상세에 제공하고, 외부 LLM의 HTTP 오류는 분석 지표에서 별도로 조회한다. 요청 없음과 수집 없음은 정상 0%로 채우지 않는다.
- 기본 운영 대시보드의 수동 가져오기용 JSON은 `deploy/monitoring/grafana/dashboards/catchhole-overview.json`에 보관한다. Grafana에서 단계별로 편집한 내용을 재시작 때 덮어쓰지 않도록 dashboard 자동 provisioning에는 연결하지 않는다. HTTP 요약은 최근 5분, 상단 Stat은 Instant query와 Last 계산을 사용하며 요청 없음의 NaN을 과거 유한값으로 대체하지 않는다. 적용·백업·가져오기는 같은 디렉터리의 `README.md`를 따른다.
- 로컬 Prometheus는 `compose.monitoring.yml`의 `catchhole-monitoring` 프로젝트로 관리한다. `-f compose.monitoring.yml`을 명시해 실행하며, Spring이 관리하는 기존 `compose.yaml`의 PostgreSQL·Redis와 실행 수명을 분리한다.
- 수집 설정은 `monitoring/prometheus.yml`에서 관리한다. Docker Desktop의 `host.docker.internal:8080`을 통해 Mac에서 실행 중인 Spring의 `/actuator/prometheus`를 15초마다 수집하고, 수집 timeout은 5초로 둔다. `job` label은 `catchhole-backend`이며 초기 구성에는 지표 제외 규칙을 두지 않는다.
- 로컬 Prometheus 이미지는 `prom/prometheus:v3.13.3`으로 고정하고, UI/API는 `127.0.0.1:9090`에만 공개한다. 데이터는 전용 `prometheus_data` named volume에 저장하고 보존 기간은 7일로 둔다.
- 운영 모니터링은 `deploy/compose.monitoring.prod.yml`과 `deploy/monitoring/`에서 관리한다. Prometheus `v3.13.3`·Grafana `13.2.2`, 15초 수집·5초 timeout, 기본 7일·5GB TSDB 블록 보존 제한과 전용 영속 볼륨을 사용한다. WAL·head 등은 별도 여유 공간이 필요하므로 5GB를 전체 디스크 상한으로 해석하지 않는다.
- 운영 수집 대상은 `deploy/monitoring/targets/catchhole-backend.json.example`을 실제 JSON으로 복사해 API 사설 IP:8081을 입력한다. Prometheus YAML의 환경변수 치환을 가정하지 않고 `file_sd_configs`로 읽는다. 실제 targets JSON과 `deploy/monitoring.env`는 커밋하지 않는다.
- CPU 패널은 Spring Boot JVM의 `process_cpu_usage`와 실행 환경 전체의 `system_cpu_usage`를 구분하며 모두 기존 Actuator 지표에 100을 곱한 최근 관측값이다. 현재 운영 컨테이너는 CPU quota·cpuset 제한 없이 EC2의 2 vCPU를 인식하므로 전체 패널은 현재 EC2 기준이고 CPU 1개 최대 사용은 약 50%, 2개는 약 100%다. Docker CPU 제한·cpuset·JVM CPU 설정을 바꾸면 `system_cpu_usage`가 컨테이너 기준이 될 수 있으므로 패널 설명을 다시 확인한다. 5분 평균이나 CloudWatch CPUUtilization과 정확히 같은 측정값으로 설명하지 않는다. 별도 exporter·9100 포트·새 Prometheus job은 사용하지 않는다.
- Grafana 데이터 소스 UID는 `catchhole-prometheus`, 주소는 같은 Compose 네트워크의 `http://prometheus:9090`으로 고정한다. 팀원은 `https://monitoring.catchhole.com`에서 기존 Grafana 계정으로 로그인한다. 모니터링 EC2의 Nginx가 HTTPS를 종료하고 Docker 내부 `grafana:3000`으로 전달하며 API Caddy는 경유하지 않는다. 익명 접속·회원가입은 계속 끄고 초기 관리자 비밀번호를 필수로 주입한다.
- 모니터링 EC2의 외부 진입은 인터넷 전체의 TCP 80·443으로 두고, 80은 ACME challenge와 HTTPS 이동에만 사용한다. Grafana 3000·Prometheus 9090은 호스트 localhost 바인딩을 유지하고 Actuator 8081은 API SG에서 모니터링 SG만 허용한다. 팀원에게 수동 SSM 터널을 요구하지 않으며 SSM은 서버 관리·Prometheus 직접 조회·복구에 사용한다.
- Nginx `1.30.5-alpine3.24`·Certbot `v5.8.0`을 고정하고 `MONITORING_NGINX_CONFIG`로 bootstrap/HTTPS conf를 선택한다. 최초 발급 전에는 `--no-deps`로 bootstrap Nginx만 실행한다. DNS·80/443·인증서 준비 뒤 HTTPS conf로 Nginx를 `--force-recreate`하고 Grafana만 `--no-deps`로 재생성한 다음 Nginx를 검사·reload한다. single-file bind mount 교체와 재생성된 Grafana의 upstream 주소를 확실하게 반영하기 위한 순서이며 접속 전환으로 Prometheus를 재시작하지 않는다.
- 인증서는 Certbot `maintenance` profile의 webroot 방식으로 발급하며 `ACME_EMAIL`은 최초 등록 연락처다. `acme_webroot`와 `/etc/letsencrypt` 전체를 보관하는 `letsencrypt_data` 볼륨을 공유하고 Nginx에서는 읽기 전용으로 마운트한다. `monitoring/renew-certificate.sh`와 `catchhole-monitoring-certbot` systemd service/timer로 하루 두 번 갱신을 시도하고 Certbot 성공 → Nginx 검사 성공 → reload 순서를 지킨다. 설정은 수동 배포하며 모니터링 CI 배포·새 알림 시스템은 추가하지 않는다.
- HTTPS용 Grafana 외부 URL·secure cookie를 적용한 뒤 HTTP SSM 로그인으로 복구하려면 이전 Compose와 환경변수를 복원하고 Grafana만 재생성한다. 볼륨이나 이미지를 되돌리는 대신 접속 설정부터 복구하며 `catchhole-monitoring-prod` 프로젝트와 기존 데이터 볼륨을 보존한다.
- 운영 적용·접근 검증·영속 데이터 보존·업데이트·롤백은 `deploy/MONITORING_DEPLOYMENT.md`를 따른다. 설정 파일 작성과 실제 AWS SG 적용·배포 완료는 구분한다. #206의 분석 작업 계측은 이 수집 기반을 재사용한다.

- #206 분석 지표는 `domain.analysis.event`의 JPA 변경 snapshot/immutable event와 commit 후 meter 기록, `service.AnalysisResultReadyTracker`의 별도 트랜잭션에서 결과 준비를 기록한다. callback에서 새 SQL을 실행하거나 커밋 thread가 REQUIRES_NEW 연결을 동기로 기다리지 않도록 bounded 비동기 queue와 주기적 fallback을 사용한다.
- AnalysisJob은 사용자 접수 세대/대기 구간/결과 준비 latch를, 양쪽 후보는 최초 비교 종료 시각·결과·원본 접수 세대를 보존한다. 같은 Job의 사용자 재시도는 새 접수, lease 회수는 같은 접수의 새 대기 구간이다. 후속 비교 완료와 부분 실패를 raw SUCCEEDED로 대신하지 않는다. metric 오류가 분석 정책을 바꾸지 않으며 event delivery는 감사 원장 수준 exactly-once를 보장하지 않는다.
- `analysis.metrics.scheduling-enabled=true`, `fixed-delay-ms=15000`은 base YAML, test에서는 비활성이다. snapshot은 집계 SQL로 갱신하고 scrape는 캐시를 읽는다. 실패는 success=0/마지막 성공 시각 유지, 빈 대기 age=0이며 대시보드는 45초 이상 오래된 값을 정상 0으로 채우지 않는다. DB Gauge는 전체 label 조합별 max로 API 중복 제거 뒤 다른 mode/type을 sum한다.
- 서비스 이용 현황은 `global.monitoring`의 읽기 전용 DB snapshot과 캐시 Gauge를 사용한다. `service-usage.metrics.scheduling-enabled=true`, `fixed-delay-ms=60000`은 base YAML, test에서는 비활성이다. 요청 묶음은 최초 접수 기준으로 재시도를 중복 제거하고, 최근 24시간 원본 이력이 불완전하면 요청 수·평균을 NaN으로 표시한다. 조회 실패·180초 이상 갱신 없음은 정상 0으로 채우지 않는다. 여러 API 인스턴스에서는 최신 정상 snapshot과 같은 전체 label의 값을 선택하며 ID label은 추가하지 않는다. 삭제된 회원·작품은 기간 집계에서도 제외된다. 정의·설정·제약은 `docs/service-usage-metrics.md`를 따른다.
- Grafana JSON은 운영 현황 catlfln / API·서버 상세 catchhole-api / 분석·AI 상세 catchhole-analysis 3개다. 설명/CPU 기준/peak thread를 API 상세에 보존하고 Python Worker는 개별 /metrics를 file_sd로 수집한다. 기본 localhost→운영 사설 bind 및 모니터링 SG 적용, actual port target 생성·원자 교체는 각 배포 안내를 따른다. 지표 정의는 `docs/analysis-metrics.md`가 담당한다.

### Local Tracing

- #271 로컬 트레이스 수신 환경은 기존 `compose.monitoring.yml`에 `compose.tracing.yml`을 겹쳐 실행한다. `catchhole-monitoring` 프로젝트와 기존 Prometheus 볼륨을 유지하며 운영 Compose에는 연결하지 않는다.
- Collector contrib `0.162.0`은 `monitoring/otel-collector.yml`, Tempo `3.1.0`은 `monitoring/tempo.yml`, Grafana `13.2.2`의 로컬 데이터 소스는 `monitoring/grafana/provisioning/datasources/local.yml`로 관리한다. 호스트에는 Collector `127.0.0.1:4318`, Grafana `127.0.0.1:3001`만 추가 공개하고 Tempo 4318·3200은 Docker 내부에서 사용한다.
- 로컬 Grafana 초기 관리자 비밀번호는 `LOCAL_GRAFANA_ADMIN_PASSWORD`로 주입한다. `monitoring/.env.tracing.example`만 커밋하고 실제 `.env.tracing.local`은 제외한다. Compose 검증은 비밀값을 출력하지 않는 `config --quiet`를 사용한다.
- Collector는 메모리 제한·batch·요청 묶음 100개 메모리 대기열·최대 30초 재시도를 사용한다. 로컬 컨테이너 메모리 상한은 Collector 256MiB, Tempo·Grafana 각 512MiB이며 운영 용량으로 보장하지 않는다. Trace 전송 실패가 분석 정책이나 기존 Prometheus 지표를 바꾸지 않도록 후속 계측을 설계한다.
- Tempo는 `-target=all`, local backend, `/var/tempo` 전체 named volume과 `overrides.defaults.compaction.block_retention: 24h`를 사용한다. Tail sampling은 아직 적용하지 않았다. 초기 수신 환경 준비와 아래 SDK·Agent의 실제 Span 생성·저장 검증을 구분하며 실행·종료·한계는 `docs/local-tracing.md`를 따른다.
- #271 Java 계측은 `monitoring/java-agent/`의 별도 Gradle 프로젝트에서 관리한다. Agent 2.32.0의 공식 SHA-256을 검증하며 SDK 1.66.0 기반 privacy extension은 SDK를 중복 번들하지 않는다. 로컬은 명시적 스크립트, 운영은 이미지에 함께 포함한 Agent·extension·설정을 사용하며 앱 의존성은 변경하지 않는다. `JAVA_TOOL_OPTIONS`로 Gradle 프로세스까지 계측하지 않는다.
- Java 로컬 샘플러는 `parentbased_always_off`: 부모 없는 새 trace를 수집하지 않고 Python Worker가 전달한 sampled 문맥의 HTTP SERVER·JDBC Span을 이어받는다. HTTP/JDBC와 executor 문맥 계측만 활성화하고 OTel metrics/logs export는 끈다. 이 설정은 내부 API 인증이나 URL 접근 제한을 대신하지 않는다.
- Java export 전 `PrivacySpanExporter`는 ID·시간·kind·status code와 허용한 HTTP method/route template/status, DB system/operation만 보존한다. SQL·URL·본문·인증값·예외 event/status description·임의 resource/scope 속성을 내보내지 않고 정제 실패 시 원본으로 fallback하지 않는다. resource는 service allowlist·local/prod 환경·UUID 인스턴스만 허용하며 알려지지 않은 환경은 unknown, 자유 인스턴스는 제외한다.
- `src/test/java/org/monitoring/traceverification/`는 운영 컴포넌트 탐색 밖의 검증 전용 패키지다. 실제 토큰 Controller·DTO·API-key filter와 합성 H2 원장을 사용하며 `application.yml`/`.env`/기존 DB를 읽지 않는다. `scripts/tracing/verification.init.gradle`과 `verify_worker_spring.py`를 명시적으로 실행해 동시 문맥 분리·샘플링·오류·전송 전 속성 제한·Tempo 저장을 검사한다. 이 결과를 실제 AiTokenServiceImpl의 lease/계정/원장 정책이나 운영 PostgreSQL 검증으로 확대하지 않는다.
- #271 6단계는 `docs/tracing-validation.md`를 따른다. `verify_worker_trace_readback.py`는 AI 합성 실험의 모든 Span ID·부모·시각을 로컬 Tempo에서 대조한다. `verify_sampling.py`와 `monitoring/otel-collector.sampling-lab.yml`은 새 임시 Collector와 합성 데이터만 사용하는 명시적 실험이며 Compose에 연결하지 않는다. 짧은 Tail 결정 시간이 늦은 오류를 놓칠 수 있으므로 실제 로컬 정책은 Python all-on·Java 부모 상속·Tail OFF를 유지하고 운영 활성화·자원 설정은 7단계로 분리한다.

### Production Tracing

- #271 운영 수신기는 `deploy/compose.tracing.prod.yml`을 기존 운영 Compose에 명시적으로 겹친다. 기존 프로젝트·Prometheus/Grafana/인증서 볼륨·데이터 소스·수동 대시보드를 보존한다. Collector 4318은 기본 localhost이며 사설 IP와 API·Worker SG를 검증한 뒤 개방한다. Tempo 조회·관리 포트는 Docker 내부에 둔다. 자원·S3/보존·준비 상태 검증은 `deploy/TRACING_DEPLOYMENT.md`를 따른다.
- API 이미지는 별도 Agent 프로젝트의 test/jar/prepareAgent를 실행해 잠긴 버전·SHA의 Agent와 privacy extension·prod 설정·entrypoint를 함께 포함한다. `BACKEND_TRACING_ENABLED=false`에서는 Agent를 로드하지 않는다. ON은 RFC1918 IPv4:4318/v1/traces와 필수 번들을 요구하며 기존 JAVA_OPTS·non-root·timezone·health 계약을 유지한다. CI는 별도 extension 테스트와 entrypoint 검증을 실행한다.
- 공개 Caddy는 traceparent/tracestate/baggage를 제거하고 Worker의 사설 API 8080 경로는 유지한다. 부모 sampling 상속은 인증이 아니며 공개 8080 우회를 SG에서 차단해야 한다. 기존 내부 API key·lease 검증과 Actuator 제한을 바꾸지 않는다.
- 운영 적용은 수신 서버/S3 → Java → 기존 비교 Worker 한 프로세스 → 종류별 확대 순서다. 운영 Agent는 부모 결정만 상속하고 Python all-on은 초기 제한적 검증 정책이며 Tail은 OFF다. 기존 immutable SHA 배포와 API lock·Worker drain을 지킨다. 설정·로컬 성공만으로 운영 적용이나 #271 완료를 선언하지 않는다. `deploy/API_TRACING.md`와 AI Worker 배포 문서의 OFF/rollback 절차를 함께 따른다.

### Database Migration

- PostgreSQL schema 변경은 `src/main/resources/db/migration`의 Flyway SQL로 관리한다.
- migration 파일은 `V{순번}__{snake_case_설명}.sql` 형식으로 작성한다.
- 공유 환경에 한 번이라도 적용된 migration은 수정하거나 삭제하지 않는다. 변경이 필요하면 다음 버전 파일에 `ALTER`, `CREATE`, `DROP`을 누적한다. Flyway checksum과 환경별 schema 이력을 일치시키기 위함이다.
- 새 Entity나 컬럼을 추가하는 작업은 JPA 매핑과 Flyway migration을 같은 PR에서 변경한다.
- Python SQLAlchemy 모델은 schema를 생성하지 않고 Flyway가 만든 공유 테이블을 조회·저장하는 매핑으로만 사용한다. 공유 컬럼을 변경하면 Java migration과 Python 매핑의 이름, 타입, nullable 여부를 함께 확인한다.
- PostgreSQL 전용 migration은 H2 기반 `test` profile에서 실행하지 않는다. migration 자체는 빈 pgvector PostgreSQL에서 실행하고, 이후 JPA `validate` 상태로 애플리케이션 기동까지 확인한다.
- 누적 분석의 실제 DB 테스트는 별도 생성한 localhost `gh180_test` DB의 `GH180_POSTGRES_JDBC_URL`을 명시할 때만 실행한다. 해당 테스트가 격리 fixture를 정리하므로 공유·운영 DB나 기존 `.env` URL을 재사용하지 않는다. Python 저장 경계와 Spring↔Worker 통합도 각각 별도 테스트 DB를 사용한다.
- 최초 V1 기준과 운영 적용 절차는 `docs/database-migration.md`를 따른다.

### Docker Deployment

- 백엔드 컨테이너 이미지는 루트 `Dockerfile`에서 빌드한다.
- 운영 컨테이너는 Docker `journald` 로그 드라이버를 사용한다. API·Worker 서버의 systemd journal은 EC2 로컬 디스크에 최대 14일·1GB로 제한해 보관하며, 외부 로그 저장소와 애플리케이션 파일 로그는 별도 요구가 생기기 전까지 추가하지 않는다.
- Dockerfile은 Gradle Wrapper로 `bootJar`를 만드는 JDK 21 빌드 스테이지와 JRE 21 런타임 스테이지를 분리한다. 런타임 컨테이너는 non-root 사용자로 실행한다.
- 운영 컨테이너는 `SPRING_PROFILES_ACTIVE=prod`와 외부 환경변수로 설정을 주입한다. AWS/S3 자격 증명은 가능하면 EC2 IAM Role을 사용하고, access key를 이미지나 커밋 파일에 넣지 않는다.
- 운영 Backend, Python AI Worker, PostgreSQL은 `APP_TIMEZONE`을 공통으로 사용하며 기본값은 `Asia/Seoul`이다. 세 writer가 timezone 없는 `TIMESTAMP` 컬럼에 서로 다른 로컬 시각을 저장하지 않도록 Amazon RDS 파라미터 그룹의 `timezone`, JVM `user.timezone`, 컨테이너 `TZ`를 함께 변경한다. Backend는 HikariCP `connection-init-sql`, AI Worker는 SQLAlchemy PostgreSQL 연결 옵션으로 새 연결의 session `timezone`을 매번 `TZ`와 동일하게 설정한다.
- 운영 AI Worker의 신규 청크 임베딩 생성은 `EMBEDDING_GENERATION_ENABLED`로 제어하고 Compose 기본값은 `false`로 둔다. MVP에서 사용하지 않는 API 비용을 차단하되 pgvector schema와 재활성화 경로는 유지하며, `true` 전환은 신규 분석·재분석에만 적용되고 기존 `NULL` 벡터를 자동 backfill하지 않는다.
- 운영 캐릭터 Fact·세계관 재비교는 같은 AI 이미지를 각각 `--worker-kind character-comparison`, `--worker-kind world-comparison` command로 실행하는 별도 서비스가 담당한다. 기본 `ai-worker`와 claim Job type을 분리하고 재비교 서비스에서는 임베딩 생성을 항상 끈다.
- 운영 `SETTING_EXTRACTION` 처리량은 `AI_WORKER_PROCESS_COUNT × AI_WORKER_CONCURRENCY`로 계산한다. 기본 운영값은 분석 Worker 5개 × 프로세스당 Job 10개 = 최대 50개다. 50개 Job 부하 테스트에서 Backend·PostgreSQL·LLM 지표가 기준에 미달하면 Worker 5개는 유지하고 프로세스당 Job과 LLM 요청을 5개로 낮춰 최대 25개로 되돌린다. 이 값은 별도 캐릭터·세계관 비교 Worker를 제외한 분석 Job 용량이며 provider 계정 전체의 분산 동시성 상한을 뜻하지 않는다.
- 분석 Worker는 빈 실행 슬롯을 확보한 뒤 Job 하나만 claim하고 즉시 실행한다. 미리 여러 Job을 claim해 프로세스 내부 대기열에 쌓지 않으며, `LLM_MAX_CONCURRENT_REQUESTS`와 `AI_WORKER_BLOCKING_MAX_WORKERS`로 프로세스 안의 provider 호출과 동기 DB/S3 offload를 각각 제한한다. 재비교 Worker는 Job·LLM 동시성을 1로 고정한다.
- Worker는 종료 신호를 받으면 신규 claim을 중단하고 `AI_WORKER_SHUTDOWN_GRACE_SECONDS` 동안 실행 중 Job과 heartbeat를 유지한다. Compose `stop_grace_period`는 내부 grace보다 길게 두어 정리 시간을 보장하며, 현재 운영값은 내부 180초·컨테이너 210초다. grace를 넘긴 Job은 heartbeat가 멈춘 뒤 Spring lease 회수 경로로 재처리한다.
- 2026-10-16 실행 컨테이너에서 확인한 운영 모델은 추출·캐릭터/세계관 비교 및 재비교 `gpt-6.1-sol`, 캐릭터·세계관 주체 해소와 챗봇 `gpt-6-luna`다. Worker의 `LLM_EXTRACTION_MODEL`·`LLM_SUBJECT_RESOLUTION_MODEL`·`LLM_COMPARISON_MODEL`은 `/opt/catchhole/worker.env`, 챗봇의 `CHAT_QA_MODEL`은 `/opt/catchhole/chatbot.env`에서 주입한다. 개별 Worker 모델이 비어 있을 때만 `LLM_MODEL`을 fallback으로 사용한다. 저장소 Compose/예제 기본값과 운영 적용값을 혼동하지 않으며, 추론 강도도 실행 컨테이너의 `LLM_REASONING_EFFORT`·`CHAT_QA_REASONING_EFFORT`를 확인한다. 역할별 표와 확인 범위는 `docs/analysis-workflow.md`를 따른다.
- AI 출력 상한은 Worker에 전달된 목적별 `LLM_*_MAX_OUTPUT_TOKENS`와 `LLM_PROVIDER_MAX_OUTPUT_TOKENS` 설정을 확인한다. 예제 기본값이나 과거 실행값을 현재 운영값 또는 공급자 자체 최대치로 단정하지 않는다. Backend 비교 최소 예약량의 raw 기준은 현재 설정된 128,256이며, #252 이후 모델 미확정 작업 접수에서는 최저 요율의 차감 하한만 검사하고 실제 모델·입력 길이를 아는 Worker 호출 직전 예약에서 최종 판단한다.
- 로컬 PostgreSQL 컨테이너는 `pgvector/pgvector:0.8.2-pg16` 이미지를 사용한다. 운영은 PostgreSQL 16 계열의 외부 Amazon RDS와 pgvector 0.8.2를 사용하므로 Docker 이미지를 실행한다고 설명하지 않는다. 로컬 이미지의 가변 `latest` 태그 사용을 피하고 운영 엔진·확장 버전과의 호환성을 확인한다.
- API 서버 운영 배포 파일은 백엔드 저장소의 `deploy/compose.api.prod.yml`, `deploy/Caddyfile`, `deploy/api.env.example`로 관리한다. Worker 서버 운영 배포 파일은 인공지능 작업 저장소의 `deploy/compose.worker.prod.yml`, `deploy/worker.env.example`로 관리한다. 두 서버의 실제 `api.env`와 `worker.env`는 각각 `/opt/catchhole`에만 두고 커밋하지 않는다.
- 운영 PostgreSQL은 외부 Amazon RDS를 사용한다. API 서버는 HikariCP 최대 10개, 분석 Worker 5개는 SQLAlchemy 연결을 각각 최대 3개, 캐릭터·세계관 비교 Worker는 각각 최대 1개로 제한해 애플리케이션 전체 최대 연결 수를 27개로 유지한다.
- 로컬과 운영 Redis는 `redis:7.4.10-alpine3.21`로 고정한다. 운영 Redis는 호스트 포트를 열지 않고 비밀번호, 64MB `noeviction`, 비영속 정책을 유지한다. 이메일·휴대폰 인증 데이터는 모두 단기 상태이므로 재시작 시 초기화를 허용한다.
- `main` 브랜치에 push되면 `.github/workflows/publish-image.yml`이 GHCR에 `ghcr.io/catchhole-soma/catchhole-backend-java:main`과 short SHA 태그를 발행한다.
- API 자동 배포는 `main` push로 시작된 이미지 발행이 성공하고 publish run의 commit SHA가 현재 `main`일 때만 실행한다. 해당 SHA의 배포 파일과 `sha-<short-sha>` 이미지를 함께 사용하며, Workflow가 API 서버의 `api.env` 속 `BACKEND_IMAGE`를 해당 SHA로 갱신한다.
- API 자동 배포와 챗봇 최초 설정은 같은 호스트의 `/opt/catchhole/.api-deploy.lock`을 공유한다. 환경 파일 읽기·수정부터 컨테이너 재생성과 health 확인까지 잠금을 유지해 이미지와 설정이 서로 덮어써지지 않게 한다. 잠금 파일은 배포 사용자 소유 0600으로 유지하며 실행 중 교체하거나 삭제하지 않는다.

### Package Structure

- base package: `org.monitoring.catchholebackend`
- 도메인별 기능 코드는 `domain` 아래에 둔다.
- 전역 공통 코드는 `global` 아래에 둔다.
- `global.notification`은 Discord HTTP 전송만 담당한다. 분석 실패 발송 흐름은 `domain.analysis.processor`, payload 조립은 `mapper`, outbox 접근은 `repository`, 설정은 `global.config.analysisnotification`에 둔다.
- 공통 응답 코드는 `global.common.response` 아래에 둔다.
- 전역 예외 코드는 `global.exception` 아래에 둔다.
- 전역 `@Configuration` 클래스는 `global.config.<영역>` 아래에 둔다.
  - 영역별 서브패키지로 분리한다 (예: `global.config.swagger`, `global.config.security`).

예시:

```text
org.monitoring.catchholebackend
├── domain
│   ├── validation
│   │   ├── controller
│   │   ├── dto
│   │   ├── entity
│   │   ├── event
│   │   ├── exception
│   │   ├── mapper
│   │   ├── processor
│   │   ├── repository
│   │   ├── service
│   │   └── type
│   ├── settingtool
│   │   ├── controller
│   │   ├── dto
│   │   ├── mapper
│   │   ├── processor
│   │   ├── repository
│   │   ├── service
│   │   └── type
│   ├── analysis
│   │   ├── controller
│   │   ├── dto
│   │   │   ├── request
│   │   │   └── response
│   │   ├── entity
│   │   ├── event
│   │   ├── exception
│   │   ├── mapper
│   │   ├── processor
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
│   ├── retrieval
│   │   └── entity
│   ├── relationship
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
│   ├── chat
│   │   ├── controller
│   │   ├── dto
│   │   ├── entity
│   │   ├── exception
│   │   ├── mapper
│   │   ├── repository
│   │   ├── service
│   │   └── type
│   ├── feedback
│   │   ├── controller
│   │   ├── dto
│   │   │   ├── request
│   │   │   └── response
│   │   ├── entity
│   │   ├── exception
│   │   ├── mapper
│   │   ├── repository
│   │   └── service
│   ├── legal
│   │   ├── controller
│   │   ├── dto
│   │   │   └── response
│   │   ├── entity
│   │   ├── exception
│   │   ├── mapper
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
│   ├── worldimage
│   │   ├── controller
│   │   ├── dto (request / response)
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
    │   ├── analysisnotification
    │   ├── auth
    │   ├── chat
    │   ├── cors
    │   ├── jpa
    │   ├── logging
    │   ├── memberwithdrawal
    │   ├── emaildelivery
│   ├── emailverification
│   ├── phoneverification
    │   ├── security
    │   ├── validation
    │   └── swagger
    ├── exception
    ├── monitoring
    ├── notification
    └── storage
```

### Domain Package

도메인 단위로 패키지를 나누고, 그 안은 레이어드 구조를 따른다.

- `settingtool`은 챗봇·오류 리포트가 공유할 읽기 전용 조회 계약이다. 현재값은 기존 snapshot/세계관 속성 계산을 재사용하고 출처 회차 필터를 과거 전체 상태 복원으로 해석하지 않는다. 모델 입력에서 회원·작품 범위를 받지 않으며 내부 API의 회원 헤더는 신뢰 서버만 구성한다. 별도 조회 로직이 확정/수동/파기 정책을 우회하지 않도록 계약과 Java/Python fixture는 `docs/setting-tools-v1.md`를 기준으로 함께 갱신한다.

### Legal Documents

- 법률 문서의 실행 시점 단일 출처는 `legal_documents`다. Front에 이용약관·개인정보처리방침 원문이나 현재 버전을 하드코딩하지 않는다.
- 게시된 문서는 불변으로 취급한다. 내용이 바뀌면 새 `document_version` 행을 만들고 기존 `PUBLISHED`를 `RETIRED`로 전환한다. 한 `document_type + locale`에는 `PUBLISHED`가 하나만 존재해야 한다.
- 회원가입은 화면에 표시한 이용약관·개인정보처리방침의 문서 ID를 받고, 둘 다 현재 `PUBLISHED`인지 회원과 동의 기록을 저장하는 같은 트랜잭션에서 검증한다. 버전만 받아 서버 현재값으로 치환하지 않는다.
- `member_legal_records`는 정확한 `legal_document_id`를 FK로 보존하고 문서 종류·버전·행위는 감사용 snapshot으로 함께 저장한다. 기록 시각은 Backend가 정한다.
- 공개 조회에서는 `DRAFT`를 절대 반환하지 않고 `PUBLISHED`와 과거 `RETIRED`만 ID로 조회할 수 있게 한다.

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
- API·Worker 통합 부하 테스트 계획과 날짜별 결과는 Java 저장소의 `docs/load-tests/`, 재사용 실행기는 `scripts/load-tests/`에서 관리한다. GitHub 통합 이슈에는 진행 요약을 남기고, 실제 측정·계획·미측정을 구분해 설정상 동시성을 검증된 처리량으로 오해하지 않게 한다.
- 운영 부하 테스트를 CI나 단계 자동 연속 실행에 연결하지 않는다. 원고·토큰·전체 응답·실제 리소스 식별자 매핑은 저장소 밖에 보관하고 공개 검토를 마친 집계만 커밋한다. 실행기 중단은 이미 접수한 분석의 취소가 아님을 기록한다.
- 분석 부하 테스트 입력은 사용자 승인 파일의 SHA-256·별칭·회차 번호를 함께 검증한다. 입력 목록을 늘릴 때 기존 실행 기록의 읽기 호환성을 검사하고, 원문 변경과 부하 증가의 영향을 분리하지 못한 비교는 그 한계를 명시한다.
- 순차 조회한 Job 상태 합계를 동시 snapshot이나 실제 동시성의 하한으로 해석하지 않는다. 재시도·timestamp 정밀도를 확인한 서버 시작~종료 구간의 겹침 수로 동시성을 별도 검증하고 원래 조회 집계도 보존한다.
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
- 인증 흐름은 `auth` 도메인에 둔다. JWT 발급/검증, refresh token 발급/폐기, 인증 쿠키, 이메일·휴대폰 인증 Redis 흐름과 SMTP/SMS port/adapter는 `domain/auth` 아래에서 관리한다.

#### Auth and Token Policy

- 가입 기본 인증 수단은 `SIGNUP_VERIFICATION_METHOD=EMAIL`이며 `PHONE` 전환을 지원한다. 공개 `GET /api/v1/auth/signup-policy`가 화면의 단일 정책 출처다. 선택하지 않은 인증 채널은 발송·확인·토큰 사용 전에 차단한다.
- 이메일 인증 Redis 구현은 `domain/auth/email`, SMTP port/adapter는 `domain/auth/mail`, 설정은 `global/config/emailverification`과 `emaildelivery`에 둔다. 기존 전화번호 구현을 보존하고 별도 Redis namespace로 토큰 혼용을 차단한다.
- 이메일은 입력 앞뒤 공백만 제거하고 저장·로그인·토큰 비교에서는 기존 대소문자를 보존한다. 발송 제한과 활성 흐름의 식별자에서만 도메인을 소문자로 바꿔 동일 도메인의 케이스로 제한을 우회하지 못하게 한다. Gmail 점/플러스 등 provider별 주소 변환은 하지 않는다.
- 이메일 인증번호는 6자리, 유효시간 5분, 재전송 대기 60초, 오입력 5회, 가입 토큰 10분이다. 재확인해도 원래 코드·토큰 만료를 연장하지 않으며 재전송은 이전 코드와 토큰을 폐기한다. Redis Lua가 값 일치 확인과 토큰 삭제를 원자 처리한다.
- 이메일 발송 상한은 주소 시간 5/일 10, IP 시간 10/일 20, 전체 KST 일 100/월 3,000이다. `EMAIL_VERIFICATION_EMAIL_HOURLY_LIMIT`, `EMAIL_VERIFICATION_EMAIL_DAILY_LIMIT`, `EMAIL_VERIFICATION_IP_HOURLY_LIMIT`, `EMAIL_VERIFICATION_IP_DAILY_LIMIT`, `EMAIL_VERIFICATION_GLOBAL_DAILY_LIMIT`, `EMAIL_VERIFICATION_GLOBAL_MONTHLY_LIMIT`로 운영에서 조정한다. 실패 발송도 횟수를 되돌리지 않는다.
- `EMAIL_PROVIDER=smtp`는 `EMAIL_SMTP_HOST`, `EMAIL_SMTP_PORT=587`, `EMAIL_SMTP_USERNAME`, `EMAIL_SMTP_PASSWORD`, `EMAIL_FROM`을 사용한다. SMTP STARTTLS·TLS1.2/1.3·호스트 인증서 검증을 필수로 하고 연결 3초·읽기/쓰기 5초, 자동 재시도 없음으로 고정한다. 운영 EMAIL 모드에서 Fake 또는 불완전한 SMTP 설정은 기동을 실패시킨다. test/e2e는 항상 Fake이며 local 기본 Fake 코드는 `123456`이다.
- `EMAIL_VERIFICATION_HASH_SECRET`은 운영 EMAIL 모드에서 최소 32바이트 별도 비밀값이다. 비활성 채널의 secret과 발송 계정은 기동에 요구하지 않는다. 환경변수를 새로 만들면 운영 Compose에도 명시적으로 전달한다.
- V42부터 `members.phone_number`는 nullable이고 실제 값의 unique 제약은 유지한다. `email_verified`는 기존 회원 false, 이메일 인증 신규 회원 true다. 전화번호 없는 신규 회원은 `phone_verified=false`이며 기존 회원 로그인이나 작품 권한에 재인증 조건을 추가하지 않는다.

- Access token은 JWT로 발급하고, refresh token은 랜덤 opaque token으로 발급한다.
- Access token 만료 기본값은 30분, refresh token 만료 기본값은 14일이다.
- Refresh token 원문은 저장하지 않는다. `refresh_tokens.token_hash`에 SHA-256 해시만 저장하고, 재발급 시 기존 token은 `revoked_at`으로 폐기한 뒤 새 token을 저장한다.
- Refresh token은 `HttpOnly` 쿠키로 전달한다. 쿠키 path는 `/api/v1/auth`, SameSite 기본값은 `Lax`, 운영 환경에서는 `Secure=true`를 사용한다.
- 회원가입과 로그인은 access token을 응답 body로, refresh token을 HttpOnly 쿠키로 함께 발급한다. 회원가입 후 별도 로그인 요청을 요구하지 않는다.
- 회원가입은 현재 `PUBLISHED` 이용약관 동의와 개인정보처리방침 확인을 한 화면 체크로 함께 받고, 만 14세 이상 확인은 별도 필수 boolean으로 받는다. API는 세 값을 모두 `true`로 검증하고 Front가 실제 표시한 `termsDocumentId`, `privacyPolicyDocumentId`가 가입 시점의 현재 게시본인지 같은 트랜잭션에서 검증한다.
- `member_legal_records`는 정확한 문서 FK와 종류·버전·행위 snapshot을 두 행으로 저장하고 `members.age_requirement_confirmed_at`과 함께 한 번 생성한 서버 시각을 사용한다. 원문·현재 버전은 `legal_documents`가 단일 출처이며 AI 원고 처리나 GA4·Meta 고지를 별도 가입 동의·업로드별 동의 이력으로 저장하지 않는다.
- PHONE 모드 인증번호 발송 API에서만 하이픈 없는 `010` 시작 11자리 전화번호를 받고, 회원가입 요청에서는 전화번호를 받지 않는다. 회원가입은 10분 TTL의 1회용 `phoneVerificationToken`에서 번호를 조회해 `members.phone_number`에 unique로 저장하고 `phone_verified=true`로 생성한다. 기존 `phone_verified=false` 회원의 로그인은 허용한다.
- 인증번호는 HMAC으로 Redis에 5분, 재전송 대기는 60초, 오입력은 5회, 가입 토큰은 10분으로 고정한다. 재전송은 이전 인증 흐름을 폐기하고 가장 최근 번호만 유효하게 한다.
- PHONE 모드 발송 제한은 Redis Lua에서 확인과 증가를 원자 처리한다. 전화번호는 1시간 5건·KST 하루 10건, IP는 1시간 10건·KST 하루 20건, 전체는 KST 하루 20건·월 200건이다. 429 제한 응답에는 `Retry-After`를 포함한다.
- 회원가입은 회원·법률 문서 기록·refresh token을 DB에 flush한 뒤 EMAIL은 Redis Lua compare-and-delete, PHONE은 `GETDEL`로 가입 토큰을 소비한다. 소비 실패 시 DB 트랜잭션을 모두 rollback하고 이메일·전화번호·회원+문서 unique 제약을 최종 동시성 방어선으로 유지한다.
- SOLAPI SMS 요청은 자동 재시도하지 않는다. timeout 뒤 실제 발송 여부를 알 수 없어 중복 SMS와 비용이 발생할 수 있기 때문이다.
- 회원가입 표시 이름은 20자 이하, 비밀번호는 8~64자이면서 영문과 숫자를 각각 하나 이상 포함하도록 검증한다. 프론트 검증과 OpenAPI schema도 같은 제약을 사용한다.
- 회원 즉시 탈퇴는 `DELETE /api/v1/members/me`에서 현재 비밀번호와 정확한 `회원 탈퇴` 문구를 요구한다. 접수 트랜잭션에서 `MemberStatus.ACTIVE → PURGING`과 모든 refresh token 폐기를 함께 커밋해 기존 access token의 다음 인증을 차단한다.
- 회원 탈퇴의 최종 전이는 `DELETED` 저장이 아니라 `PURGING → members 행 hard delete`다. 이메일·휴대폰 번호 재가입은 최종 삭제 뒤 허용하며, 유예·복구·soft delete를 이 흐름에 섞지 않는다.
- 회원 탈퇴 작품 파기는 `MemberWorkPurgeCoordinator` Interface를 통해 기존 WorkPurge Module만 사용한다. 별도 S3 삭제 Adapter를 만들지 않고, 탈퇴 중 실패한 `FAILED`·`PARTIAL_FAILED` 작품 요청은 인증이 차단된 사용자를 대신해 자동 재접수한다. 모든 작품 행이 사라진 뒤에만 회원 직접 참조와 회원 행을 삭제한다.
- `member_withdrawal_requests`와 완료된 `work_purge_requests`는 회원 hard delete 뒤에도 최소 파기 감사를 유지하도록 회원 FK를 두지 않는다. 이메일·전화번호·원고·S3 key를 저장하지 않고 완료 후 기본 365일만 보존한다.

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

- 다회차 업로드는 선행 단일 분석 없이 새 작품부터 두 방식 모두 허용한다. `/episodes/upload-policy`는 호환을 위해 `requiredSingleEpisodeCount=0`, `multiEpisodeUploadEnabled=true`를 반환하고, 현재 원문의 서로 다른 단일 업로드 분석 성공 회차 수와 캐릭터·세계관 검토 대기 수·분량 상한을 계속 제공한다. 검토 대기 후보는 안내에만 사용하고 업로드를 막지 않는다. 초기 여러 회차를 바로 분석할 수 있게 선행 완료 조건과 환경별 임시 해제 설정을 제거했다.
- 단일·다회차 원고는 한 요청의 텍스트 합계 250,000 Unicode code point까지 허용한다. TXT/DOCX/HWP/HWPX에서 추출한 공백·제목을 포함하고 설정집은 제외한다. 분량 검사는 S3 저장 전에 수행한다. 감지 응답의 `totalUploadCharacters`는 이 제한용 합계이며 기존 공백 제외 `totalCharCount`와 구분한다.

- 회차 원문 전문은 DB에 저장하지 않고 S3에 저장한다. DB에는 `content_s3_key`, `content_s3_version`, `content_hash`, `char_count`, `content_updated_at`만 둔다.
- 회차 원문 key는 `works/{workId}/episodes/{episodeNo}/{UUID}/{episodeNo}.txt`로 매 저장본을 고유하게 만든다. 보관된 회차 번호를 재사용해도 기존 원문을 덮어쓰지 않으면서 S3에서 회차 번호를 식별하기 위함이다.
- `episodes.content_updated_at`은 원문 직접 수정이나 파일 교체 때만 갱신하고 제목만 바꿀 때는 유지한다.
- 회차 업로드 요청 한 번은 `UploadBatch` 하나로 추적하고, 원본 파일 단위는 `UploadFile`로 추적한다.
- 업로드에서 생성된 회차는 `episodes.source_file_id`로 원본 업로드 파일을 추적한다.
- 같은 작품 안에서 회차 번호는 중복될 수 없다.
- 회차 삭제는 `ARCHIVED` tombstone과 `episode_source_purge_requests`를 먼저 같은 트랜잭션으로 커밋한 뒤 S3 원문·업로드 원본의 모든 version과 delete marker, `episode_chunks`, 검토 전 캐릭터·세계관 후보를 파기한다. 실패 요청은 스케줄러가 재시도한다. Episode 식별자와 이미 확정한 캐릭터·세계관 설정은 유지하되, 확정·무시 후보의 원문 인용과 raw AI payload는 비워 파기된 근거를 다시 노출하지 않는다. 다회차 단일 파일의 한 회차를 삭제하면 공유 업로드 원본 전체를 파기하고 `UploadFile.storageUrl`을 비우되 형제 회차의 분리 원문은 유지한다. 활성 목록, 최신 회차 번호 계산과 회차 번호 중복 검사는 `ARCHIVED` 회차를 제외한다.
- 회차 파일 교체는 새 원문을 먼저 저장한 뒤 새 content key를 제외한 기존 회차 prefix와 이전 업로드 원본을 완전 파기하고, 삭제와 같은 파생 데이터 정리를 수행한다. 자동 재분석이나 확정 설정 재계산은 하지 않으며 사용자가 경고를 확인한 뒤 해당 회차의 `SETTING_EXTRACTION`을 별도로 요청한다.
- 회차 제목은 사용자 확정값 또는 원문의 명시적 회차 제목 행에서만 가져온다. 감지하지 못하면 `null`로 두며 원본 파일명을 제목으로 대체하지 않는다.
- 회차 원고와 설정집 원본은 TXT·DOCX·HWP·HWPX를 허용하고, 명시적으로 첨부한 빈 파일과 파일당 10MB 초과를 서버에서도 거절한다. multipart 요청 전체 제한은 25MB로 둔다. DOCX는 실제 압축 해제량을 누적해 20MB를 초과하거나 본문 탐색 중 ZIP 엔트리가 256개를 초과하면 거절해 압축 폭탄이 서버 자원을 고갈시키지 않게 한다.
- 설정집은 최초 TXT/DOCX/HWP/HWPX 원본을 `upload_files.storage_url`에 보존하고, 편집 텍스트는 `setting_book_revisions`의 불변 hash/key/version/Unicode code point 분량으로 관리한다. 변경 저장마다 `works/{workId}/setting-books/{settingBookId}/{nonce}/{normalizedOriginalBasename}.txt` 새 key를 쓰고 현재 revision 포인터를 갱신한다. 본문과 기준 시점이 모두 같은 저장은 버전이나 저장 객체를 만들지 않는다. 원본 MIME·크기는 바꾸지 않으며 커밋 실패 시 새 객체를 보상 삭제한다. 분석 근거는 현재 포인터가 아닌 분석 당시 revision으로 읽고, 작품·회원 파기 시 모든 revision/chunk/저장 객체를 함께 정리한다.
- 설정집 단독 업로드는 `AUTOMATIC + ORDERED_PROVISIONAL` 추출 Job 하나를 함께 큐에 넣는다. 편집 저장은 분석을 시작하지 않으며, 본문 없는 설정집 분석 POST는 최신 저장 revision을 사용한다. 회차 업로드 API는 설정집 동시 첨부를 처리하지 않는다. 신규 분석은 같은 설정집의 이전 검토 대기 후보·숨김 비교를 더 이상 실행하거나 적용하지 못하게 하되 확정·무시 이력과 이미 완료된 설정은 보존한다.
- 설정집 PATCH는 편집 시작 시 읽은 필수 `expectedRevisionNo`(구형 revision 없는 설정집만 0)를 작품 잠금 아래 현재 번호와 비교한다. 최신값 polling으로 편집 기준을 바꾸지 않으며 stale 번호는 동일 내용 저장도 `UPLOAD_SETTING_BOOK_REVISION_CONFLICT` 409로 거절한다. 검사 전에는 원문/기준 시점/이력/Job/저장 객체를 바꾸지 않는다. 필드 누락·null·음수는 400이고 구형 client도 새 계약으로 갱신해야 한다. 분석 출처의 revision UUID/hash/최신 attempt 검증은 별도로 유지한다.
- 분석 출처는 `sourceKind=EPISODE|SETTING_BOOK`으로 분기한다. 설정집에는 가짜 Episode/회차 번호/선행 회차를 만들지 않으며 singleton run과 `setting_book_chunks`를 사용한다. Worker는 `supportedSourceKinds`로 설정집 지원을 명시해야 하고 생략·null·빈 목록은 회차 전용이다. 설정집 비교·lease도 frozen revision 포인터를 검사하므로 A→B→A 편집 후 과거 lease를 재사용할 수 없다.
- 공개 후보 목록·상세·집계·그룹 변경·세계관 사용량 재개는 선택적 `sourceAnalysisJobId`로 원본 추출 Job만 범위를 지정한다. 생략하면 기존 회차 범위를 유지하고, 설정집 재분석 전 시도는 명시적 이력 조회만 허용한다. 배치 목록의 current Job은 회차와 설정집별 최신 시도를 모두 포함하며 후보 집계는 범위 지정 API를 사용한다. 설정집 출처가 알려진 확정값은 출처 불명 legacy로 취급하지 않고, 두 출처 간 현재값 갱신에도 작가 수정·비교 버전·의존 검증을 유지한다. 같은 설정집·원문 hash·인용 근거의 사용자 반려는 새 시도에도 유지하되 변경된 내용이나 인용은 재검토한다.
- 설정집 기준 시점은 revision의 `referenceTimeKind=UNSPECIFIED|WORK_START|EPISODE`와 `referenceEpisodeNo`로 고정한다. EPISODE는 등록 여부와 무관한 양수 N화 종료 시점이며 실제 회차 ID/출처 번호가 아니다. 기준 시점만 바꿔도 새 revision과 입력 hash를 만들고 미완료 분석을 무효화하되 저장은 분석을 큐에 넣지 않는다. raw `contentHash`는 UTF-8 본문 hash로 유지한다. 반려 재사용은 본문·기준 시점·근거가 모두 같아야 하며 lease는 현재 revision ID를 별도로 검사한다. 설정집 Worker는 세 Job 유형 모두 `supportsSettingBookReferenceTime=true`를 명시해야 한다.
- 설정집 자동 반영은 현재값의 실제 모든 출처 시점을 검사한다. WORK_START는 N화 종료보다 이르고, UPDATE·삭제·이동은 알려진 더 이른/같은 시점과 후행 CURRENT 근거를 검토로 남긴다. 검증된 PRESENT 자동 UPDATE·회복 삭제는 BOOK의 선택적 UNSPECIFIED 위치만 생략할 수 있고, 양립 보완인 기존 목적 경로 MERGE는 선후 시점만으로 막지 않지만 값이 같아도 PRESENT 현재 출처가 필요하며 빈/불명 출처를 통과시키지 않는다. 새 ADD의 부재 경로는 이 MERGE 검사를 받지 않는다. 부수 삭제·원 경로·이동 경로, 비PRESENT·기준시점 불일치·출처 불명·수동 현재값 보호는 유지한다. 회차↔설정집 두 방향에 적용하며 업로드 순서·revision·등록 최대 회차로 서사 시간을 추정하지 않는다. 수동 확정은 기존 엄격한 시점 정책을 유지하고 `currentSourceTimes`는 모든 CURRENT 출처를 보존한다.
- 캐릭터의 `AUTOMATIC + COMPLETED + PAST + HISTORY_ONLY` 후보는 설정집·회차 출처 모두 기존 이력 확정 경로로 자동 반영한다. 현재값을 바꾸지 않으므로 시간·현재값 변경에만 해당하는 자동 보류를 적용하지 않으며 수동 보호 slot도 독립 이력 추가를 막지 않는다. 현재값·출처·대표 시점은 바꾸지 않고 대상·원문/시도·근거·schema·의존성·사용자 후보 수정 검증은 유지한다. 기존 보류 행을 지우거나 소급 확정하지 않으며 `UNKNOWN/HYPOTHETICAL/REVIEW_REQUIRED`와 세계관 정책은 바꾸지 않는다.
- 자동 누적 비교의 확정 모순은 같은 canonical 항목의 현재 Job 기여 후보만 검토로 보류하고 그 후보의 실제 변경 slot/path의 frozen 입력 값·부재·출처를 append-only 복원한다. 함께 제거한 STATUS 및 root 이동의 원/목적 경로도 기존 mutation refs로 복원하며, 이동 경로의 sourceCandidateIds는 원래 근거와 같은 Job의 이동 기여자를 보존해 그 경로의 후속 파생 후보만 함께 보류한다. 값의 currentSourceTimes는 원래 의미 출처를 유지한다. 다른 항목은 계속 자동 반영한다. 캐릭터 묶음 REVIEW_REQUIRED는 PRESENT·기준시점 일치·정확한 현재 P/Q type/key에 한해 기존 targetSnapshotRef를 읽기 전용 anchor로 쓸 수 있고 제안·제거·저장 mutation target은 null이다. 세계관 CURRENT 대 단일 새 주장 모순은 CONFLICT + REVIEW_REQUIRED + GENERAL_UNCERTAINTY과 정확한 frozen PRESENT CURRENT matched/proposed 경로·출처를 사용한다. 일반 SINGLE + GENERAL_UNCERTAINTY 및 anchor 없는 캐릭터 검토는 앞선 정상값을 보류하지 않는다. 원문 값·모든 모순값을 보존하고 final 자동 반영은 automaticReviewHoldReason을 반드시 따른다. 비교 의미가 바뀐 미완료 구결과는 기존 invalidateFrom과 lease 배출 뒤 새 분석으로 비교하며 기존 완료 이력은 보존한다.
- 직접 수정·삭제한 캐릭터 slot은 `characters.manually_edited_slots`, 세계관 경로는 기존 수동 경로 표지로 보호한다. 설정집 ADD/UPDATE/MERGE/REMOVE와 부수 삭제·루트 이동은 선택 대상·원 경로·목적 경로 전체를 검사하며 삭제 후 값이 없는 slot/path도 보호한다. Worker 문맥의 `protectedSlots`/`protectedPaths`는 정규 경로로 이 표지를 전달해 실제 반영 전 임시 투영도 같은 보호를 적용한다.
- 완료한 최신 설정집 후보의 `applyToCurrent=true`는 선택한 실제 대상 경로만 명시적으로 바꾼다. 실제 current version·후보 timestamp·현재 원문/시도·완료 상태를 검사하고 그룹은 모든 버전을 어떤 쓰기보다 먼저 원자 검증한다. 미래/불명 근거는 raw flag만으로 현재값으로 승격하지 않으며 저장한 현재 반영 검토 선택이 필요하다. 새 대상은 일반 확정을 사용하고 없는 version을 추측하지 않는다. 명시 반영 후에도 해당 경로 수동 보호를 유지하며 false는 기존 후행 이력 보호를 그대로 적용한다.
- 회차 파일 처리 단계는 `source`(요청 원본) → `detected`(`DetectedEpisode*`) → `confirmation`(사용자 확정 입력) → `finalized`(`FinalizedEpisode*`) → `created`/`saved`(영속화 결과) 용어와 타입으로 구분한다. 서로 다른 단계의 값을 `episodes`, `parsed`처럼 같은 이름이나 타입으로 뭉뚱그리지 않는다.
- 회차 API의 업로드 방식은 공용 `UploadType`이 아니라 `EpisodeUploadType`의 세 값만 노출한다. 사전 감지는 `EpisodeDetectionRequest`, 최종 저장은 `EpisodeUploadRequest`로 DTO를 분리하고 multipart JSON part는 `metadata`로 통일한다.
- 최종 업로드에서 `SINGLE_EPISODE`는 `singleEpisodeNo`가 필수이며 `episodeConfirmations`를 보내지 않는다. 두 다회차 방식은 단일 회차 전용 필드를 보내지 않고, 필수 `episodeConfirmations`의 각 `detectionOrder`를 감지 결과와 일치시킨다.
- GH219 여러 파일 업로드의 확정 목록은 화면에서 회차 번호순으로 재정렬될 수 있다. `detectionOrder`로 원본 파일·본문에 연결하고 파일 선택 순서의 회차 번호 오름차순을 강제하지 않는다. 단일 파일의 본문 순서 검증과 두 방식의 감지 식별자 누락·중복·범위 및 회차 번호 중복 검증은 유지한다.
- `TextDocumentReader`는 TXT·DOCX·HWP·HWPX 형식/크기/빈 파일 검증과 텍스트 추출을, `EpisodeFileParser`는 원본 파일과 단일 회차 감지 힌트에서 회차 경계·번호·제목·본문을 `DetectedEpisode*`로 만드는 일을 담당한다. confirmation은 parser에 전달하지 않으며, 사용자 확정 번호·제목 적용과 `FinalizedEpisode*` 조립은 `EpisodeUploadProcessor`가 담당한다.
- 회차 원본 `UploadFile`은 `markEpisodesParsed(episodeStartNo, episodeEndNo, episodeCount)`로 최종 생성 범위와 파싱 완료를 함께 기록하고, 회차 범위가 없는 설정집은 `markParsed()`만 사용한다. API 응답도 `episodeStartNo`/`episodeEndNo`/`episodeCount`로 노출한다.
- 회차 조회, 수정, 삭제, 업로드는 모두 먼저 작품 소유권을 확인한다.
- Worker의 회차 처리 상태 변경은 단계별 엔드포인트를 나누지 않고, progress 요청의 `episodeStatus`로 명시적으로 전달한다. 자유 형식 표시 문구인 `currentStep`에서 상태를 추론하지 않는다.

#### 오류 검사 우선 검수 정책 (#303)

- GH310 재검수는 Work 잠금 아래 `current` report를 퇴역한 뒤 새 `revision`/`baselineRevision`을 만든다. baselineId=reportId이며 회차별 partial unique current와 revision unique를 유지한다. Episode/배치 목록·원문 reader는 현재 report만 읽는다. 추출 차단은 현재 report에 검토 종료 receipt가 없는지 확인하며 퇴역 report의 receipt는 쓰지 않는다(GH312 S2). 일반 원고/설정 저장은 무효화만 하며 검사·추출을 자동 접수하지 않는다.
- reportVersion은 JSON dirty checking의 flush와 분리한 명시적 CAS다. seed 캡처/준비 완료/보류/무효화/퇴역 및 후속 결과 변경은 Work 잠금 아래 증가시키고 조회·반복 파기는 증가시키지 않는다. Episode.contentRevision은 원문 쓰기·교체·파기마다 올려 같은 hash/key의 A→B→A도 거절한다. 해시가 실제로 없는 legacy/준비 실패 report만 null baseline hash로 새 기준을 준비하며 남아 있는 fingerprint는 우회하지 않는다.
- 검수 기준 정정은 `ValidationCorrectionService`에서 실제 기존 편집과 원자 연결한다. 캐릭터 전체 편집의 현재 snapshot version/전체 slot CAS와 지정 slot 외 변경 금지, 세계관 version·전체 scope/path를 검증하고 일반 Character PATCH 호환은 유지한다. 세계관 old/new 경로는 편집 전후 `getStoredPropertyPath`의 실제 저장 이름을 사용해 alias 요청 문자열을 scope에 남기지 않는다. 일반 저장을 직접 정정으로 추정하지 않으며 현재와 다른 history/BASELINE_ONLY 정정은 거절한다. 서버의 실제 scope만 `explicitAuthorCorrections`로 전달한다.
- DIAGNOSTIC_ONLY는 실제 자동 적용/후보 확정 식별값으로 서버가 결정하며 SUCCEEDED만으로 추정하지 않는다. Job 없는 직접 회차의 캐릭터 확정도 식별값을 보존한다. 진행 중 추출·비교를 중간에 막지 않게 자격을 확인한다. Work 잠금 안에서 확정 전 입력의 실제 출처 ID/hash·후보/Job 저장 위치·수명·파기 대기도 검증하고 입증 불가면 DIAGNOSTIC_BASELINE_UNAVAILABLE로 보류한다. 진단은 retained 원문으로 옛 출처를 재연결하거나 live 현재값/타겟 자기 근거로 보충하지 않으며 일반 fresh 기준의 retained-key 허용은 유지한다. report 없는 legacy 회차 최초 진단 입구는 별도 범위다.
- `ValidationStagingEvents.ReportInvalidated(workId,reportId)`는 Work 잠금 안의 동기 삭제 경계다. 후속 결과/인용/checkpoint도 같은 트랜잭션에 정리한다. 교체 정리의 retainedContentKey는 새 원문만 보존하며 contentRevision/hash/key/version 검증을 생략하지 않는다. 작품/회원 파기에서는 정정 사본부터 삭제한다. 상세 계약은 `docs/validation-execution-v1.md`를 따른다.

- GH310 실행은 `VALIDATION_EXECUTION_ENABLED=false`가 기본이다. 활성화에는 lease/회수 한도, 양쪽 모든 InvocationBudget와 다섯 Terra 역할 cap을 명시한다. 일반 API 기동에는 이 예산이 필요하지 않으며 과거 대기 report를 자동 backfill하지 않는다. 명시 START/RECHECK 복구 또는 RETRY만 queue한다. retry는 Work 잠금 아래 최신 활성 회차를 확인하되 성공한 원 receipt 재전송과 기존 lease 복구에는 후속 회차만으로 거절하지 않는다.
- 전용 lease/실행 세대·inputDigest와 current check revision/checkInputDigest는 public reportVersion과 다르다. 두 분기는 별도 checkpoint CAS를 쓰며 heartbeat/조회/동일 결과 재전송은 reportVersion을 올리지 않는다. 원문 I/O 전후 실제 lease/현재 입력을 확인하고 I/O 뒤 짧은 Work → Job → 항목/원장·계정 잠금 안에서 다시 검증한다.
- 서버는 실제 code point 원문·전체 catalogue·각 설정집의 길이로 필수 grid와 versioned modelSettings/modelSourceCatalogue/modelClues를 산출한다. Worker 실행은 이를 소비하며 모델이 source/member/work/lease/cap을 정하지 않는다. BOOK_SOURCE는 전문 읽기, BOOK_COVERAGE는 target×book 구간 검사다. 정상 0건은 저장된 완료 check/typo/검증된 NO_CHECK 처리로만 센다. 공개 results에는 전체 bundle·catalogue·checkpoint·호출 원장을 노출하지 않는다.
- SETTING gridVersion1은 manifest의 모든 BOOK_SOURCE 의무와 pendingBookRefs를 먼저 보존하고 해당 book의 성공 전문 검증 때만 길이·BOOK_COVERAGE를 확정한다. 다른 book의 일시 I/O 실패는 성공한 planner/selection/coverage/checkpoint 저장을 막지 않는다. Work 아래 단조 확장으로 동시 준비를 합치며 pending book은 merge/SETTING완료/NO_CHECK를 막고, 실제 source 변경·파기는 기존 전체 metadata 검증·무효화 경계를 유지한다.
- 같은 입력 RETRY는 성공 단계/check와 실제 원장 누계를 보존한다. 유효 saved seed의 준비 실패는 source 수명/hash 검증 뒤 기존 materialize로 복구하고 seedless/invalidated는 새 기준 접수 경계를 유지한다. 명시 retry에서만 현재 cap/호출·시간·입출력 상한을 새 budgetRevision에 채택하며 segmentation/context/catalogue/search 폭과 모델·추론은 보존한다. 부족한 필수 generation role cap은 409이며 기존 RETRY key는 원 response_snapshot을 반환한다. 초기 optional query embedding은 명시 cap+기존 색인 호환 모델과 미사용 증명을 요구한다. actual reserve cap은 항상 검사하고 reuse-aware QUERY retry preflight는 Task3의 typed receipt 복원과 함께 완성한다.
- 검수 원장은 실제 validation_job_id 및 원래 lease/generation을 가진다. 분석 예약 경로는 VALIDATION_* 목적을 거절한다. 미호출이 확실한 RELEASE만 cap을 반환하고, UNKNOWN/만료/무효화는 hold를 풀되 호출 cap과 늦은 실제 정산 소유권을 보존한다. Work/회원 파기는 UNKNOWN 호출도 drain한 뒤 account hold 해제와 자식 → usage → Job → report 삭제 순서를 지킨다.
- canonical author_correction은 null이며 plural authorCorrectionProofs는 실제 정정 사실이다. 자유문자열 질문의 서사 시점/조건 권위로 승격하지 않으며 DB_RAW_DISAGREEMENT는 NEEDS_CONTEXT로 둔다. typed 시간·조건 대응이 입증되는 후속 계약 전에는 자동 해소하지 않는다.
- GH306의 `ValidationDataService`는 내부 API key와 신뢰 서버의 회원·작품·report 범위 및 expected baseline/target/source fingerprint를 검증해 저장 기준과 실제 원문만 읽는다. 원문 I/O 전후 scalar 조회로 교체·파기·무효화를 확인하고 live 현재값으로 보충하지 않는다. Spring Boot 4 HTTP 경계에는 Jackson 2 JsonNode를 직접 반환하지 않고 `ValidationDataMapper.toTransport`의 표준 JSON Map을 사용한다. 원문 위치는 Unicode code point이며 전문·부분 읽기의 범위를 유지한다. 전용 Job이 있으면 X-Validation-Job-Id와 실제 X-Worker-Lease-Token을 읽기 전후 요구한다. 기존 no-job 호출 계약은 유지하며 read endpoint가 Job/결과/추출 release를 생성하지 않는다. 공통 계약은 `docs/setting-validation-v1.md`다.

- `domain.validation`은 고정 기준과 전용 ValidationJob/lease·의미 단계·check revision·finding/근거·분기 checkpoint를 소유한다. 검토 종료 전에는 추출 Job·후보·journal·자동 확정이 0이다. GH312 S2의 검토 종료 receipt(V97)와 release Job은 `ValidationReviewCompletionService`가 Work 잠금 아래 같은 트랜잭션에서 공개 생성 경로(`createReviewReleaseExtractionJob`)로 만든다. 마지막 판단 저장은 이를 호출하지 않는다. GH312 S3부터 release 회차(현재 report에 검토 종료 receipt)의 확정 설정은 `ValidationSettingsApplicationService`만 쓴다. 추출 완료 또는 명시 요청에서 두 도메인을 한 트랜잭션에 모두 반영하거나 아무것도 쓰지 않고 V99 HELD_REVIEW receipt를 남기며, 사람의 확정·승격 진입점은 `AnalysisCandidateSourceGuard.assertManualWriteAllowed`로 409다. 반영 방식·결정 초안 저장은 허용하고 회차 전체 확정이 공개 그룹 확정 본문으로 그 결정을 반영한다. 계약은 `docs/validation-review-v1.md`다.
- report에 소유권, 접수 당시 batch와 원고 fingerprint를 고정한다. 재검수 report는 재검수 시점 회차의 현재 원본 업로드 batch에 묶어 파일 교체 뒤 release Job도 새 batch에 만든다(GH312). 접수 Work 잠금 아래 기존 확정 state 캡처를 읽기 전용으로 재사용해 캐릭터/세계관 전체 확정 이력과 앞선 활성 원문/실제 설정집 revision을 보존한다. 정규화는 저장 seed만 사용하며 typed 원문 실패는 report 보류로, DB 오류는 롤백으로 구분한다.
- create/reanalysis/retry/claim/늦은 lease/후보 적용은 Job 정책이 아니라 report의 회차를 확인한다. target-only legacy 관계도 차단하며 claim SQL에서 미리 제외한다. no-job report는 원고/배치 목록의 얇은 projection과 별도 report 목록으로 표시하고 가짜 Job/성공 건수를 만들지 않는다. 기존 일반 추출의 실제 반영 완료/후행 claim 조건은 유지한다.
- 원고·기준 출처 변경/파기와 확정 설정 변경은 Work 잠금 안에서 새 기준 복사본을 무효화한다. 단, 회차 전체 확정 APPLIED receipt가 있는 report는 확정 설정 변경으로 무효화하지 않는다(GH312 S3, 자기 반영 포함). 기존 설정 직접 수정은 허용하되 재검사를 요구한다. 늦은 정규화는 같은 트랜잭션의 무효화도 flush 후 재확인한다. 작품/회원 파기는 새 자식 행을 먼저 제거한다. 계약과 후속 release 원칙은 `docs/episode-validation-staging.md`를 따른다.
- 검수 기준을 바꾸는 기존 비교 callback도 Work → Job → Character 잠금 순서를 지킨다. singleton/batch EXCLUDE가 대표 서사 시점을 실제 변경할 때 기준 복사본을 같은 트랜잭션에서 무효화하고, no-job 배치 최근 활동 시각에는 report.updatedAt을 포함한다.
- GH310 실행 PostgreSQL은 새 loopback `gh310_execution_test`의 `GH310_EXECUTION_POSTGRES_JDBC_URL`로만 실행한다. `ValidationExecutionPostgresIntegrationTest`는 실제 Flyway/JPA validate·동시 claim/게시/원장·원문 ABA/파기와 저장 계획 복구를 검증하며 합성 storage/provider와 실제 모델 품질을 구분한다. 기존 DB/.env는 재사용하지 않는다.
- GH303 실제 PostgreSQL은 새 loopback `gh303_error_first_test`의 `GH303_ERROR_FIRST_POSTGRES_JDBC_URL`만 사용한다. 기존 DB/.env는 재사용하지 않고 skip은 통과로 계산하지 않는다.

#### Analysis Domain Policy

- AnalysisJob은 작품에 속한 단일 회차 AI 분석 작업의 상태와 결과 메타데이터를 추적한다. `UploadBatch`는 업로드 출처 묶음이며 분석 실행 단위가 아니다.
- 첫 분석 안내는 계정 전체 기준으로 V64의 `analysis_guide_shown_at`과 `first_analysis_started_at`을 사용한다. 조회는 비소비, 노출 선점은 회원 잠금 아래 한 번만 성공하며 Job 생성과 최초 분석 시각을 같은 트랜잭션에 저장한다. 작품 삭제 후에도 경험 기록을 유지한다. 예시 확정은 실제 분석·설정 변경을 호출하지 않는다. 상세 API·이관 범위는 `docs/analysis-mode-guide.md`를 따른다.
- 원문 텍스트는 `Episode`의 S3 저장 구조를 재사용한다. `analysis_jobs`에는 작업 메타데이터와 누적 모드의 고정 S0·검증 변경 기록을 저장하며 원문 전문이나 raw LLM 응답을 중복 저장하지 않는다.
- 분석 실패 이력은 `analysis_jobs.error_message`에 누적하지 않는다. Discord 발송 이력은 원시 오류 없이 `analysis_failure_notifications`에 식별자·고정 실패 코드·안전한 진단 snapshot·전송 상태로 따로 기록한다.
- 새 회차 분석으로 대체된 legacy 캐릭터·세계관 비교 Job은 `cancelForSourceChange`로 `CANCELED` 마감한다. 토큰 예약 해제·후보 unlink·flush 순서는 유지하며 정상적인 교체를 `FAILED` 장애로 기록하거나 Discord 실패 알림으로 보내지 않는다.
- 화면은 업로드 묶음에 생성된 회차별 `AnalysisJob.status`를 집계하고, 각 Job의 단일 대상 `Episode.status`를 단계별 상태로 보여준다.
- 신규 분석 작업은 정확히 한 회차를 `analysis_job_episode_targets`에 스냅샷으로 저장한다. 이후 회차 원본 교체나 `ARCHIVED` 전이로 과거 작업의 대상이 바뀌지 않게 하며, 과거 batch-wide 작업의 복수 target 연결은 조회 이력 호환용으로만 유지한다.
- 분석 배치 목록은 `UploadBatch`를 페이지 항목으로 사용하고, 같은 분석 목적·회차의 재시도 이력 중 최신 `AnalysisJob`만 현재 상태에 포함한다. `UploadBatch` 자체를 독립 분석 실행 식별자로 해석하지 않는다.
- 분석 목록은 현재 페이지 batch IDs를 제한한 `AnalysisBatchSummaryRepository`의 최신 대상·표시 상태 SQL 집계를 사용한다. 목록용으로 `AnalysisJob`/후보/설정집 본문 엔티티나 큰 상태 JSON을 읽지 않는다. 회차 `episodeJobGroups`와 설정집 `settingBooks`를 분리하고, 설정집 후보 수는 선택된 원본 Job ID별 다건 집계로 구한다. 상세 조회를 카드마다 반복해 heap·전송량을 키우지 않기 위한 경계다.
- 회차 진행 조회는 `/analysis-jobs/progress`의 1~100개 raw ID 제한과 전체 소유권·공개 유형 검증 뒤 두 scalar projection만 읽는다. 입력 중복은 최초 순서를 보존하며 대상 스냅샷을 단일 episode fallback보다 우선한다. 상태 확인에 큰 JSON·원문·Job/Episode 엔티티를 읽지 않기 위한 경계이며 HTTP/JPA 경계를 실제 PostgreSQL SQL·entity-load 통계로 검증한다.
- GH296 캐릭터·세계관 후보 목록은 후보 엔티티와 비교 문맥을 유지하면서 `CandidateReviewMetadataRepository`의 불변 scalar 메타데이터를 명시적으로 Mapper에 전달한다. 원본/비교 Job proxy의 ID만 참조하고 `automatic_input_state`, `run_base_state`, `state_journal` 또는 전체 `AnalysisJob`은 읽지 않는다. `includeLegacyCandidates=true`도 같은 경계를 따르며 상세·변경 API는 기존 완전한 엔티티 경로를 유지한다. 상태 정책은 `AnalysisReviewState`와 후보 predicate overload로 공유하고, 실제 HTTP 직렬화 뒤 PostgreSQL SQL·entity-load 통계로 검증한다.
- GH296 `/world-setting-candidates/summary`는 필수 `batchId`와 선택 `sourceAnalysisJobId`로 기존 목록 최상위 값에서 `groups`만 제외한 전체 범위 집계를 반환한다. 목록과 같은 source 검증·회차 범위·검토 집계·활성 비교 Job 수를 재사용하고 후보/Job 엔티티는 조회하지 않는다. 출처 생략은 EPISODE만, 지정은 해당 추출 origin만 포함하며 과거 설정집 이력을 읽기 가능한 상태와 수정 가능한 상태로 구분한다. 캐릭터 검토 화면이 세계관 개수를 얻기 위해 후보 본문 전체를 가져오지 않도록 둔 경계다.
- 같은 화면의 세계관 비교 집계는 `/world-setting-candidates/progress?batchId=...`의 세 count만 반환한다. 기존 `countReviewSummary`의 EPISODE·배치·검토 대기·토큰 실패 범위를 유지하고 후보 목록이나 Job 엔티티를 조회하지 않는다. 새 진행 계약은 Backend를 먼저 배포한 뒤 Frontend에 적용한다. 상세 계약은 `docs/analysis-progress.md`를 따른다.
- 회차 출처 Worker의 claim·진행·완료·실패 처리는 신규 Job의 단일 대상 `Episode.status`만 전이한다. 설정집·설정집 숨김 비교는 Episode 상태를 요구하거나 갱신하지 않는다. 한 회차 실패가 다른 회차 Job이나 상태를 변경하면 안 된다.
- 공개 분석 작업 생성 API는 `batch_id`를 필수 입력으로 받고 `episode_id`를 선택 범위 지정자로 허용한다. `episode_id`가 없으면 batch의 현재 회차마다 Job을 하나씩 생성해 목록으로 반환하고, 있으면 해당 회차 Job 하나를 목록으로 반환한다.
- 본인 작품의 분석 작업만 생성/조회할 수 있으며, 다른 회원의 작품이나 다른 작품에 속한 분석 대상은 404로 응답한다.
- Python AI Worker는 작업 claim과 `AnalysisJob` 상태 변경에 `/api/internal/**` 내부 API를 `X-Internal-Api-Key`로 인증해 사용한다. Worker에는 원문 본문을 응답하지 않으며, 출처별 `episode` 또는 불변 `settingBook`의 S3 key/version/hash/charCount 메타데이터, 활성 캐릭터 설정 schema와 `ACTIVE` 캐릭터 목록을 전달한다. 각 캐릭터는 ID·대표 이름과 현재 `STATUS`의 `factKey/factValue`만 가지며 UUID provenance·이력·`valueJson`은 1차 추출 문맥에 노출하지 않는다. 현재 STATUS는 임의 절단하지 않고, legacy raw snapshot의 표시값은 provenance를 작품 전체 bulk 조회해 보완하되 복원 불가능한 고아 값만 `null`로 유지한다. `ARCHIVED` 캐릭터는 이후 원고 매칭과 상태 문맥에서 제외한다.
- Worker claim은 `allowedJobTypes`가 필수이며, claim 성공 시 5분 lease token을 발급한다. progress·heartbeat·complete·fail, 토큰 예약과 세계관 내부 API는 같은 `X-Worker-Lease-Token`을 검증한다. 만료 Job은 다음 claim에서 최대 3회까지 checkpoint부터 재대기시키고, 예약 중 토큰을 해제한 뒤 한도를 넘으면 실패 처리한다.
- 새 누적 Worker는 EPISODE·SETTING_BOOK 모두 `supportsSemanticCurrentContext=true`를 요청하고 claim의 최상위 `semanticCurrentContext`를 필수 검증한다. 이 읽기 문맥은 known/provisional identity와 같은 `getInputState` 한 개에서 모든 현재 `factType/factKey/factValue/valueJson`(AGE/LEVEL·비활성 STATUS 포함) 및 모든 category의 세계관 전체 경로/문자열 값을 파생한다. shared `analysisContext`나 비교 echo에 넣지 않으며 provider에는 내부 UUID 대신 로컬 참조를 사용한다. complete·run/generation/input/source hash·identity XOR/중복·nullable key를 검증하고 전체 실제 UTF-8 직렬화 1MiB/슬롯+속성 4096 상한을 넘으면 절단하지 않는다. deterministic carrier 오류만 기존 실패 경로로 마감·journal INCOMPLETE·lease/예약 토큰 해제해 대기열을 막지 않으며 source/run/hash conflict를 이 오류로 바꾸지 않는다. 구 capability 생략/false 및 CONFIRMED_ONLY 응답은 기존 계약을 유지한다. 의미축 판단에 완전한 고정 CURRENT를 제공하되 이를 identity proof·원문 근거·자동 반영 권한으로 사용하지 않기 위한 규약이다.
- `CONFIRMED_ONLY`의 `SETTING_EXTRACTION` Job은 `CHUNKS_READY → CHARACTER_CANDIDATES_SAVED → CHARACTER_COMPARISONS_HANDED_OFF → WORLD_CANDIDATES_PUBLISHED → WORLD_COMPARISONS_FINISHED` checkpoint를 단조 증가시킨다. `CHARACTER_COMPARISONS_HANDED_OFF`는 회차별 후보 게시 책임을 그룹 숨김 Job에 넘겼다는 뜻이며 비교 완료로 재해석하지 않는다. 같은 batch의 모든 원 Job이 후보를 게시했거나 더 이상 게시할 수 없는 상태가 된 뒤에만 그룹 Job을 예약하므로 원 Worker slot은 입력 barrier를 기다리지 않는다. 구 Worker의 `CHARACTER_COMPARISONS_FINISHED`는 실제 비교 결과가 남아 있는 호환 경로로만 읽는다.
- GH301 부모 progress의 반복 인계는 같은 배치·입력 hash의 `SUCCEEDED/FAILED` 비교를 다시 예약하지 않는다. 사용자 재시도·연결 변경, 새 입력, 원문 재분석으로 `RECOMPARISON_REQUIRED`가 된 입력은 예약할 수 있다. 후보가 추가됐다 제거되어 과거 hash로 돌아오는 경우도 실제 무효화를 우선한다. 활성 Job 중복 방지·작품 잠금은 유지하며 실패를 자동 유료 재시도로 바꾸지 않는다.
- 새 `SETTING_EXTRACTION` 생성·재분석 요청에서 `reviewMode` 생략은 `AUTOMATIC`이며 단일 회차는 명시적인 `MANUAL` 선택을 유지한다. 새 다회차 전체 분석은 항상 `AUTOMATIC + ORDERED_PROVISIONAL`이고 자동 단일도 같은 입력 정책을 사용한다. 수동 단일의 `analysisMode` 생략은 `CONFIRMED_ONLY`, `EPISODE_VALIDATION`의 반영 방식 생략은 `MANUAL`을 유지한다. 업로드 화면의 자동 기본 선택과 API 생성 정책을 일치시키되 기존 Job의 실패 재시도는 최초 정책과 입력을 그대로 보존한다. 실행 ID·generation·회차 목록/순서·원문 hash/key/version/회차 번호·반영 방식은 생성 시 고정하며 기존 Job을 소급 변경하지 않고 구 Worker에는 광고한 모드만 claim한다.
- 같은 작품의 `SETTING_EXTRACTION`은 짧은 작품 row 잠금 후 새 statement에서 claim 조건을 재확인한다. 누적 모드의 후속 회차는 같은 run/generation predecessor가 `SUCCEEDED`이고 journal이 `SEALED`여야 한다. 다른 작품의 실행은 유지하고 LLM 호출 동안 DB 잠금을 유지하지 않는다.
- 수동 누적 실행은 첫 Job의 S0에 당시 검증한 변경 기록을 순서대로 replay한다. 자동 누적 실행은 각 회차 claim 직전에 실제 저장된 캐릭터·세계관을 다시 읽고 앞 회차의 검토 대기 후보를 별도 `unresolvedReferences`로 고정한다. 같은 회차의 1차·2차 비교와 재시도는 이 `automatic_input_state`와 hash를 일관되게 사용한다. 후보 현재값을 과거 journal replay 원본으로 사용하지 않는다. 도메인 validator와 `AnalysisJournalContributor`가 모든 source 후보 coverage를 검증한 뒤 seal한다. `AnalysisStateSource`는 설정 조회·매핑만 담당한다.
- 누적 대상의 `provisionalSubjectKey`는 실제 FK와 별도이며 비교 도중 실제 대상을 생성하지 않는다. 자동 반영 Job 완료는 `Work → Job` 순서로 잠근 같은 트랜잭션에서 캐릭터·세계관 기존 확정 규칙을 적용하고 `automatic_applied_at`과 성공 상태를 함께 기록한다. 검수 release Job은 예외로, 후보 하나라도 대기로 남으면 전체를 되돌리고 회차 전체 확정 APPLIED일 때만 `automatic_applied_at`을 기록한다(GH312 S3). 다음 회차 claim은 이 완료 기록까지 요구하므로 저장 전 설정을 읽지 않는다. 모호한 연결·검토 필요·사용자 수정·오래된 비교는 대기로 남긴다. 자동 반영만으로 사용자 수정 무효화를 호출하지 않으며 작가의 수정은 기존 무효화 규칙을 유지한다.
- 자동 검토 출처는 후보의 `reviewed_automatically`로 보존하며 세계관의 `reviewed_by`에 작가를 기록하지 않는다. ordered 입력의 `reviewSource`는 `HUMAN`과 `AUTOMATIC`을 구분한다. 확정된 인물 발견의 이름·근거를 별칭 문맥으로 보존하되 이름의 부분 일치만으로 인물을 병합하지 않는다. 미해결 참고는 현재 설정이나 매칭 가능한 대상에 섞지 않는다. 수동 `CONFIRMED_ONLY`의 기존 입력·프롬프트는 유지한다.
- 수동 누적 `MANUAL + ORDERED_PROVISIONAL`은 첫 실패 비교 묶음에서 후속 묶음·단계·회차 진행을 중단한다. 수동 `MANUAL + CONFIRMED_ONLY`는 실패와 독립적인 세계관 묶음 비교를 계속한다. 자동 누적 모드는 개별 비교 묶음 실패의 원문 주장을 미확정 참고로 보존하고 다른 정상 묶음의 비교·저장과 다음 회차를 계속한다. 세계관 묶음은 수동 직접 검토·자동 누적 모두 독립적으로 검증한 `decisions`와 typed `failures`가 전체 후보를 중복 없이 정확히 덮을 때 하나의 완료 트랜잭션으로 저장할 수 있다. 정상 결정은 기존 고정 문맥·경로 충돌·합성 범위 검증을 그대로 통과해야 하고 실패 후보는 FAILED로 남으며 자동 누적에서만 불변 실패 참고를 등록한다. 순차 캐릭터 묶음은 자동·수동 모두 독립적으로 검증한 정상 결정과 격리 가능한 실패를 함께 저장할 수 있다. 수동 누적은 실패가 남으면 다음 묶음·회차를 차단하고 자동 누적만 다음 진행을 허용한다. 실패 후보의 결과 참조·의존은 허용하지 않으며 작업 단위 오류를 정상 결정과 섞어 완료할 수 없다. 수동 세계관의 정상 병합 결정과 출처 membership은 보존하고 실패 행만 재비교한다. 수동 성공은 자동 반영하지 않으며 같은 주체 그룹의 확정은 실패 행의 재비교를 기다릴 수 있다. 토큰 부족·lease 상실·입력 불일치·Backend 오류·추출 단계 전체 실패는 작업을 중단한다. 자동 누적의 후보별 주체 연결 실패는 아래 준비 실패 계약에 따라 격리한다. PENDING/PROCESSING은 실패 참고로 가장하지 않는다. 사용자 재시도는 원래 Job/run/generation/S0/input/checkpoint와 완료 prefix를 보존한다. 임시 대상을 legacy hidden 재비교 Job으로 보내지 않는다.
- 자동 모드의 실패 격리는 후보의 `canDeferFailedComparison()`을 claim·journal 마무리·작업 완료에서 공통으로 사용한다. 실제 비교 문맥이 저장된 묶음의 격리 가능한 AI 실패와, 자동 누적 후보에 명시적으로 기록한 `preparation_failure_stage`의 실패만 허용한다. 주체 연결 실패는 실제/임시 대상 연결 없이 보존하며 비교 준비의 값 형식·문맥 크기 실패는 해당 후보/묶음만 보류한다. 단계 기록 없는 선검증 실패와 Backend 원인 코드가 있는 세계관 실패는 계속 차단한다. AI의 입력 문맥 오류도 격리 가능한 비교 코드로 기록하지 않는다. 같은 오류가 처리 단계에 따라 다르게 취급되어 후속 회차가 잘못 진행되는 것을 막기 위한 규칙이다. 실패 참고의 멱등 기준은 후보가 다른 사건에 사용됐는지가 아니라 해당 실패 참고의 eventId이며, 주체 등록에 쓰인 첫 후보도 원문·값을 빠짐없이 보존한다.
- 자동 누적 세계관 연결의 실행 실패는 `failureCode`와 빈 대상 목록을 받으며 `FAILED`로 저장한다. 정상적인 `AMBIGUOUS`와 구분하고 실제/임시 대상을 생성하지 않는다. 선택적 캐릭터 연결 보강의 실패도 원본 후보 최초 저장 시 단계와 실패 코드를 기록하고 Spring이 실패 참고와 보류 안내를 완성한다. 계정 인증·결제·토큰·lease·입력 변경·저장 오류는 후보 격리 코드로 우회하지 않는다.
- 캐릭터 비교는 동일 인물의 모든 FactType을 한 논리 그룹으로 원문 순서로 claim하며 후보 수·문자 수로 자르거나 큰 입력을 Java에서 미리 보류하지 않는다. AI가 전체 prompt 입력 256,000토큰과 출력 상한 128,000토큰으로 분할한다. Sol 6.1 캐릭터 비교 입력은 생성과 같은 전체 payload의 provider token-count API로 정확히 확인하며 동일 입력의 count는 재사용한다. count 실패에는 추정 fallback 없이 Job 오류로 중단하고, complete는 모든 후보를 정확히 한 decision/typed failure로 덮는다. 묶음 decision·dependency·제거 참조 목록에 20/30개 개수 제한을 다시 넣지 않는다. 새 인물 묶음은 모든 유형의 snapshot을 제공하며 30개 문맥 제한을 적용하지 않는다. 기존 단건과 타입이 명시된 예전 저장 batch의 snapshot 정책 및 세계관 속성 비교 한도는 유지한다. `X-Character-Comparison-Person-Batches: true`를 광고한 claim에만 새 묶음을 생성하며, 없는 구 Worker에는 기존 유형별 묶음을 반환한다. 새 batch/context의 `canonicalFactType=null`은 인물 전체 범위이고 각 candidate의 `canonicalFactType`을 검증·저장·projection에 사용한다. 예전 batch의 타입 값은 그대로 유지한다.
- 자동 누적 비교 준비는 캐릭터 설정 형식 오류를 항목별로 보류한다. 세계관 묶음은 기존 원문 순서를 유지하여 후보 20개 이내로 나누고 앞 묶음 처리를 반영한 뒤 다음 묶음을 준비한다. Java는 내부 JSON 문자 수로 비교를 차단하지 않으며 Worker가 최종 LLM 입력·예상 출력 토큰을 검사한다. 초과한 묶음은 후보별 원본 경로·값을 보존한 `REVIEW_REQUIRED + BATCH_LIMIT_EXCEEDED`로 완료하고 journal 참고에도 미확정 상태·동일 사유를 남긴다. 후보 수·source coverage·문맥 token/version·canonical 경로 검증과 고정 입력 오류 중단은 유지하며 전체 문맥을 임의 절단하지 않는다.
- 캐릭터 자동 저장 전에 실제 저장과 같은 순수 검증 계획을 순서대로 계산한다. 앞서 수용한 후보만 예상 상태에 적용하고 실패 항목과 의존 항목은 보류한다. 검증 예외를 DB 쓰기 후 삼키지 않으며 실제 저장 장애는 회차 완료 트랜잭션 전체를 롤백한다. 명시적 이력 저장은 현재 상태를 변경하지 않는다. 자동 모드의 격리 가능한 준비·비교 실패는 인프라 오류 후 같은 작업 재개에서도 초기화하지 않는다. 이미 journal에 기록한 실패 참고와 새 비교 결정이 중복되는 것을 막는다.
- 세계관 후보의 `sourceAnalysisJobStatus`·`sourceAnalysisJournalStatus`는 원본 회차 상태를 나타내는 선택 응답이다. 비교 PENDING이나 별도 비교 Job 수를 순차 실행 중단으로 오인하지 않도록 제공한다. 일반 사용량 중단의 배치 비교 재개와 순차 회차 Job 재개는 구분하며 후보 목록·분석 목록은 같은 배치 전체 집계로 재개 불가 대상(순차/자동 반영 중)을 확인한다. 페이지·필터로 보이지 않는 후보도 포함하고 실제 토큰 잔액은 POST에서 재검증한다.
- 세계관 신뢰도는 필수 유한 숫자 0~1의 원값을 보존한다. 정해진 세 등급값만 허용하거나 반올림해 정상 추출을 거절하지 않는다.
- 분석 목록의 완료 집계는 누적 기록 봉인과 자동 저장 완료까지 확인한다. 실패·무효화된 앞 회차에 막힌 대기는 실행 중으로 세지 않는다. 검토 후보가 남는 것과 분석 실패를 구분한다.
- 자동 분석의 미확정 참고는 업로드 묶음과 무관하게 같은 작품의 앞 회차별 최신 추출 작업에서 수집한다. 최신 작업이 실패·진행 중·무효여도 이전 성공 후보로 돌아가지 않으며 원문 manifest·대기 상태·원문 인용을 검증한다. 무효 작업에서는 원문이 유효한 명시적 사용자 수정만 최신 편집값/수정안으로 참고하고, 제외 수정안·보관된 인물·비어 있는 값은 제외한다. 비교 실패의 제안값·잘못된 경로·모델 판단 문장은 참고 근거로 복사하지 않는다. 다른 실행의 journal을 replay하거나 참고 후보를 실제 대상·확정 사실로 승격하지 않으며, claim에서 고정한 입력/hash와 작품 단위 수정·원문 파기 무효화를 그대로 유지한다. 업로드를 나눴다는 이유로 앞 회차 정보를 잊거나 오래된 분석을 다시 신뢰하는 문제를 방지하기 위한 규칙이다.
- Worker 입력 문맥의 미확정 참고 목록은 고정된 참조 식별자로 정렬해 반환한다. PostgreSQL jsonb의 객체 키 순서와 메모리 생성 순서가 달라도 claim과 후속 비교가 동일한 목록을 받아야 하며, Worker의 입력 동일성 검증을 느슨하게 바꾸어 해결하지 않는다.
- 세계관 비교 진단은 완료·실패 API의 typed `diagnostics`로만 저장한다. 최대 30개 항목의 시도 번호, 안전한 규칙 코드, 입력 후보 ref, 고정 비교 문맥에 실제 있던 대상·범위·속성 경로만 허용한다. 원문 prompt/response·설정값은 진단에 저장하지 않는다. 후보·경로를 특정 못했으면 빈 목록을 사용한다. 정상 복구를 포함해 후보별 관련 진단과 후보를 특정하지 못한 공통 진단을 합쳐 중복 제거 후 시도 순서상 최근 30개를 `comparisonDiagnostics` 응답에 보존하며 재비교 시작 때 이전 진단을 비운다. 실패 항목 진단은 해당 source refs로 제한하고 전체 완료 진단은 묶음의 source refs로 제한한다.
- 자동 저장이 끝난 회차의 보류 후보는 `manualReviewAvailable`로 직접 검토 가능 여부를 제공한다. 캐릭터는 사용자가 값을 명시적으로 저장한 뒤 `applyEditedValue`로 현재 설정에 반영하고, 세계관은 저장된 사용자 결정 draft로만 비교 실패·재비교 필요 후보를 확정한다. 세계관의 원래 FAILED 비교 상태는 보존하여 사람의 결정을 AI 비교 완료로 위장하지 않는다. 자동 반영의 정상 EXCLUDE는 자동 제외로 처리하고 사용자 반려 정책으로 승격하지 않는다.
- 누적 캐릭터 비교의 표시용 기존값은 실제 비교 직전 상태를 Backend가 `rawComparisonJson.backendComparisonBefore`에 덮어써 보존한다. AI가 보낸 동명 필드는 신뢰하지 않는다. 이전 데이터는 저장된 비교 입력으로 복원하며 현재 DB로 대체하지 않는다. 세계관 후보 응답의 `analysisMode`는 회차 간 동일 경로 갱신과 같은 회차 중복을 구분하기 위한 계약이며 최종 순차 반영 검증은 Backend가 담당한다.
- GH201 정식 캐릭터·세계관 설정 직접 변경은 완료된 순차 분석의 입력·결과를 보존한다. Work 잠금 아래 진행·대기 및 같은 입력으로 재개할 FAILED/INCOMPLETE 실행이 있으면 의미 있는 변경을 거절하고, 의미와 수동 보호 상태가 같은 no-op 저장은 허용한다. 이미 종료된 실행의 기록을 현재 설정으로 다시 계산하지 않으며 다음 신규 분석만 변경값을 읽는다. 원문 수정·교체·삭제는 `Work → ordered Job → candidate/설정` 잠금 순서를 유지하되 변경된 회차 자신의 Job과 영향 이후 미완료 순차 입력만 무효화한다. 후행 `SUCCEEDED + SEALED`이고 자동 반영까지 완료된 결과는 보존한다. 회차 이동은 바뀐 Episode ID로 당사자를 식별하고 이전/새 번호의 최솟값부터 미완료 영향을 확인한다.
- 무효화된 Job은 동일 입력 재개를 거절하며 새 ordered 생성은 시작 회차 또는 이후의 확정 이력을 `ANALYSIS_FUTURE_HISTORY_CONFLICT`로 구분해 거절한다. 지원하지 않는 과거 복원을 현재 snapshot으로 대체하거나 유료 재실행을 자동 시작하지 않는다. 동일 원문의 최신 회차 Job이 재개 가능한 순차 실패이면 새 생성도 `ANALYSIS_ORDERED_JOB_RETRY_REQUIRED`로 거절해 완료 checkpoint를 버리지 않도록 한다. 기존 개별 MANUAL 분석은 원문 변경·무효화·정상 완료 등 기존 생성 검증을 통과하는 경우에만 명시적으로 실행한다.
- 순차 분석은 실패 회차부터 같은 Job/run/checkpoint로 재개하고 전체 완료 뒤 기존 batch 후보를 검토한다. 선행 실패로 막힌 대기는 계속 분석 중이라고 안내하지 않으며 부분 검토·잔여 취소·부분 완료 상태를 추가하지 않는다. 원고 목록도 최신 ordered journal의 무효화와 저장 미완료를 완료로 표시하지 않는다. 상세 범위와 남은 한계는 `docs/gh201-analysis-recovery.md`를 따른다.
- 실행 중 ordered Job의 무효화는 해당 Episode의 진행 상태와 미정산 토큰 예약도 같은 트랜잭션에서 정리한다. 토큰 정리는 동기 `AnalysisRunInvalidatedEvent`로 연결해 token→lease→state service의 생성자 순환을 피한다. 이미 정산된 사용량은 바꾸지 않는다.
- S0의 미래 출처 검사는 현재 snapshot뿐 아니라 전체 확정 후보·캐릭터 Fact 이력·최초 등장 회차도 확인한다. REMOVE로 현재 slot이 사라졌거나 인물 발견만 확정된 경우도 현재 snapshot을 과거 설정으로 오인하지 않기 위함이다.
- ordered 캐릭터 수정 후보는 `applyEditedValue=true`를 명시한 사용자 확정에서만 현재 실제 snapshot·schema로 ADD/UPDATE를 검증한다. AI 비교로 가장하지 않고 `USER_EDIT` 출처를 기록하며 기존 AI journal은 무효화 상태로 보존한다. `confirmed_application_mode`가 `APPLY_PROPOSAL`인 선행 확정만 후속 임시 의존성의 실제 반영 근거로 인정한다.
- 명시적 새 ordered 실행은 동일 원문·대상·값·근거의 사용자 반려를 S0 `references`의 `HUMAN_REJECTION_POLICY/EXACT_SOURCE_CLAIM_V1` 해시로 이어받는다. 이 해시는 서버 판정용이며 prompt/current fact/scoring history로 전달하지 않는다. 비교 전 원본 후보는 보존하면서 반려 reference로 coverage를 채우고, 정확히 반려된 discovery에 직접 연결된 설정은 미해결 상태로 둔다. 이름만으로 차단하거나 불변 원문 manifest가 없는 legacy 후보에 소급하지 않는다.
- 원문 파기는 기존 후보 근거뿐 아니라 영향 이후 ordered journal·S0·자동 시작 문맥에 복사된 identity evidence·참고 주장과 비교 context도 정리한다. S0는 실행 중 불변이다. 근거 삭제는 무효화된 입력 또는 정상 완료된 후행 기록에만 허용하며, 완료 상태·정산·설정값을 바꾸지 않는다. 커밋 후 원문 파기 재시도도 완료 후행을 다시 무효화하지 않는다. 당시 hash는 감사용으로 보존하되 `sourceEvidencePurged` 기록은 다시 replay하지 않는다.
- `ORDERED_PROVISIONAL`은 회차별 고정 입력으로 캐릭터 비교를 원 Job 안에서 끝낸 뒤 `CHARACTER_COMPARISONS_FINISHED`를 기록한다. 같은 batch의 후속 회차가 앞 회차 저장을 기다리므로 그룹 handoff barrier를 사용하지 않으며 ordered 후보는 legacy 그룹 숨김 Job에 섞지 않는다.
- Worker 실패는 `AnalysisFailureCode`로 분류해 `analysis_jobs.failure_code`와 후보별 비교 실패 코드에 저장한다. `error_message`는 운영 진단용 원문이며 공개 DTO의 실패 문구는 코드별 안전한 사용자 메시지만 사용한다. URL, 내부 API 경로, stack trace가 공개 응답에 섞이지 않게 한다.
- 기존 확정 Fact·이력·발견의 일반 인용만 `QUOTE_NOT_FOUND`이면 값과 원출처를 보존하고 복사 문맥의 근거를 `UNAVAILABLE`로 비운다. 신규 근거·offset·원문 hash/manifest·작품/Job 연결·서사 시점 검증은 완화하지 않는다. 원문 확인 불가를 값 삭제나 VERIFIED 승격으로 해석하지 않기 위한 경계다.
- Worker의 선택적 `failureDiagnostics`는 v1 고정 코드·단계·실제 HTTP/요청/trace 식별자만 받는다. 새 HTTP 필드는 Jackson 3 `JsonNode`, 저장 모델은 typed `AnalysisFailureDiagnostics`를 사용하고 nonthrowing sanitizer가 잘못된 부가 진단과 검증되지 않은 원인 ID를 버린다. 진단 오류로 본래 `/fail` 상태 전이를 거절하거나 기존 `AnalysisFailureCode`의 격리·재시도 정책을 바꾸지 않는다. 후보의 `comparison_failure_analysis_job_id`/`comparison_failure_batch_id`는 서버가 승인한 실제 실행에서 같은 실패 전이에 고정하며 원 추출 Job으로 재비교 실행을 추정하지 않는다. 이 필드와 현재 진단은 새 시도에서 비우고 과거 outbox snapshot은 바꾸지 않는다.

- `WORLD_CANDIDATES_PUBLISHED` 이후 `SETTING_EXTRACTION`이 `AI_TOKEN_QUOTA_EXHAUSTED`로 중단되면 완료된 1차 추출·비교와 `Episode.ANALYZED`를 보존하고, 남은 세계관 후보만 같은 코드의 재개 가능한 중단 상태로 표시한다. 미해결 토큰 중단 후보나 해당 후보의 활성 `WORLD_SETTING_COMPARISON` Job이 남아 있는 동안 새 전체 분석 생성과 다른 실패 Job을 통한 우회 재시도를 차단하고 배치 단위 세계관 비교 재개 API로만 복구한다. 후보가 모두 완료·기각되고 활성 복구 Job이 사라지면 새 분석 생성은 다시 허용한다.
- 공개 분석 생성·재시도는 최소 첫 추출 예약량, 세계관 비교 시작·일괄 재개는 최소 첫 비교 예약량을 사전 확인한다. 이 검사는 빠른 사용자 피드백용이며, 동시 실행에서 실제 사용 권한은 Worker 호출 직전의 계정 잠금 기반 원자적 예약이 최종 결정한다.
- `WORLD_SETTING_COMPARISON`은 사용자 재비교 요청 한 건을 처리하는 내부 Job type이다. 공개 분석 목록·진행률·회차 실행 잠금에서 제외하고 동일 후보의 활성 Job은 하나만 허용한다. 후보가 `PENDING_REVIEW`를 벗어나 Job이 obsolete가 되면 후보 상태를 되돌리거나 실패 이력을 만들지 않고 no-op 성공시킨다.
- 세계관 후보 목록의 `activeComparisonJobCount`는 해당 배치의 `PENDING/RUNNING WORLD_SETTING_COMPARISON` Job 수를 반환한다. 프론트는 이 값으로 일괄 재개 중인 `PENDING` 후보와 활성 Job이 없는 복구 대상을 구분한다.
- `CHARACTER_FACT_COMPARISON`은 `analysis_jobs.setting_candidate_id`를 호환 seed로 두되 새 Job은 `batch_id + character_comparison_input_hash`가 식별하는 NEW/EXISTING 논리 그룹 전체를 처리하는 내부 Job type이다. NEW baseline은 빈 snapshot version 0이고 실제 캐릭터는 만들지 않는다. 같은 활성 입력 hash는 partial unique index로 하나만 예약하고, 오래된 Job의 완료·실패·lease 회수는 최신 입력 후보 상태를 바꾸지 않은 채 no-op 처리한다. 새 그룹 Job은 claim에 `supportsCharacterComparisonGroups=true`를 보낸 Worker에게만 배정해 rolling deploy 중 구 Worker가 가져가지 못하게 한다. 공개 분석 목록·회차 상태 전이에서는 계속 제외한다.
- Worker는 분석 작업 생성과 상태 전이를 위해 백엔드 DB에 직접 접근하지 않는다. 다만 청킹, 설정 후보, 리포트 같은 분석 산출물 저장은 데이터 양과 모델 안정성에 따라 내부 API 또는 Worker의 DB 직접 저장 중 선택할 수 있으며, DB 직접 저장을 선택하면 관련 스키마/문서 변경을 함께 관리한다.

#### AI Token Domain Policy

- #252 사용자 원장은 `numeric(22,2)`와 BigDecimal을 사용한다. `AiTokenChargePolicy`는 정확한 `gpt-6-luna`만 1/20, 나머지 모델은 1배로 환산하고 예약에 정책 version·분모·환산 예약량을 동결한다. 0.05 단위를 보존해 소량 호출별 반올림 손실을 피하고 정산·해제·UNKNOWN 늦은 정산에도 같은 정책을 사용한다. 서비스 부담 운영 백필은 사용자 차감 0이다.
- provider 입출력/cached/raw 예약량, Job 합계, 챗봇 실행 예산과 내부 응답은 raw 정수 그대로다. 계정 응답만 환산 단위 JSON number이며 프론트에 새 내부 수치를 노출하지 않는다. 모델을 모르는 분석·비교 접수 검사는 raw 최소량의 최저 요율 하한이고, 실제 모델의 계정 잠금 예약이 최종 권한이다.
- V83은 기존 계정·지급량을 보존하며 배포 전 일반 원장은 모델명과 무관하게 LEGACY_RAW_V1(1배)을 유지한다. 신규 정책을 NULL/과거 예약에 소급하지 않는다. 구 Java writer를 중지한 뒤 새 Java를 기동하며, V83 적용 후 정수 매핑 버전으로 롤백하지 않는다. 상세 계약과 배포 경계는 `docs/ai-token-usage.md`를 따른다.

- `AI_TOKEN_DEFAULT_GRANT`는 신규 토큰 계정의 최초 지급량과 운영자가 추가 사용량 요청을 승인할 때의 지급량에 공통 사용한다. 승인 API에서 별도 지급량을 받거나 현재 누적 지급량으로 다음 지급량을 계산하지 않는다.
- `AiTokenUsageResponse.remainingPercent`는 회계용 누적 지급량이 아니라 현재 `AI_TOKEN_DEFAULT_GRANT`를 100% 기준으로 계산하고 최대 100%로 제한한다. 누적 `grantedTokens`·`usedTokens`·`reservedTokens`와 실제 `remainingTokens`는 그대로 유지한다.
- 추가 사용량 피드백은 앞뒤 공백을 제거한 Unicode 35~1,000자로 검증하고 한 회원에게 `PENDING` 요청은 하나만 허용한다. 원고 원문이나 AI 전체 출력은 자동 첨부하지 않는다.
- 일반 피드백은 앞뒤 공백을 제거한 Unicode code point 10~1,000자로 검증하고 `feedbacks`에 횟수 제한 없이 매번 새 행으로 저장한다. 일반 피드백 보상용 추가 사용량 요청은 기존 승인·지급 원장을 재사용하되 `GENERAL_FEEDBACK_REWARD` 출처로 회원당 전체 상태를 통틀어 한 번만 생성한다. 이후 의견은 요청 상태와 무관하게 저장하고 새 보상 요청을 만들지 않는다.
- 의견 자동 안내는 활성 작품의 보관되지 않은 실제 회차가 합계 3개 이상이고 기존 의견이 없는 회원에게 계정당 한 번만 제공한다. 조회는 읽기 전용이며 POST 선점과 의견 작성은 같은 회원 row 잠금으로 직렬화한다. `members.feedback_prompt_shown_at`을 의견 작성 여부와 독립적으로 보존해 닫은 사용자와 여러 기기의 중복 노출을 막는다.
- 다른 추가 사용량 요청이 `PENDING`이면 일반 피드백은 저장하되 보상 요청 생성은 보류한다. 일반 피드백 보상 요청과 사용량 소진 요청은 출처를 구분하고, 사용량 소진 요청의 기존 처리 대기 중복 방지 규칙을 유지한다.
- 추가 사용량 승인·거절 API는 `/api/v1/admin/ai-token-extension-requests/**`의 `ROLE_ADMIN` 전용 경로로 둔다. 승인은 요청 행과 토큰 계정을 잠근 뒤 `grantedTokens` 증가, 요청 `APPROVED` 전이, 요청 ID가 연결된 `MANUAL` 지급 원장을 같은 transaction에서 한 번만 저장하며 `usedTokens`와 `reservedTokens`를 초기화하지 않는다.
- MVP 운영자 계정은 일반 회원가입으로 만든 전용 회원을 DB에서 `ADMIN`으로 한 번 승격하고 다시 로그인해 새 access token을 발급받아 사용한다. 계정 승격 외 토큰 잔액·요청 상태·지급 원장은 DB에서 직접 수정하지 않는다.

#### Character Setting Domain Policy

- 캐릭터·세계관 후보 목록은 반영/제외/직접 확인/분석·반영 진행 네 가지 묶음 전체 집계를 필수 int64로 제공한다. 페이지·필터와 무관하며 미확정의 비교 PENDING/PROCESSING 또는 원본 회차 자동 반영 대기를 진행 수에 포함하여 전체 합과 미확정 합이 일치하게 한다. 후보의 `automaticApplicationPending`은 미확정 + 원본 Job AUTOMATIC + automaticAppliedAt 없음 + PENDING/RUNNING일 때만 참이다. 사용자 후보 수정·연결·확정·제외·재비교는 Work 잠금 뒤 무효화 전에 같은 조건으로 409를 반환해 자동 완료와 수동 변경을 직렬화한다. GH199 이후 완료된 앞 회차 후보 변경으로 후속 분석을 무효화하지 않는다. 진행·대기 또는 고정 입력으로 재개할 실패/미완료 순차 분석이 있으면 후보 변경만 잠시 거절하고 Job은 보존한다. 직접 설정 변경과 같은 공통 검증을 사용해 마지막 회차 실패도 보호하며 멱등 완료 응답은 유지한다. 자동 반영 보류는 선택적 `automaticReviewHoldReason`에 별도로 기록하고 원래 비교 operation·근거·성공 상태를 덮지 않는다. 공개 오류·판단 문장만 자연어로 정리하며 실제 이름·원문·설정값·저장된 AI 응답은 보존한다. 표현을 다듬는 사유만으로 분석 실패나 유료 재시도를 추가하지 않는다. 이는 기존 AI의 실제 ID·알 수 없는 참조·내부 enum 노출 검증을 해제하는 정책이 아니다.
- 이미 입력을 고정한 순차 분석과 무관한 구 방식 후보의 비교 재시도는 활성 순차 분석 중에도 허용한다. 재시도 결과는 진행 중인 순차 실행에 섞지 않고 다음 신규 분석에서만 참고하며, 해당 후보 원본 회차의 자동 반영이 끝나지 않은 경우에는 기존 409를 유지한다. 사용자 재시도를 막아도 현재 순차 실행의 정합성이 높아지지 않으므로 작품 단위 활성 실행 잠금을 이 경로에 추가하지 않는다.

- 캐릭터 후보 목록의 `attentionRequiredCandidateCount`는 업로드 batch 전체에서 검토 대기이면서 `AMBIGUOUS` 연결 또는 `FAILED/RECOMPARISON_REQUIRED` 비교 상태인 후보를 중복 없이 센다. 필터·페이지와 무관하며 확정·무시 후보는 제외한다. 연결 필요만 세는 기존 `matchRequiredCandidateCount`는 호환을 위해 유지한다. 비교 실패 후보가 좌측에는 확인 필요로 보이지만 전체 요약에서 누락되는 불일치를 막기 위한 계약이다.

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
- 후보·Fact의 nullable `narrativeTime`은 `factTime`(사실 성립 시점)과 `referenceTime`(관측 기준 시점)을 분리한다. exact Unicode offset과 원 Job·청크에 결속된 근거만 저장·재검증하며 legacy null을 회차나 생성 시각으로 추정하지 않는다. 확정 시 후보의 검증된 자료를 Fact에 복사한다. 회차 재분석으로 예전 청크 UUID만 사라진 경우, 성공한 원 Job 또는 이미 검토한 후보의 시점은 원 Job에 고정한 원문 manifest·hash·인용·offset 검증으로 읽기를 복구한다. 실제 존재하는 다른 회차 청크, 미완료 신규 추출, 설정집 청크에는 이 복구를 적용하지 않는다.
- 캐릭터별 `currentNarrativeTime`은 기존 비교 결정의 `advanceCurrentNarrativeTime`이 후보의 검증된 referenceTime을 선택한 경우만 기존 확정 경계에서 적용한다. PRESENT REMOVE·중복 EXCLUDE·HISTORY_ONLY도 가능하지만 과거·가정·불명·실패·보류는 갱신하지 않는다. 시간만 바뀌어도 version/hash·ordered journal/projection을 갱신해 다른 FactType·분할·재개 비교가 같은 시점을 읽게 한다. 원 Job이 교차하는 한 그룹도 전체 선택의 expectedCurrent 연결을 따르며 UUID·같은 comparedAt 순서로 시점을 정렬하지 않는다.
- GH301 원문 파기로 기존 대표 시점의 인용이 비어도 유효한 새 PRESENT 관측으로 시점을 갱신할 수 있다. 파기된 인용은 복원하지 않으며 기존 확정값과 expectedCurrent 연결을 보존한다. 신규 그룹의 승격/중복 제외 목록은 상태 변경 전에 고정하고, 승인된 시점 선택을 가진 제외 후보만 실제 생성한 인물에 귀속한다. 기존 인물의 전부 EXCLUDE 그룹도 시점 확정을 수행하며 사용자 직접 반려와 전부 제외된 미등록 그룹은 인물·Fact·시점을 생성하지 않는다. 비교 후 인물을 보관했다면 제외 승인은 완료하되 보관된 인물의 시점·버전은 유지한다. 전체 계약과 회귀는 `docs/analysis-continuity.md`를 따른다.
- `advisoryCurrentContext`에는 대표 시점, 모든 current source Fact의 시점·근거, 동일 캐릭터의 비교 범위에 해당하는 noncurrent/REMOVE 이력을 동결한다. 새 인물 묶음은 모든 FactType의 이력을 포함하고 예전 유형별 묶음은 해당 유형 범위를 유지한다. 현재 slot이 비어도 이력은 제공하며 history를 활성 P/Q target으로 만들지 않는다. 입력 한도는 필수 이력을 자르지 않고 기존 오류로 중단하며 원문 파기 때 새 시점 근거·고정 복사본도 기존 파기 경로에서 지운다.
- `SettingCandidate` 생성은 Python AI Worker가 담당하고, Spring API는 사용자 검토를 위한 조회/수정부터 담당한다. 후보 생성 API를 Spring에 추가해야 할 때는 Worker 저장 책임을 함께 재검토한다.
- `SettingCandidate` 수정은 `PENDING_REVIEW` 상태에서만 허용한다. 확정/무시 이후 수정은 `CharacterFact` 반영 정책과 동기화 문제가 생기므로 별도 정책이 정해질 때까지 막는다.
- `CHARACTER_DISCOVERY` 후보는 설정 콘텐츠가 없으므로 일반 설정명·표시값 수정 API에서 거절하고, 캐릭터 연결 해소와 확정/무시만 허용한다.
- 설정 후보 MVP 내용 수정은 사용자용 설정명과 표시값만 받으며, 캐릭터 연결 API와 내용 수정 API는 서로의 필드를 변경하지 않는다.
- 후보 응답의 설정명 편집 가능 여부와 prefix는 현재 작품의 활성 schema를 동일 resolver로 해석한 `attributeNameEditable`, `attributeNamePrefix`로 제공한다. exact/alias는 잠그고 pattern만 열며, 해석 불가 후보 조회는 실패시키지 않고 이름 편집을 잠근다.
- 후보의 화면 설정명은 활성 schema로 해석한 `attributeDisplayName`으로 제공한다. exact/alias는 registry의 `displayName`, pattern은 구체 suffix를 사용하고 발견·해석 불가 후보는 null로 둔다. 새 schema를 추가할 때 프론트 번역 목록을 함께 고쳐야 하는 의존성을 없애기 위함이며 저장·수정용 `attributeName`은 유지한다.
- 이름과 값이 실질적으로 바뀌지 않은 후보와 캐릭터 연결만 바뀐 후보는 기존 AI `valueJson`을 그대로 유지한다. 동적 suffix 공백과 표시값 앞뒤 공백의 저장 문자열 정규화는 rich JSON을 축소하지 않고 적용한다.
- `SettingValueType.JSON` 복합 후보의 이름 또는 값이 실제로 바뀌면 기존 prefix를 유지하고 suffix와 `valueJson.name`을 동기화한 뒤, 현재 후보 `valueJson`을 name-only object로 의도적으로 교체한다. 타입 계약이 없는 숨은 속성은 merge·추측·보존하지 않는다.
- 후보 내용 수정은 `valueType`, `evidenceSpans`, `rawAiResultJson`을 변경하지 않는다. `rawAiResultJson`은 최초 AI payload 보관용이며 confirm 시 수정 전 구조화 값을 복원하는 source로 사용하지 않는다.
- confirm은 현재 후보 `valueJson`을 append-only `CharacterFact`에 보존한다. AI 비교가 `ADD`/`UPDATE`/`MERGE`를 제안한 경우 snapshot에는 Worker가 반환한 `proposedFactValue`와 `proposedValueJson`을 반영한다. `REMOVE`는 회복·종료 후보 자체를 current에 넣지 않고 Fact 이력에만 append한 뒤 같은 캐릭터의 현재 `STATUS` 제거 목록을 snapshot과 provenance에서 함께 제거한다. 신규 Worker는 target 없이 제거 목록을 한 건 이상 보낸다. 구버전의 순수 `REMOVE + canonical target` 요청은 effective 제거 집합 한 건으로 검증·적용하되 구 Java rollback 호환을 위해 저장 shape는 target-only로 유지하고, target과 목록을 함께 보낸 전환 요청만 중복 제거한 신규 shape로 저장한다. 복합 후보 수정 전 rich JSON을 최초 AI payload에서 다시 복원하지 않으며 중첩 typed JSON 편집은 후속 범위다.
- GH215 완료된 누적 분석 후보의 직접 검토는 기존 내용 수정 PATCH의 `reviewedApplicationMode`와 `expectedUpdatedAt`으로 저장한다. V72에 저장 방식과 snapshot version을 보관하고 응답의 저장 방식으로 새로고침 뒤 같은 선택을 복원하며, 내용·대상이 달라지면 선택을 해제한다. 캐릭터 연결의 `userModified`만으로 내용·시점 불확실성을 승인하지 않으며 원본 후보 값과 검증된 제안 값이 같고 독립적인 정상 ADD 또는 같은 대상인 경우만 연결 후 선택을 이어받는다. 확정은 저장 방식·후보 시각·현재 snapshot version과 원문·순차 실행·후행 현재값 보호를 모두 검사한다. 값 편집 후 저장한 직접 검토 초안은 비교 Worker가 없으므로 `PENDING`을 `NOT_REQUIRED`로 바꾸며 기존 성공/실패 근거는 보존한다. 완료된 단일 회차 `MANUAL + CONFIRMED_ONLY`도 저장 초안을 사용한다. 이 경우 값 편집은 이전 저장 방식을 유지하거나 현재 반영으로 저장하고 전체 재비교하지 않는다. 아래 명시적 최종 결과 그룹 확정 계약을 따른다.
- `SettingCandidate` 확정/무시는 POST action API로 처리한다. 처음 `CONFIRMED`로 전환되는 `SETTING` 후보는 `CharacterFact`를 항상 새 이력으로 저장하고, 기본 `APPLY_PROPOSAL`은 완료된 비교 제안을 `WorkCharacter` snapshot과 provenance에 반영한다. 사용자가 `HISTORY_ONLY`를 선택하면 Fact만 저장하고 값 snapshot·provenance는 바꾸지 않는다. 캐릭터 현재 슬롯이 비어 있을 때도 제안 operation 또는 확정 application mode가 `HISTORY_ONLY`인 Fact는 현재값 보호의 과거 적용 흔적으로 사용하지 않는다. 실제 ADD/REMOVE 적용 흔적·직접 수정·늦은 회차 보호는 유지한다. 검증된 PRESENT 관측의 대표 시점 선택이 함께 적용되는 경우에는 대표 시점과 snapshotVersion을 갱신한다. 최신 그룹 revision의 `EXCLUDE`는 최종 그룹 확정에서 `DISMISSED + NOT_REQUIRED`로 전환하며 Fact·snapshot·provenance·이력을 만들지 않는다. 입력이 다시 열리기 전 자동 제외하지 않아 앞 회차가 늦게 합류해도 재검토할 수 있게 한다. `REVIEW_REQUIRED`는 제안 적용 확정을 막되 이력 저장은 허용하고, `CHARACTER_DISCOVERY`는 캐릭터와 최초 등장만 반영하며 Fact를 만들지 않는다.
- GH233 단건 캐릭터 연결은 요청 후보 하나만 수정하고 일괄 연결만 전체 대기 그룹을 수정한다. 연결된 후보의 응답·그룹 표시 이름은 실제 캐릭터 이름을 쓰며 저장된 추출 이름·원문 표현은 조회에서 변경하지 않는다. 명시적 직접 검토는 유효한 기존 연결을 추출 이름 `미상`보다 우선하되 모호한 연결·원문·대상 검증은 유지한다.
- 신규 검토 화면은 같은 `batchId`의 NEW 정규화 이름 또는 EXISTING 캐릭터 ID별 전체 대기 후보를 그룹 confirm API 한 번으로 처리한다. 요청 ID가 서버의 전체 대기 그룹과 일치하고 서버가 반환한 최신 비교 revision인지 잠금 뒤 검증하며 부분 성공을 허용하지 않는다. 동일한 revision·decision hash 재전송은 같은 성공 결과를 반환하고 다른 결정은 충돌시킨다. 이름/연결 변경은 old/new 그룹 입력을 무효화해 재비교한다. 완료된 단일 회차 수동 검토의 내용 수정·제외는 다른 후보의 제안·선택을 보존하며 비교 Job을 만들지 않는다. 실제 신규 캐릭터는 검증된 그룹 확정 트랜잭션에서만 하나 만든다.
- `AGE`, `LEVEL` 후보는 확정 전에 구조화 대표값을 우선 확인하고, 값이 없을 때만 표시값을 사용해 0 이상이면서 Java `Integer` 범위의 정확한 정수인지 검증한다. 소수·음수·범위 초과 값은 캐릭터 결정과 Fact 저장 전에 거절해 상세 응답과 전체 수정 계약이 어긋나지 않게 한다.
- 후보 `valueJson` object의 정확한 `value` key는 숨겨진 대표값 envelope로 유지한다. `PROFILE`, `STAT`, `SKILL`, `ITEM`, `STATUS`의 나머지 최상위 공개 property는 상세 응답을 전체 수정 요청으로 그대로 왕복할 수 있도록 key가 공백 없이 100자 이하이고 앞뒤 공백·예약 key 충돌·정규화 후 중복이 없어야 하며, 직접 문자열 값은 비어 있지 않고 앞뒤 공백이 없어야 한다. 공개 property가 있는 scalar schema는 선언 타입과 호환되는 `value` envelope도 가져야 한다. 위반 후보는 캐릭터 결정과 Fact 저장 전에 거절한다.
- `UNRESOLVED` 신규 SETTING도 빈 snapshot에서 그룹 비교를 완료해야 확정할 수 있으며 원본값 `ADD` fallback은 두지 않는다. 확정 시 동명 `ACTIVE` 캐릭터가 생겼으면 즉시 적용하지 않고 전체 후보를 기존 캐릭터 scope의 `PENDING`으로 바꿔 재비교한 뒤 재조회시키며, 동명 `ARCHIVED` 캐릭터는 명시적 이름 충돌로 거절한다. 신규 확정도 검증된 `ADD/UPDATE/MERGE/REMOVE/HISTORY_ONLY/EXCLUDE/REVIEW_REQUIRED` 결과와 의존성을 기존 캐릭터와 같은 규칙으로 적용한다.
- `NUMBER` 후보의 `attributeValue`는 Java `BigDecimal`로 해석 가능한 숫자 문자열이고 `valueJson.value`는 JSON number이며 두 값은 수치적으로 같아야 한다. `BOOLEAN`은 표시값을 소문자 `true`/`false`로 제한하고 JSON boolean과 일치시킨다. 이 계약은 저장 후보와 Worker proposal에 같게 적용해 표시값과 snapshot 값이 엇갈리지 않게 한다.
- 후보 응답의 `valueValidation`(`VALID`, `INVALID`, `NOT_APPLICABLE`)은 현재 활성 schema와 저장된 값에서 조회 시점에 파생하고 DB에 저장하지 않는다. 값 계약 오류도 수정 API를 사용할 수 있는 `PENDING_REVIEW`에서만 `repairable=true`이며, 확정·무시 후보와 schema 없음·모호성·타입 불일치는 `repairable=false`로 내려 현재 수정 API로 고칠 수 있다고 안내하지 않는다. 두 종류 모두 2차 비교와 단건·그룹 확정 전에 fail-closed로 거절한다.
- 원 분석 또는 hidden 비교 Job이 `INVALID` 후보를 claim하면 비교 제안을 비우고 `NOT_REQUIRED`로 격리한 뒤 다음 후보를 계속 처리한다. `PENDING_REVIEW`는 유지해 무시와 가능한 수정을 허용하고, 수정에 성공하면 기존 내용 변경 상태 전이로 다시 `PENDING` 비교를 요청한다.
- confirm으로 생성하는 `CharacterFact`는 `setting_candidate_id` FK로 원본 `SettingCandidate`를 연결하고, 구체적인 원문 인용은 후보의 `evidence_spans`에서 조회한다. 기존 Fact는 추정 backfill하지 않고 `NULL`로 유지하며, 근거 JSON을 Fact에 중복 저장하지 않는다.
- `SettingCandidate` 확정 반영처럼 후보/요청 데이터를 저장용 Entity로 변환하는 코드는 service에서 `Entity.create()` 파라미터를 직접 조립하지 말고 mapper의 `toEntity` 계열 메서드로 분리한다. service는 권한 확인, 조회, 트랜잭션 흐름, 저장 호출, 도메인 메서드 조율에 집중한다.
- `CharacterFact`는 append-only 타임라인이다. 값·근거·current 상태를 수정하지 않으며 `is_current` 컬럼도 사용하지 않는다. 현재값의 유일한 authority는 `WorkCharacter.currentAge/currentLevel`과 JSON snapshot이다.
- 새 캐릭터 후보의 같은 회차 내 순서는 실제 저장 근거의 `start_offset`과 구형 `startOffset`을 모두 읽어 가장 이른 원문 위치를 먼저 사용한다. 생성 시각·UUID는 원문 위치 다음의 결정적 정렬 기준이다. 이미 배정된 후보는 묶음 생성 순서와 C 참조 순서를 문맥 조회·완료·이전 결정 재적용·그룹 확정에서도 유지한다. 미배정 후보의 원문상 자리를 두고 배정 후보끼리만 별도 총순서로 복원해 비교 함수의 전이성을 깨지 않으며, 참조 누락·중복/문맥/의존 검증은 완화하지 않는다. 기존 근거/확정 이력을 재작성하지 않는다. 실패 재개는 이전 batch/ref 연결을 지운 뒤 새 claim으로 참조를 다시 부여하고 완료된 앞부분은 보존한다.
- 그룹 Job의 `characterComparisonInputHash`는 배정 이전의 원문 순서(`SettingCandidateChronology.sourceSorted`)만 사용한다. FactType별 batch 배정·재시도 초기화에 따라 바뀌는 처리 순서를 입력 변경으로 오인하지 않도록 하며, 비교 문맥과 확정에는 기존 배정 순서를 유지한다. 기존 정상 Job도 최초 claim에서 미배정 원문 순서로 검증됐으므로 hash 형식을 바꾸지 않고 호환한다.
- `MATCHED` 또는 `AUTO_MATCHED_BY_NAME` 후보 반영은 `SettingCandidate`와 `WorkCharacter`를 pessimistic write lock으로 조회하고, 새 Fact append, snapshot 변경, `character_snapshot_sources` provenance 변경, `snapshotVersion` 증가를 한 트랜잭션에서 처리한다. 관련 slot의 값·source 또는 캐릭터 대표 시점이 달라질 때 트랜잭션당 version을 정확히 한 번 증가시킨다. snapshot 제거가 있는 비교는 제한된 LLM 문맥 밖 상태 변경도 놓치지 않도록 완료와 사용자 확정 전 모두 비교 당시 `snapshotVersion`의 정확한 일치를 요구한다. 같은 캐릭터의 후보 묶음은 mutation 전 initial version을 고정해 모든 제거 제안을 그 값으로 preflight하고, 묶음 앞 후보가 만든 version 증가를 뒤 후보의 외부 stale 변경으로 오인하지 않는다. 값만 반영하는 독립 slot 비교는 기존 문맥 hash 기준을 유지한다.
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
- 일반 생성·후보 확정·이름 수정·복구는 다른 `ACTIVE` 동명을 거절하며, `ARCHIVED`끼리 또는 `ACTIVE`와 `ARCHIVED`의 동명은 허용한다. 작품 row의 pessimistic write lock 아래에서 이름 검사와 생성을 직렬화한다. 이름만으로 기존 인물을 찾을 때는 정규화된 전체 목록을 검사하고 둘 이상이면 임의 선택 대신 기존 이름 충돌로 거절한다.
- 설정집 자동 승격의 별도 동일명 예외는 `raw_ai_result_json.setting_book_identity_proof` v1만 소비한다. `ORDERED_CHARACTER_SUBJECT_RESOLVER`의 `DISTINCT_IDENTITIES`, 현재 origin/revision/hash/기준 시점, 전체 제공된 actual 또는 discovery target 그룹, 분리된 부모 프로필·명시 별도 인물 인용·각 child의 Unicode code-point 원문 귀속을 `SettingBookIdentityValidator`로 검사한다. 이름·개수·서로 다른 offset만으로 허용하지 않으며 누락/위조/수정/actual-new 혼합은 검토로 남긴다. 신규 예외는 해당 검증 그룹에서 현재 승격이 만든 ID만 부모 키로 재사용하고 기존 실제 동명 재연결은 제공된 전체 active target만 허용한다. 일반 이름 충돌·시간·직접 수정 보호는 유지하고, 검증 후 자동 승격한 프로필 근거는 다음 실제 인물 연결 문맥에 보존한다.
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

#### Family Relationship Domain Policy (#259, #260, #261)

- 가족 관계는 `domain/relationship`에 둔다. 기존 추출 프롬프트·SettingCandidate 생성 책임은 유지하며, 현재 PROFILE 슬롯과 sourceFacts 양쪽에 존재하는 Fact만 관계 입력으로 사용한다. HISTORY_ONLY 이력으로 현재 관계를 복원하지 않는다.
- 관계·검토 후보·Fact 근거 연결을 분리한다. 원문은 복사하지 않고 기존 Fact evidence API를 사용한다. 원문 파기는 현재 설정의 무효화나 서사상 관계 종료를 뜻하지 않는다.
- 부모/자녀는 PARENT_OF로 방향을 정규화하고 배우자/형제는 UUID 문자열 순 대칭으로 저장한다. 유형·세부 유형·명시 시점이 다른 관계는 합치지 않으며, 미상 부모·상대 감정·정확한 시점을 추론하지 않는다.
- 관계 쓰기는 Work 잠금 안에서 sourceToken·expectedVersion을 검증한다. requestId/hash로 재전송을 처리하고 관계 정정은 이전 row를 철회한 뒤 새 근거로 대체 확정하여 과거 명제를 보존한다.
- 관계 조회는 현재 슬롯 및 보관 상태를 다시 계산한다. 복수의 독립 근거 중 하나라도 유효하면 유지하고, 마지막 근거 소실은 REVIEW_REQUIRED다. 보관/복구로 생긴 부모 순환도 검토로 표시하며 확정 시 순환 검사는 이 간선을 포함한다.
- 관계 응답의 current와 hasEvidence는 별개다. 현재 설정에서 빠진 활성 캐릭터의 Fact도 원문이 보존되면 근거를 열 수 있어야 한다. 작품/회원 영구 삭제는 관계 evidence→candidate→relationship을 Fact보다 먼저 정리한다.
- #260은 기존 AI 추출 결과나 프롬프트를 확장하지 않는다. 현재 설정의 제한된 명시 문법을 `ExplicitFamilyRelationshipParser`로 읽고 같은 작품의 활성 인물 이름이 정확히 한 명일 때만 자동 연결한다. 미지원 자유문은 관계 부재로 해석하지 않으며, 대화·감정·만남으로 가족 관계를 추론하지 않는다.
- 명시 문법은 가족 항목 외 현재 PROFILE의 출생 순서·특징 값에서도 읽는다. 자녀 역할의 `넷째`·`두 번째`·숫자 서수와 외동·막내·맏, 장남·차남·장녀·차녀를 지원하되 부모·배우자 역할에 적용하거나 다른 자녀를 생성하지 않는다. `은하의 언니`처럼 단순 상대명의 소유격은 ASSERTED지만 `언니: 은하` 및 남성·여성·사람 등의 설명으로 끝나는 상대는 UNCERTAIN으로 검토한다. 뒤 설명은 허용된 등급/레벨+직업 명사구만 읽으며 부정·추측·중첩 명제를 잘라 버리지 않는다. 사망·거주·모름 등의 상황문을 상대 이름으로 받지 않고 혈통·가문·지원하지 않는 친족을 특정 부모 간선으로 추론하지 않는다. 상세 어휘와 합성 현재 13개·이력 4개 분류는 자동처리 계약과 파서 테스트를 따른다.
- #278은 가족 항목의 역할 앞표기, 완결된 관계 절의 쉼표·접속어, 공동 상대 및 현재 소유자와 정확히 같은 부모·자녀 이름 접미사를 지원한다. 모든 절과 상대를 검증하고 미지원·부정·추측·과거 범위를 잘라 일부만 자동 확정하지 않는다. 가족 항목의 앞 형제 호칭은 기존 UNCERTAIN으로 유지하며 가문·세가를 사람으로 만들지 않는다. `docs/family-relationship-coverage-gh278.md`는 실제 현재 슬롯의 읽기 전용 진단과 합성 회귀를 구분한다. 실제 설정·인물명·식별자는 저장소나 이슈에 기록하지 않는다.
- 명확한 현재 설정은 별도 확인 클릭 없이 관계를 확정한다. 불확실·동명이인·미등록·순환·상충은 검토에 남기며 기존 설정 검토 모드/집계와 섞지 않는다. `CURRENT_SETTING_RULE` 후보의 생성 키는 현재 슬롯 토큰과 원래 명제 기준이며 대상 해석 결과를 포함하지 않는다. 재처리는 이미 존재하는 대기 후보·사용자 수정·제외·철회를 되돌리지 않는다.
- 명제가 형제 호칭 규칙 확장이나 같은 슬롯의 미지원 문법 해소로 UNCERTAIN에서 ASSERTED로 읽혀도 동일 원본 명제와 출처의 구 UNCERTAIN 요청 ID가 있으면 그 규칙 후보를 재사용한다. 사용자가 현재 필드를 바꾼 경우에도 원래 ID로 찾아 기존 대기·수정·제외·확정·철회와 버전을 보존하기 위함이다. 새 명제나 독립 출처까지 차단하지 않으며 이력 Fact는 문법상 인식 가능해도 CURRENT 슬롯과 출처에 함께 없으면 후처리 입력으로 복원하지 않는다.
- PROFILE provenance 변경과 인물 생성/이름변경/보관/복구는 기존 Work 쓰기 잠금 안에서 `Work.relationshipInputVersion`만 증가시킨다. 관계 처리기는 별도 트랜잭션에서 처리하므로 실패가 설정 확정을 되돌리지 않는다. 새 promotion caller도 Work 잠금을 먼저 획득해야 한다.
- `relationship.processing.scheduling-enabled=true`, `fixed-delay-ms=10000`은 base 설정이며 test에서는 끈다. scheduler는 한 번에 10개 작품을 처리하고 실패는 1분 뒤 재시도한다. 새 입력 또는 수동 재시도는 이전 실패 대기를 해제한다. 기존 작품의 초기 세대 0은 자동 백필하지 않으며 현재 설정 처리 API로 요청한다.
- 대기 조회는 작품 ID와 입력 버전을 함께 캡처하고 처리·실패 기록에 같은 버전을 전달한다. Work 잠금 뒤 입력 버전이 다르면 오래된 작업은 건너뛰며, 실패 기록 전 새 입력이 저장됐거나 해당 버전이 이미 완료됐으면 실패 상태를 쓰지 않는다. 롤백 직후 저장된 새 설정에 이전 실패의 대기 시간을 적용하지 않기 위함이다.
- 자동 확정 근거는 `validatedInputVersion`이 Work 입력 세대와 같아야 지원 근거다. 후처리 대기/실패 중에는 REVIEW_REQUIRED로 보류하고 성공한 트랜잭션에서 다시 검증한다. 수동 확정은 #259 현재 근거 검증을 유지한다. `relationship_processing_states`도 작품/회원 파기 때 Work보다 먼저 삭제한다.
- API·가계도 계약은 `docs/family-relationships-v1.md`, 자동처리와 검토는 `docs/family-relationship-processing-v1.md`를 따른다. 공유 fixture는 `docs/contracts/family-relationships-v1.json`과 `docs/contracts/family-relationship-processing-v1.json`을 사용한다.
- #261 가계도는 `GET /family-tree`의 요약 조회를 사용하며 기존 상세 페이지를 전수 왕복해 구성하지 않는다. `FamilyTreeServiceImpl`의 읽기 전용 REPEATABLE_READ로 인물·유효 관계·후보 수를 같은 DB 스냅샷에서 읽는다. GraphReader의 VALID+CURRENT가 기본이고 과거 포함도 VALID+HISTORICAL만 추가한다. 초점은 활성 인물의 연결 가족 범위이며 간선 기본 100/최대 200, 상한 전 합계와 truncated를 명시한다. 원문·전체 근거는 기존 상세 API를 사용한다. revision은 수동 확정/철회와 범위 내용의 변경 감지값이며 쓰기 버전이나 권한이 아니다. 상세 및 실행하지 않은 제한 백필 절차는 `docs/family-tree-v1.md`를 따른다.
- #261 미등록 인물은 `RelationshipMentionReader`가 현재 출처·명시성·상충·보관 상태를 검사한 PENDING 규칙 후보만 별도 MENTION 도형으로 표시한다. 후보 상태나 확정 관계 row를 만들지 않으며 후보 ID 기반 `mention:` 식별자로 이름 병합을 막는다. 가계도와 후보 `displayableAsMention`은 같은 판정을 사용하고, optionalLinkCount는 작품 전체 선택적 연결 수다. 늦은 캐릭터 등록은 자동 연결하지 않고 기존 후보 수정·확정 API로만 연결한다. 저장 모델을 바꾸지 않고 설정에만 언급된 인물을 표시하기 위한 구분이다.
- #261 끝점 호칭은 parser가 일치시킨 명시적 역할을 `FamilyTreeRoleResolver`가 현재 지지 근거·실제 끝점·방향·세부 유형·시점에 대조해 읽기 전용으로 제공한다. PARENT_OF의 subjectRole/objectRole은 각각 부모/자녀이며 명시되지 않거나 충돌한 끝점은 PARENT/CHILD다. 한쪽 호칭·이름·프로필 성별로 반대편을 추론하지 않고, 조회 revision에 호칭을 포함한다. DB 및 후보/관계 생성 identity는 변경하지 않는다.
- #261 등록 인물의 가계도 image는 기존 `WorldSettingImageResponse`를 재사용하며 실제 표시된 인물만 `CharacterImageService`로 한 번에 읽는다. MENTION은 null, 경로 없는 기본 이미지는 기존 기본 아이콘이다. 개인 이미지는 기존 인증 파일 경로를 유지한다. 표시 이미지 변경도 응답 revision에 반영하되 관계 projector·입력 세대·LLM·배치 identity와 분리해 사진 수정이 관계나 화면 위치를 바꾸지 않게 한다.
- #261 명시적 조상·후손은 `ANCESTOR_OF`로 정규화하고 입력 역방향은 `DESCENDANT_OF`다. nullable `generationGap`은 N대손의 N만 보존하며 미상은 null, 중간 인물·부모 간선은 만들지 않는다. 후보·확정·가계도 응답에 같은 필드를 제공하고 원래 문구는 기존 Fact 근거로 보존한다. 기존 비조상 canonical/request identity는 유지하며 새 조상 identity에는 gap을 포함한다. 조상이라는 이유로 HISTORICAL을 추정하지 않는다.
- #261 미등록 언급의 동일 인물 묶기는 사용자 명시 요청으로만 `RelationshipMentionGroup`에 저장한다. `relationship-mention-groups` API는 Work 잠금·requestId/hash·후보/그룹 버전·현재 표시 가능 출처와 묶인 그래프의 순환/세대 차이를 검증한다. 그룹은 실제 캐릭터가 아니며 각 후보·관계·근거는 유지한다. 별개 후보는 이름으로 자동 합치지 않고, 명시 그룹만 `mention-group:<UUID>`로 투영한다. 후보 수정·확정·제외는 해당 멤버만 떼고 그룹 버전을 올린다. 전체 해제는 원래 후보별 도형을 복원하며 작품 purge는 후보 다음 그룹을 삭제한다.

- 조상·부모는 같은 순환 검사에 참여한다. `RelationshipGenerationRules`는 같은 명시 시점의 부모=1·알려진 조상=N 제약을 비교하고 불일치한 연결 영역을 `GENERATION_GAP_CONFLICT` 검토로 보류한다. 미상 gap·기존 부모만의 영역에는 이 제약을 덧씌우지 않는다. 번호 대손 문법의 소유자 이름 접미사는 현재 프로필 소유자와 정확히 일치할 때만 허용하고 중첩 소유격·집단·추측·부정을 단정으로 승격하지 않는다.


#### World Setting Domain Policy

- 설정집 세계관의 `raw_extraction_json.epistemic_status`는 내부 추출기의 `ASSERTED|UNRESOLVED_CLAIM` 판정이다. 자동 반영과 누적 CURRENT 투영은 root와 지정된 `sourceCandidates[]` 직접 원본 객체 전체가 `ASSERTED`일 때만 허용하고, 누락·불명·혼합·잘못된 wrapper는 기존 검토 보류로 남긴다. 무관한 원문/중첩 key를 재귀 검색하거나 legacy BOOK 누락을 확정으로 추정하지 않는다. EP의 기존 누락 동작은 유지한다. Worker singleton·주체·batch 후보는 보존된 `rawExtractionJson` JSON object를 nullable 내부 필드로 전달하며, Jackson 2 Entity node는 Mapper에서 Map으로 바꿔 실제 MVC 응답에 node 내부 필드가 노출되지 않도록 한다. 공개 사용자 수정 DTO에는 이 내부 표지 입력을 추가하지 않는다.

- 세계관 설정은 캐릭터 `SettingCandidate`와 섞지 않고 `domain/worldsetting`의 `WorldSetting`, `WorldSettingCandidate`로 관리한다. 확정본·1차 후보는 `world_settings`, `world_setting_candidates`에 저장하고, 묶음 비교의 실행·canonical 결정·원본 provenance는 `world_setting_comparison_batches`, `world_setting_comparison_decisions`, `world_setting_comparison_decision_sources`에 저장한다. `world_setting_facts`나 삭제·보관·복원·전체 변경 로그는 만들지 않는다.
- `WorldSetting` 한 행은 `workId + category + normalizedSubjectName` 대상 하나다. 세부 설정은 루트의 문자열 leaf 또는 선택적 1단계 `scopeName` 아래 문자열 leaf로 JSON object에 저장하고, 생성 후 빈 object 상태는 허용하지 않는다. 이 범위 계층은 세계관 도메인에만 적용하며 캐릭터 `SettingCandidate`/`CharacterFact`의 key 계약을 변경하지 않는다.
- 대상명·범위명·설정명·설정값은 앞뒤 공백만 제거하며 내부 공백은 보존한다. 대상 중복키는 Unicode NFC와 `Locale.ROOT` 소문자로 정규화하고, property 중복은 `scopeName + settingName` 전체 경로를 같은 기준으로 정규화해 검사한다. `1층/출몰 규칙`과 `2층/출몰 규칙`은 동시에 허용하지만 같은 전체 경로는 허용하지 않는다. 동일 루트 key를 문자열 leaf와 scope object로 동시에 쓰는 충돌도 거절한다.
- 직접 생성·수정 API는 JSON 전체를 받지 않는다. 대상 정보 수정, 설정 한 개 추가, 설정 한 개 수정 요청을 분리하고 현재 `version`을 확인한 뒤 실제 변경마다 version을 증가시킨다.
- 사용자 직접 입력의 동일 분류·대상과 동일 대상 내 범위+설정명 전체 경로 중복은 Backend가 전체 DB와 unique 제약을 기준으로 최종 판정한다. Frontend의 현재 페이지나 필터 결과를 최종 중복 근거로 사용하지 않는다.
- 세계관 후보 한 행은 회차에서 추출한 설정 속성 하나다. 1차 추출, 2차 비교 제안, 최종 사용자 결정을 같은 행에 보존하되 LLM은 확정본을 직접 변경하지 않고 Spring의 confirm 트랜잭션만 `WorldSetting`을 변경한다.
- 하나의 분석 Job에는 세계관 후보를 최대 500개 게시하고, 정규화한 `category + subjectName + scopeName + settingName`이 같은 후보를 한 건만 허용한다. 주체 해소 API가 미해결 후보 전체를 최대 500개 한 요청에서 원자 저장하므로 게시 단계도 같은 상한을 사용한다. AI는 여러 1차 추출값을 `SINGLE/MERGED/CONFLICT`로 판정하고 모든 원문 근거를 보존한다. `CONFLICT` 후보는 사용자가 최종값을 수정했다는 명시적 결정 없이는 반영하지 않지만 그룹 제외는 허용한다. Backend는 Worker 게시와 사용자 그룹 확정 양쪽에서 동일 전체 property 경로 중복을 방어한다.
- 작가가 후보의 최종 분류·대상·범위·설정명·반영 방식·값을 저장하면 전용 결정 수정 API가 `PENDING_REVIEW + COMPLETED` 상태를 유지한 채 해당 후보의 `final*` 초안을 즉시 갱신한다. 일반 수정은 한 후보만, 분류·대상 일괄 수정은 같은 현재 그룹의 모든 요청 후보를 한 트랜잭션으로 저장하며, 이후 후보 조회·필터·그룹 key는 `final*` 초안을 우선 사용한다. 이 저장은 2차 LLM 재비교로 보내거나 비교 제안을 초기화하지 않는다. confirm 트랜잭션에서 현재 `WorldSetting`을 기준으로 `ADD`는 전체 경로 부재, `UPDATE/MERGE`는 전체 경로 존재, 루트 key와 범위명 무충돌을 직접 검증하고 구조적 오류를 반환한다. 수정하지 않은 LLM 제안의 외부 stale 문맥만 기존 `RECOMPARISON_REQUIRED` 흐름을 사용한다.
- 세계관 그룹 확정은 화면의 남은 검토 대기 row 전체를 한 번에 적용하는 흐름을 기준으로 한다. 예전 체크박스 부분 확정을 전제로 신규 대상 생성 뒤 선택하지 않은 row를 재비교 상태로 바꾸지 않으며, 재비교는 확정 전 외부에서 대상·속성이 달라진 stale 문맥에만 사용한다.
- 같은 `batchId + category + normalizedSubjectName` 원본 비교 그룹의 선택 후보는 대상 그룹 확정 요청 하나로 처리하되, 작가가 row별 최종 분류·대상을 다르게 정할 수 있다. 최종 대상 key 순서로 각 대상을 잠그고 모든 대상·property를 먼저 검증한 뒤 최종 대상별 생성·변경을 한 트랜잭션으로 반영하며, 같은 최종 대상 안의 앞선 `ADD`를 다음 후보의 재비교 사유로 판단하지 않는다. 여러 최종 대상에 반영하면 그룹 응답의 단일 `worldSettingId`·version은 `null`이고 후보별 연결 대상으로 결과를 표현한다.
- 외부 변경이 관련 key에만 영향을 주면 해당 row, 대상 생성·삭제·identity 변경이면 그룹 전체를 `RECOMPARISON_REQUIRED`로 전환하고 선택 후보를 하나도 부분 반영하지 않는다. 같은 행의 다른 설정 변경으로 version만 달라진 경우는 허용한다.
- 1차 `evidence_spans`·회차는 후보 원본으로 보존하고 2차 비교·재비교가 변경하지 않는다. 원고가 바뀐 경우에만 새 1차 분석 후보와 근거를 생성한다.
- 기존 속성과 의미가 같아 `EXCLUDE`하는 비교 결과는 대상 ID와 실제 속성명을 함께 받아 해당 속성값을 `beforeValue`로 보존한다. 특정 기존 속성과 비교하지 않은 일시적 사건 등의 제외만 `beforeValue`가 없을 수 있으며, 매칭 속성명만 있고 대상이 없는 요청은 거절한다.
- 같은 canonical 분류·주체에서 범위·설정명이 정리용 경로만 다르고 전체 기존 설명·원문 근거의 적용 대상과 조건이 같으면 `EXCLUDE/MERGE/UPDATE`로 의미 비교를 완료할 수 있다. 범위 미지정이나 동명만으로 `REVIEW_REQUIRED`를 강제하지 않는다. 지역·시점·조건·부분집합·불확실성이 다른 주장은 자동 동등 취급하지 않으며 명시적 검토 판단은 보존한다. 원본 범위·이름·값·근거를 수정하지 않고 실제 대상·전체 matched 경로 및 UPDATE/MERGE의 proposed 경로 일치를 계속 검증한다. root ADD는 실제 전체 경로·tree 충돌을 거절하되 범위 없는 동명이라는 이유만으로 거절하지 않는다. EXCLUDE는 전체 기존 값을 beforeValue에 보존하고 저장값을 쓰지 않는다.
- 명시적인 `SCOPE_UNRESOLVED`는 확정 설정 전용·누적 비교 모두 범위 없는 단일 출처와 입력의 실제 scoped 속성을 연결할 수 있다. 두 설정명이 달라도 허용하지만 제안 경로·값은 원본 null 범위와 설정명·값을 유지한다. 동명 다중 출처 공유 검토의 호환 조건과 실제 target/path 검증은 유지한다.
- 누적 세계관 비교에서 범위가 명시된 단일 출처 후보가 다른 범위의 기존 속성과 연결되면 `REVIEW_REQUIRED + SCOPE_MISMATCH`로 두 경로를 보존할 수 있다. 실제 제공된 canonical 대상의 기존 경로만 허용하며 기존 경로의 범위는 root(`null`)여도 된다. 제안 경로는 원본 후보의 범위·설정명을 유지하고, 후보·journal 참고에 기존 경로와 이전값·사유를 함께 남긴다. 값은 자동 반영하지 않지만 정상 비교 완료로 처리해 다른 묶음·다음 회차를 계속한다. 작가는 최종 결정 draft를 저장한 뒤 원본 경로의 `ADD` 또는 기존 경로의 `UPDATE/MERGE`를 재비교 없이 확정할 수 있다. 다중 출처·없는 대상/속성·동일 범위·임의 경로 변경은 이 검토 사유로 완화하지 않으며 `CONFIRMED_ONLY`에는 적용하지 않는다.
- 범위 차이로 설명할 수 없는 세계관 대상·내용 불확실성은 `REVIEW_REQUIRED + GENERAL_UNCERTAINTY`로 정상 비교 완료한다. 단건·확정 설정 전용 묶음·누적 묶음 모두 지원하되, 묶음 결정은 출처 후보 하나씩 원본 경로와 값을 보존한다. 대상이나 기존 속성을 명시하면 실제 비교 문맥의 유효한 대상·전체 경로여야 하며 이동 요청과 자동 확정은 허용하지 않는다. 누적 journal과 다음 회차 참고에는 원본 주체와 원문 근거, `UNCONFIRMED` 및 일반 검토 사유를 유지하므로 선택된 기존 대상을 확정 사실로 승격하지 않는다. `SCOPE_UNRESOLVED`·`SCOPE_MISMATCH`의 기존 조건은 완화하지 않고, 과거 `FAILED` 후보를 새 사유로 재분류하지 않는다. 구조적으로 유효한 ‘판단 보류’를 응답 오류로 취급하지 않으면서도 확정 설정은 보호하기 위한 계약이다.
- 세계관 묶음 완료는 수동 직접 검토·자동 누적 모두 성공 결정과 typed 후보 실패의 합집합이 원본 출처를 정확히 한 번 덮어야 한다. 성공 결정·출처 membership과 실패 상태·안전한 진단을 같은 트랜잭션에 저장하며 정상 다중 출처 병합은 원자로 보존한다. quota·lease·고정 입력·저장 오류는 후보 실패로 완료할 수 없다. failed-only 재비교는 실패 행의 연결만 초기화하고 완료된 병합을 유지한다. 수동 비교 성공은 기존 사람 검토 의미를 유지하고 실패 행은 자동 journal 실패 참고에 등록하지 않는다. 기존 수동 그룹 확정은 실패 행 재비교를 기다릴 수 있으며 이 정책으로 부분 확정 UI를 추가하지 않는다.
- 묶음 비교 `ADD`가 기존 대상의 root 문자열 설정을 새 공통 scope 아래로 함께 이동하려면 `existingRootPropertyNamesToMove`를 명시한다. Backend는 같은 대상의 실제 root 경로와 destination 충돌을 검증하고 이름·현재값 snapshot을 결정 JSONB에 저장하며, 원본에 없던 합성 scope는 기존 child·이번 ADD·root 이동을 합쳐 서로 다른 child가 둘 이상일 때만 허용한다. 사용자가 AI 결정을 그대로 그룹 확정할 때만 confirm 직전 snapshot을 재검증해 root 이동과 새 property들을 한 deep copy, version 1회 증가로 반영하고 실제 적용 version을 결정에 기록한다. shared decision의 source 하나라도 작가 수정안이 AI안과 달라지면 decision-wide 비활성 상태를 영속화해 모든 source 응답에서 이동 목록을 숨기고 적용도 막는다. 결정 편집·제외는 이동을 취소하고, snapshot이 있는 결정의 단건 확정·제외는 금지한다. 이동 뒤 상세 근거는 확정 candidate를 rewrite하지 않고 적용 version 이전의 기존 root 동일 설정 이력만 새 scoped 경로에 projection한다.
- 재비교 충돌은 후보 상태를 먼저 commit한 뒤 HTTP 409로 응답해야 한다. Service는 `WorldSettingCandidateConfirmResult.recomparisonRequired`를 정상 반환하고 Controller가 commit 이후 `AppException`으로 변환한다. 이를 위해 전용 예외 클래스를 추가하거나 `noRollbackFor=AppException.class`로 다른 확정 오류의 rollback 범위를 넓히지 않는다.
- 같은 확정·제외 요청은 멱등 처리하고 `CONFIRMED ↔ DISMISSED` 반대 전이는 충돌로 거절한다. `UPDATE`와 `MERGE`는 DB에서 모두 최종 문자열로 한 property를 교체하되 제안 의미를 기록하기 위해 enum을 구분한다.
- `recompare` API는 비교 제안을 비우고 `PENDING`으로 전환한 뒤 멱등한 `WORLD_SETTING_COMPARISON` Job을 생성한다. 실제 LLM 호출은 별도 AI comparison runner가 claim해 수행하며 HTTP 요청 트랜잭션 안에서 호출하지 않는다.
- 1차 세계관 후보 게시와 2차 비교 상태 전이는 `domain/worldsetting` 내부 Worker API만 수행한다. Backend는 lease·작품·분류·후보 소유권, 최대 3개 비교 문맥 ID·version, exact 대상과 property를 검증하고 `beforeValue`와 base version을 직접 산출한다. Worker는 `world_settings`를 직접 수정하지 않는다.
- 세계관 canonical 주체 해소는 정규화 exact 대상 최대 20개를 모두 받아 2개 이상이면 `AMBIGUOUS`로 저장하고, LLM fuzzy 선택만 최대 3개로 제한한다. 주체 해소 LLM의 토큰 예약이 거절된 경우에만 기존 비교 실패 API가 아직 `PENDING`인 현재 후보를 `AI_TOKEN_QUOTA_EXHAUSTED`로 중단하며, 다른 실패 코드의 `PENDING` 전이는 계속 거절한다.
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
- HTTP 로그 연결은 `global.config.logging.RequestIdFilter`가 Security 앞에서 생성하는 UUID와 MDC `requestId`, 응답 `X-Request-ID`를 사용한다. 외부 ID는 채택하지 않으며 동기 REQUEST/ERROR 처리 후 자신이 소유한 MDC 키만 정리한다. 로그 패턴은 공통 YAML에서 관리한다.
- API·관리 포트가 분리돼도 같은 정책을 적용하도록 `RequestLoggingConfig`를 관리 context의 `ManagementContextConfiguration.imports`에도 등록한다. 각 서버에서 한 번만 적용하며 정상 `/healthz`·`/actuator/health`·`/actuator/prometheus`는 완료 로그만 생략한다.
- 삭제·탈퇴 로그의 업무 ID는 `purgeRequestId`·`withdrawalRequestId`로 표기한다. 기존 API 본문의 `requestId`와 DB 필드는 유지한다. HTTP 요청과 여러 재시도에 걸친 업무 작업을 혼동하지 않기 위함이며, 스케줄러·별도 스레드에는 HTTP ID를 임의 전파하지 않는다. 적용 범위와 조회 명령은 `docs/request-logging.md`를 따른다.
- Entity에서 nullable 여부가 전역/작품 범위 같은 도메인 의미를 갖거나, JSON·정책 컬럼의 저장 목적이 이름만으로 명확하지 않으면 필드 위에 한국어 주석으로 의미와 필요한 예시를 남긴다.

### Branch and Worktree

- 작업을 시작하기 전에 기존 로컬·원격 브랜치와 최근 PR의 네이밍을 확인한다.
- 브랜치는 `<type>/gh-<이슈번호>-<작업설명>`을 사용한다. Jira 작업은 기존처럼 `<type>/nvm-<번호>-<작업설명>`을 따른다. 예: `feat/gh-207-mdc-request-id`.
- 브랜치 이름에 `codex/` 접두사를 붙이지 않는다.
- 기존 저장소 폴더에서 브랜치를 전환해 작업하며, 별도 worktree를 기본으로 생성하지 않는다.

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

- 챗봇 검토·사용 중 새로 확인한 조회/답변/비용 한계는 [#247](https://github.com/catchhole-soma/catchhole-backend-java/issues/247)에 재현 조건·현재/기대 동작·검증 상태·관련 PR을 누적한다. 미검증 우려와 확인된 문제를 구분하고, 개별 개선 PR로 누적 이슈 전체를 자동 종료하지 않는다.
- GitHub Issue는 `.github/ISSUE_TEMPLATE/` 아래의 Issue Form을 사용한다.
- 버그는 `bug_report.yml`, 기능 요청은 `feature_request.yml`, 일반 작업/문서는 `task.yml`을 사용한다.
- 빈 이슈 생성을 막기 위해 `config.yml`의 `blank_issues_enabled: false`를 유지한다.
- 템플릿을 추가하거나 필드를 바꿀 때는 이 문서의 협업 규칙도 함께 갱신한다.

이 방식을 선택한 이유는 이슈 생성 시 재현 절차, 요구사항, 완료 기준 같은 필수 정보를 누락하지 않도록 하기 위함이다.

### 분석 상태 재사용 (#294)

- `AnalysisRunStateService.prepareState(job)`의 `PreparedState`는 Job 잠금을 보유한 한 번의 처리에서만 지역 변수로 사용한다. 전역·ThreadLocal·요청 간 캐시로 보관하지 않는다. `projectedState()`는 읽기 전용 view이며 append 뒤에는 다시 얻는다.
- 입력·선행 회차를 한 번 검증하고, append는 새 event만 기존 projection에 순서대로 적용한다. 중복 event의 동일 내용은 멱등 처리하고 충돌·잘못된 경로는 부분 반영 없이 거절한다. lease·원문·journal 상태 검증과 개별 이력·근거는 유지한다.
- `AnalysisHumanRejectionPolicy.beginMatching()`도 같은 처리 안에서만 사용한다. 최신 references를 매번 확인하고, anchor 조회는 작품·후보 ID 및 도메인별로 재사용한다. 실제 후보 또는 원문 상태를 요청 간에 공유하지 않는다.

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
- 기본 H2 DB 이름은 `${random.uuid}`로 Spring Context마다 분리하고 `src/test/java/.../support/TestClassContextCustomizerFactory`를 test 전용 `META-INF/spring.factories`에 등록해 Context 캐시도 테스트 클래스별로 분리한다. 서로 다른 클래스가 같은 DB의 schema를 생성·삭제하거나 커밋된 fixture를 공유하지 않게 하기 위함이다. 같은 클래스의 메서드 간에는 기존 트랜잭션 rollback 또는 명시적인 fixture 정리를 유지하며 PostgreSQL 테스트의 명시적인 DB override는 보존한다.
- Gradle 테스트 JVM heap은 1GiB, Spring TestContext 캐시는 8개로 제한한다. 전체 테스트에서 여러 JPA Context를 보관하다 CI heap이 고갈되는 것을 막기 위한 테스트 전용 설정이며 운영 JVM 설정을 바꾸지 않는다.
- `main` 대상 PR은 `.github/workflows/ci.yml`에서 전체 테스트와 `bootJar`를 실행하고 실패 보고서를 7일간 보관한다. 배포 전에 실패를 확인하기 위한 검증 전용 workflow이며 이미지 발행·운영 배포는 실행하지 않는다. `main` push의 기존 이미지 발행 테스트도 유지한다.
- GH304 실제 연결 검증은 `ComparisonEvidenceHttpPostgresIntegrationTest`에서 전용 Testcontainers EPISODE·Spring HTTP·합성 저장소를 사용한다. `GH304_AI_CHECKOUT`은 AI checkout, `GH304_PYTHON`은 별도 기존 venv의 Python 경로다. 이 두 값을 지정하면 실제 Python 실패 보고와 localhost 가짜 Discord, 변경 전 DTO 수신을 함께 실행한다. 외부 provider/webhook과 실제 원고는 사용하지 않는다. Java 시간 payload export의 기존 빈 디렉터리 `BATCH_TIME_FIX_OUTPUT`을 Python에도 전달해 네 계약 검증을 실행한다.
- 이메일·휴대폰 인증의 TTL, Lua rate limit 경계, 이전 코드 폐기, 오입력 잠금과 동시 토큰 소비는 `redis:7.4.10-alpine3.21` Testcontainers 통합 테스트로 검증한다.

- 그룹 검토 응답의 `analysisMode`는 원 후보 Job의 정책이다. `CONFIRMED_ONLY`의 구형 요청·여러 회차 그룹은 그룹 공통 비교 revision을 확인하고, `ORDERED_PROVISIONAL`은 회차별 고정 문맥·실제 상태·의존성을 검증한다. 자동 보류 후보의 명시적 사용자 수정 확정과 그룹 멱등 결정 hash의 `applyEditedValue`를 함께 보존한다.

### World Image Catalog (GH194)

- 공용 도감·별칭·대상 선택은 `worldimage` 도메인과 `world_image_catalog`, `world_image_aliases`, `world_setting_images`에서 관리한다. 상세 계약·자산 배포 순서는 `docs/world-image-catalog.md`를 따른다.
- 이미지 제작·업로드·기존 데이터 보정 도구는 `scripts/world-images/`, 생성된 SHA manifest는 그 아래 `manifests/`에 둔다. Java는 manifest를 읽지 않고 DB에 등록된 도감과 테마를 조회하므로 제작 자료를 `src/main/resources`나 배포 JAR에 포함하지 않는다. 서버 기동에 필요한 Flyway SQL은 기존 `src/main/resources/db/migration`에 유지한다.
- 대표 이미지 선택은 설정 내용/설정 version/분석 상태와 독립적이다. 대상 잠금과 별도 이미지 version으로 충돌을 막고, 대상 분류 변경은 맞지 않는 공용 이미지 선택을 해제한다. 사용자가 고른 개인 이미지는 분류 변경에도 유지한다.
- 공용 이미지 경로는 활성 도감 또는 테마 슬롯에 등록된 SHA만 허용한다. S3 임의 key를 클라이언트에서 받거나 기존 원고 저장소를 공개하지 않는다. 선택·도감 검색 API는 인증/작품 소유권 경계를 유지한다.
- V63 이후 캐릭터·세계관 자동 이미지는 확정/수정 트랜잭션에서 매칭해 선택 행에 저장한다. 조회에서 Matcher·종족 별칭 조회를 호출하지 않는다. 규칙·사용자 선택 우선·운영자 보정 절차는 `docs/automatic-subject-images.md`를 따른다. 캐릭터 이름/설명 추론과 LLM 프롬프트 변경은 하지 않으며 이미지 별칭을 대상 동일성 판단에 사용하지 않는다.
- 개인 이미지의 V59 테이블·CHI1 프로토콜·보호 범위는 `docs/private-world-images.md`를 따른다. GH199 새 업로드는 로그인·작품 소유권으로 보호하는 PNG와 파일명을 받는다. 기존 CHI1 복호화 키는 여전히 서버에서 받지 않는다. 계정 보관함과 작품 소유권을 확인하고 개인 암호문을 공개 도감 경로에 등록하지 않는다. `works/{workId}/private-world-images/`는 작품 전체 버전 purge에 포함되며, 사용 중 이미지 삭제는 거절한다. 개인 선택과 업로드 예약·완료·삭제 요청의 DB 변경은 작품 잠금으로 직렬화한다. 저장소 입출력은 V65 상태 계약에 따라 잠금 밖에서 수행한다. 원고 암호화 변경은 보류다.

- 캐릭터 이미지는 같은 `worldimage` 도메인의 V60 `character_images`에서 관리한다. 수동 도감·개인 이미지·공통 기본 선택은 자동 연결보다 우선하고 설정 snapshot/version/분석 상태와 독립적이다. 개인 이미지 삭제 시 세계관·캐릭터 참조를 모두 확인한다. 목록은 저장된 선택과 그 이미지만 일괄 조회한다.

- 장르 이미지는 V61/V62의 `world_image_theme_assets`(초기/기본 슬롯)와 `world_image_recommendations`(도감 ID·테마 다대다)로 구성한다. 장르 매핑은 `WorldImageThemes` 한 곳에서 관리하며 로맨스·코미디·일상·기타는 modern-common이다. 미일치 기본값은 조회 시 해당 슬롯을 사용하고 수동·개인·기본 고정이 우선한다. 추천과 실제 대상 자동 매칭을 혼동하지 않고 LLM 추출을 변경하지 않는다. 적용된 migration은 수정하지 않는다.

- GH194 V65 개인 이미지 저장소 입출력은 DB 트랜잭션 밖에서 수행하고 예약/완료/삭제 요청만 짧게 잠근다. `worldimage/type/PrivateWorldImageStatus`의 UPLOADING/READY/DELETING 상태로 조회·선택을 제한하며 시도별 저장소 경로로 지연 정리와 재업로드를 격리한다. 삭제 대기와 1시간 지난 업로드는 `worldimage/processor/PrivateWorldImageCleanupScheduler`가 최대 20건씩 회수한다. V66의 `cleanup_attempted_at`을 저장소 호출 전에 커밋하고 가장 오래 시도하지 않은 순서로 회수해 반복 실패가 후속 항목을 막지 않게 한다. `world-image.cleanup.scheduling-enabled=true`, `fixed-delay-ms=10000`은 공통 YAML, 테스트 비활성은 test YAML에 둔다. 작품 purge는 UPLOADING 예약이 남으면 기다려 늦은 파일 쓰기와 겹치지 않게 한다.

## GH199 — 늦은 검토와 코드 없는 내 이미지

현재값 보호의 회차 선후는 분석 시 고정한 `AnalysisJob.sourceEpisodeNo`로 판단한다. 캐릭터 근거에 이미 저장된 `CharacterFact.effectiveFromEpisodeNo`는 유지하되, 없으면 원본 Job의 고정 번호를 사용한다. 변경 가능한 `Episode.episodeNo`로 과거 근거를 추정하지 않으며 고정 번호가 없는 legacy는 출처 불명으로 현재값을 보호한다.

`docs/gh199-late-review-and-private-images.md`를 따른다. 완료된 순차 후보와 개별 `MANUAL + CONFIRMED_ONLY` 후보 확정은 동일한 항목별 최신값·제거 기록·직접 수정 보호를 재사용하고 충돌은 이력에만 남긴다. 일반 비교의 버전·현재값·의존성·범위 이동 검증은 생략하지 않으며, 확정 직전 공통 `AnalysisCandidateSourceGuard`로 현재 원문과 파기 예약을 확인한다. 원문 manifest 없는 legacy는 보관·파기 검증을 유지하고 이미 완료한 동일 확정 요청은 기존 멱등 응답을 유지한다. 세계관의 `historyOnly` 후보를 현재값 근거로 재사용하지 않는다. 후보 변경만으로 후속 Job을 무효화하거나 LLM을 추가 호출하지 않는다. V67 계정 이미지/CHI1 호환과 V68 세계관 이력·수동 경로 표시를 유지한다. 기존 CHI1 문서 중 새 업로드에도 키를 요구하는 내용은 이 정책으로 대체한다.

캐릭터 현재값의 직접 수정 보호는 V73 `user_content_modified`로 판단한다. `user_modified`는 대상 연결·검토 선택도 포함하므로 자동 처리/검토 안전장치에는 계속 사용하되, 그것만으로 과거 회차의 값을 영구 고정하지 않는다. 새 Java 후보와 새 Python INSERT는 내용 수정 여부를 false로 시작하고 `updateReviewContent`에서 true를 기록하며 연결·재시도·검토 선택은 이 값을 지우지 않는다. V73 이전 행의 NULL은 기존 `user_modified`를 그대로 보호 기준으로 사용하고 확정 데이터는 소급 재적용하지 않는다.

- GH215 `acceptDisplayedResults=true` 그룹 확정은 완료된 동일 원본 Job의 `MANUAL + CONFIRMED_ONLY` 후보에만 허용한다. 전체 미확정 그룹과 후보별 `expectedUpdatedAt`, 저장 초안의 모드·버전, 현재값 snapshot, 원문·동시 실행·schema·실제 대상 및 후행 현재값 보호를 검사한다. 그룹 내부 수정·제외로 달라진 AI 입력 hash/revision이나 선행 후보 제외·이력 선택 때문에 재비교하지 않는다. 남은 제안의 최종 문장을 그대로 승인하며, 다른 후보에서 제외된 문장을 자동으로 빼지 않는다. 최종 상태에서 이미 없는 STATUS 제거는 no-op으로 처리하되 제거 타입·키·중복 검증은 유지한다. 동명 캐릭터의 동시 생성은 조용히 재연결하지 않고 충돌로 거절한다. 자동 반영·연결 변경·회차 간 비교 계약을 이 플래그로 우회하지 않는다.

### GH219 명시적 최종 확정

회차 출처에서 작가가 저장한 현재 반영 선택은 캐릭터 snapshot 버전 변경만으로 거절하거나 최신·수동 수정·삭제 기록 때문에 이력으로 바꾸지 않는다. 완료된 순차 후보와 MANUAL + CONFIRMED_ONLY의 명시적 최종 확정에서 적용하며 AI 자동 반영과 구형 비교 검증은 유지한다. 수정 초안은 확정 시점의 최신 snapshot에 선택 항목만 적용하고 부수 삭제는 재사용하지 않는다. 종료 상태(active=false)는 해당 STATUS 제거로 반영한다. 최종 결과 승인 그룹은 표시된 제안과 부수 변경을 적용한다. 같은 항목에 여러 현재 반영은 CURRENT_SELECTION_CONFLICT로 원자적으로 거절해 작가가 하나를 선택하게 한다. 후보 expectedUpdatedAt·선택 모드·원문 버전·작품 소유권·실행 잠금·스키마·멱등성 검증은 유지한다. 이는 위 GH199/GH215의 명시적 최종 확정에 대한 snapshot 버전/이력 보호 우선 규칙을 대체한다. 설정집 출처는 위 `applyToCurrent`의 현재 version·저장 선택·서사 시점 보호를 유지한다.


### Hangul uploads (GH224)

- 공통 `upload/parser/TextDocumentReader`와 `DocumentFormat`이 TXT/DOCX/HWP/HWPX 지원과 MIME을 결정한다. 다회차 여러 파일에 TXT 전용 제한을 다시 추가하지 않는다.
- Apache POI `poi:5.5.1`은 HWP의 OLE 컨테이너만 연다. HWP 본문 레코드와 HWPX ZIP/StAX는 `DocumentReadLimits`의 실제 읽기량·개수·깊이 제한 안에서 읽는다. 그림·스크립트·전체 서식 객체를 생성하는 파서로 대체하지 않는다.
- 표 셀은 탭, 행/문단은 줄바꿈, 글상자는 연결된 문단 뒤에 한 번 배치한다. 머리말·꼬리말·각주·미주·숨은 설명은 제외한다. 상세 계약과 미지원 형식은 `docs/hangul-upload.md`를 따른다.
- 개인 원고와 추출 결과는 저장소/테스트 fixture에 넣지 않는다. `scripts/upload-fixtures/`의 합성 자료만 사용한다. hwplib/hwpxlib는 fixture 생성과 로컬 대조에만 사용하며 운영 의존성이 아니다.

- GH223은 모든 장르에서 스탯을 유지하고 소유물 표시명만 소지품으로 바꾼다. API의 기본 SKILL 표시명과 AI 입력 스키마는 스킬을 유지하며, 장르별 스킬 제목은 Front 표시 규칙이다. V74는 이전 기본값인 전역 SYSTEM_SEED의 items.item만 갱신한다. 저장 키·자료형·작가별 표시명·세계관 IMPORTANT_ITEM·추출 프롬프트는 유지한다.

### 채팅 실행 v1 (#237)

- 계약은 `docs/chat-execution-v1.md`, 양쪽 공통 fixture는 `docs/contracts/chat-execution-v1.json`이다. 질문/시도/provider 호출의 ID를 구분한다.
- Spring `domain/chat/{controller,dto,entity,exception,mapper,repository,service,type}`와 `global/config/chat`은 실행·권한·원장을 담당한다. 내부 wire DTO는 버전 경계를 함께 검토하도록 `ChatContract`에 record로 모으고 OpenAPI 이름은 `Chat*V1`으로 고정한다.
- Python `app/chatbot/{http_app,client,runtime,outbox,providers}.py`와 `app/schemas/chat_execution.py`는 독립 HTTP 진입점이다. 기존 Worker replica/lease loop에 HTTP listener를 추가하지 않는다.
- `CHAT_ENABLED=false` 기본. 공통 Spring YAML의 CHAT_* 설정은 local/prod 모두 환경변수로 지정한다. FastAPI는 별도 Compose로 격리하며 #258 이후 검증된 동일 publish SHA의 AI 배포에 연결한다. 기능 flag를 꺼도 기존 예약 복구는 계속한다.
- `CHAT_OPERATIONS_ENABLED=false`는 새 서비스 부담 운영 백필 접수만 제어하며 공개 `CHAT_ENABLED`와 독립적이다. 공개 채팅을 닫은 초기 백필을 위해 어느 게이트든 켜면 별도 32자 이상·공백이 아닌 `CHAT_CAPABILITY_SECRET`을 검증하고, `CHAT_PYTHON_URL`은 공개 채팅을 켤 때만 요구한다. 기존 운영 ID의 동일 입력 재전송·실행·정산·복구는 게이트를 꺼도 허용하므로 drain 동안 secret을 보존한다.
- 내부 키를 요구하는 `GET /api/internal/v1/chat-runs/operations/readiness`는 DB·provider 호출 없이 `operationsEnabled`, `publicChatEnabled`, `capabilityConfigured`만 반환한다. HTTP 200은 인증·조회 성공이며 기능 활성화 보장이 아니다. 배포·백필 사전 점검은 필요한 boolean을 명시적으로 검사한다. 실행 생성으로 연결을 시험하지 않기 위한 경로다.
- 채팅은 실행에 묶인 tools route를 사용하며 member/work를 LLM 인자에서 받지 않는다. provider SDK 재시도도 실제 호출마다 `Execution.call()` 예약 경계 안에 있어야 한다.
- 실행 한도는 `EXECUTION_LIMIT_EXCEEDED`, 계정 잔액 부족은 `QUOTA_EXHAUSTED`로 구분한다. HTTP 429만으로 잔액 부족을 추정하지 않는다. 실패 안내와 재시도 판단에서 계정 상태와 실행 제한을 혼동하지 않기 위함이다.
- `CHAT_ATTEMPT_TOKEN_BUDGET` 기본값 240,000은 Python 해석 출력 1,500·답변 3,000·출력 절단 복구 최대 6,000과 동기화한다. 최초 호출과 schema/참조 오류·절단 복구는 목적별 최대 두 시도를 공유하며 각 호출을 새로 예약·정산한다. 누적 예약 상한을 실제 소비나 모델별 사용자 할인으로 해석하지 않는다.
- UNKNOWN은 사용량 0이 아니다. 원문 변경/삭제 시 저장 답변 자체도 무효화한다. 후속 대화 저장소가 생기면 같은 파기 계약을 연결한다.
- 이 변경은 모델/프롬프트/기존 분석 결과에 영향을 주지 않는다. 테스트 stub은 tests/support 전용이며 운영 handler로 사용하지 않는다.

### RDB 단일 질의응답 연계 (#238)

- `docs/chat-qa-v1.md`는 Python과 동일한 단일 질문 처리·응답·사례 문서다. 질문 분류와 관련 행 선택은 Python이 맡고 Java의 현재값·권한·조회·실행 DTO를 재사용한다. 모델은 SQL·회원/작품 ID·임의 인용을 생성하지 않는다.
- `ChatQaHttpEndToEndTest`는 `GH238_AI_CHECKOUT`으로 지정한 Python checkout의 실제 QA handler와 Spring의 조회·인증·원장·결과 저장을 HTTP로 연결한다. provider 응답과 S3 공급은 합성 대역이고 API key를 사용한 모델 품질 측정은 아니다. 환경변수가 없으면 skip되므로 일반 CI 통과와 실제 HTTP 검증을 구분한다.

### 원문 검색 준비 (#239)

- `domain/retrieval/entity`는 Python이 준비하는 검색 manifest/청크의 생명주기 매핑이다. V76 schema와 Python 매핑, 공통 `docs/retrieval-index-v1.md`를 함께 검증한다. vector 필드/생성은 Python/pgvector가 소유한다.
- 검색용 `retrieval_sources`/`retrieval_chunks`를 기존 분석 `episode_chunks`와 분리한다. 회차/작품 purge에서 검색 텍스트·벡터·manifest도 삭제하며, 파기 요청이 남으면 물리 데이터가 있어도 `retrieval_current_chunks`에서 제외한다.
- 운영 실행기는 기존 OPERATIONAL_BACKFILL 원장과 RETRIEVAL_INDEX_SERVICE_PAID_V1 정책을 사용해 사용자 토큰을 예약·차감하지 않는다. provider 호출 전·저장 전 원문 검증은 기존 Work row 잠금과 직렬화한다. Java V76/파기 코드 배포 후 AI를 배포한다.
- #240의 모든 검색 경로는 현재 출처 일치/보관·파기/서버 작품·회차 범위와 policy/model/version을 재검증한다. 원문 회차 번호를 사건 시점으로 해석하지 않으며 설정집·과거 원문은 지원 전 준비 완료로 집계하지 않는다.

### RDB·원문 결합 답변 (#240)

- 양쪽 공통 계약/fixture는 `docs/chat-hybrid-retrieval-v1.md`와 `docs/contracts/chat-hybrid-v1.json`이다. 기존 SettingTool enum에 검색 청크를 가장하지 않고 `ChatContract.RetrievalReference`/`Result.retrievalSources`로 출처 종류를 구분한다. 빈 새 목록은 구형 JSON과 호환되도록 생략한다.
- 실행별 `retrieval-sources` route는 기존 내부 key·capability·runner와 Work 잠금, 도구 상한을 사용한다. `ChatRetrievalSources`는 회원/작품/회차/manifest/파기·정책과 실제 원문 SHA256·Unicode code point 위치를 확인한다. 전체 인용 필드 hash의 receipt가 있어야 finish에 사용할 수 있고 완료 직전 다시 현재 원문을 대조한다.
- 키워드/인접 조회의 미임베딩 텍스트는 허용한다. 실제 벡터가 있으면 model `text-embedding-3-small`, policy `cl100k-800-100-boundary-v1`, `CHAT_RETRIEVAL_EMBEDDING_VERSION`(기본 v1)을 맞춘다. 새 설정은 base YAML과 API Compose에서 local/prod 모두 환경변수로 지정한다. 이번 단계에는 새 Flyway migration이 없다.
- `CHAT_SCOPE_INVALID`는 사용할 수 없는 범위/인용, `CHAT_SOURCE_UNAVAILABLE`는 저장소 장애다. 원문 조회 실패를 정상 빈 근거로 바꾸지 않는다. getText는 원문 전체를 읽은 뒤 Python 색인과 같은 UTF-8 2,000,000 byte 상한을 검사한다. 기존 저장 원고의 읽기 지원을 신규 업로드·수정 제한과 분리하며, S3 응답 VersionId·전송 중 크기 제한은 제공하지 않는다는 한계를 문서에 유지한다.
- `ChatRetrievalSourcesPostgresIntegrationTest`는 실제 PostgreSQL/Flyway 및 인용·파기 경계를 검사한다. `GH240_AI_CHECKOUT`을 명시하면 Python `tests/support/chat_retrieval_http_client.py`와 실제 HTTP로 start→인용 등록→finish를 검증한다. 합성 원고/S3 대역이며 실제 모델 품질이나 운영 배포 검증이 아니다.


## 일회성 작품 대화 (#241)

- `chat.session-cleanup.scheduling-enabled`는 기본 true이며 test 프로파일에서는 false다. 만료 시각을 조정한 테스트 fixture를 백그라운드 정리가 먼저 지우지 않도록 하고 만료 서비스는 직접 검증한다.
- `docs/chat-sessions-v1.md`를 Java/AI/Front에 동기화한다. 명시적 종료 또는 마지막 질문/답변 후 2시간 만료이며 읽기/탭 닫힘은 TTL을 바꾸지 않는다.
- 세션 종료는 본문·인용 삭제와 새 호출/게시 차단을 소유하고 기존 실행 원장의 늦은 정산은 유지한다. 원문 무효화와 작품/회원 파기에 같은 경계를 적용한다.
- 과거 대화는 지칭 해석용 비신뢰 문맥이다. 최대 6턴/답변 1,000/전체 8,000 UTF-16 단위와 생략 안내를 유지하고 이번 요청의 RDB·근거를 다시 조회한다. 사용자 입력의 세션/작품 ID를 권한으로 신뢰하지 않는다.
- Front는 생성 SDK를 사용하고 본문을 URL/localStorage/sessionStorage에 저장하지 않는다. 인증 세대·작품·세션 캐시 분리와 이탈 후 늦은 응답 폐기를 유지한다.
- 합성 모델 출력 기반 로컬 연결 테스트와 실제 모델 품질 측정을 구분한다. 기존 기능 플래그는 기본 OFF, 운영 활성화/백필은 별도다.

### 챗봇 설정 경로·조건 조회 개선 (#247)

- OPT-33의 선택적 `SettingToolItemV1.provenance`는 인물 Fact별 sourceKind, nullable contentEdited, 보존된 후보 확정 메타데이터 applicationAction, Fact 생성 시각 recordedAt을 구분한다. CURRENT MERGE의 복수 출처를 하나의 origin으로 대표하지 않으며 기존 origin은 호환 의미를 유지한다. 후보 없는 수동/레거시는 UNKNOWN, user_content_modified의 null은 미상으로 남긴다. updatedAt·수동 보호 slot·파기 가능한 raw 비교 JSON으로 내용 편집이나 마지막 현재 반영 행위/시각을 추정하지 않는다. Java/Python 계약과 생략·복수 출처 fixture를 함께 검증하며 정확한 의미와 배포 순서는 `docs/setting-tools-v1.md`를 따른다.

- 챗봇이 새로 작성하는 설명·목록·조건 explanation은 존댓말(~습니다/~요)을 사용한다. AI의 공통 `ANSWER_STYLE_INSTRUCTIONS`는 hybrid answer v7·등록 설명/현재 조건/이력 조건 v2에 적용하고 계획 v6은 유지한다. 사용자·이전 대화 등 자료의 반말을 따르지 않는다. 말투 변경으로 저장값·정확한 인용·AI의 supports.quote를 바꾸지 않으며 정규식 말투 변환이나 추가 모델 호출을 하지 않는다. 검색·정산·인용 검증 계약은 유지하고 공통 문서에서 이전 실행 기록과 새 말투 검증을 구분한다.
- `CHARACTER_FIELDS`/`WORLD_FIELDS`는 실제 현재/허용 이력의 값 없는 경로 메타데이터다. 경로 발견 단계에서는 인물 `factType` 등 각 FIELD 도구의 허용 필터를 pagination 전에 적용하고, `settingKey`·`scopeName`은 받지 않는다. 모델이 관측된 경로 ref를 고르면 오케스트레이터가 실제 저장 key/type/scope를 후속 CURRENT·HISTORY·COLLECTION 조회에 전달해 pagination 전에 필터한다. 경로 발견과 값 조회의 인자를 혼동하지 않으며 문맥 예산으로 조회 완전성을 대신 판단하지 않는다.
- `FIND_CHARACTERS`의 현재 scalar 조건 검색은 `CharacterSnapshotAccessor`를 재사용해 수동/레거시 형식을 일치시킨다. 조건 전에 활성 인물을 잘라 거짓 전체 결과를 반환하지 않으며 전체 scan 비용은 `docs/setting-tools-v1.md`에 명시한다. 조건 실패를 과거 Fact나 부분 문자열로 확장하지 않는다.
- `CHARACTER_CURRENT_COLLECTION`/`WORLD_CURRENT_COLLECTION`은 작품의 등록 대상 현재 설정을 필터 후 페이지로 제공한다. 응답 `collection`은 모집단 대상 수·설정이 있는 대상 수·설정 행 수를 구분한다. 값 누락을 의미 조건 불일치로 처리하지 않으며 STATUS의 생존·부활 등을 Java가 추정하지 않는다.
- COLLECTION 후속 페이지는 `readToken`을 요구하고 page 0 최종 재확인에도 적용한다. 작품·도구·필터·대상 집합/이름/version·세계관 수동 수정 경로와 확정 근거 상태·표시 schema를 묶고 요청 전후 scalar 재조회로 변경을 감지한다. 같은 값의 후보 확정으로 근거만 달라지는 경우도 포함하며 전역 world version 정책은 바꾸지 않는다. token은 권한이나 저장된 과거 snapshot이 아니며 기존 인증·현재값·직접 근거 검증을 유지한다. 기존 도구의 응답에는 collection 필드를 생략하고 공통 fixture를 별도로 추가한다.
- 작품 범위 `WORLD_FIELDS`는 현재 경로만 category/scope/key별로 합친다. 세계관 목록·현재 COLLECTION·작품 FIELD의 `worldCategory`는 동일 분류를 뜻한다. 전체 설정 scan 비용과 FIND 기존 페이지의 snapshot 비보장 범위는 계약에 명시한다.

### OPT-31 후속 대화·조회 규칙 — 2026-10-15 현재

- 현재 프롬프트는 QA plan v7·hybrid plan v8/answer v9이다. OPT-31 초기 답변 v8 뒤 관련 맥락·검증 실패 복구를 추가했다. 앞선 말투 보완 당시 v6/v7 기록은 보존하되 현재 동작은 이 규칙과 공통 `docs/chat-query-quality-v1.md`를 따른다. [#247 OPT-31](https://github.com/catchhole-soma/catchhole-backend-java/issues/247) 후속 실제 모델 검증은 77dc74e의 6건, 최종 제품은 표현만 보완한 265fc68로 구분하며 이전 실행을 새 구현의 통과 기록으로 사용하지 않는다. 최종 Python 2,853 passed/54 skipped/기존 warning 1이며 실제 ANSWER에는 PARTIAL이 포함되어 정답률로 집계하지 않는다.
- 인사·감사·챗봇 도움말만 있는 요청은 planner의 GREETING/THANKS/HELP와 비어 있는 조회 필드를 검증한 뒤 고정 존댓말을 기존 ANSWER로 반환한다. 문자열 감지로 사실 질문을 가로채지 않는다. CONVERSATION_LIMIT는 내부 모델에 유지하고 공개 결과에는 CLARIFICATION에서만 표시하며 실제 데이터/원문 오류는 숨기지 않는다.
- sourceFallback은 허용 계획과 조회 지점의 typed gap, 정상 INSUFFICIENT_EVIDENCE, 명시한 대상, 완전한 조회와 남은 예산을 모두 확인한 뒤 같은 실행의 원문 경로를 재사용한다. 현재값/명시적 부정의 정상 답변, collection·집계·명확화·부분/절단·자료 변경/원문 오류·실행 실패는 자동 전환하지 않는다. 세계관 전체 이름의 완전한 빈 조회에서만 공백으로 구분된 해당 분류 표시어를 한 번 제거해 같은 분류로 재조회한다. 기존 후보·권한·분류·범위를 우회하지 않는다.
- 계획 복구는 requestedParts의 주체·대상·전후·인과 범위와 최초 획득 조건을 유지하고 schema 필드 경로·고정 규칙만 재전달한다. 형식/계약/JSON/출력 절단을 구분하며 최초 포함 두 번과 기존 예약·정산을 유지한다. 원시 질문·원문·모델 응답·거절값·비밀값·임의 예외 본문을 진단 로그에 저장하지 않고, 구조 복구 성공을 의미적 정답으로 간주하지 않는다.
- 일반 claim의 relatedFacets는 관련 원문 맥락이며 요구 충족이 아니다. 미답 요구는 missingFacets와 PARTIAL로 남기고 정확한 supports.quote·참조/위치·최종 인용 검사를 유지한다. hybrid.answer의 ResponseValidationFailure만 미검증 문장을 버린 NONE으로 복구해 남은 검색이나 검증된 PARTIAL을 보존한다. 전송/provider·출력 절단 복구·권한/조회 실패는 숨기지 않으며 계획·등록 답변의 실패 경계와 전체 실행 상한은 유지한다.
- hybrid plan v8은 최초 습득 항목의 HISTORY와 현재 보유 목록의 CURRENT를 구분하고 실제 최초 획득은 원문 PROCESS로 확인하도록 지시한다. 최초 등록/사용 기록을 실제 최초로 단정하지 않는다. 일반 원문 경로의 직접 근거 조회는 `3 * max_rounds + 2 + collection 검사 수`의 도구 여유를 남기며 기본 두 라운드는 검사 수 외 8회분이다. 전체 상한을 늘리거나 키워드·벡터 실행을 보장하지 않는다. 후속 실제 검증에서 HISTORY+PROCESS와 키워드·벡터 두 라운드까지 확인했지만 최초 획득의 입증은 미확인으로 남았다. 상세 호출량·중간 실패와 SHA별 범위는 공통 품질 문서에 보존한다.
- 실제 77dc74e 검증 6건에는 자연 발생한 모델 응답 검증 실패가 없었다. related 맥락 경로의 실제 확인과 typed 검증 실패 복구의 합성 회귀를 구분한다. 최종 265fc68는 같은 프롬프트·검색·검증에 안내 표현만 보완했으며 전체 자동 테스트·로컬 기동으로 확인했고 실제 모델을 다시 호출하지 않았다.

### GH287 고정 보류 참고와 Worker 기능 광고

- 앞선 미확정 후보는 원래 freeze 시점에 optional `selection`을 함께 저장하고 replay에서는 `automaticInputState`만 읽는다. 기존 입력/hash를 backfill하거나 현재 후보로 재구성하지 않는다. 주체 해소의 MATCHED/actual·provisional 연결은 후속 내용 비교 실패와 구분해 보존하고, 모호/실패한 주체 연결·실패한 operation 대상은 권위 있는 링크로 복원하지 않는다. 원래 schema로 held/failed STATUS를 판별하고 active=false를 보존한다.
- `X-Analysis-Reference-Selection: true`를 받은 응답에만 `selection`과 캐릭터 advisory의 `selectionNames`를 노출한다. 누락/false에는 필드 자체를 생략하며 모든 claim/context/주체 조회에 같은 boolean을 명시적으로 전달한다. metadata freeze·advisory hash/contextToken은 capability와 무관하고 구 overload는 false다. `selectionNames`는 기존 frozen 주체 별칭·원 후보 표기를 전달하는 선택 전용 metadata다.
- AI는 EPISODE의 모델 입력만 같은 도메인·관련 주체로 투영하며 전체 transport/equality/source/lease/journal을 유지한다. 보류는 현재값·선택 대상·변경/의존 참조·과거 후보 자동 승인이 아니다. 1차 캐릭터 입력은 기존 이름/활성 STATUS와 별도 미확정 이름/STATUS 힌트, 세계관은 원문·metadata·정적 규칙만 사용하며 BOOK 입력과 기존 시점 보호를 유지한다.

- GH310 query embedding 예약은 QUERY 역할에만 typed queryInput을 요구하고 V95 immutable validation_call_input으로 원 호출 identity를 고정한다. UTF-8 query hash는 trim/정규화 없이 실제 provider 입력과 같아야 하며 현재 input/manifest/기존 index model/version/1536 차원을 대조한다. retry는 현재 VECTOR 의무·완전 READY receipt·원 SETTLED SUCCESS usage와 당시 완전한 index 처리 범위를 검증해 새 embedding이 필요한 소진 실행만 queue 전에 거절한다.
- 같은 입력의 FOLLOW_UP은 영향 check 전부를 원자 revise하고 보조 checkpoint version만 전진시켜 성공 lookup/book 근거를 보존한다. 현재 서버 check revision/digest/outcome만 완료 권위이며 source/report 무효화·물리 파기의 사본 삭제는 그대로 유지한다.
- 실제 Python 실행 연결 검증은 ValidationExecutionHttpPostgresIntegrationTest와 AI tests/support/validation_execution_http_client.py를 사용한다. GH310_AI_CHECKOUT/GH310_PYTHON 및 guarded loopback DB를 명시하고 provider/S3만 합성한다. 성공 모델 추가 호출0과 추출/후보0을 실제 counter/DB로 확인하며 모델 품질·운영 배포 결과로 보고하지 않는다.

- 성공 VECTOR 조회의 JUDGE-only 재개는 live index 재구축 자체에 의존하지 않는다. 원래 완전 READY receipt·원 호출 identity·현재 input/check 의무와 leased source/hash 검증을 유지하고, 원래 실패/부분/미준비 receipt는 재사용하지 않는다.
