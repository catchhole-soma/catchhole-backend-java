# ADR-0003: API 다중화 시 로그와 지표를 Loki·Alloy 중심으로 중앙 수집한다

| 항목 | 내용 |
| --- | --- |
| 상태 | **제안 (초안)** — [ADR-0001](0001-api-multi-instance-ecs-fargate.md)이 채택될 때 유효 |
| 작성일 | 2026-10-30 |
| 작성자 | mkshie |
| 관련 이슈 | [#336](https://github.com/catchhole-soma/catchhole-backend-java/issues/336), [#119](https://github.com/catchhole-soma/catchhole-backend-java/issues/119) |

> 팀 검토 전 제안이다. Loki·Alloy·CloudWatch Logs 등 제품의 채택은 확정되지 않았다. [Infrastructure Flow](../infrastructure-flow.md)는 모니터링 구현체를 별도 ADR에서 수집 방식·보존 기간·운영 비용과 함께 비교하도록 정했으며, 이 문서는 그중 **API 로그와 지표 수집**만 다룬다. 알림 채널은 범위 밖이다.

## 1. 배경

| 구분 | 현재 상태 | 다중화 후 문제 |
| --- | --- | --- |
| 로그 저장 | Docker `journald` driver로 EC2 journald에 최대 14일 또는 1GB 보관. SSM으로 접속해 `journalctl`·`docker compose logs`로 조회([README](../../README.md)) | Fargate에는 접속해서 볼 호스트 journald가 없다. task가 교체되면 로그도 함께 사라진다 |
| 로그 형식 | 텍스트 패턴에 `requestId` MDC를 포함([application.yml](../../src/main/resources/application.yml)) | 여러 task의 로그를 요청 단위로 묶으려면 task 식별자와 검색 가능한 필드가 필요하다 |
| 중앙 수집 | 미구현([Infrastructure Flow](../infrastructure-flow.md)) | — |
| 지표 수집 | 모니터링 EC2의 Prometheus가 고정 대상 파일(`file_sd_configs`)로 API 사설 IPv4의 8081을 수집([prometheus.yml](../../deploy/monitoring/prometheus.yml), [모니터링 배포](../../deploy/MONITORING_DEPLOYMENT.md)) | task IP가 바뀔 때마다 대상 파일이 맞지 않게 된다 |
| DB snapshot 지표 | 분석 상태(15초)·서비스 이용(60초) 지표를 각 실행자가 DB에서 조회해 보고 | task 수만큼 같은 조회를 반복하고 같은 값을 보고한다. 대시보드가 합계로 집계하면 배로 보인다 |
| 기존 관측 스택 | 모니터링 EC2에 Nginx·Prometheus·Grafana, 트레이싱 구성에 Tempo(메모리 제한 512MiB)·OTel Collector(256MiB). 운영 Java Agent는 기본 OFF([API 추적 안내](../../deploy/API_TRACING.md)) | 로그만 별도 도구로 가면 지표·트레이스와 이어 보기 어렵다 |

## 2. 판단 기준

1. task가 교체되거나 종료돼도 로그가 남는다. 여러 task의 로그를 한 곳에서 requestId로 검색할 수 있다.
2. 기존 Grafana에서 지표·트레이스와 이어서 본다.
3. 장애 때 로그 수집 경로가 함께 죽어 로그를 못 보는 상황을 피한다.
4. 비용과 운영 부담이 작다.
5. 로그가 외부 사업자로 나가는지 확인한다. 로그 정책상 민감값은 남기지 않지만, 외부 전송은 개인정보 처리 관점의 검토 대상이다.

## 3. 검토한 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| A. CloudWatch Logs만 사용 (`awslogs`) | 설정이 가장 간단하고 운영할 서버가 없다 | 로그 양만큼 과금된다. Grafana에서는 CloudWatch 데이터 소스로 따로 조회하게 되고 Tempo와 바로 이어지지 않는다 | 가장 단순한 대안 |
| B. Loki + Alloy 자체 운영 | 기존 Grafana에서 지표·로그·트레이스를 함께 본다. 로그 양 과금 대신 서버·S3 비용만 든다 | Loki를 운영해야 한다. 수집 경로가 장애면 그동안 로그를 볼 수 없다 | C의 기반 |
| C. **B + CloudWatch Logs 짧은 보존 병행 (제안)** | Loki가 장애여도 CloudWatch에 원본이 남는다 | 두 경로를 설정해야 하고 일부 비용이 겹친다 | 제안 |
| D. 관리형 Grafana Cloud | 운영 부담이 없다 | 로그가 외부 사업자로 전송된다. 개인정보 처리 검토가 먼저 필요하다 | 검토 후 대안 |

Promtail은 지원이 종료되어 새 구성에 쓰지 않는다. Grafana는 Alloy를 후속 수집기로 안내한다.

## 4. 제안하는 결정

### 4.1 로그

- **수집 경로**: ECS FireLens(Fluent Bit 사이드카)로 backend 컨테이너의 표준 출력을 받아 **Loki와 CloudWatch Logs에 동시에** 보낸다. Fargate에서는 다른 컨테이너의 표준 출력을 직접 읽을 수 없어, 수집기를 사이드카로 두는 대신 FireLens가 출력을 넘겨준다.
- **CloudWatch 보존**은 Loki 장애 대비용으로 짧게(예: 3~7일) 둔다. 기간은 비용 산정 후 정한다.
- **로그 형식**: 운영에서는 Spring Boot structured logging(`logging.structured.format.console`)으로 JSON을 출력한다. requestId와 trace ID, task 식별자를 필드로 검색할 수 있게 한다. 로컬 개발 출력은 지금처럼 텍스트로 둘 수 있다.
- **Loki 위치**: 기존 모니터링 또는 트레이싱 서버에 단일 바이너리 모드로 실행하고, 로그 원본(chunk)은 S3에 저장한다. 해당 서버의 메모리 여유를 확인한 뒤 결정한다.
- Java Agent의 OTLP 로그 전송은 운영에서 Agent가 기본 OFF라 첫 단계에서는 쓰지 않는다.

### 4.2 지표

- **task마다 Alloy 사이드카**를 둔다. Alloy가 같은 task 안의 `localhost:8081/actuator/prometheus`를 수집해 Prometheus로 remote write한다. task IP가 바뀌어도 대상 파일을 고칠 필요가 없다.
- Prometheus는 remote write 수신(`--web.enable-remote-write-receiver`)을 켜야 한다. 수신 경로는 기존 Nginx를 거쳐 인증하고, 보안 그룹은 API task 대역만 허용한다.
- **DB snapshot 지표**는 [ADR-0001](0001-api-multi-instance-ecs-fargate.md)의 스케줄러 분류에 따라 한 task만 갱신하게 한다. 그 전까지는 대시보드 집계를 합계 대신 최댓값으로 바꾼다.

## 5. 결과

- **좋아지는 점**: task 교체와 무관하게 로그·지표가 남는다. Grafana 한 화면에서 Tempo의 느린 요청부터 해당 요청의 로그와 지표까지 이어서 볼 수 있다. 고정 대상 파일 관리가 사라진다.
- **대가**: Loki·Fluent Bit·Alloy 설정과 운영이 늘어난다. CloudWatch 병행 비용과 S3 비용이 생긴다. 로그 형식이 JSON으로 바뀌어 기존 journald 조회 습관과 달라진다.
- **위험**: Loki 장애나 지연 동안 Grafana에서 로그를 볼 수 없다. 이때는 CloudWatch 보존본으로 조회한다. 수집기 버퍼를 넘는 장애가 길어지면 Loki 쪽 로그가 빠질 수 있다.

## 6. 검증 기준

| 시나리오 | 목표 |
| --- | --- |
| 두 task로 나뉜 요청 | requestId 하나로 두 task의 로그를 Grafana에서 함께 조회 |
| task 강제 종료 | 종료 직전 로그가 Loki와 CloudWatch에 남음 |
| Loki 중지 | CloudWatch에 로그가 계속 쌓이고, Loki 복구 후 수집이 재개됨. 빠진 구간이 있으면 기록 |
| task 교체 | 대상 파일을 고치지 않아도 새 task 지표가 수집됨 |
| DB snapshot 지표 | task 수와 무관하게 같은 값을 보여 줌 |

## 7. 미결정·미확인

| 항목 | 상태 |
| --- | --- |
| Loki를 실행할 서버와 메모리 여유 | 미확인 |
| Loki·CloudWatch 보존 기간과 월 비용 | 미산정 |
| Prometheus remote write 인증 방식 | 미결정 |
| 운영 로그 JSON 전환 범위와 기존 조회 절차 갱신 | 미결정 |
| 관리형 Grafana Cloud 사용 시 개인정보 검토 | 미실시 |

## 8. 참고

- [Grafana Loki](https://grafana.com/docs/loki/latest/)
- [Grafana Alloy](https://grafana.com/docs/alloy/latest/)
- [Amazon ECS FireLens](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/using_firelens.html)
- [Spring Boot logging](https://docs.spring.io/spring-boot/reference/features/logging.html) — structured logging
- [Prometheus 실행 옵션](https://prometheus.io/docs/prometheus/latest/command-line/prometheus/) — remote write 수신
- [Request Logging](../request-logging.md) — requestId MDC
