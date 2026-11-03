# GH374 누적 회차 최초 준비·목록 조회 메모리 검증

## 범위

`AnalysisHistoryMemoryPostgresIntegrationTest`는 실제 PostgreSQL/Flyway/JPA와 localhost Spring HTTP를 사용한다.
측정 대상은 다음 두 경로다.

- `GET /api/v1/works/{workId}/episodes`: 인증된 작품 소유자의 회차 목록
- `POST /api/internal/v1/analysis-jobs/claim`: 42화 자동 누적 분석의 **최초 입력 준비와 claim**

#365의 `world-setting-comparison-batches/claim-next`와 다른 경로다. 이미 준비된 입력으로 비교 후보를
가져오는 작업의 성능 수치를 이번 회차 최초 준비와 직접 비교하지 않는다.

## 고정 조건과 운영 사례와의 차이

- 변경 전 코드는 `34804254e9f3b424bea1176ccab4cd8778900a12`. 동일 테스트·계측 파일을 별도 checkout에 복사한다.
- 양쪽 테스트 JVM은 `-Xms256m -Xmx768m`, Gradle worker 1개, Spring context cache 1개다.
- 과거 회차는 41개다. DB `jsonb::text` 기준 세 컬럼의 합계를 운영 집계와 정확히 맞춘다.
  `automatic_input_state` 34,344,206 bytes, `run_base_state` 11,282,343 bytes,
  `state_journal` 30,348,371 bytes = 총 **75,974,920 bytes (72.46MiB)**.
- journal은 35개 v1·6개 v2다. 각각의 총 JSON text 크기는 29,538,310 / 810,061 bytes다.
  v1은 전체 이력 REPLACE, v2는 APPEND_HISTORY이며 실제 상태 서비스로 두 버전의 재생 hash를 검사한다.
- 이전 후보 1,768개는 캐릭터 832개(확정 641·무시 191), 세계관 936개(확정 871·무시 62·검토 대기 3)다.
- 후보 raw JSON도 운영의 상태별 합계(캐릭터 4,897,343 bytes, 세계관 603,691 bytes)로 교정한다.
  캐릭터의 두 raw 컬럼 합계를 합성 raw AI 컬럼 하나에 넣으므로 컬럼별 분포까지 동일하다는 뜻은 아니다.
- 현재 상태에는 캐릭터 41개와 확정 Fact 641개, 세계관 대상 181개와 확정 후보 871개의 출처를 포함한다.
  Fact의 인용은 합성 `episode_chunks`를 이용해 **실제 DB trigger가 검증한 저장 receipt**로 읽는다.
  확정 캐릭터 Fact는 모두 이력으로 두며, 추가 현재 슬롯을 추론하지 않는다.
- 새 입력은 기존 fact의 텍스트를 분산 교정해 `jsonb::text` 1,776,908 bytes로 맞춘다.
  과거 Job JSON도 실제 이력 항목의 문자열에 크기를 분산하며 단일 거대 임의 필드를 만들지 않는다.
- 실제 원고·사용자 식별자·S3·외부 LLM·운영 DB를 사용하지 않는다. 전체 원고의 내용 분포, 운영의
  인물/세계관별 이력 편중, RDS 지연, 캐시, 동시 요청 및 당시 GC 상태는 복제하지 않는다.
  특히 과거 JSON의 **합계와 버전 수**를 맞춘 것이며, 회차별 세 컬럼 분포를 동일하게 복제한 것은 아니다.
- 기본 실행은 목록과 최초 claim을 한 요청씩 따로 측정한다. 각 경로 직전에 fixture 준비 객체를 회수하도록
  명시적 GC를 실행한다. 운영의 동시 요청 OOM 전체를 재현했다는 의미가 아니다.
- setup·크기 교정·검증·해시 사후 대조는 측정 구간에서 제외하며, 성능 실행 동안 다른 Gradle 테스트를 실행하지 않는다.

## 보존 검증

