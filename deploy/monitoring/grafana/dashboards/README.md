# 캐치홀 운영 현황 대시보드

[catchhole-overview.json](catchhole-overview.json)은 지표 패널 12개(상단 Stat 4개·주요 API p95 비교 그래프 1개·인프라 그래프 7개)의 수동 가져오기·복원용 스냅샷이다. `인프라 · EC2 및 Java 애플리케이션` 행 제목은 지표 패널 수에 포함하지 않는다. Grafana에서 단계적으로 패널을 수정하고 저장하며, 이 디렉터리에는 자동 dashboard provisioning을 적용하지 않는다. 파일이 GUI에서 저장한 후속 변경을 덮어쓰지 않도록 하기 위한 결정이다. 서버에 JSON을 복사하거나 Grafana를 재시작하는 것만으로 대시보드가 갱신되지는 않는다.

- 대시보드 제목: `캐치홀 운영 현황`
- 대시보드 UID: `catlfln`
- 데이터 소스: `CatchHole Prometheus`, UID `catchhole-prometheus`
- JVM·API·EC2 CPU 조회 라벨: `job="catchhole-backend"`, `application="catchhole-backend"`, `environment="prod"`
- 기본 조회 범위: 최근 6시간, 자동 새로고침: 30초

복원할 때는 현재 Grafana 대시보드 JSON을 먼저 내보내 보관한다. Grafana의 대시보드 가져오기에서 이 JSON을 선택하고 데이터 소스 UID가 일치하는지 확인한다. 같은 UID `catlfln`을 덮어쓰면 GUI에서 추가한 패널도 스냅샷 시점으로 돌아가므로 대상과 내용을 확인한 뒤 저장한다. 이후 구성을 보관하려면 최신 대시보드를 다시 내보내 스냅샷을 갱신한다.

## 기본 패널과 조회 방식

| 패널 | 조회·표시 방식 | 해석 |
| --- | --- | --- |
| 초당 API 요청 수 (RPS) | Stat, instant, `rate(...[5m])` | 최근 5분의 초당 평균 처리 요청 수 |
| API 서버 오류율 (5xx) | Stat, instant, 최근 5분 5xx 요청 수 / 전체 요청 수 × 100 | HTTP 500~599의 비율. 4xx는 오류 분자에 포함하지 않음 |
| API 응답 시간 p95 | Stat, instant, 최근 5분 histogram bucket 합산 | API 요청 약 95%의 응답 시간이 이 값 이하인 것으로 추정 |
| Spring Boot JVM CPU 사용률 | Stat, instant, `process_cpu_usage × 100` | Spring Boot JVM 프로세스의 최근 CPU 사용률. 5분 평균이나 EC2 전체 CPU 지표가 아님 |
| 주요 API별 응답 시간 p95 | Time series, range, 단위 ms | 주요 API 5개의 최근 5분 p95를 한 그래프에서 비교 |
| API 서버 EC2 전체 CPU 사용률 | Time series, range, `system_cpu_usage × 100` | JVM이 보고하는 실행 환경 전체 CPU 사용률. 현재 제한 없는 EC2 기준 |
| Spring Boot JVM CPU 사용률 추이 | Time series, range | 인스턴스별 Spring Boot JVM 프로세스 CPU 사용률의 추이 |
| JVM 힙 메모리 | Time series, range | 인스턴스별 힙 사용량과 최대한도의 추이 |
| GC 정지 시간 | Time series, range, 단위 ms | 인스턴스별 최근 5분 GC 1회당 평균 정지 시간·계측 구간의 최대 정지 시간 |
| DB 연결 풀 사용 현황 | Time series, range | 인스턴스·풀별 활성 연결, 유휴 연결, 최대 연결 수의 추이 |
| JVM 스레드 수 | Time series, range | 인스턴스별 현재 전체·데몬·최대 동시 플랫폼 스레드 세 선. 데몬은 전체의 일부 |
| DB 연결 대기 수 (Pending) | Time series, range | 인스턴스·풀별 DB 연결을 얻기 위해 기다리는 스레드 수 |

상단 HTTP 세 패널은 `/actuator.*`와 `/healthz`를 제외하며 **내부 API 요청은 포함**한다. 주요 API 비교는 아래 다섯 경로만 조회한다. 모든 p95는 HTTP 응답 시간의 추정값이며, 요청을 접수한 뒤 진행되는 비동기 분석 작업의 완료 시간을 나타내지 않는다.

