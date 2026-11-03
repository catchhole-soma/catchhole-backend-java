# GH365 세계관 claim 메모리·이력 저장 검증

`WorldComparisonMemoryPostgresIntegrationTest`는 실제 PostgreSQL/Flyway/JPA와 localhost Spring HTTP의
`POST /api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/claim-next`를 실행한다.
운영 DB·원고·사용자 식별자·S3·LLM을 사용하지 않는다. 저장소 mock 호출이 없었는지도 검사한다.

## 비교 조건

- 변경 전: `b6dc189`의 별도 checkout에 **동일한 테스트 파일과 JDBC 계측 helper만** 복사한다.
- 변경 후 legacy: 같은 60개 PENDING 후보와 기존 전체 배열 journal 형식으로 조회 변경만의 효과를 확인한다.
- 변경 후 compact: 동일한 시작 이력과 같은 새 이력 40개를 `APPEND_HISTORY`로 기록해 저장 방식의 효과를 분리한다.
- 테스트 JVM은 `-Xms256m -Xmx768m`, Gradle worker는 하나다. 제품 JVM 옵션은 변경하지 않는다.
- 입력은 DB `octet_length(automatic_input_state::text)` 기준 1,496,423 byte다.
  기존 journal은 40개 누적 `historyFacts` 전체 배열을 저장해 5,723,520 byte 전후로 교정한다.
  문자 수·공백이 포함된 `jsonb::text` 크기이며 PostgreSQL TOAST 압축 크기가 아니다.
- 후보는 같은 세계관 대상에 속한 60개다. 실제 HTTP로 20개씩 3번 claim하고 후보 ID 중복·누락,
  원문 순서, 값·인용, 입력 hash, journal 불변성 및 최종 160개 이력 전체의 canonical hash를 확인한다.
  이력 값과 인용은 전부 합성이며 실제 오류 원고의 데이터 분포를 재현한 것은 아니다.
- 고정 입력에는 인물 노드 2개(그중 하나는 비교와 무관한 합성 이력)와 세계관 대상 1개를 둔다.
  운영 사례의 인물 41개·세계관 181개 분포 및 다른 동시 요청의 live heap을 복제하지 않는다.
  60개 후보가 모두 `PROCESSING`으로 claim된 것을 확인하며 LLM 비교·complete·분석 성공까지 실행한 것은 아니다.
- 각 sample은 새 작품/Job을 만들고 setup이 끝나면 명시적으로 GC한 뒤 claim을 측정한다.
  첫 claim(60개 대기), 다음 claim(40개), 마지막 claim(20개)을 따로 기록한다.
  다른 Gradle/부하 테스트와 동시에 실행하지 않는다.
  세 종류의 대기 후보 수를 섞어 p95/p99를 계산하지 않으며, 각각 3회 관측의 중간값·범위만 비교한다.

## 측정 의미

- `JdbcResultMetrics`는 JDBC prepared SELECT의 SQL, 실제 ResultSet column 이름과 `next()`로 소비한 행 수를 기록한다.
  바인딩 값·결과 본문은 기록하지 않고 추가 `getString()`도 호출하지 않는다.
  `executeMillis`는 JDBC 실행/결과 반환까지만이며 Hibernate 매핑 시간은 포함하지 않는다.
- `candidateLogicalJobJsonBytes`는 Job JSON을 선택한 후보 결과 행 수 × DB의 입력+journal text byte다.
  **DB 조회 결과의 논리적 중복량**이며 실제 네트워크 전송량, heap 점유 또는 LLM 입력량이 아니다.
  같은 큰 Job을 단건으로 읽는 정상 조회는 이 후보 중복량에서 제외한다.
- `sampledPeakHeapBytes`는 JVM heap을 5ms 간격으로 관측한 최댓값이다. 실제 절대 peak·RSS가 아니다.
  `heapBeforeBytes`와 `heapAfterBytes`는 작업 경계의 사용 heap이며 after에 GC를 강제하지 않는다.