- 회차 목록 42개와 최신 상태, claim 대상·기존 캐릭터 목록·세계관 CURRENT 문맥을 확인한다.
- DB에 저장된 입력 hash가 claim 응답 및 같은 fixture에서 사전 capture한 전체 상태 hash와 일치해야 한다.
- 저장 입력에 캐릭터 41개·세계관 181개·검토 대기 참고 3개·확정 Fact 641개가 모두 유지돼야 한다.
  모든 Fact의 저장 근거 상태가 VERIFIED이고 정확한 합성 인용문이 유지돼야 한다.
- 요청 전후 과거 Job의 세 JSON과 모든 캐릭터/세계관 후보 및 Fact의 DB 행 digest가 같아야 한다.
- 서로 다른 실행의 자동 생성 UUID는 안정적인 합성 이름으로 바꿔 전체 입력/claim/목록 semantic hash를 비교한다.
  lease token·만료 시각과 목록의 변경/생성 시각, UUID에 종속되는 입력 hash는 비교에서 제외한다.
  실제 입력 hash의 정합성은 각 요청에서 별도로 검증한다.
- UUID 정렬에 따라 순서가 달라지는 semantic CURRENT의 캐릭터/세계관 소유자 집합과 참고 목록은 안정적으로 재정렬한다.
  참고 목록의 실제 응답 순서가 해당 fixture의 참조 ID 정렬과 일치하는지는 각 요청에서 별도 검증한다.
  이력·인용의 순서는 정렬하거나 잘라내지 않는다.
- 별도 `overlap` 모드는 같은 작품의 목록 GET과 최초 claim POST를 두 platform client thread에서 함께 시작한다.
  두 client 요청 구간이 실제로 겹쳤는지 확인하고 JVM/SQL 합계를 한 구간으로 측정한다. 서버 내부 두 SQL의
  정확한 동시 실행까지 증명하는 값은 아니며, 당시 운영 동시성의 복제라고 해석하지 않는다.
  요청 시작 조건은 같지만 빨라진 목록 요청이 먼저 끝나므로 실제 겹침 시간까지 고정되는 것은 아니다.
  목록의 42화 원시 상태는 claim 커밋 전후에 따라 UPLOADED/CHUNKING일 수 있으므로 둘 다 허용하고
  동시 모드의 semantic hash에서는 이 상태 하나만 공통 표지로 바꾼다. 분석 상태 IN_PROGRESS는 검증한다.
- 이는 분석 **준비**의 동등성 검증이며 유료 모델 응답 품질·회차 전체 분석 완료를 검증한 것은 아니다.

## 계측 의미

기존 `JdbcResultMetrics`를 그대로 사용해 준비된 SELECT의 SQL·선택 열·소비 행 수를 기록한다.
바인딩이나 결과 값을 읽어 기록하지 않는다. `rowsSelectingJobJson`은 큰 Job JSON을 선택한
결과 행 수이며, 원문 크기·네트워크 전송량·그 행의 실측 heap을 의미하지 않는다.

`RuntimeProbe`는 JVM heap을 5ms 간격으로 관측한다. sampled peak는 절대 peak나 RSS가 아니다.
할당량은 HTTP client/server와 JDBC 계측을 포함한 전체 platform thread 누적 할당량 차이다.
경계 heap, GC 횟수/시간, process CPU 시간과 GC 로그도 기록한다. 소수 표본의 중간값·범위를
보고하고 p95/p99나 동시 사용자 수용량으로 확대하지 않는다.

OOM·timeout·HTTP 오류 시 시작 artifact와 종료 코드·GC 로그를 보존한다. 실패를 0ms/0bytes로
계산하지 않고, 성공 응답이 없는 조건에서 응답 동등성을 주장하지 않는다. 합성 조건에서 OOM이
재현되지 않았다면 메모리 감소만 보고하고 OOM 완전 제거를 주장하지 않는다.

## 재현

Docker와 Java 21이 필요하다. 기본 `test`에서는 opt-in 환경 변수가 없어 생략된다.