Stat 네 패널은 `instant=true`, `range=false`로 현재 시점의 결과를 조회하고 reducer는 `last`를 사용한다. `lastNotNull`로 NaN·null을 건너뛰어 과거의 정상 값을 현재 값처럼 표시하지 않는다. API 비교와 인프라 그래프 총 여덟 개는 `range=true`, `instant=false`로 선택한 조회 범위의 이력을 표시한다. API p95 비교·GC·EC2 CPU의 범례도 `last`를 사용해 현재 계산 불가 상태를 과거의 유효 값으로 대체하지 않는다. 그 밖의 인프라 그래프 범례는 마지막 유효 값(`lastNotNull`)이므로 현재 상태를 보장하지 않는다.

## JVM CPU와 EC2 CPU

Spring Boot JVM CPU는 해당 Java 프로세스의 요청 처리뿐 아니라 GC·JIT 등 JVM 내부 작업도 포함한다. Caddy·Redis 등 다른 프로세스의 CPU는 포함하지 않는다. 2026-10-01 운영 확인 시 컨테이너에 CPU quota·cpuset 제한이 없고(`cpu.max=max 100000`, 허용 CPU `0-1`), EC2와 JVM 모두 **2 vCPU**를 인식했다. 따라서 현재 CPU 1개를 계속 꽉 쓰면 약 50%, 2개를 모두 꽉 쓰면 약 100%다. 컨테이너 CPU 제한이나 JVM CPU 설정이 바뀌면 이 분모를 다시 확인한다.

EC2 CPU 패널은 같은 JVM의 기존 Actuator 지표 `system_cpu_usage × 100`을 직접 조회한다. 현재 CPU quota·cpuset 제한 없이 EC2의 2 vCPU 전체를 인식하므로 실행 환경 전체의 다른 프로세스·커널까지 포함한 CPU 사용률이다. CPU 1개 최대 사용은 약 50%, 2개 최대 사용은 약 100%다. Docker CPU 제한·cpuset·JVM CPU 설정을 바꾸면 JVM이 보고하는 실행 환경이 컨테이너 기준이 될 수 있으므로 EC2 전체라는 설명과 분모를 다시 확인한다. [Java 실행 환경 정의](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/OperatingSystemMXBean.html)에 따라 어떤 컨테이너에서나 같은 호스트 범위라고 가정하지 않는다.

JVM·EC2 CPU 모두 최근 관측값인 Gauge이며 5분 rate·평균으로 계산하지 않는다. [CloudWatch CPUUtilization](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/viewing_metrics_with_cloudwatch.html)과 측정 범위·수집 간격이 달라 정확히 같은 값으로 해석하지 않는다. 기존 Actuator·Prometheus 수집을 재사용하므로 새 소프트웨어 설치·SG·포트·Prometheus 설정 reload는 필요하지 않다. CPU 범위 확인은 [운영 배포 안내](../../../MONITORING_DEPLOYMENT.md)를 따른다.

## 주요 API p95 비교

| 표시 이름 | HTTP method | route template |
| --- | --- | --- |
| 원고 업로드 | POST | `/api/v1/works/{workId}/episodes` |
| 분석 요청 접수 | POST | `/api/v1/works/{workId}/analysis-jobs` |
| 분석 상태 조회 | GET | `/api/v1/works/{workId}/analysis-jobs/{analysisJobId}` |
| 세계관 설정 후보 조회 | GET | `/api/v1/works/{workId}/world-setting-candidates` |
| 확정 인물 설정 검색 | GET | `/api/v1/works/{workId}/character-facts/search` |

각 series는 정확한 `method`·`uri` 라벨로 `http_server_requests_seconds_bucket`을 선택하고, `rate(...[5m])`를 `le`별로 합산한 뒤 `histogram_quantile(0.95, ...) × 1000`으로 ms를 계산한다. 성공·실패 응답을 함께 집계한다. 실제 작품·분석 ID 대신 route template을 사용하며 서로 다른 API의 p95를 합산하거나 평균내지 않는다.

2026-10-01 초기 적용 검증 시 다섯 경로의 bucket과 수집 정상(`up=1`)을 확인했지만 당시 최근 6시간 요청 수 증가는 모두 0이었다. 이는 초기 관측 기록이며, 이후 실제 요청이 들어오면 해당 API의 p95가 계산되기 시작한다. 요청이 없는 구간의 원시 p95는 NaN이지만 Grafana range 범례의 `last`가 이를 null로 반환하면 UI에는 `계산 불가`로 표시한다. 초기 검증 당시의 실제 원인은 요청 없음이었으며, 표시만으로 현재 원인을 단정하지 않는다. 요청이 없거나 값이 누락된 구간을 0ms 선으로 채우지 않는다. 분석 요청 접수·상태 조회의 응답 시간은 비동기 분석이나 LLM 작업 완료 시간이 아니다.

