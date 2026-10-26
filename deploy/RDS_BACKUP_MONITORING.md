# RDS 자원·백업 수집과 Discord 알림

관련: [#174](https://github.com/catchhole-soma/catchhole-backend-java/issues/174) · [백업 정책](../docs/rds-backup-policy.md) · [기존 모니터링 배포](MONITORING_DEPLOYMENT.md)

## 범위와 현재 상태

2026-10-26 **00:38:03 KST 운영 DB용 알림 규칙 3개를 활성화했고, 00:38:40 KST 평가에서 모두 Normal임을 확인했다.** 운영 RDS 조회 → exporter → Prometheus 수집은 전날 연결했으며, 승인 후 전용 웹후크와 `catchhole-infra-discord` contact point를 연결하고 합성 발생·복귀 메시지의 실제 수신을 확인했다. 세 운영 규칙은 `isPaused=false`이며 평가 API와 Grafana 화면에서 정상 평가 상태를 확인했다.

이어 같은 날 **01:13:11~01:43:07 KST 운영 규칙과 같은 조건의 모의 입력 시험 15단계를 완료했다.** 지연 경보 발생·유지·복귀, 필수 지표 누락·timestamp 동결·HTTP 503의 수집 경보와 복귀를 확인했다. 최종 분석의 15개 검사도 통과했으며 **01:43:43 KST 시험 자원 정리 완료**, 운영 target 9개와 기존 규칙·설정 보존을 확인했다. 01:44:45 KST 운영 대상만 조회한 최종 관측에서도 수집은 정상이었다.

같은 날 #174의 나머지 범위인 **일일 자동 스냅샷·백업 보존 설정·DB CPU·저장 공간·연결 수** 수집을 운영에 추가했다. 아래 현황을 구분한다.

| 구간 | 확인 상태 |
| --- | --- |
| LRT 주의·긴급·수집 상태 3개 | 위 시각에 운영 활성화·발생/유지/복귀·실제 Discord 수신 검증 완료 |
| 현재 DB 한도·앱 연결 예산·최근 7일 자원 기준선 | 2026-10-26 읽기 전용 조회 완료. 아래 기준선에 기록 |
| 추가 11개를 포함한 총 14개 규칙 | 확장 수집기 운영 적용 및 전체 PromQL의 유효한 정상값 조회 확인. 기존 3개 활성·추가 11개 일시정지 상태로 등록. **추가 11개의 발생/복귀 검증과 운영 활성화는 진행 중** |
| 실제 복원·데이터·서비스 검증 | #175의 별도 작업. RPO 15분·RTO 2시간은 목표이며 이번 경보 시험으로 입증하지 않음 |

이 문서의 초기 배포·15단계 시험 기록은 기존 3개 규칙에 대한 증거다. 추가 규칙의 검증 결과는 마지막 완료 점검표에 별도로 기록하며, 계획과 기존 성공 기록만으로 #174 전체를 완료 처리하지 않는다.

## 운영 적용과 관측 기록

확인 시각은 **2026-10-25 14:31:10 UTC / 23:31:10 KST**다. 아래 값은 배포 직후의 관측이며 장기간 정상 동작이나 실제 복원 성공을 증명하지 않는다.

| 적용·확인 항목 | 결과 |
| --- | --- |
| 실행 권한 | 기존 모니터링 EC2 역할에 `CatchHoleRdsBackupRead` 인라인 정책 추가. 대상 DB 하나의 `rds:DescribeDBInstances`만 허용하고 기존 권한 유지 |
| 배포 설정 | 실제 env를 보존하고 `RDS_BACKUP_DB_IDENTIFIER` 추가. 기존 monitoring·tracing Compose에 신규 RDS overlay만 적용 |
| 서비스 변경 | exporter 시작, Prometheus에 RDS job을 추가한 뒤 Prometheus만 재생성 |
| 기존 수집 대상 | API 1개 + Worker 7개 모두 `up=1` 유지 |
| 신규 수집 대상 | RDS exporter `up=1`; 전체 정상 target 9개 |
| API 응답·LRT 유효성 | `api_success=1`, `lrt_valid=1` |
| 수집 상태 계산 | `collection_unhealthy=0` — 해당 관측에서 수집 신뢰 조건을 만족. Grafana 경보가 실행됐다는 뜻은 아님 |
| 복원 가능 시각 지연 | **288.453초**, 약 4분 48초 |
| 마지막 유효 관측 경과 시간 | **42.141초** |
| exporter 조회 집계 | `poll_total=3`, `poll_errors_total=0` |
| exporter 메모리 | **46.88MiB / 192MiB 제한** |
| Prometheus 저장 | 기존 TSDB 볼륨과 **30d / 5GB** 설정 유지. 두 제한 중 먼저 도달한 기준으로 과거 블록을 정리하므로 30일 데이터 보존을 보장하는 뜻은 아님 |
| RDS 보호 설정 | 자동 백업 **7일**, 삭제 보호 설정 변경 없음 |
| 배포 전 백업 위치 | 서버의 `/opt/catchhole-monitoring/.rollout-gh174-20261025` |

### Discord 연결과 합성 발생·복귀 검증

2026-10-26 KST 사용자 승인 후 아래 전송 경로를 연결했다. 웹후크 URL과 인증값은 문서·저장소에 기록하지 않는다.

| 설정·확인 항목 | 결과 |
| --- | --- |
| 수신 채널 | Discord `CatchHole 운영 알림` 서버의 `#인프라-알림` |
| 전용 웹후크 | `CatchHole 인프라 알림` 생성 |
| Grafana contact point | `catchhole-infra-discord`, UID `dg2b0s7utbh1cf` |
| 복귀 메시지 | resolved 전송 활성화 |
| 전송 시간 | 야간을 포함한 24시간 |
| 전송 간격 | `group_wait=0s`, `group_interval=1m`, `repeat_interval=15m` |
| 합성 테스트 규칙 | `rds-channel-test-0e1fab777b` |
| 발생 메시지 수신 | **2026-10-26 00:34 KST**, `FIRING`, `A=1`, `B=1` |
| 복귀 메시지 수신 | **2026-10-26 00:36 KST**, `RESOLVED`, `A=0`, `B=0` |
| 평가 결과 확인 | **2026-10-25 15:36:50 UTC / 2026-10-26 00:36:50 KST**, API에서 테스트 규칙 `Normal` 확인 |

합성 테스트는 고정된 시간 식의 값이 시간 경과로 **1 → 0**이 되도록 구성했다. 규칙을 수정·정지·삭제해 복귀 메시지를 만든 것이 아니며, 같은 식의 평가 결과 변경에 따라 Grafana가 발생과 복귀를 판정하고 실제 채널에 전달했다. 운영 DB나 백업에는 영향을 주지 않았다.

이 결과는 **Grafana의 합성 조건 평가 → 발생·복귀 판정 → 지정된 Discord 채널 수신**을 확인한 증거다. 실제 백업 지연·AWS 조회 오류를 운영에서 발생시킨 시험, 모든 RDS 규칙의 장애 전이 검증, 15분 재알림 실측, 실제 DB 복원 또는 RPO·RTO 달성의 증거는 아니다. 합성 규칙은 정상값 복귀와 Discord 수신을 확인한 뒤 삭제했다.

### 운영 규칙 활성화

**2026-10-25 15:38:03 UTC / 2026-10-26 00:38:03 KST**, 복원 가능 시각 주의·긴급·수집 상태의 운영 규칙 3개 모두 `isPaused=false`임을 확인했다.

활성화 후 세 규칙의 마지막 평가 시각은 모두 **2026-10-25 15:38:40 UTC / 2026-10-26 00:38:40 KST**였다. API에서 각각 `health=ok`, `lastError=null`, 알림 인스턴스 `Normal`을 확인했고 Grafana 화면에서도 3개 모두 Normal이었다. 이때 API의 `state=inactive`는 경보 조건이 발생 중이지 않다는 뜻이며 규칙 일시정지 상태를 뜻하지 않는다.

활성화 직전에는 다음 운영 수집 상태를 확인했다.

| 항목 | 결과 |
| --- | --- |
| 수집 대상 | API 1개 + Worker 7개 + RDS exporter 1개, **9개 모두 up** |
| AWS 조회·LRT 유효성 | `api_success=1`, `lrt_valid=1` |
| 수집 상태 계산 | `collection_unhealthy=0` |
| 복원 가능 시각 지연 | **344.287초**, 약 5분 44초 |
| 마지막 유효 관측 경과 시간 | **39.99초** |
| exporter 조회 집계 | `poll_total=69`, `poll_errors_total=0` |
| exporter 메모리 | **46.15MiB / 192MiB 제한** |

### 운영 규칙과 같은 조건의 모의 입력 시험

시험 환경은 `gh174-validation-20261026-a`, 모의 DB 식별자는 `synthetic-no-db`이며 실제 DB를 만들지 않았다. 기존 모니터링 EC2 내부에 메모리 제한 **64MiB**의 모의 수집기를 두고 시험 label·제목·설명·Grafana 폴더를 운영과 분리했다. 계산식·시간 조건·**60초 평가·15초 scrape·통지 간격**은 운영 규칙과 같게 유지했다. 실제 AWS 조회 실패, WAL 전송 중단, DB 삭제·복원을 유발한 시험은 아니다.

아래는 재시작한 시험의 최종 결과다. 시험 제어기는 **01:43:07 KST**에 전체 15단계를 통과 처리했고, 별도 분석의 15개 검사도 모두 통과했다. 시각은 **2026-10-26 KST**이고 Discord 시각은 화면에 표시된 분 단위 수신 시각이다. Grafana 평가 시각과 Discord 수신 시각을 같은 시각으로 간주하지 않는다.

| 입력 변경 시각 | 모의 입력 | Grafana 평가 시각 | 관측한 상태 전이 | Discord 수신 표시 |
| --- | --- | --- | --- | --- |
| 01:13:11 | 정상 지연 300초 | 01:13:30 | 주의·긴급·수집 모두 Normal | 신규 경보 없음 |
| 01:14:01 | 지연 660초를 짧게 유지 | 01:14:30 | 새 주의 발생 없음. 주의는 `Normal (NoData, KeepLast)` | 신규 지연 경보 없음 |
| 01:15:01 | 짧은 초과 직후 API 실패 상태 | 01:17:30 | 수집 경보 발생. 주의·긴급은 기존 Normal 유지 | **01:17 수집 FIRING** |
| 01:17:32 | 정상 수집·지연 300초 | 01:18:30 | 수집 경보 복귀, 세 규칙 모두 Normal | **01:18 수집 RESOLVED** |
| 01:18:52 | 지연 660초 지속 | 01:21:30 | 주의 발생. 입력 변경 후 약 **157.3초** | **01:21 주의 FIRING** |
| 01:21:43 | 지연 960초 | 01:22:30 | 긴급 발생, 주의 유지. 입력 변경 후 약 **46.8초** | **01:22 긴급 FIRING** |
| 01:23:03 | 경보 중 API 실패 상태 | 01:25:30 | 수집 경보 발생. 주의·긴급은 `Alerting (NoData, KeepLast)`로 유지 | **01:25 수집 FIRING**, 지연 복귀 없음 |
| 01:25:33 | 지연 960초 유지·수집 재개 | 01:26:30 | **수집만 Normal**, 주의·긴급 유지 | **01:27 수집 RESOLVED** |
| 01:27:04 | 정상 지연 300초 | 01:27:30 | 주의·긴급 Normal, 수집 Normal 유지 | **01:27 주의·긴급 RESOLVED** |
| 01:28:34.55 | 필수 지표 누락 | 01:31:30 | 수집 경보 발생 | **01:31 수집 FIRING** |
| 01:31:35.10 | 필수 지표 다시 제공 | 01:32:30 | 세 규칙 모두 Normal | **01:32 수집 RESOLVED** |
| 01:33:05.382 | 관측 timestamp 동결 | 01:37:30 | HTTP `up=1`이어도 유효 관측이 오래돼 수집 경보 발생 | **01:37 수집 FIRING** |
| 01:37:36 | 관측 timestamp 갱신 재개 | 01:38:30 | 세 규칙 모두 Normal | **01:39 수집 RESOLVED** |
| 01:39:06 | 모의 수집기의 HTTP 503 | 01:41:30 | 수집 경보 발생. 입력 변경 후 약 **143.6초** | **01:41 수집 FIRING** |
| 01:41:36 | 모의 수집기 최종 정상화 | 01:42:30 | 세 규칙 모두 Normal | **01:42 수집 RESOLVED**, 시험 자원 삭제 전 확인 |

이 표의 660·960·300초는 모의 입력의 기준값이다. 평가 시에는 timestamp 생성 후 경과한 시간이 더해지므로 실제 알림 값이 기준값과 정확히 같지는 않다. 예를 들어 주의·긴급 발생 메시지의 값은 각각 약 **674.64초·974.64초**였다.

수집 복귀와 지연 복귀의 Discord 표시가 모두 01:27이지만, 평가 기록에서는 **01:26:30 수집만 복귀 → 01:27:30 지연 경보 복귀**로 구분된다. 따라서 조회가 다시 가능해졌다는 이유만으로 아직 높은 지연 경보를 정상으로 바꾸지 않았음을 확인했다. 짧은 초과 후 조회 실패 구간에서도 추가 관측 없이 주의 경보가 새로 발생하지 않았다.

timestamp 동결 시험에서는 **01:35:05.749 KST**에 마지막 유효 관측의 경과 시간이 120초를 초과한 것을 처음 읽었고, **01:37:30 KST** 평가에서 수집 경보가 발생했다. 입력 변경부터 평가까지 약 **4분 25초**다. HTTP 접근 성공만으로 정상 수집이라고 판단하지 않았으며, 120초 최신성 기준과 수집 이상 2분 지속 조건·평가 간격이 함께 작용하므로 “동결 후 2분 안에 알린다”는 의미가 아니다.

첫 시도는 시험 제어기가 `Normal (NoData, KeepLast)`를 정확히 `Normal`이라는 문자열과 비교해 짧은 초과 단계에서 시간 초과로 끝났다. **원문 상태를 보존하면서 기본 상태만 비교하도록 제어기를 보완**하고 01:13 KST경 다시 시작했으며, 알림 규칙은 바꾸지 않고 첫 시도 기록을 별도로 보존했다. `Normal (NoData, KeepLast)`는 유효한 정상 관측을 뜻하지 않는다.

최종 분석 근거는 작업 아티팩트 `rds-alert-behavior-2026-10-26/analysis/summary.json`, `analysis/review.md`, `result.json`, `cleanup-result.json`과 별도 Discord UI 확인 기록이다. 분석기는 평가 상태와 시간 조건을 검사하며 Discord 수신은 화면에서 별도로 확인했다. 입력 반영 시각은 제어기가 Prometheus에서 처음 확인한 시각이므로 정확한 최초 scrape 시각은 아니다. 상태 수집은 약 10초 간격이어서 그 사이의 모든 순간 상태를 입증하지 않는다.

### 시험 자원 정리와 운영 보존 확인

최종 정상화 평가와 Discord 복귀 메시지를 확인한 뒤 **2026-10-26 01:43:43 KST**에 시험 자원 정리를 완료했다. 정리 후 API에서 조회한 운영 규칙의 최근 평가 시각은 **01:43:40 KST**였고, 세 규칙 모두 `Normal`, `health=ok`, `lastError=null`이었다. Grafana 화면에서도 시험 규칙 없이 운영 규칙 3개만 Normal임을 확인했다.

| 정리·보존 항목 | 확인 결과 |
| --- | --- |
| 시험 규칙·Grafana 폴더 | 제거 후 없음 확인 |
| 시험 Prometheus target·target 파일 | 제거 후 없음 확인 |
| 모의 수집기 컨테이너 | 제거 후 없음 확인 |
| 기존 운영 target | 시험 전후 label·scrape URL이 정확히 같고 **9개 모두 up** |
| 운영 규칙 그룹 | 시험 전후 설정 동일 |
| 확인한 운영 설정 파일 5개 | 시험 전후 해시 동일 |
| 정리 오류 | 없음 |

**01:44:45 KST**에는 시험 환경을 제외하고 운영 대상 label로 한정해 각각 시계열 1개씩 조회했다. `api_success=1`, `lrt_valid=1`, `up=1`, 유효 관측 경과 **17.645초**, 복원 가능 시각 지연 **204.961초**, `poll_errors_total=0`을 확인했다.

이번 시험은 모의 입력으로 **실제 Prometheus·Grafana 규칙의 발생·유지·복귀와 Discord 전달**을 확인한 것이다. 실제 AWS API 장애, WAL 업로드 실패·DB 고장·복원, RPO 15분 또는 RTO 2시간 달성을 검증한 것은 아니다. **15분 재알림은 설정만 확인했으며 15분 동안 경보를 유지해 실제 재전송을 측정하지 않았다.**

## 현재 DB 한도와 자원 기준선

2026-10-26 운영 설정·실행 컨테이너·읽기 전용 SQL을 대조했다. 비밀번호·연결 URL·계정 ID·사설 주소는 증거 요약에 포함하지 않는다.

| 항목 | 확인값·해석 |
| --- | --- |
| DB 사양·할당 공간 | PostgreSQL 16.14, `db.t4g.small`, gp3 **30GiB** |
| DB 최대 연결 | `max_connections=181` |
| 예약 연결 | `reserved_connections=2` + `superuser_reserved_connections=3` = **5**; 세 설정 모두 `pending_restart=false` |
| 일반 연결 상한 | **181−5=176**. 예약 권한을 일반 앱 용량에 포함하지 않음 |
| API | 실행 Hikari `max=10`, `min=2` |
| 분석 Worker | 5개 × SQLAlchemy `pool_size=3`, `max_overflow=0` = **15** |
| 캐릭터·세계관 비교 Worker | 각각 `pool_size=1`, `max_overflow=0` = **2** |
| 챗봇 검색 | 1개, `CHAT_RETRIEVAL_ENABLED=true`, 별도 `pool_size=2`, `max_overflow=0` = **2** |
| 상시 앱 풀 예산 | **10+15+2+2=29**. 과거 27개에는 챗봇 검색 풀이 빠져 있었음 |
| 일회성 검색 백필 | 실행 중이면 별도 최대 **2**, 상시 앱과 동시에 실행하면 **31** |
| 설정상 미할당 부분 | **176−29=147**. 실제 남은 슬롯이나 성능 여유를 보장하는 수치가 아님 |
| 조회 시점 client backend | **11개**, 이번 감사 연결 1개 포함. 실제 연결 수는 계속 변함 |

Hikari와 SQLAlchemy의 최대값은 항상 열려 있는 연결 수가 아니다. 풀은 프로세스별이므로 replica·프로세스 수·overflow 변경 시 예산을 다시 합산한다. IntelliJ·psql·별도 migration/점검 도구와 배포 중 중복 프로세스도 연결을 소비한다. 저장소의 일반 Spring 기동 Flyway는 주 DataSource를 사용한다. 실행 API에서도 별도 `SPRING_FLYWAY_URL/USER/PASSWORD`, `SPRING_APPLICATION_JSON`, Flyway JVM/명령 override가 없음을 값 노출 없이 확인했으므로 별도 풀 10개를 가산하지 않는다. 별도 설정이나 migration 프로세스를 도입하면 다시 확인한다. [Spring Boot Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [PostgreSQL 예약 연결](https://www.postgresql.org/docs/16/runtime-config-connection.html)

최근 7일 기준선은 **2026-10-19 11:55~10-26 11:45 KST**, 지표별 **10분 구간 1,008개**다. 각 구간의 Average·Minimum·Maximum을 읽었으며, 10분 평균들의 p95를 원시 샘플의 p95나 쿼리 지연 p95로 해석하지 않는다.

| 지표 | 관측 결과 | 초기 임계값 선정 이유 |
| --- | --- | --- |
| CPUUtilization | 구간 Maximum의 최대 **26.3926%**, 10분 Average의 p95 **5.8304%** | 일상 부하와 충분히 떨어진 **80% 초과 5분**을 지속 압박의 시작 신호로 사용. 병목 확정 기준은 아님 |
| FreeStorageSpace | 구간 Minimum의 최소 **28,510,535,680 bytes**, 약 **26.55GiB** | `max(할당량의 20%, 5GiB)` 미만 5분. 현재 30GiB에서는 **6GiB**를 남기고 조사·대응할 여유 확보 |
| DatabaseConnections | 구간 Maximum의 최대 **21개** | 일반 상한 176의 80%인 **140.8 이상**, 정수 연결 수로 **141개 이상 5분**. 상시 예산 29와 별도로 DB 전체의 포화 접근을 감지 |

141개는 정상 앱 수요나 권장 풀 크기가 아니다. 현재 예산 29에 비해 큰 증가가 생기면 경보 전에도 원인을 조사할 수 있고, 이 규칙은 DB 전체 연결 포화에 대비한 최소 알림이다. Hikari의 active/max/pending과 DB 잠금·트랜잭션을 함께 봐야 하며 DB 전체 연결 여유만으로 특정 앱 풀 대기를 배제할 수 없다. CloudWatch `DatabaseConnections`는 클라이언트 네트워크 연결 지표이고 RDS 내부 연결 등을 제외하므로 SQL 세션 수와 정확히 같지 않다. [AWS RDS 지표 정의](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-metrics.html)

기준선 요약은 작업 아티팩트 `rds-monitoring-completion-2026-10-26/resource-baseline-summary.json`에 보존했다. 이 기간에 관측되지 않은 부하를 검증한 것은 아니며, 인스턴스·스토리지·풀·replica 변경 또는 지속적인 오경보가 생기면 근거와 임계값을 함께 갱신한다.

## 데이터 흐름과 판단 기준

```text
RDS DescribeDBInstances (DB 하나, 60초: LRT + 보존 기간·할당량·백업 창)
RDS DescribeDBSnapshots (DB 하나의 자동 스냅샷, 300초: 모든 페이지)
CloudWatch GetMetricStatistics (AWS/RDS 3개 지표, 각각 60초)
  → rds-backup-exporter (원천별 background 조회·검증·캐시)
  → Prometheus (15초마다 같은 /metrics 수집)
  → Grafana (60초 평가, 기존 3개 + 추가 11개 규칙)
  → 기존 catchhole-infra-discord contact point → #인프라-알림
```

LRT와 백업 설정·스냅샷은 **RDS 제어 API**, CPU·공간·연결은 **CloudWatch의 기본 AWS/RDS 지표**를 읽는다. 기존 EC2/애플리케이션 CPU 패널을 CloudWatch로 바꾸는 작업이 아니다. exporter는 DB에 SQL로 접속하지 않으며 `/metrics` 요청은 메모리 캐시만 읽는다. AWS의 WAL 보관 주기·AWS 지표 생성 주기·우리 조회/평가 주기는 서로 다르다. `LatestRestorableTime`은 AWS가 제공한 복원 가능 시점이며 원인별 WAL 전송 지표가 아니다. [AWS DBInstance API](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_DBInstance.html), [시점 복구](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PIT.html)

| 원천 | 조회·통계 | 신뢰 조건 |
| --- | --- | --- |
| LRT | DescribeDBInstances, 60초 | `up=1`, 대상 DB 응답 성공, LRT 유효, 유효 관측 나이 **0~120초**, 미래 LRT 아님 |
| 백업 설정 | 같은 DB 응답, 추가 호출 없음 | API 성공, 보존 0~35일·양수 할당량·유효 UTC 백업 창, metadata 관측 나이 **0~120초** |
| 자동 스냅샷 | DescribeDBSnapshots, 300초; 전체 페이지의 대상·유형·상태·완료 항목의 생성 시각 검증 | 전체 조회 성공·유효성, 관측 나이 **0~900초**, 미래 시각 아님 |
| RDS CPU | GetMetricStatistics, 60초 주기, **Period=60 / Average / Percent** | 해당 지표 API 성공·유효성, 유효 조회 나이 **0~120초**, AWS 원천 시각 나이 **0~300초** |
| RDS 남은 공간 | 동일, **Period=60 / Minimum / Bytes** | 같은 자원 신뢰 조건 + 백업 설정의 할당량 신뢰 조건 |
| RDS 연결 수 | 동일, **Period=60 / Maximum / Count** | 같은 자원 신뢰 조건; 일반 연결 한도는 실측 181/5를 규칙 생성 시 전달 |

자원 지표는 조회 종료 시각을 분 경계로 내림하고 그 직전 10분에서 가장 최신의 유효한 점을 사용한다. 미완성 현재 분을 포함해 값이 흔들리지 않도록 한 경계다. AWS 응답 순서를 최신순으로 가정하지 않고, 잘못된 최신 점 대신 오래된 정상값을 골라 숨기지 않는다. CPU는 1분 평균이고 공간·연결 수는 각각 1분 최저·최고다. “5분 지속”은 신뢰 조건을 만족한 관측값의 연속 조건이며 매 순간 CPU가 80%를 넘었다는 뜻은 아니다.

백업 설정·LRT·스냅샷·자원은 독립적으로 유효성을 판단한다. 예를 들어 보존 0일로 LRT가 없어져도 유효한 보존 0일 경보는 낼 수 있고, 스냅샷 API 실패가 정상 LRT까지 무효로 만들지는 않는다. 자원은 지표별로 독립 조회하므로 CPU API 실패가 공간/연결의 성공 상태를 덮어쓰지 않는다. 실패 시 이전 값은 진단용으로 남기되 성공·유효 플래그를 내리고 시각을 갱신하지 않는다. 최초 정상값이 없는 timestamp는 생략하며 미관측 값을 0으로 채우지 않는다.

### 14개 규칙

| 규칙 | 조건 | 경보 등급·시간 |
| --- | --- | --- |
| LRT 주의 | 현재 시각−LRT **>600초** | warning, 연속 유효 초과 **2분** |
| LRT 긴급 | **>900초** | critical, 추가 대기 없음 |
| LRT 수집 상태 | LRT 신뢰 조건 불충족 | warning, **2분** |
| 자동 백업 비활성 | 유효한 보존 기간 **0일** | critical, 추가 대기 없음 |
| 정책보다 짧은 보존 | **1~6일** | warning, 추가 대기 없음 |
| 정책보다 긴 보존 | **7일 초과** | info, 추가 대기 없음. 보호 약화가 아닌 변경 사유·비용 점검 |
| 설정 수집 상태 | metadata 신뢰 조건 불충족 | warning, **2분** |
| 일일 스냅샷 완료 확인 지연 | 가장 최근 마감된 백업 창의 시작 이후 생성된 `available` 자동 스냅샷 없음 | warning, 마감은 **백업 창 종료+1시간**, 추가 대기 없음 |
| 최신 자동 스냅샷 실패 | 수집기가 관측한 최신 완료 상태(`available`/`failed`, 생성 시각 있음)가 `failed` | warning, 추가 대기 없음 |
| 스냅샷 수집 상태 | 전체 스냅샷 목록 신뢰 조건 불충족 | warning, **2분** |
| CPU 압박 | 유효 CPU **>80%** | warning, 연속 **5분** |
| 저장 공간 부족 | 유효 남은 공간 **<max(할당량×20%, 5GiB)**; 현재 **6GiB 미만** | warning, 연속 **5분** |
| 연결 포화 접근 | 유효 연결 수 **≥(181−5)×80%=140.8**; 정수로 **141개 이상** | warning, 연속 **5분** |
| 자원 수집 상태 | CPU·공간·연결 중 하나라도 해당 신뢰 조건 불충족 | warning, **2분** |

LRT의 10분 주의는 기존 30분 관측 최대 약 8분 17초와 RPO 15분 목표 사이의 초기값이다. 10분·15분과 같은 값은 초과가 아니다. CPU 80%·공간 6GiB와 같은 값도 해당 경보에 포함되지 않으며 연결은 이상 조건이다. 연결 한도·예약 설정을 변경하면 규칙을 다시 생성해야 한다. 공간 기준은 현재 유효 할당량을 사용한다.

현재 백업 창은 **UTC 16:35~17:05 / KST 01:35~02:05**, 완료 확인 마감은 **KST 03:05**다. 1시간은 조사 시작을 위한 운영 여유이며 AWS가 약속한 완료 시간은 아니다. `SnapshotCreateTime`은 생성 시각이지 완료 시각이므로 **상태가 available인지 함께 확인**한다. 마감 전에는 직전 마감 창을 계속 검사하고, 다음 날이나 다음 백업 창이 열렸다는 이유만으로 미완료 경보를 자동 해제하지 않는다. 창 종료를 넘어 백업이 계속될 수 있고 높은 부하로 백업이 생략될 수도 있으므로, 경보 자체를 복구 불가능이나 데이터 유실로 단정하지 않는다. [AWS 자동 백업 창](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_ManagingAutomatedBackups.html)

스냅샷 실패 규칙은 **이 프로세스가 관측한** 생성 시각이 있는 최신 완료 상태(`available`/`failed`)와 그 시각을 메모리에 보존한다. `creating/copying/deleting`은 생성 시각이 아직 없어도 정상적인 진행 상태로 취급하지만 기존 실패를 해제하지 않는다. 실패 항목이 목록에서 사라져 오래된 available만 남거나 전체 목록이 비어도 실패를 해제하지 않으며, **관측한 실패보다 생성 시각이 엄격히 뒤인 available**이 있어야 해제한다. 같은 시각의 available은 해제 근거가 아니다. 빈 목록의 백업 없음과 일일 마감 조건도 별도로 평가한다.

이 캐시는 영속 이력이 아니어서 **exporter 프로세스 재시작 시 이전 실패 기억은 사라진다**. 5분 조회 사이에 발생했다가 사라져 한 번도 관측하지 못한 실패도 모두 탐지하지는 못한다. 일일 완료 확인·LRT 감시로 보완하되 이를 완전한 실패 이벤트 원장으로 설명하지 않는다. 상세 원인과 경과는 RDS 이벤트·설정 변경 기록을 대조한다.

실제 장애 조건 규칙은 관측 불가 시 **KeepLast**, 네 종류 수집 상태 규칙은 NoData/Error 시 **Alerting**을 사용한다. LRT 주의와 자원 5분 조건은 Grafana Pending 대신 PromQL 연속 이력으로 계산한다. 수집 실패·누락은 연속 이력을 끊으며, 높은 값이 재개됐으나 이력이 부족하면 이미 발생한 경보를 정상으로 뒤집지 않는다. 정상 복귀는 새 유효값으로 해당 조건 해소를 확인했을 때다. 자세한 계산은 [규칙 안내](monitoring/rds-backup-alerts/README.md)를 따른다. [Grafana No Data와 Error](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/nodata-and-error-states/)

Grafana 평가 60초·scrape 15초·원천 지연·통지 시간이 더해지므로 추가 대기 없음이 즉시 도착을 뜻하지 않는다. 스냅샷의 900초 유효성 기준에 수집 경보 2분과 평가 시간이 더해질 수 있다. LRT 주의·긴급은 독립 규칙이라 함께 유지될 수 있다. 전송은 **24시간 / group_wait 0초 / group_interval 1분 / repeat_interval 15분 / resolved 켬**이며, 전역 notification policy 대신 이 전용 규칙에만 설정한다.

## 직접 확인할 지표

Grafana Explore에서 `catchhole-prometheus`를 선택한다. 공통 label은 `environment`, `region`, `db_instance_identifier`이고 job은 `catchhole-rds-backup`이다. 운영 DB 하나·exporter 하나를 전제로 하며 시험 환경과 운영 label을 반드시 구분한다.

| 지표 | 의미 |
| --- | --- |
| `up{job="catchhole-rds-backup",environment="prod"}` | Prometheus→exporter HTTP 접근. AWS 조회 성공과 별개 |
| `catchhole_rds_backup_api_success`, `catchhole_rds_backup_lrt_valid` | 대상 DB 조회 성공 / LRT 값 유효 여부 |
| `catchhole_rds_backup_latest_restorable_timestamp_seconds` | 마지막 유효 LRT. 실패 시 캐시 유지하므로 단독 정상 판정 금지 |
| `catchhole_rds_backup_last_valid_observation_timestamp_seconds` | 유효 LRT를 관측한 시각. LRT가 같아도 유효 응답이면 갱신 |
| `catchhole_rds_backup_metadata_valid`, `catchhole_rds_backup_last_metadata_valid_observation_timestamp_seconds` | 백업 설정 유효성과 관측 시각 |
| `catchhole_rds_backup_retention_days`, `catchhole_rds_backup_allocated_storage_bytes` | 보존 일수 / 할당 바이트 |
| `catchhole_rds_backup_backup_window_start_seconds`, `catchhole_rds_backup_backup_window_end_seconds` | UTC 자정부터 백업 창 시작·종료까지의 초 |
| `catchhole_rds_snapshot_api_success`, `catchhole_rds_snapshot_valid` | 모든 페이지를 읽고 검증했는지 |
| `catchhole_rds_snapshot_has_available` | 유효한 전체 목록에 available 자동 스냅샷이 하나라도 있는지 |
| `catchhole_rds_snapshot_latest_available_timestamp_seconds` | available 자동 스냅샷 중 최신 **생성 시각** |
| `catchhole_rds_snapshot_latest_snapshot_failed` | 프로세스가 관측한 최신 완료 상태가 failed인지. 더 늦은 available 전까지 실패 유지; 메모리 캐시는 재시작 때 초기화 |
| `catchhole_rds_snapshot_last_valid_observation_timestamp_seconds` | 마지막 유효 전체 목록 관측 시각 |
| `catchhole_rds_resource_cpu_percent` | 최근 유효 CPU 1분 평균 |
| `catchhole_rds_resource_free_storage_bytes` | 최근 유효 남은 공간 1분 최저값 |
| `catchhole_rds_resource_database_connections` | 최근 유효 연결 수 1분 최고값 |

자원 지표 각각에는 `_api_success`, `_valid`, `_source_timestamp_seconds`, `_last_success_timestamp_seconds`, `_last_poll_timestamp_seconds`, `_poll_total`, `_poll_errors_total`, `_invalid_total`이 붙는다. 예를 들어 CPU의 원천 시각은 `catchhole_rds_resource_cpu_percent_source_timestamp_seconds`다. API 성공과 유효 데이터 확보를 분리해 API가 200을 반환해도 빈/오래된 결과를 정상으로 오인하지 않는다. 각 원천의 오류 횟수와 poll 시각은 진단에 사용한다.

계산식은 [생성기](monitoring/rds-backup-alerts/README.md)의 `queries.json`으로 확인한다. `/healthz`는 HTTP 프로세스 생존 확인이며 upstream 성공이나 수집 최신성을 뜻하지 않는다.

## 권한과 비용

기존 모니터링 EC2 역할의 SSM·Tempo 권한을 보존하고 [정책 예시](monitoring/rds-backup-read-policy.json.example)에 필요한 조회 권한만 추가한다. DB 로그인·개인 AWS 키·SSO 캐시를 컨테이너에 넣지 않는다.

| 권한 | 범위·이유 |
| --- | --- |
| `rds:DescribeDBInstances` | 대상 DB ARN 하나. LRT·보존·할당량·백업 창 조회 |
| `rds:DescribeDBSnapshots` | 대상 DB 및 해당 자동 스냅샷 ARN prefix. 코드도 대상 DB·자동 스냅샷으로 제한 |
| `cloudwatch:GetMetricStatistics` | `Resource: *`와 `aws:RequestedRegion` 조건. 이 API는 리소스별 ARN 제한을 지원하지 않으므로 코드의 DB/지표 선택을 IAM의 DB별 격리와 혼동하지 않음 |

수정·삭제·복원·PutMetricData·CloudWatch 알람 생성 권한은 추가하지 않는다. 실제 역할로 세 종류 조회가 성공하는지 검증하고, 실패하면 권한·리전·IMDS·네트워크를 나누어 확인한다. IMDS나 SG를 추측으로 바꾸지 않는다. [RDS 권한 범위](https://docs.aws.amazon.com/service-authorization/latest/reference/list_rds.html), [CloudWatch 권한 범위](https://docs.aws.amazon.com/service-authorization/latest/reference/list_cloudwatch.html)

CloudWatch 조회는 **3개×분당 1회×60×24×30 = 129,600회/30일**의 정상 주기 계산이다. 재시도·기동·수동 검증 요청은 추가될 수 있다. GetMetricStatistics는 월 100만 API 요청 무료 구간 적용 대상이지만 다른 사용량과 공유하므로 **추가 비용 0을 보장하지 않는다**. 별도 CloudWatch 알람·custom metric·새 EC2는 추가하지 않고 기존 Prometheus/Grafana를 사용한다. 수집기의 메모리·CPU·네트워크와 TSDB 추가 시계열은 기존 서버 자원을 소비하므로 배포 전후 사용량을 확인한다. [CloudWatch 비용 안내](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/cloudwatch_billing.html), [요금](https://aws.amazon.com/cloudwatch/pricing/)

RDS metadata는 기존 분당 1회 호출에 필드를 더 읽어 호출 수를 늘리지 않는다. 스냅샷은 5분마다 전체 목록을 조회해 한 페이지일 때 **8,640회/30일**, 여러 페이지이면 그만큼 늘어난다. 이 요청 계산은 백업 저장 비용이나 AWS 전체 청구액의 추정이 아니다.

## 운영 배포와 확인 절차

1. 현재 운영 DB·리전·EC2 역할, 전용 Grafana 그룹과 contact point를 재확인한다. 기존 3개 규칙·UID·활성 상태, Compose·실제 env·target·이미지·IAM 추가 정책을 권한 제한된 경로에 백업한다. 원문 env·webhook·토큰을 출력하거나 커밋하지 않는다.
2. 최소 조회 권한을 먼저 추가한다. 기존 `AWS_REGION`, `RDS_BACKUP_DB_IDENTIFIER`는 보존한다. 새 RDS overlay는 `ENABLE_SNAPSHOT_COLLECTION=true`, `ENABLE_RESOURCE_COLLECTION=true`를 명시한다. exporter 자체 기본값은 false지만 이 overlay를 적용하면 새 API 조회를 시작하므로 권한 준비 뒤 적용한다. 별도 env 키가 자동으로 치환된다고 가정하지 않는다.
3. `config --quiet`와 고정 버전 이미지 빌드·검사를 통과한 뒤 **exporter만** 교체한다. 현재 이미 존재하는 RDS mount/job/target을 재사용하므로 이번 확장만을 위해 Prometheus·Grafana·API·Worker를 재생성할 필요는 없다.

```sh
cd /opt/catchhole-monitoring
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml -f compose.rds-backup.prod.yml config --quiet
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml -f compose.rds-backup.prod.yml build rds-backup-exporter
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml -f compose.rds-backup.prod.yml up -d --no-deps rds-backup-exporter
```

4. 새 API 성공·유효성·원천 시각·캐시를 RDS/CloudWatch 읽기 결과와 대조한다. 컨테이너는 **192MiB 제한·non-root·read-only·외부 포트 없음**을 유지한다. 신규 값뿐 아니라 기존 LRT·API 1개·Worker 7개·RDS exporter target 총 9개, TSDB 볼륨과 실제 **30d/5GB** 유지 여부를 확인한다.
5. [규칙 생성](monitoring/rds-backup-alerts/README.md)에서 `--include-extended --max-connections 181 --reserved-connections 5`를 지정해 14개 JSON을 검토한다. 기본 출력은 모두 paused다. 기존 3개가 활성인 전용 그룹을 갱신할 때 **paused 14개를 그대로 PUT해 기존 감시를 정지하지 않는다**. 기존 UID/활성 상태를 보존하고 추가 11개만 paused로 저장하거나, 전체 검토 후 명시적으로 활성화한 그룹을 적용한다. 그룹 전체를 교체하는 API라는 점에 유의한다.
6. 별도 시험 label·모의 값으로 새 규칙의 경계·지속·누락·발생/복귀와 실제 Discord 수신을 검증한다. 운영 연결을 고갈시키거나 디스크를 채우거나 보존 기간을 바꿔 시험하지 않는다. 시험 임계값/규칙을 운영에 남기지 않고 운영 임계값·UID·활성 상태를 다시 읽어 확인한다.
7. 최종 평가 `Normal/health=ok/lastError=null`, 수집 상태, 전송 설정, 시험 자원 정리와 기존 대상 보존을 기록한다. 이 확인이 끝나기 전에는 추가 11개 적용 완료나 #174 종결을 선언하지 않는다.

최초 설치라면 별도로 `monitoring/targets/rds-backup/` 디렉터리와 예제 target을 설치하고 `promtool check config`를 실행한다. 새 directory mount가 생기므로 그때만 Prometheus 재생성이 필요하고 짧은 수집 공백이 생긴다. 기존 volume을 보존하며 전체 stack `down`·`down -v`는 사용하지 않는다. 이후 target 파일 변경은 file discovery가 반영한다.

```sh
cp monitoring/targets/rds-backup/target.json.example monitoring/targets/rds-backup/target.json
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml -f compose.rds-backup.prod.yml run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml -f compose.tracing.prod.yml -f compose.rds-backup.prod.yml up -d --no-deps --force-recreate prometheus
```

## 알림을 받았을 때

먼저 본 사람이 Discord에 **“확인·대응 시작 / 대상 경보 / 확인한 시각”**을 남기고 조사 결과와 다음 조치를 이어서 기록한다. 다른 팀원은 같은 파괴적 조치를 중복 실행하지 않도록 채널 기록을 확인한다. 알림은 야간에도 보내지만 야간 즉시 응답·24시간 온콜을 보장하지 않는다. 미응답은 15분 반복 알림으로 다시 알리며, 그것만으로 사람의 확인이나 RTO 2시간 달성이 보장되는 것은 아니다.

| 경보 | 먼저 확인할 것·초기 조치 |
| --- | --- |
| LRT 지연 | 수집 건강 → RDS 콘솔의 LRT → 보존/DB 상태·최근 백업 이벤트·AWS 상태·최근 변경을 대조. 디스크 부족이나 WAL 장애로 단정하지 않음 |
| 보존 0일 / 1~6일 | 실제 설정과 변경 이력·승인 사유를 확인. 정책 7일 복원 필요성과 적용 영향을 검토한 뒤 변경하며 경보 수신만으로 즉시 재설정하지 않음 |
| 보존 7일 초과 | 보호 약화 경보가 아님. 승인된 연장인지와 비용·원래 정책 문서의 갱신 필요성을 확인 |
| 스냅샷 완료 지연 / 실패 | RDS → 스냅샷 → 시스템에서 최신 생성 시각·상태, 자동 백업 창과 이벤트 확인. creating 지속·백업 생략·조회 실패를 구분하고 LRT로 PITR 최신성도 별도 확인 |
| CPU | 해당 1분 평균·최근 추세와 연결/잠금·긴 트랜잭션·배포/배치·Hikari 대기를 함께 확인. 크레딧·IO 등 추가 원인 지표는 진단에 활용하며 CPU 하나로 증설 결정하지 않음 |
| 저장 공간 | 실제 할당량/남은 공간·증가 속도, WAL/테이블·index·임시 작업 원인과 최근 변경 확인. 백업·업무 데이터를 임의 삭제하지 않고 용량 조정의 시간·비용·제약을 검토 |
| 연결 | CloudWatch 전체 연결 추세와 SQL client backend 집계·앱 풀/replica·관리 세션 확인. Hikari pending/active와 lock을 함께 확인하고 풀이나 DB max_connections를 무조건 늘리지 않음 |
| 수집 상태 | `up` → 해당 원천 API 성공 → 유효성 → 관측 시각/원천 시각 순서. IAM·IMDS·API 접근·exporter 오류 분류·Prometheus/Grafana 조회를 확인. 캐시값으로 정상 판정하지 않음 |
| 복귀 메시지 | 새 유효값으로 해당 조건이 해제됐는지 확인. 수집 복귀·스냅샷 available·LRT 회복·서비스 복구는 서로 다른 사실로 기록 |

추가 SQL이 필요하면 기존 접속 설정을 내부에서만 사용하고 읽기 전용 트랜잭션·짧은 timeout을 적용한다. `pg_settings`의 연결 설정과 `pg_stat_activity`의 backend_type/state별 **집계**만 우선 확인하며 쿼리 원문·사용자·client IP·DB 비밀번호를 출력하지 않는다. 점검 연결 자체가 연결 수에 포함됨을 기록한다.

모니터링 EC2/Grafana 전체 중단이나 Discord 전달 경로 자체 장애를 같은 Grafana만으로 보장할 수 없다. **외부 감시·전송 경로 정기 확인은 후속 범위**로 남긴다. #174의 완료는 정한 RDS 감시·전달 경로의 검증이며 모든 단일 장애점 제거, 실제 복원 성공, RPO/RTO 달성을 뜻하지 않는다. #175에서는 격리 복원과 데이터/schema/API·Worker 검증, 소요 시간 및 운영 전환 같은 미측정 구간을 구분한다.

## 검증과 완료 점검

기존 exporter의 LRT/cache/HTTP·SDK Stubber 12개 시험과 위 15단계 운영 경보 시험은 **확장 전 범위**다. 추가 수집과 14개 규칙은 해당 변경을 포함한 테스트를 다시 실행하고 결과를 별도로 기록한다.

```sh
docker build -t catchhole-rds-backup-exporter:gh174 deploy/monitoring/rds-backup-exporter
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges --mount type=bind,src="$PWD/deploy/monitoring/rds-backup-exporter",dst=/tests,readonly -w /tests catchhole-rds-backup-exporter:gh174 python -m unittest discover -p 'test_*.py' -v
python3 -m unittest discover -s deploy/monitoring/rds-backup-alerts -p 'test_*.py' -v
```

PR CI의 `rds-monitoring` job은 Python 3.13에서 고정 SDK 의존성·Stubber 기반 exporter 시험과 Prometheus 3.13.3 promtool 기반 규칙 시험을 실행한다. 기존 Java 빌드/테스트·tracing extension/entrypoint 검증도 유지한다. 실제 CI 성공은 해당 PR 결과로 별도 확인한다. main 머지에는 경로 필터가 없어 모니터링/문서 변경도 이미지 발행·API 재배포를 실행한다.

| 완료 증거 | 현재 기록 |
| --- | --- |
| DB 한도·예약·상시 29/백필 31·최근 7일 기준선 | 읽기 전용 확인 완료. 위 표와 아티팩트 참조 |
| 기존 LRT 3개 운영·전이·Discord 수신 | 2026-10-26 00:34~01:44 확인 완료 |
| 새 수집기·14개 규칙의 로컬 회귀 | **최종 결과 대기**. 기존 12개/15단계 결과로 대체하지 않음 |
| 최소 IAM·추가 원천 운영 조회·배포 전후 자원/대상 보존 | **최종 적용·확인 대기** |
| 추가 규칙 발생/지속/누락/복귀 및 Discord 수신 | **검증 대기** |
| 운영 임계값·14개 활성 상태·최종 정상 평가·시험 자원 정리 | **확인 대기** |
| #174 결과/PR 링크·Epic #119 갱신 | 위 증거를 확인한 뒤 수행 |

## 되돌리기

확장만 되돌릴 때는 **추가 11개 규칙을 먼저 정지하고 이전 3개 규칙 그룹을 복원**한다. 정지·삭제로 온 메시지는 백업 정상화로 해석하지 않는다. 백업한 기존 exporter 이미지·Compose·추가 수집 플래그를 복원하고 exporter만 재생성한다. 재빌드 전에 현재 이미지 ID/복구용 태그를 확보해 같은 `:gh174` 태그 덮어쓰기 후에도 이전 이미지로 돌아갈 수 있게 한다. LRT 수집과 기존 API/Worker target·TSDB·tracing을 보존하고 추가 권한은 다른 의존성이 없는지 확인한 뒤 제거한다.

RDS 감시 전체를 제거해야 할 때만 세 기존 규칙도 정지하고 RDS target JSON을 제거한 뒤 exporter를 중지·제거한다. 필요하면 이전 Prometheus 설정·Compose를 복원하고 Prometheus만 재생성한다. `DescribeDBInstances` 권한까지 제거하면 기존 LRT 감시도 동작하지 않는다는 점을 구분한다. 기존 SSM·Tempo 권한과 영속 볼륨은 유지한다.