```bash
# baseline에도 현재 checkout과 동일한 아래 두 test 파일만 복사한다.
# src/test/java/org/monitoring/catchholebackend/domain/analysis/service/AnalysisHistoryMemoryPostgresIntegrationTest.java
# src/test/java/org/monitoring/catchholebackend/support/JdbcResultMetrics.java
scripts/load-tests/history-memory/run.sh /absolute/baseline /absolute/results/baseline baseline 3
scripts/load-tests/history-memory/run.sh "$PWD" /absolute/results/candidate candidate 3
python3 scripts/load-tests/history-memory/summarize.py \
  /absolute/results/baseline /absolute/results/candidate \
  --output docs/load-tests/gh374-history-memory-results.json

# 동일 하네스의 별도 2요청 겹침 조건. serial과 분리해 집계한다.
scripts/load-tests/history-memory/run.sh /absolute/baseline /absolute/results/baseline-overlap baseline 3 overlap
scripts/load-tests/history-memory/run.sh "$PWD" /absolute/results/candidate-overlap candidate 3 overlap
python3 scripts/load-tests/history-memory/summarize.py \
  /absolute/results/baseline-overlap /absolute/results/candidate-overlap \
  --output docs/load-tests/gh374-history-memory-overlap-results.json
```

서로 다른 실행에는 새 결과 폴더를 사용한다. runner는 HEAD, 변경 파일, 전체 `src/main` 내용 hash, Java/Docker 환경,
계측 파일 SHA-256, JVM 인자, JUnit XML, GC 로그, 경로별 측정값을 보존한다.
요약기는 양쪽 exit 0·HTTP 200·실제 sample 수·동일 하네스·전체 semantic hash 및 v1/v2 재생
일치를 확인한 뒤에만 집계한다.
커밋용 결과는 같은 SQL·선택 열을 query profile로 한 번만 저장하고 각 요청의 실행 횟수·소비 행·
실행 시간 합계를 연결한다. 전체 개별 SELECT 기록과 GC 로그는 로컬 원본 artifact에 남긴다.

## 실행 결과

2026-11-03 로컬에서 실행했다. 변경 전/후 각각 serial 3개 fixture와 overlap 3개 fixture가 모두
성공했다. 측정 HTTP 요청 총 24개가 모두 200이었고, 각 fixture에서 입력·응답·저장 이력과 근거
보존 검사를 통과했다. 다른 Gradle 실행과 겹치지 않게 순차 실행했다.

- Java: Microsoft OpenJDK 21.0.11+10-LTS, G1, `-Xms256m -Xmx768m`
- 호스트: macOS 26.6.2, aarch64, JVM 가용 프로세서 15개
- Docker Engine 29.6.2, Docker 메모리 한도 8,320,815,104 bytes
- PostgreSQL 16.14 aarch64, 이미지 `pgvector/pgvector:0.8.2-pg16`
- baseline: `34804254e9f3b424bea1176ccab4cd8778900a12`
- 변경 후: 구현 커밋 `cef633e`와 같은 `src/main` 소스다. 측정 당시에는 아직 커밋 전이었으며,
  serial/overlap의 `src/main` 내용 SHA-256은 모두
  `2c17be5d2cfa363fc3d0d0d3f09cb3a763464af5f726cc3efa996d75123da96b`다.
  HEAD와 실제 측정 소스가 다른 경우를 숨기지 않도록 결과 JSON에 소스 hash·diff hash를 함께 기록했다.

아래 수치는 **중간값 (최솟값–최댓값)**이며, 첫 요청도 포함한 3개 성공 표본이다. 별도의 워밍업 표본을
제외하지 않았다. MiB는 1,048,576 bytes이고, 할당량은 구간 누적 값이라 heap 상한보다 클 수 있다.

### 단독 요청

