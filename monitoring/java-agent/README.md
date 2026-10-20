# Spring HTTP·JDBC 트레이스 연결

이 디렉터리는 앱과 분리된 Java 21 Gradle 프로젝트다. Spring 의존성에 OTel SDK를 추가하지
않고 Java Agent와 전송 전 개인정보 제거 확장을 사용한다. 로컬은 아래 실행 도구로,
운영은 Docker 이미지에 번들한 뒤 명시적 플래그로 활성화한다. 사용자 접수부터 DB 큐를
건너는 추적 문맥 저장은 포함하지 않는다.

## 준비

Java 저장소 루트에서 다음 명령을 실행한다.

```bash
monitoring/java-agent/setup-local.sh
```

`agent.lock.json`은 공식 Agent **2.32.0**, 릴리스 체크섬과 같은 SHA-256, 해당 릴리스가 사용하는 SDK **1.66.0**을 고정한다. 다운로드는 전용 `cache/`에서 임시 파일로 받고 체크섬을 확인한 뒤 교체한다. `latest` URL을 사용하지 않는다. 일치하는 기존 파일은 재사용한다. Gradle Wrapper로 별도 프로젝트의 단위 테스트와 확장 JAR을 빌드하며 Spring 앱 테스트나 기동은 수행하지 않는다.

산출물:

- `cache/opentelemetry-javaagent-2.32.0.jar`
- `build/libs/catchhole-tracing-privacy.jar`

Agent·SDK 버전을 변경할 때는 lock의 URL·버전·체크섬·SDK 버전을 함께 검토하고 단위 테스트와 HTTP·JDBC 연결 검증을 다시 수행한다. 빌드 결과와 다운로드는 Git에서 제외한다. 확장 JAR에는 OTel SDK를 포함하지 않으며 Agent가 소유한 SDK에 SPI로 연결한다.

## 선택 실행

Spring을 평소처럼 실행하면 Agent는 적용되지 않는다. 아래 명령은 앱 JVM에만 Agent 옵션을 전달한다.

```bash
monitoring/java-agent/run-local.sh boot-run

# 이미 빌드한 별도 실행 JAR 또는 검증용 classpath:
monitoring/java-agent/run-local.sh /absolute/path/application.jar --spring.profiles.active=local
monitoring/java-agent/run-local.sh classpath "$FIXTURE_CLASSPATH" example.FixtureApplication
```

`boot-run`은 init script로 `bootRun`의 JVM 인자에만 옵션을 붙인다. `JAVA_TOOL_OPTIONS`로 Gradle daemon까지 계측하지 않는다. 실제 앱 실행은 평소 로컬 DB·서비스 설정을 사용하므로 격리 검증은 별도 합성 fixture로 수행한다. 이 문서의 classpath 예제 자체는 DB fixture를 만들지 않는다. Agent 체크섬과 확장 JAR 존재 확인에 실패하면 앱을 시작하지 않는다.

직접 Java를 실행하거나 IDE에 설정할 때의 JVM 옵션은 다음 세 가지다.

```text
-javaagent:/absolute/path/monitoring/java-agent/cache/opentelemetry-javaagent-2.32.0.jar
-Dotel.javaagent.extensions=/absolute/path/monitoring/java-agent/build/libs/catchhole-tracing-privacy.jar
-Dotel.javaagent.configuration-file=/absolute/path/monitoring/java-agent/agent-local.properties
```

테스트 수신기로 보내야 하면 `-Dotel.exporter.otlp.traces.endpoint=http://localhost:PORT/v1/traces`로 override할 수 있다. 설정 파일보다 JVM system property·OTel 환경변수가 우선하므로, 별도로 주입한 OTel 변수가 있는 환경에서는 실제 설정을 확인한다. 비밀값이 포함될 수 있는 전체 환경변수 출력은 하지 않는다.

## 수집 범위와 전송 정책

