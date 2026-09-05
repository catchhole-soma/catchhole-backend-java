# 운영 부하 테스트

통합 작업 이슈: [Java #164](https://github.com/catchhole-soma/catchhole-backend-java/issues/164)

API·Worker·RDS를 함께 거치는 부하 테스트의 계획과 결과는 Java 저장소에서 관리한다. 이슈에는 진행 상태와 요약을, 이 디렉터리에는 재현 조건과 상세 결과를 남긴다. AI 저장소에는 같은 보고서를 복제하지 않는다.

## 현재 상태

| 시나리오 | 상태 | 기록 |
| --- | --- | --- |
| 작품 목록 조회 1·5·10 VU | 2026-09-05 완료 | [조회 결과](2026-09-05-api-read.md) |
| 실제 TXT 분석 10 → 50 → 100건 | 최초·재측정 각 10건, 50건, 100건 완료; 최신 Job 100/100 종료·산출물 98/100 통과 | [분석 계획·결과](2026-09-05-ai-analysis.md) |

조회 테스트에서 3,589건이 성공했지만 그 조회 테스트에서 업로드·AI 분석은 수행하지 않았다. 이후 [분석 실행기](../../scripts/load-tests/analysis.mjs)로 최초 10건, 재측정 10건, 50건, 100건을 사용자 승인에 따라 각각 실행해 총 170건을 분석했다. 최신 100건은 50건 동시 진행·나머지 대기 후 114.153초에 전부 SUCCEEDED로 종료됐고 산출물은 98/100 통과했다. 두 작품의 세계관 후보 총 4개가 같은 구조 검증 조건에서 실패한 것을 SSM journal로 확인했다. 실행 p95 56.018초, 접수~종료 p95 104.469초다. API·Worker 자원 샘플은 수집했지만 RDS 지표·실제 청구액은 미확인이다. 순차 polling 합계 최대 52는 동시 snapshot이 아니며 서버 시각 구간 기준 최대 동시 진행은 50이다. 버전·입력·캐시 차이를 분리하지 못했으므로 장기 운영 용량으로 일반화하지 않는다. 누적 앱 사용량 8,947,933·잔액 1,052,067토큰이며 추가 분석은 실행하지 않았다. 이슈에 결과를 공유한 뒤 문서·검토한 집계·실행기를 함께 버전 관리한다. 아래 결과 문서의 미게시·미실행 표시는 각 과거 기록 시점의 상태이며 최신 결과를 덮어쓰지 않는다.

## 보관 위치와 공개 범위

- 이 디렉터리와 실행기의 변경은 운영 부하 테스트를 자동 실행하지 않는다. 다만 현재 저장소의 이미지 발행 Workflow는 모든 `main` push를 대상으로 하므로 문서·실행기만 병합해도 기존 이미지 발행·배포 절차는 실행될 수 있다. PR 생성과 운영 병합·배포 승인은 구분한다.
- `docs/load-tests/`: 실행 방법, 판정 기준, 날짜별 결과 보고서.
- `docs/load-tests/results/`: 민감정보 검토를 마친 작은 집계 JSON만 보관한다. 과거 결과는 덮어쓰지 않는다.
- `scripts/load-tests/`: 재실행 가능한 테스트 스크립트와 외부 요청 없는 검사.
- 원고·인증 토큰·쿠키·전체 API/AI 응답·S3 key·실제 계정 및 서버 접속 정보는 공개 저장소와 이슈에 넣지 않는다. 실행 중 Job/작품 식별자 매핑과 원본 로그는 저장소 밖의 비공개 실행 기록에 둔다.
- 같은 날짜에 다시 실행하면 `run-02` 등 실행 식별자를 붙인다. 이미지 SHA, 입력 파일 SHA-256, 실행기 버전 및 실행 시각을 함께 기록한다.
- 기존 `apps/tmp/api-read-load/` 파일은 이관 근거로 남겨두며, 이후의 공식 기록은 이 디렉터리에 갱신한다.

## 1. 완료한 조회 테스트 재현 방법

Mac에서 k6로 공개 운영 API에 요청한다. EC2 안에서 부하 발생기를 실행하거나 로컬 서버를 띄우는 방식이 아니다. 아래 `k6 run works.js` 명령은 실제 운영 요청이므로 의도한 점검 시간에만 실행한다. CI에는 자동 연결하지 않는다.

### 준비

1. Java 저장소 루트에서 실행한다. 기존 측정 도구는 k6 v2.1.0이었다. 재측정할 때도 버전을 기록한다.
2. 작품이 한 개 이상 등록된 테스트 계정의 액세스 토큰을 저장소 밖 `/private/tmp/catchhole-load-test-token`에 저장한다. `Bearer ` 접두사 없이 토큰 문자열만 넣는다. 토큰 자체를 명령행이나 이슈에 붙이지 않는다.
3. 파일 권한을 제한한다.

```sh
chmod 600 /private/tmp/catchhole-load-test-token
```

4. 먼저 외부 요청을 보내지 않는 집계 검사를 실행한다. 아래 값은 검사 전용 더미이며 실제 계정 토큰을 사용하지 않는다.

```sh
k6 run --quiet --no-usage-report -e ACCESS_TOKEN=offline-test-token scripts/load-tests/self-check.js
```

### 운영 실행

각 코드 블록의 명령을 하나씩 실행하고 결과를 확인한다. 이전 실행이 실패하면 다음 단계로 넘어가지 않는다. 결과 파일이 이미 있으면 이름을 바꿔 이전 측정값을 보존한다.

```sh
k6 run --no-usage-report -e STAGE=smoke -e RESULT_FILE=/private/tmp/catchhole-read-smoke.json scripts/load-tests/works.js
```

```sh
k6 run --no-usage-report -e STAGE=1 -e RESULT_FILE=/private/tmp/catchhole-read-1.json scripts/load-tests/works.js
```

```sh
k6 run --no-usage-report -e STAGE=5 -e RESULT_FILE=/private/tmp/catchhole-read-5.json scripts/load-tests/works.js
```

```sh
k6 run --no-usage-report -e STAGE=10 -e RESULT_FILE=/private/tmp/catchhole-read-10.json scripts/load-tests/works.js
```

- 1 VU·1분 → 5 VU·2분 → 10 VU·5분; 각 가상 사용자는 응답 후 1초 대기한다. 10 VU를 상시 처리 중인 HTTP 요청 10개 또는 실제 계정 10개로 해석하지 않는다.
- `/api/v1/works`의 HTTP 200, `success=true`, 비어 있지 않은 작품 목록을 확인한다. 조회 실패 또는 health 이상이면 실행기를 중단한다.
- `works_duration_ms`에만 작품 조회 시간을 기록한다. health·사전 조회 요청은 `httpRequestsIncludingHealthAndPreflight`에 포함되지만 작품 조회 집계에는 포함되지 않는다.
- 응답 시간은 요청 전송·서버 응답 대기·수신의 합계이며 DNS 조회·TCP 연결·TLS 연결 시간은 제외한다.
- `durationMs`의 평균·p95·p99·최대와 `failures`의 성공/실패 건수를 본다. 합산 요청 수로 나누지 않고 각 단계의 값을 별도로 비교한다.
- 임시 판정 기준은 p95 < 1,000ms·오류율 0%다. 종료 코드 0과 예정 시간 완료도 함께 확인한다. 중간 중단을 성공으로 기록하지 않는다.
- JSON의 `approximateStartedAt`은 종료 시각에서 실행 시간을 뺀 근사치다. 새 실행은 실제 시작·종료 시각과 종료 코드를 별도 기록한다.
- 1건 smoke와 과거 시각 집계 수정 전 smoke 결과는 성능 통계에서 제외한다.

## 2. 분석 테스트 진행 순서

설정 추출 Worker 5개 × 컨테이너당 10슬롯 = 최대 50개 Job을 기준으로 계획한다. 실제 컨테이너 수·적용 환경변수는 실행 직전에 별도로 확인한다. 캐릭터·세계관 재비교 Worker 각 1개는 이 50에 포함하지 않는다.

| 단계 | 신규 작품/회차/분석 Job | 목적 |
| --- | ---: | --- |
| 1차 | 각각 10개 | 실제 파일 업로드 → AI 분석 → 결과 저장 기본 안정성 |
| 2차 | 각각 50개 | 50슬롯 활용, API·Worker·DB 부하 및 지연 관찰 |
| 3차 | 각각 100개 | 설정은 50슬롯을 유지하고 초과 요청의 대기 → 실행 → 종료 검증 |

최초 계획 세 단계는 총 160건이고 추가된 재측정 10건까지 실제 누적 170건을 실행했다. 요청 수를 누적 100건으로 오해하지 않는다. 재실행할 때도 이전 단계가 끝나고 결과·잔액·비용을 검토한 뒤 다음 단계 실행을 결정한다. 한 번의 명령으로 세 단계를 연속 실행하지 않는다.

같은 계정에 별도 작품을 만들면 독립된 분석을 요청할 수 있다. 동일 회차의 이미 대기/실행 중인 분석은 중복 접수할 수 없고, 계정 토큰은 여러 작업의 예약량까지 공유한다. 계정별 토큰 처리의 DB 잠금이 있어 실제 다계정 부하와 완전히 같지는 않다.

상세 입력 조건·API 순서·측정 정의는 [분석 계획](2026-09-05-ai-analysis.md)을 따른다. 100건 접수 자체를 100건 동시 실행 또는 50슬롯 실측 검증 완료로 기록하지 않는다.

### 분석 실행기

Java 저장소 루트에서 Node.js로 실행한다. 준비 과정에서 확인한 버전은 v25.9.0이며 외부 패키지는 추가하지 않았다. 실행기는 운영 도메인과 승인 입력 목록을 고정한다. `fixture-004`·`fixture-005`의 SHA-256과 회차 번호를 함께 검증하며 임의의 다른 파일은 거부한다. 입력을 바꾸면 원문별 추출량도 달라지므로 실행 시간 차이를 부하 증가 때문으로만 해석하지 않는다.

외부 요청·토큰 조회 없는 오프라인 검사:

```sh
node scripts/load-tests/analysis.mjs self-test
```

인증과 health·토큰 사용량 확인만 수행:

```sh
node scripts/load-tests/analysis.mjs check
```

다음은 사용법 예시다. `LOAD_TEST_TXT`에는 지정한 비공개 원고 경로를, `LOAD_TEST_RUN_DIR`에는 `prepare`가 출력한 실제 디렉터리를 넣어야 한다. 이미 준비한 실행이 있으면 `prepare`를 다시 실행하지 않는다. 새 실행마다 새로운 작품이 만들어진다.

```sh
node scripts/load-tests/analysis.mjs prepare --count 10 --file "$LOAD_TEST_TXT"
```

위 명령은 작품·업로드만 만들고 분석하지 않는다. 비공개 `state.json`과 정제된 `summary.json`을 `/private/tmp/catchhole-analysis-*` 디렉터리(700), 파일(600)에 저장한다. 도중 실패한 준비를 자동 재시도하거나 생성된 작품을 삭제하지 않는다.

다음 명령은 **유료 AI 분석을 실제로 접수**한다. SSM 관찰 준비, 서버 시간대 및 외부 비용 기준을 확인한 뒤 실행한다. `LOAD_TEST_BUDGET_NOTE`는 사용자와 확인한 비용 기준이며 코드가 금액 상한을 강제하는 기능은 아니다.

```sh
node scripts/load-tests/analysis.mjs run --dir "$LOAD_TEST_RUN_DIR" --minutes 20 --budget-note "$LOAD_TEST_BUDGET_NOTE"
```

한 번 접수를 시작한 실행 디렉터리는 다시 `run`할 수 없다. timeout 등으로 응답이 불명확하면 GET으로 이미 생성된 Job을 찾아 관찰하며 생성 POST를 자동 재전송하지 않는다. 토큰은 요청마다 비공개 파일에서 다시 읽고 계정이 바뀌면 중단한다.

인증 만료나 관찰 중단 후, 같은 실행을 **추가 분석 생성 없이** 이어서 확인하려면:

```sh
node scripts/load-tests/analysis.mjs observe --dir "$LOAD_TEST_RUN_DIR" --minutes 20
```

관찰 종료 시각이 연장되므로 새 관찰 구간을 결과에 기록한다. 서버 health 이상이면 관찰 실행기도 멈추므로 운영자는 SSM에서 기존 Job을 계속 확인한다. 프로세스를 강제 종료해 `run.lock`이 남았으면 기존 관찰 프로세스가 종료됐는지 확인하기 전에는 잠금을 지우지 않는다.

`summary.json`의 `passed`는 실행기가 확인한 Job·산출물·HTTP 검사에만 해당한다. 수동 CPU·메모리·DB·로그·비용 관찰과 최종 단계 확대 판단을 대신하지 않는다. HTTP 시간은 연결 수립·JSON 수신/해석을 포함하여 기존 k6 `response.timings.duration`과 측정 경계가 다르다.

`maximumSampledRunning`은 순차 조회 합계이므로 동일 시각의 실제 동시성이나 그 하한이 아니다. 100건 실행에서 이 값은 52였지만 서버 시작~종료 구간의 최대 겹침은 50이었다. 원래 합계는 보존하고, 재시도·시각 정밀도를 확인한 구간 계산을 별도 결과로 기록한다.

2026-09-05 승인된 외부 AI 예산은 10·50·100건 전체 합계 약 50,000원이다. 단계별 실측 사용량과 비용을 검토하고 확대하며, 자세한 산정·여유액 기준은 [분석 계획](2026-09-05-ai-analysis.md#전체-비용-예산--2026-09-05-사용자-승인)에 기록한다. `tokenTotals`는 실패를 포함한 모든 관측 Job의 보고된 사용량이며 `missingUsageJobs`는 사용량 누락 건수다. 공급자 청구액 전체나 자동 비용 차단 기능으로 해석하지 않는다.

## 3. 서버 관찰

API 서버와 Worker 서버 각각의 AWS Systems Manager **Session Manager 터미널**에서 관찰한다. API 서버의 SSH 차단을 해제하지 않는다. 두 서버에서 같은 시작/종료 시간대를 기록하며, 실행 중 5초 간격을 목표로 자원 샘플을 수집한다. 수동으로 놓친 구간은 미수집으로 표시한다.

아래 읽기 전용 명령은 두 서버에서 각각 실행할 수 있다. `docker stats`는 별도 SSM 세션에서 띄우고 다른 세션에서 상태·로그를 확인한다.

```sh
date -Is
```

```sh
sudo docker ps --format '{{.Names}} {{.Image}} {{.Status}}'
```

```sh
sudo docker stats
```

```sh
free -h
```

```sh
df -h /
```

API Backend의 시작 전/종료 후 재시작 횟수와 OOM 상태:

```sh
sudo docker inspect -f 'status={{.State.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' catchhole-backend-1
```

최근 API 로그:

```sh
sudo journalctl CONTAINER_NAME=catchhole-backend-1 --since '10 minutes ago' --no-pager
```

Worker는 `docker ps`로 확인한 실제 컨테이너 이름을 같은 위치에 넣어 각각 확인한다. 결과 기록에서는 상대 시간 조회만으로 끝내지 않고 테스트 시작·종료의 절대 시각과 시간대를 지정해 해당 구간 로그를 확인한다. 원본 로그를 공개 이슈에 그대로 붙이지 않는다.

Journal은 로그 저장소이며 CPU·메모리·RDS 성능 이력 수집기가 아니다. `docker stats --no-stream` 한 번과 종료 후 `free -h` 값으로 부하 중 최댓값을 추정하지 않는다. Docker CPU는 여러 코어 사용 시 100%를 넘을 수 있으므로 컨테이너 CPU와 호스트 CPU를 같은 비율로 비교하지 않는다.

RDS 성능은 EC2의 `docker stats`로 측정할 수 없다. 기존 RDS 화면이나 별도 승인한 읽기 전용 DB 관측으로 확인한 연결·잠금 대기 등의 값만 기록한다. 수집 경로를 준비하지 못했다면 RDS는 미측정으로 남기며 DB 안정성 검증 완료로 결론 내리지 않는다. 새로운 CloudWatch 설정이나 로그 외부 전송은 이번 범위에 추가하지 않는다.

## 4. 진행·중단 기준

실행 전 점검 시간, 관찰 종료 시각, 해당 단계의 최대 신규 Job 수, 외부 AI 비용 한도를 기록한다. 분석 응답 시간 목표는 아직 합의하지 않았으며, 10건 결과를 본 뒤 후속 단계의 지연 판정 기준을 정한다. 가입자 수·DAU 수용량으로 환산하지 않는다.

- **확대 보류:** 한 건이라도 접수 실패·Job 실패/취소·토큰 중단·후보 비교 실패 또는 관찰 종료까지 미완료가 있으면 원인을 확인하고 다음 단계로 확대하지 않는다. 표본이 적은 10건 p95는 참고치다.
- **신규 접수 중단:** 서비스 health 이상, 5xx/timeout 증가, DB 연결 오류, OOM·컨테이너 재시작, 반복되는 외부 AI 429 또는 토큰 예약 실패가 관찰되면 추가 요청과 단계 확대를 멈춘다. 수동 관찰 한계와 중단 시각을 기록한다.
- 접수 API가 timeout이면 성공 여부가 불명확할 수 있다. 같은 작품/회차의 Job 조회로 확인하기 전에는 생성 요청을 자동 재전송하지 않는다.
- 실행기를 중단해도 이미 접수한 `PENDING/RUNNING` Job은 취소되지 않는다. 진행 중 Job·API 건강 상태·추가 비용을 계속 확인한다. 401로 polling이 끊긴 경우도 Job 실패로 간주하지 말고 인증 복구 후 같은 Job을 다시 관찰한다.
- Worker 강제 종료, 운영 Compose 변경, DB 상태 직접 수정, 테스트 작품 삭제를 자동 중단 수단으로 쓰지 않는다. 서버 조치가 필요하면 범위와 drain 절차를 확인한 뒤 별도로 결정한다.
- 50슬롯에서 문제가 드러나면 기존 [Worker 배포 가이드](https://github.com/catchhole-soma/catchhole-backend-ai/blob/main/deploy/WORKER_EC2_DEPLOYMENT.md)의 25슬롯 fallback을 검토한다. 적용은 별도 운영 변경이며 설정을 바꾼 전후 결과를 같은 조건의 결과처럼 합치지 않는다.
- 최종 결과와 필요한 근거를 보존한 뒤 이번 실행의 작품 ID 목록만 대상으로 정리 여부를 결정한다. 삭제는 기존 작품 파기 API를 사용하고 일반 작품·S3 버킷 전체·DB 테이블을 일괄 삭제하지 않는다.

## 관련 문서

- [프로젝트 문서 목록](../README.md)
- [운영 인프라 구조](../infrastructure-flow.md)
- [분석 API와 상태](../analysis.md)
- [토큰 예약·정산 정책](../ai-token-usage.md)
- [업로드 처리 흐름](../upload-episode-workflow.md)