- `allocatedBytes`는 HotSpot의 전체 platform-thread 누적 할당량 차이다. HTTP server/client와 계측을 포함하며
  살아 있는 객체 크기가 아니다. GC 횟수·시간, JVM process CPU 시간과 GC 로그도 함께 기록한다.
- append 측정은 준비 상태를 한 번 만든 뒤 한 트랜잭션에서 메모리 journal 변경 40개를 차례로 append하고 최종 flush를 한 번 한다. 40번의 DB 저장 왕복 측정이 아니다.
  기존 전체 배열과 새 추가 행 배열이 동일한 이력·인용을 복원하는지 검사한다.
  fixture 생성·크기 교정·해시 사후 검증은 claim 시간에서 제외한다.
- 응답 비교 hash는 매번 새로 생성되는 후보 UUID와 source envelope를 제외한 후보의 전체 응답 필드다.
  출처 envelope의 입력 hash는 각 sample의 고정 입력과 별도로 비교한다.
- OOM/timeout이면 `*-started.json`, Gradle 종료 코드와 GC 로그를 보존한다. 실패한 실행을 지연 0ms나
  성공 sample로 집계하지 않으며, 성공 응답이 없는 조건은 응답 전후 동등성을 주장하지 않는다.

## 재현

Docker가 필요하다. 기본 전체 test 실행에서는 이 큰 fixture가 생략되며 아래 runner가 명시적으로 활성화한다.
각 실행은 같은 기준의 계측을 사용하므로 후보 JSON 조회 시 추가 직렬화로 부하를 만들어내지 않는다.

```bash
# baseline checkout에는 두 파일을 현재 checkout과 동일하게 복사한다.
# src/test/java/org/monitoring/catchholebackend/support/JdbcResultMetrics.java
# src/test/java/org/monitoring/catchholebackend/domain/worldsetting/service/WorldComparisonMemoryPostgresIntegrationTest.java
scripts/load-tests/comparison-memory/run.sh /absolute/baseline /absolute/results/baseline legacy baseline 3
scripts/load-tests/comparison-memory/run.sh "$PWD" /absolute/results/legacy legacy candidate 3
scripts/load-tests/comparison-memory/run.sh "$PWD" /absolute/results/compact compact candidate 3
python3 scripts/load-tests/comparison-memory/summarize.py \
  /absolute/results/baseline /absolute/results/legacy /absolute/results/compact
```

runner는 heap init script, Java/Docker 버전, HEAD, uncommitted diff 목록, JUnit XML, GC 로그와 JSON 결과를 남긴다.
동일한 결과 폴더를 재사용하면 이전 실패 파일이 섞일 수 있으므로 실행마다 빈 폴더를 사용한다.
생략 건수가 아닌 실제 실행·성공 건수와 `exit-code.txt`를 확인한다.

## 실행 결과

2026-11-03, Java 21.0.11·PostgreSQL 16.14·Docker 29.6.2(할당 메모리 7.75GiB)에서 순차 실행했다.
세 조건 각각 **3개 fixture / 9개 HTTP claim**이 성공했다. JVM 인자로 `-Xms256m -Xmx768m`을 확인했다.
세 조건의 최종 이력·인용 hash와 후보 응답 semantic hash가 모두 같았다.

다음 표는 **첫 claim: 대기 후보 60개**의 3회 관측이다. 시간·할당량·GC·CPU는 중간값,
sampled peak heap은 관측 범위다. JVM heap 768MiB에서 baseline OOM 자체는 재현되지 않았다.

