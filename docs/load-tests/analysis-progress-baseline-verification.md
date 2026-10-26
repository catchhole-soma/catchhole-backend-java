# #291 진행 조회 baseline 검증

2026-10-24 GPT-6 Astra 독립 검증. 측정 중에는 저장된 파일만 읽었으며 요청·DB 조회·빌드·프로세스 조작을 실행하지 않았다. 결과 생성 이후 보존 JAR와 프런트 자산의 SHA-256을 다시 계산해 manifest와 일치함을 확인했다. 제품·실행기 코드는 변경하지 않았다.

## 판정

현재 baseline은 **중단된 순차 분석 진행 화면의 전후 비교 기준으로 사용 가능**하다. 공식 원자료는 workspace `output/analysis-progress-optimization-20261024/evidence/before/`이며 `smoke-before/`와 섞지 않는다. 성능 개선·운영 OOM 해결 판정은 after 및 SQL 검증 전에는 하지 않는다.

## 사전 점검

- baseline manifest의 BE `79c2b0ee3e872bf38c8da57480736de238184491`, FE `74d10ece56698233ecad000025918f9648e4f29c`와 tracked diff 없음 확인. 실제 실행 JAR hash `6e14ef0f4f4994e4dedb5f66ebd3ccb1deff8a56c3bfef7844957a0c273d511a` 및 보존된 frontend-before 전체 자산 hash 재검증 일치.
- Java21.0.12.1의 실제 MaxHeapSize=805306368(768MiB), InitialHeapSize=256MiB. PostgreSQL16.14 컨테이너 2CPU/1GiB, 호스트 native Java/production Vite preview. API CPU/RSS의 별도 제한은 없다. 실행기는 localhost 전용 포트/API/DB를 검사하며 .env import와 기본 Compose를 끈다.
- `fixture-first.json`과 `fixture.json` 동일. manifest 내용에서 hash 필드를 제외해 재계산한 SHA-256은 `586ec0a7cefd73e6807c43649ab4bd632dc3b587a67e89a6bb42fa63c8275d08`과 일치한다.
- 30회차/30작업, SUCCEEDED+SEALED+automaticAppliedAt 22개, FAILED+INCOMPLETE 1개, 뒤 PENDING 7개. 동일 run/generation의 predecessor/sequence로 뒤 회차가 차단된다. 세 JSONB::text UTF-8 합계 18,161,746bytes(약17.32MiB), 완료 작업17,301,914bytes, 최대 작업1,803,551bytes. 분산된 짧은 합성 fact/provenance/evidence 객체를 사용하며 단일 거대 문자열만으로 맞춘 fixture가 아니다.
- 캐릭터 후보410개, 세계관404개는 모두 비교 완료다. 따라서 이 fixture는 세계관 페이지의 최초 조회 비용을 포함하지만 비교 진행 중의 연속 polling을 측정하지 않는다. 대응하는 active 상태 측정은 별도 조건으로 양쪽 binary에서 수행해야 한다.
- 브라우저 준비 조건은 30개 실제 카드, 자동 반영 완료22/비교 미완료1/앞 회차 대기7, 중단 제목과 활성 재개 버튼을 2RAF 뒤 재확인한다. 스크린샷과 저장된 visible text도 실제 중단 화면과 일치한다. network idle을 준비 시점으로 사용하지 않는다.

## 완료된 공식 측정 관측

공식 summary 생성 후 raw pages/API와 대조했다. Chromium149.0.7827.55, 1280×800, navigation마다 새 context/HTTP cache disabled, 준비3회와 본 navigation10회를 분리한다. 공식 측정 전 JVM을 재시작했고 health만 먼저 호출했다는 실행자 설명을 받았다. `initial-cold-jvm-request`는 첫 job round로 해석하며, 뒤 comparison은 이미 같은 JVM에서 job round가 수행된 상태다. OS/DB cold cache나 완전 미사용 JVM을 의미하지 않는다.

| 완료 관측 | 결과 |
|---|---|
| navigation10회 | 실패0, ready 중앙327.50ms / p95·최대476.90ms |
| window load | 중앙63.85ms / p95·최대194.50ms |
| job round30회 | 실패0, 30개 병렬 상세 GET의 round 완료 중앙141.39ms / p95158.38ms / 최대158.91ms |
| world comparison30회 | 실패0, 중앙1318.79ms / p951452.86ms / 최대1475.20ms |
| steady polling3×65초 | 각630개 job-detail 요청, 각1,044,288 encoded body bytes, 모두 성공·collector 오류0·censored0 |
| resource 수집 | 554개 표본, processUnavailable0; 마지막 표본은 summary 생성 직전 |