| 경로 / 지표 | 변경 전 | 변경 후 | 중간값 변화 |
|---|---:|---:|---:|
| 회차 목록 할당량 MiB | 671.35 (671.27–688.63) | 0.93 (0.92–18.04) | −99.9% |
| 회차 목록 sampled heap peak MiB | 591.38 (586.27–594.37) | 153.81 (153.51–160.61) | −74.0% |
| 회차 목록 응답 시간 ms | 323.80 (296.13–454.22) | 11.58 (10.59–129.09) | −96.4% |
| 회차 목록 GC 횟수 / 시간 ms | 7 / 20 | 0 / 0 | |
| 최초 claim 할당량 MiB | 1,291.63 (1,291.39–1,460.51) | 432.54 (432.37–597.50) | −66.5% |
| 최초 claim sampled heap peak MiB | 652.44 (651.21–701.37) | 406.75 (405.82–453.64) | −37.7% |
| 최초 claim 응답 시간 ms | 6,729.63 (6,446.05–7,041.62) | 1,563.62 (1,531.20–1,753.77) | −76.8% |
| 최초 claim GC 횟수 / 시간 ms | 14 / 30 | 3 / 6 | |

목록의 전체 SELECT는 양쪽 모두 6회, 소비 행도 88행으로 같다. 차이는 Job 결과의 큰 JSON을
선택하지 않는 데 있다. 최초 claim의 전체 SELECT는 1,734→1,576회, 소비 행은 5,278→3,558행이다.

| 큰 Job JSON을 선택한 결과 행 | 변경 전 | 변경 후 |
|---|---:|---:|
| 회차 목록 | 42 | 0 |
| 최초 claim | 88 | 1 |

claim의 88행은 현재 Job 선점 1행, run 순서 조회 3회의 합계 6행, 출처 확인용 단건 조회
40행, 과거 참고 Job 조회 41행으로 구성된다. 서로 다른 Job 88개라는 의미는 아니다.
변경 후 1행은 **지금 선점하는 현재 Job**이며, 과거 Job의 입력·기준 상태·journal을 실어 온 행은 0이다.
반환 행이 없는 lease 회수 쿼리 등의 SELECT 자체는 남아 있다.

### 같은 작품의 목록과 claim을 함께 시작

| 두 요청 구간의 합계 지표 | 변경 전 | 변경 후 |
|---|---:|---:|
| 할당량 MiB | 1,965.72 (1,965.21–2,148.85) | 433.79 (433.35–617.63) |
| sampled heap peak MiB | 759.08 (753.54–762.73) | 413.69 (410.17–427.82) |
| 두 응답 완료까지 ms | 7,089.43 (6,944.89–7,100.00) | 1,875.13 (1,526.87–2,301.90) |
| GC 횟수 / 시간 ms | 30 / 114 | 3 / 5 |
| 큰 Job JSON 결과 행 | 130 | 1 |
| 전체 SELECT / 소비 행 | 1,740 / 5,366 | 1,582 / 3,646 |

변경 전 최고 관측점은 762.73MiB로 768MiB 상한에 가까웠지만 **이번 조건에서는 OOM이 발생하지 않았다**.
따라서 운영 OOM을 그대로 재현했거나 완전히 제거했다고 주장하지 않는다. 같은 규모의 누적 데이터와
두 동시 시작 요청에서 큰 과거 JSON 조회와 불필요한 복사를 줄이자 메모리 점유와 할당이 감소했다는 결과다.
client 구간 겹침은 변경 전 376.68–583.85ms, 변경 후 10.20–170.94ms였다. 개선된 목록이 빨리 끝나
동시 점유 시간도 짧아졌으므로, 동일 시간 동안 동시 부하를 유지한 시험으로 해석하지 않는다.

### 결과와 근거의 동등성

모든 실행에서 캐릭터 41개·세계관 181개·확정 Fact 641개·검토 대기 참고 3개가 유지됐다.
Fact 641개 모두 저장 근거가 VERIFIED이고 같은 인용문을 보존했다. 과거 Job 41개의 세 JSON과
후보 1,768개·Fact 641개 전체 행의 요청 전후 digest도 같았다. 입력의 DB JSON text 크기는
양쪽 모두 1,776,908 bytes이며, 단독 요청의 claim 응답 본문은 6개 표본 모두 123,636 bytes였다.