| 지표 | 변경 전 + 기존 journal | 조회 개선 + 기존 journal | 조회 개선 + 추가 이력 journal |
| --- | ---: | ---: | ---: |
| HTTP 경과 시간 | 2,597.99ms | 170.08ms | 73.63ms |
| sampled peak heap | 748.84–759.00MiB | 395.58–407.25MiB | 289.43–303.36MiB |
| JVM 할당량 | 1,060.52MiB | 233.39MiB | 132.25MiB |
| JVM process CPU | 894.61ms | 321.17ms | 272.49ms |
| GC 누적 시간 | 21ms | 3ms | 3ms |
| 큰 Job JSON을 선택한 후보 결과 행 | 120 | 0 | 0 |
| 위 행의 논리적 입력+journal 중복량 | 826.23MiB | 0 | 0 |

변경 전 `pendingCandidates`와 비교 그룹 조회가 각각 60행에서 같은 Job JSON을 선택했다.
실제 ResultSet 열 수는 각각 **154→93, 144→83**이다. 후보 행은 그대로 읽고 부모 Job의 큰 JSON 열을 제외했다.
Job 자체의 잠금·고정 입력 검증에 필요한 단건 조회까지 없앴다는 뜻은 아니다.

각 요청은 후보 수와 직전 요청이 남긴 heap 상태가 다르므로 분리해 기록한다.
괄호는 3회 관측의 최솟값–최댓값이며, 별도 warmup 없는 첫 sample도 포함한다.

| 대기 후보 / claim 후보 | 변경 전 경과 시간 | 조회 개선 + 기존 journal | 조회 개선 + 추가 이력 journal |
| --- | ---: | ---: | ---: |
| 60 / 20 | 2,598ms (2,559–2,835) | 170ms (154–374) | 74ms (72–266) |
| 40 / 20 | 1,829ms (1,819–1,837) | 160ms (159–191) | 59ms (57–111) |
| 20 / 20 | 1,020ms (989–1,025) | 166ms (165–181) | 61ms (60–69) |

2·3차 claim의 sampled peak는 baseline 최대 761.42MiB, 조회 개선+기존 journal 최대 475.11MiB,
추가 이력 journal 최대 460.94MiB였다. 다음 요청마다 GC를 강제하지 않으므로 사용 heap이
단일 요청의 필요 메모리나 live set과 같다고 해석하지 않는다.

이력 저장 효과는 같은 변경 후 코드에서 **한 트랜잭션의 append 40회 후 최종 flush 1회**로 별도 측정했다.
입력 1,496,423 byte와 최종 160개 이력·근거는 동일하다.

| 지표 | 전체 배열 변경 40개 | 추가 행 변경 40개 |
| --- | ---: | ---: |
| `state_journal::text` | 5,723,311 byte | 43,273 byte |
| 저장량 감소 | — | 99.24% |
| append 경과 시간 중간값 | 149.13ms | 32.74ms |
| JVM 할당량 중간값 | 551.51MiB | 134.87MiB |
| JVM process CPU 중간값 | 507.08ms | 61.53ms |

이 값은 합성 40회 변경의 결과이며 모든 작품에서 같은 비율의 감소를 보장하지 않는다.
변경 전과 legacy 실행에서는 기존 v1 journal을 그대로 읽었고 compact 실행은 v2를 저장했다.
기존 저장 데이터를 압축 형식으로 일괄 변환하지 않았다.

기계가 읽을 수 있는 관측값·단계별 범위·fixture/계측 파일 SHA-256은
[`gh365-comparison-memory-results.json`](gh365-comparison-memory-results.json)에 보관한다.
동일한 최종 테스트 파일을 baseline/current checkout 양쪽에 사용했다.

상세 로컬 artifact 없이도 검토할 수 있도록
[`gh365-comparison-memory-evidence.json`](gh365-comparison-memory-evidence.json)에 27개 요청의
runtime·HTTP 상태·후보 중복 행/논리 byte와 모든 SELECT 종류별 실제 실행 횟수·소비 행 수를 보관한다.
공통 `queryProfiles`는 실제 ResultSet 열 수와 두 Job JSON 열의 포함 여부를 나타내며,
`uniqueSql`에는 변경 전·후 pending/group 조회의 실제 SQL 4개와 ResultSet column 목록만 중복 없이 담았다.
바인딩·상태 JSON·원문·fixture 식별자는 포함하지 않는다.
첫 baseline 요청의 두 후보 조회가 각각 60행이고 두 JSON 열을 선택해 합계 120행이 되는 것을 직접 확인할 수 있다.

