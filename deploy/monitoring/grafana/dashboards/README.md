# 캐치홀 운영 대시보드

운영 화면은 다음 세 JSON으로 관리한다. 수동 가져오기용이며 자동 dashboard provisioning은 사용하지 않는다. datasource UID는 `catchhole-prometheus`, 기본 최근 6시간/30초 새로고침, 브라우저 시간대다.

| JSON | UID | 목적 |
| --- | --- | --- |
| [catchhole-overview.json](catchhole-overview.json) | catlfln | HTTP·서비스 이용·분석 핵심 요약과 수집 상태 |
| [catchhole-api.json](catchhole-api.json) | catchhole-api | 주요 API 지연·EC2/JVM/Heap/GC/DB/Threads |
| [catchhole-analysis.json](catchhole-analysis.json) | catchhole-analysis | 대기→실행→결과→LLM 원인 |

모든 화면에 환경 필터와 시간 범위를 이어가는 화면 링크가 있다. API 상세는 API 인스턴스, 분석 상세는 Worker 종류/인스턴스를 선택한다. Worker 필터는 Python 패널에만 적용되며 DB·회차 결과는 전체 서비스 관측이다. DB Gauge는 API 인스턴스를 max로 중복 제거한 후 서로 다른 유형/모드를 합산한다. 세부 정의는 [분석 지표 문서](../../../../docs/analysis-metrics.md)를 따른다.

## 가져오기와 보존

현재 GUI 대시보드 JSON을 먼저 export해 저장한다. API 상세와 분석 상세를 먼저 가져온 뒤 기존 UID catlfln의 overview를 덮어써 링크를 완성한다. 서버에 JSON을 복사하거나 Grafana를 재시작하는 것만으로 화면이 갱신되지는 않는다. 운영 Grafana 버전은 13.2.2이며 JSON schemaVersion은 기존 42를 유지한다.

새 계측이 배포·수집되기 전에는 분석 패널이 데이터 없음일 수 있다. 배포된 모든 Worker의 `up`, Java snapshot success와 마지막 갱신 시각을 함께 확인한다. Worker 수집 정상 수는 현재 5개 분석+2개 비교=7개가 기준이다. scale을 바꾸면 이 설명과 수집 대상을 함께 갱신한다.

## 결과 없음과 수집 장애

운영 현황의 `서비스 이용 현황`은 전체 회원 수, 최근 7일 분석 이용자 수, 최근 24시간 분석 요청 수·요청당 평균 회차 수를 표시한다. 1분마다 DB에서 갱신한 현재 값이므로 대시보드 시간 선택은 고정 7일·24시간 창을 바꾸지 않는다. 여러 회차의 최초 접수를 1건으로 세고 사용자 재시도와 내부 후속 비교는 요청 수에서 제외한다. 탈퇴·작품 삭제로 사라진 기록은 기간 집계에서도 제외된다. 정확한 정의·과거 기록 제한은 [서비스 이용 지표 문서](../../../../docs/service-usage-metrics.md)를 따른다.

서비스 이용 Stat은 최신 정상 snapshot을 선택하고 같은 전체 label의 값을 연결한다. 조회 실패·180초 이상 갱신 없음은 `관측 없음`이다. 과거 원본 요청의 묶음 기록이 불완전하면 요청 수는 `기록 부족`, 평균은 `계산 불가`로 표시한다. 정상 조회에서 요청이 없으면 요청 수 0과 계산할 수 없는 평균을 구분한다. 기존 16개 운영 패널을 보존하고 HTTP 요약 아래에 네 개의 서비스 이용 Stat을 추가한다.

Stat은 Instant/Last를 사용하고 과거 유효값으로 현재 NaN을 채우지 않는다. 분석 결과 준비 p95는 성공·부분 성공만, 완전 성공률은 success/(success+partial_success+failure)이며 취소 제외다. 최근 종료 결과가 없으면 계산 불가다. LLM 호출이 없는 유휴 구간의 p95도 계산 불가다.

운영 현황과 분석 상세의 `회차 결과 준비 시간 p95 · 최근 5분`은 회차별 최근 통계다. 별도 `마지막 완료 작품 분석 소요 시간`은 한 요청의 모든 대상 회차가 준비된 실제 시간으로, 새 완료가 생길 때까지 유지하고 API 재시작 후 DB에서 복구한다. 옆의 `마지막 작품 분석 완료 후 경과`로 해당 값이 언제 완료된 요청인지 확인한다. 새 요청이 진행 중인 동안에는 이전 완료 값이 유지된다. 상세 범위·재시도·과거 기록 복구는 지표 문서를 따른다.

새 마지막 완료 카드는 전용 DB snapshot이 정상이고 45초 미만으로 신선할 때만 표시한다. 완료 기록이 없거나 조회가 실패·정체되면 `관측 없음`이며 0초로 채우지 않는다. 여러 API 인스턴스가 있으면 최신 완료 timestamp를 먼저 고르고 같은 label의 duration을 연결한다. 가장 큰 duration을 고르면 가장 최근 작품과 가장 오래 걸린 작품을 혼동한다. 기존 카드를 보존하고 분석 Stat 영역을 3개씩 2줄로 배치한다.

실행 가능한 대기와 의존 대기는 구분하고 빈 queue의 oldest age는 0이다. DB snapshot 실패 또는 45초 이상 갱신 없음은 queue를 정상 0으로 표시하지 않는다. Worker 마지막 종료 시각 0은 아직 처리 종료가 없다는 뜻이며, 유휴 때문에 종료 경과가 길어질 수 있어 queue/active/up과 함께 확인한다.

HTTP 패널은 `/actuator.*`와 `/healthz`를 제외하고 내부 API 요청은 포함한다. 요청 없음의 0/0 오류율·p95는 계산 불가이며 RPS 0과 구분한다. API 상세의 GC 평균도 이벤트가 없으면 계산 불가다. 수집 자체가 없는 경우에는 up와 target 수부터 확인한다.

현재 세 JSON의 78개 dashboard PromQL은 promtool 3.13.3으로 문법을 확인했고, [쿼리 검증 fixture](../../tests/dashboard-promql.test.json)의 17개 시나리오는 snapshot 실패·정체·중복 API 관측·다른 mode 합산·부분 성공/취소의 성공률, 첫 완료의 0 기준값, 마지막 완료 선택과 5분 이후 유지·label 변경, 서비스 이용 snapshot의 최신 값 선택·빈 집계·불완전 이력을 검증한다. JSON 변경 시 모든 target expr을 추출해 template 변수를 실제 값으로 치환한 후 promtool check rules를 다시 실행한다.

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
