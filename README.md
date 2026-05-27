# CatchHole Backend

캐치홀의 Spring Boot 백엔드 서버입니다.

## 개발 환경

| 항목 | 내용 |
| --- | --- |
| Java | 21 LTS |
| JDK | JDK 21 |
| Spring Boot | 4.0.6 |
| Build Tool | Gradle |
| Database | PostgreSQL |
| Package | `org.monitoring` |

## 주요 종속성

| 종속성 | 용도 |
| --- | --- |
| Spring WebMVC | REST API 개발 |
| Spring Security | 인증/인가 기반 |
| Spring Data JPA | DB 연동 |
| PostgreSQL Driver | PostgreSQL 연결 |
| Validation | 요청값 검증 |
| Actuator | 헬스 체크 및 모니터링 |
| Lombok | 반복 코드 감소 |
| DevTools | 로컬 개발 편의 |
| Configuration Processor | 설정 자동완성 지원 |
| Docker Compose Support | 로컬 DB 실행 연동 |

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

## 참고

Spring Security가 포함되어 있어 초기 실행 시 기본 로그인 화면이나 `401 Unauthorized` 응답이 나올 수 있습니다. 이후 개발용 보안 설정을 추가할 예정입니다.