입력·claim·회차 목록의 전체 semantic hash가 각 실행 모드 안에서 변경 전/후 모든 표본에 일치했다.
v1/v2 journal의 실제 서비스 재생 결과 hash도 같았다. UUID에 종속되는 정렬·시각·lease만 위 규칙대로
처리했으며, 결과 내용·이력·인용·근거를 잘라내거나 요약해서 메모리를 줄인 결과가 아니다.

- [단독 요청 전체 지표·SQL profile·hash](gh374-history-memory-results.json)
- [동시 시작 요청 전체 지표·SQL profile·hash](gh374-history-memory-overlap-results.json)
- 원본 artifact 디렉터리 이름: `baseline-serial-1`, `candidate-serial-1`, `baseline-overlap-1`, `candidate-overlap-1`.
  로컬 `artifacts/gh374-history-memory/` 아래에 개별 SQL, 합성 정규화 입력·응답, JUnit XML, GC 로그를 보존했다.

준비 중의 `baseline-smoke-1`은 fixture DB 제약 실패, `baseline-smoke-2`는 수동 중단이었다.
`candidate-smoke-1`은 실제 과거 Job 41개 단건 로드가 남은 것을 발견해 실패했고, 해당 경로를
`AnalysisHumanRejectionPolicy`의 메타데이터 조회로 수정했다. 준비용 smoke는 성공한 실행까지 모두
공식 집계에서 제외했다. 위 결과는 수정 후 동일 하네스 SHA로 수행한 네 실행만 사용한다.

## 일반 회귀 테스트와의 구분

성능 하네스는 양쪽 모두 768MiB에서 완료했다. 일반 전체 테스트는 별도 JVM 설정을 사용한다.
초기 전체 실행과 두 클래스 격리 실행에서는 테스트마다 Spring/JPA Context를 다시 만드는
`AutomaticAnalysisIntegrationTest`의 Context 초기화 중 1GiB heap이 고갈됐다.
관측된 실패 위치는 repository query 초기화의 `QueryRenderer`였으며, 이를 위 HTTP 성능 시험의
OOM이나 운영 장애 재현으로 집계하지 않는다.

일반 회귀 테스트에는 2GiB heap, Context cache 1개, 25개 클래스마다 JVM 교체를 적용했다.
실제 분석 입력을 줄이거나 검증을 생략하지 않으며, 클래스 안의 테스트와 동시성 검증은 그대로 실행한다.
별도로 기존 알림 동시 선점 테스트의 준비 UPDATE를 executor 밖으로 이동했다.
준비 코드가 모든 발송 묶음을 잠가 실제 `SKIP LOCKED` 선점을 방해하던 경쟁을 제거한 것으로,
다른 묶음의 동시 선점과 오래된 lease 응답 거절 검증은 유지한다.

`./gradlew test bootJar` 전체 실행은 8분 39초 동안 끝까지 실행됐으며 Gradle 기준 4,646건 중
377건은 환경 조건에 따른 생략, 8건은 테스트 fixture/기대값 오류였다. 출처 열은 `updatable=false`인데
메모리에서만 바꾸던 fixture를 실제 DB 불일치로 바꾸고, 특정 회차의 유효한 관계도가 없을 때의
기존 `RELATIONSHIP_GRAPH_NOT_FOUND` 기대값을 바로잡았다. 제품 코드는 변경하지 않았다.

그 뒤 아래 명령으로 영향받은 세 클래스 **329건을 실패·생략 없이 재검증**하고 `bootJar`가 성공했다.

```bash
./gradlew test \
  --tests '*EpisodeRelationshipGraphIntegrationTest' \
  --tests '*EpisodeRelationshipGraphPostgresIntegrationTest' \
  --tests '*FrozenCurrentCaptureFailureIntegrationTest' bootJar
```

전체 실행의 나머지 4,261건과 수정 클래스의 329건에는 중복이 있으므로 합산한 수를 새 테스트 수로
표현하지 않는다. GitHub CI는 PR의 최종 소스에서 필터 없는 전체 테스트와 `bootJar`를 다시 실행한다.