job-round latency는 개별 GET latency가 아니라 30개 응답이 모두 완료될 때까지의 시간이다. API 직접 측정 bytes는 decoded body이며 브라우저 `request.sizes()`의 encoded body와 구분한다. navigation 표본10개의 nearest-rank p95는 최대값과 같으므로 표본 한계를 명시한다. 자원 누적 비교는 전체 실행 표본과 각 steady window를 구분하고 측정 시간으로 정규화해야 한다.

## after 비교 및 보고 조건

1. `summary.polling[].requests`는 navigation부터의 요청을 포함한다. 65초 steady 호출 수는 반드시 raw의 `observationStartMs <= request.startMs <= observationCutoffMs`로 다시 계산한다. 위630은 해당 범위를 적용한 값이다. 최초 진입 요청과 steady 요청을 합쳐 분당 수치로 설명하지 않는다.
2. 같은 fixture/script/runner hash, JVM flags, DB 제한, browser/artifact 조건을 대조한다. fixture hash는 분석 JSON/world payload hash·상태 집계·건수·생성 script를 포함하지만 DB 전체 모든 열의 hash는 아니다. bcrypt/auth 제외와 함께 이 범위를 유지하고 중간 mutation이 있으면 원래 fixture를 복원/검증한다.
3. 별도 active 전환 케이스는 전후 같은 전환·window를 써서 검증하며, 중단 상태의630→idle polling 결과와 섞지 않는다. 실패·censored·오류를 성공 표본 통계에서 조용히 버리지 않는다.
4. after SQL/성능 결과와 실제 자동 재개/무효화/반영 latch 회귀는 이 검증서의 완료 범위 밖이다. SQL 수집은 타이밍 후 실행하고, 큰 JSON 조회 제거는 실제 SELECT와 entity-load 테스트로 확인한다.
5. JSON 논리 bytes는 HTTP payload/Java retained heap과 다르다. 0.5초 RSS/heap 표본 최고치는 실제 순간 peak를 보장하지 않는다. 합성 readonly 관측으로 retry write-path OOM 또는 운영 장애 해결을 주장하지 않는다.

## SQL·metadata 사후 대조

`before/sql/index.json`의 전체167 SELECT·분석 JSON 포함33 SELECT는 **4종 별도 호출 집합**의 합계다. 실제 페이지 한 번의 SQL 수가 아니다. 모든 bind가 치환됐고 EXPLAIN 오류0이다.

| 별도 호출 | 실제 SELECT | 분석 JSON 포함 SELECT |
|---|---:|---:|
| job-one: 상세1개 | 5 | 1 |
| jobs-thirty: 상세30개 | 150 | 30 |
| comparison: 세계관 page0/size1 | 7 | 1 |
| episodes: 회차 목록 | 5 | 1 |

job-one은30개 round와 중복된 비교용 표본이다. 작품·인증 등 다른 페이지 호출 SQL을 수집하지 않았으므로 이 합계를 전체 페이지 비용으로 설명하지 않는다. EXPLAIN execution time은 JDBC 전송·Hibernate JSON 변환·HTTP 완료 시간을 포함하지 않는다.

`before/fixture-readback.json`을 별도로 읽어 job30/target30, sequence0–29, generation1, predecessor 연속 연결, job별 회차 매핑,22/1/7 상태 및 automaticAppliedAt22개의 일치를 검증했다. world404의 PENDING_REVIEW+COMPLETED 분포도 확인했다. 이 metadata readback hash는 `f1f7f56f0fcc10bbc484aa46ca4c45bb17ff0cdbfd9da04307d55e99c35d28d1`이며 after에서 동일 필드 readback과 대조해야 한다.

## 별도 active BEFORE

`before-active/active.json`과 전용 runner를 대조했다. 명시된15,000ms 구간에 시작한 요청은 개별 job124개, 전체 세계관 후보 페이지3개이며 오류·실패·censored0이다. runner hash도 저장값과 같다. 초기 navigation 이후 RUNNING 제목과 카드30개를 확인해 측정하며, 3초 타이머의 경계와 응답 완료 후 예약 때문에124개를 정확히4회/5회 round로 반올림하지 않는다. after 역시 같은 기준으로 구간을 필터링한다.

이 케이스는 미리 준비된 RUNNING/INCOMPLETE 작업과 PROCESSING 비교 상태의 **active polling 표본1회**다. 실제 Worker가 실패→재개→완료로 상태 전이한 증명이나 반복 표본의 p95가 아니다. 22/1/7 steady 결과와 분리한다. active 수집 후 `fixture-first.json`과 현재 `fixture.json`이 byte-identical임을 확인했으며, 원래 fixture 복원은 실행자의 reseed 결과에 근거한다. 검증자가 DB 재조회를 수행하지는 않았다.

Baseline 검증 완료: main 표본·SQL ledger·metadata·active 표본에서 구현 진행을 막을 문제를 발견하지 않았다. 위 측정 범위와 보고 조건은 after 비교에도 적용한다.