아래 export는 저장된 raw 27개 요청으로부터 중복량을 다시 계산하고,
기존 summary의 각 단계별 시간·heap·할당량·GC·CPU 중간값과 범위를 모두 대조한 뒤 evidence를 작성한다.
측정/테스트를 재실행하지 않는다.

```bash
python3 scripts/load-tests/comparison-memory/export_evidence.py \
  /absolute/artifacts/gh365-memory \
  docs/load-tests/gh365-comparison-memory-results.json \
  docs/load-tests/gh365-comparison-memory-evidence.json
```

로컬 상세 artifact는 workspace 상위 `artifacts/gh365-memory/` 아래 `baseline-legacy-final`,
`candidate-legacy`, `candidate-compact`에 있다. baseline의 사전 1회 smoke 실행은 최종 표에서 제외했다.
첫 runner가 긴 JUnit 보고서 파일명에 붙는 Gradle hash를 처리하지 못해 candidate-legacy의 XML은 보존하지 못했다.
그 실행의 성공은 Gradle 로그·종료 코드·9개 HTTP 결과·3개 parity 기록으로 확인했고,
baseline/compact XML은 보존했다. runner는 현재 hashed 파일명도 복사한다.

운영보다 단순한 대상 분포, 다른 DB 지연·동시 부하, 첫 요청 JIT 영향이 있으므로 운영 p95/p99,
동시 사용자 수용량 또는 OOM 완전 제거의 증거로 사용하지 않는다. 확인된 것은 같은 큰 기존 journal을 둔
실제 claim 경로에서 불필요한 Job JSON 결과 중복이 없어지고 할당량이 줄었으며,
추가 이력 저장이 같은 최종 내용을 훨씬 작은 journal로 표현한다는 점이다.

## 기능·호환 회귀

- 전체 `./gradlew test bootJar`: 총 4,761개 중 **4,441 passed / 320 skipped / 실패 0**, 빌드 성공(6분 23초). 위 성능 실행은 opt-in 별도 검증이다. 신규 PostgreSQL 조회·잠금 17개는 생략 없이 통과했다.
- 동반 Python reader 전체: **5,221 passed / 145 skipped**, 집중 88개 및 Ruff 통과. [AI PR #125](https://github.com/catchhole-soma/catchhole-backend-ai/pull/125).
- 추가로 새 전용 localhost PostgreSQL에서 `OrderedAnalysisHttpWorkerIntegrationTest.tenEpisodesCarryTemporaryIdentitiesAddRemoveAndWorldUpdatesAcrossRealHttp`를 실행했다. 실제 Spring–Python HTTP로 합성 10회차를 처리했고 **SUCCEEDED + SEALED 10개, v2 journal 10개**를 확인했다. Python 독립 재생이 10개 입력/출력 hash와 최종 typed projection을 대조해 통과했다(1 passed, 테스트 10.711초).
- 이 실제 연동은 수동 누적 임시 상태의 캐릭터·세계관 비교이며 **테스트 힙 1GiB, 관계 기능 OFF, 외부 LLM 대신 합성 provider 50회 호출** 조건이다. 공식 설정 자동 확정이나 관계 분석 완료까지 검증한 사례는 아니다. 최초 실행은 기존 하네스의 관계 stub 부재로 API key 검사에서 외부 호출 전에 중단됐고, 테스트 범위 밖 관계 기능만 끈 재실행에서 통과했다. 운영 설정은 바꾸지 않았다.
- DB의 추가 이력 이벤트는 10개(내용이 있는 이벤트 5개), 추가 이력 행은 30개였다. 기존 출처·의존 기여의 별도 행은 유지하므로 REMOVE 결정 수와 동일한 숫자로 해석하지 않는다.
