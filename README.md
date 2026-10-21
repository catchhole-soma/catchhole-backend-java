# CatchHole Backend

캐치홀의 Spring Boot 백엔드 서버입니다.

## 개발 환경

| 항목 | 내용 |
| --- | --- |
| Java | 21 LTS |
| JDK | JDK 21 |
| Spring Boot | 4.0.6 |
| Build Tool | Gradle |
| Database | PostgreSQL 16 + pgvector 0.8.2, Redis 7.4.10 |
| Package | `org.monitoring` |

## 주요 종속성

| 종속성 | 용도 |
| --- | --- |
| Spring WebMVC | REST API 개발 |
| Spring Security | 인증/인가 기반 |
| Spring Data JPA | DB 연동 |
| Spring Data Redis | 휴대폰 인증 TTL·원자적 rate limit·1회 토큰 저장 |
| PostgreSQL Driver | PostgreSQL 연결 |
| Flyway | PostgreSQL schema migration 관리 |
| Validation | 요청값 검증 |
| Actuator | 헬스 체크 및 모니터링 |
| Lombok | 반복 코드 감소 |
| DevTools | 로컬 개발 편의 |
| Configuration Processor | 설정 자동완성 지원 |
| Docker Compose Support | 로컬 PostgreSQL·Redis 실행 연동 |

## 버전 선택 이유

- **Java 21 LTS**: 안정성과 라이브러리 호환성이 좋아 팀 개발에 적합
- **Spring Boot 4.0.6**: 현재 사용하는 Spring Boot 4 안정 버전
- **Java 25**는 최신 LTS지만, 실무 자료와 검증 사례가 더 많은 Java 21을 우선 선택
- **Spring Boot 3.5**는 신규 프로젝트 기준 지원 기간이 짧아 제외

## 실행

```bash
./gradlew bootRun
```

## 테스트

```bash
./gradlew test
```

## 운영 로그 확인

운영 API 컨테이너 로그는 Amazon EC2 호스트의 systemd journal에 최대 14일 또는 1GB까지 보관합니다. API 서버의 `/opt/catchhole`에서 현재 Backend 로그를 확인합니다.

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml logs --tail=200 backend
```

실시간으로 이어서 확인합니다.

```bash
sudo -u ubuntu docker compose --env-file api.env -f compose.api.prod.yml logs -f backend
```

컨테이너 재생성 전 로그를 포함한 최근 로그는 journal에서 조회합니다.

```bash
sudo journalctl CONTAINER_NAME=catchhole-backend-1 --since '1 hour ago' --no-pager
```

시간·컨테이너 ID·이미지·메시지 같은 전체 필드는 JSON 형태로 확인할 수 있습니다.

```bash
sudo journalctl CONTAINER_NAME=catchhole-backend-1 -n 1 -o json-pretty
```

Caddy와 Redis는 컨테이너 이름을 각각 `catchhole-caddy-1`, `catchhole-redis-1`로 바꿔 조회합니다. 현재 journal 사용량과 상세 운영 절차는 [API Amazon EC2 배포 문서](deploy/EC2_DEPLOYMENT.md)를 참고합니다.

## 참고

로컬 Collector·Tempo·Grafana와 Python Worker→Spring HTTP·JDBC 연결 검증은 [로컬 트레이스 환경](docs/local-tracing.md)을 참고합니다. Java 계측은 [명시적 로컬 Agent 실행](monitoring/java-agent/README.md)으로 적용합니다. 지연·재시도·취소·전송 장애 및 Head/Tail 실험은 [6단계 검증](docs/tracing-validation.md)에 정리했습니다. 운영 수신 서버는 [Trace 배포 안내](deploy/TRACING_DEPLOYMENT.md), Java Agent의 기본 OFF·활성화·롤백은 [API 추적 안내](deploy/API_TRACING.md)를 따릅니다. 설정 준비와 실제 운영 검증 완료를 구분합니다.

Spring Security가 포함되어 있어 초기 실행 시 기본 로그인 화면이나 `401 Unauthorized` 응답이 나올 수 있습니다. 이후 개발용 보안 설정을 추가할 예정입니다.

DB schema 작성 규칙과 최초 운영 전환 절차는 [Database Migration](docs/database-migration.md)을 참고합니다.

휴대폰 인증 API, Fake/SOLAPI provider와 Redis 정책은 [Auth Domain](docs/auth.md)을 참고합니다. 로컬은 `SMS_PROVIDER`가 없거나 `fake`이면 인증번호 `123456`을 사용하고, `solapi`이면 실제 SMS를 발송합니다. test/e2e는 항상 Fake provider를 사용하며 운영에서는 SOLAPI 설정이 필수입니다.

- [채팅 실행·인증·사용량 계약 v1](docs/chat-execution-v1.md) — #237, 기본 비활성; 자연어 orchestration은 #238.
- [가계도 요약 조회 v1](docs/family-tree-v1.md) — #261, 유효 관계·인물의 동일 스냅샷 조회, 범위·상한·변경 감지와 공개 전 제한 백필 절차.