## 요청 없음·계산 불가·데이터 없음

- HTTP 요청 지표가 있고 최근 5분 요청 수가 0이면 RPS는 0이다. 오류율은 0/0, 전체·API별 p95는 관측 요청이 없어 원시 조회 값이 NaN이 된다. 상단 HTTP Stat의 NaN·null은 `요청 없음`으로 표시하고, 수치 필드가 없는 빈 조회 결과는 `데이터 없음`으로 표시한다.
- 요청은 있지만 5xx 지표가 아직 없으면 오류율은 0%다. 요청 분모가 없는 상태까지 0%로 채우지 않는다.
- GC 지표가 있고 최근 5분 GC 이벤트가 0회면 평균 정지 시간은 0/0으로 NaN이 된다. 계산할 수 없는 평균을 0ms로 채우지 않는다.
- API 비교·GC의 range 조회에서 NaN은 각각 `요청 없음`·`GC 없음`, null은 `계산 불가`로 표시한다. 최신 null은 요청·GC 이벤트가 없어서 계산할 수 없는 경우와 수집 공백 모두 가능하다. `up{job="catchhole-backend"}`와 해당 요청·GC count의 최근 구간 증가량을 함께 확인해 원인을 판단한다.
- 필요한 지표 자체가 없어 수치 필드가 없는 빈 조회 결과는 `데이터 없음`이다. 아직 해당 요청이 없어서 지표가 생성되지 않은 경우와 수집 장애를 구분해야 한다. 수집 상태와 데이터 소스를 별도로 확인한다.

## GC·스레드 해석

GC 평균은 `jvm_gc_pause_seconds_sum`의 최근 5분 rate를 `jvm_gc_pause_seconds_count`의 rate로 나눈 뒤 1000을 곱해 ms로 표시한다. GC 종류·원인별 합계와 횟수를 인스턴스별로 먼저 합산하므로 이벤트 수를 반영한 평균이다.

최근 최대는 `jvm_gc_pause_seconds_max`의 인스턴스별 최대에 1000을 곱한다. Micrometer의 기본 TimeWindowMax는 **step 1분 × bufferLength 3개**로, 1분마다 버퍼를 회전하고 값이 완전히 만료되기까지 최대 3분을 유지한다. 이 계측 구간은 평균의 최근 5분, 대시보드에서 선택한 전체 기간, JVM 시작 이후의 누적 최대와 다르다. 새 GC 기록 없이 관측 구간이 만료되면 최대값은 0이 될 수 있다. 별도 step·distribution 설정을 적용하면 이 구간도 다시 확인한다.

스레드 그래프는 `jvm_threads_live_threads`·`jvm_threads_daemon_threads`·`jvm_threads_peak_threads`를 표시한다. 전체 수에는 데몬도 포함되므로 선들을 합산하지 않는다. 최대 동시 스레드는 JVM 기동 또는 peak 초기화 이후 동시에 살아 있던 스레드 수의 최고값이며, 현재 스레드 수나 누적 생성 수가 아니다. CPU에서 실제 실행 중인 스레드 수나 요청 대기 수를 뜻하지 않으며, Java 21의 해당 계측은 가상 스레드를 포함하지 않는다.

## 힙·DB 기준값

2026-10-01 운영 서버 지표에서 힙 최대한도 `805306368` bytes = **768 MiB**, HikariCP 최대 연결 수 **10**을 확인했다. 이는 스냅샷 작성 시점의 값이며 설정이 바뀌면 지표와 설명을 함께 확인한다.

힙 사용량은 `area="heap"`인 메모리 풀의 사용량을 합산한다. 현재 G1의 최대값 `-1`은 미정이므로 제외하고 양수인 최대값만 합산한다. 이 값은 JVM 힙 한도이며 JVM 전체 메모리나 서버 전체 메모리가 아니다.

DB의 `사용 중`은 `hikaricp_connections_active`, `유휴 연결`은 재사용 가능한 연결인 `hikaricp_connections_idle`, `최대 연결`은 `hikaricp_connections_max`다. 최대 10은 API의 연결 풀 한도이며 DB 서버 전체의 연결 한도가 아니다.

별도 Pending 그래프는 `hikaricp_connections_pending`을 표시한다. 유휴 연결 수가 아니라 연결을 얻기 위해 기다리는 스레드 수다. 0은 수집 시점에 연결을 기다리는 스레드가 없다는 뜻이며, 15초 수집 사이에 발생하고 사라지는 짧은 대기까지 없었다는 뜻은 아니다.