- `parentbased_always_off`: 부모가 없는 새 Java root는 수집하지 않고, 선택된 원격/로컬 부모의 자식만 수집한다. 일반 health·사용자 접수·claim에 sampled 부모 헤더가 없으면 남기지 않는다. URL 차단이나 인증 기능은 아니며, sampled 부모가 전달되면 해당 요청도 선택될 수 있다.
- HTTP server(Tomcat/Servlet), MVC route template, JDBC·DataSource, executor 문맥 지원만 선택한다. 외부 HTTP client·AWS·Redis·스케줄러 등의 자동 계측은 켜지 않는다. 선택 계측 조합의 호환성은 고정 버전에서 실제 검증해야 한다.
- OTLP/HTTP `http://localhost:4318/v1/traces`로 전송한다. Agent의 metrics/log exporter는 꺼도 기존 Micrometer→Actuator→Prometheus 메트릭과 애플리케이션 로그는 계속 동작한다.
- SDK는 Span 최대 1,024개 대기열, 최대 256개 배치, 1초 주기, export/OTLP 5초 timeout을 사용한다. 수신기 중단·큐 초과·프로세스 강제 종료 시 유실될 수 있다. API 응답을 끝낼 때 전체 큐 flush를 강제하지 않는다.

## 전송 전에 제거하는 정보

`PrivacyCustomizer`가 모든 설정된 Span exporter를 `PrivacySpanExporter`로 감싼다. 원본 SpanData를 그대로 보내는 우회 경로를 만들지 않는다. 정제에 실패한 배치는 실패 결과로 종료한다.

보존: Trace/Span/Parent ID·선택 flag·종류·시간·상태 코드, 고정 목록의 HTTP method·controller route template·100~599 응답 코드, PostgreSQL/H2와 고정 DB 명령.

제거: 원문 SQL·SQL query text·connection string·실제 URL/path/query·header·body·임의 속성·예외 message/stacktrace·모든 Span event·status description·link 속성·tracestate·원본 resource/계측 scope 메타데이터. Span 이름은 허용한 method/route 또는 DB 명령에서 새로 만든다. DB statement sanitizer도 유지하지만 개인정보 제거를 그것에만 의존하지 않는다.

Resource는 `service.name`의 `catchhole-backend` 또는 `catchhole-backend-tracing-verification`,
`deployment.environment.name`의 `local|prod`, UUID 형식 `service.instance.id`만 남긴다.
다른 서비스 이름은 `catchhole-backend`, 허용하지 않은 환경은 `unknown`으로 정규화하고
자유 인스턴스 값은 제거한다. HTTP route도 현재 token 예약/정산/해제와 Job 진행/완료/실패의
정적 내부 controller template만 허용한다. 알려지지 않은 route는 실제 URL로 대체하지 않고
이름에서 생략한다. 운영은 [별도 기동·배포 절차](../../deploy/API_TRACING.md)를 따른다.

운영 Docker 빌드는 이 프로젝트의 `test jar prepareAgent`를 실행해 lock의 SHA-256으로
Agent를 검증하고 extension·운영 설정과 함께 이미지에 넣는다. `prepareAgent` 산출물은
`build/agent/opentelemetry-javaagent.jar`이며 기존 로컬 `setup-local.sh`의 cache와 분리된다.
런타임은 `BACKEND_TRACING_ENABLED=false`가 기본이며 로컬 스크립트의 동작은 유지한다.

이 설계는 세부 SQL·예외 stacktrace를 보는 기능을 의도적으로 제공하지 않는다. 정제 전 데이터가 JVM 메모리에서 생성될 수는 있지만 OTLP exporter에는 정제된 값만 전달한다. Python SDK의 업무 Span에는 이 Java 확장이 적용되지 않으므로 Python의 개인정보 정책은 별도로 유지한다.

## 검증 범위

단위 테스트는 개인정보 canary 제거, 부모 ID·시각·상태 보존, route/명령 허용 목록, legacy JDBC 속성 정규화, root·unsampled 부모 제외, SPI 등록을 검증한다. 실제 Agent의 HTTP·JDBC instrumentation과 원본 OTLP payload·Tempo readback은 별도 통합 실험으로 확인해야 한다. 단위 테스트만으로 실제 연결 성공이나 운영 배포를 주장하지 않는다.

공식 근거:

- https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.32.0
- https://opentelemetry.io/docs/zero-code/java/agent/disable/
- https://opentelemetry.io/docs/languages/java/configuration/
- https://opentelemetry.io/docs/zero-code/java/agent/extensions/
