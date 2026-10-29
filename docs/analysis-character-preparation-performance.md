# 누적 분석 준비의 PostgreSQL 성능 검증 — GH294

`CharacterPreparationPostgresPerformanceIntegrationTest`는 후보별로 같은 입력을 재조회·복원하던 비용과 요청 내 재사용 변경을 비교한다. 실제 PostgreSQL, Flyway, JPA 트랜잭션과 localhost Spring HTTP를 사용한다. 운영 데이터, S3, LLM과 유료 호출은 사용하지 않는다.

## 재현 범위

- 격리 Testcontainer: `pgvector/pgvector:0.8.2-pg16`. 기존 DB와 `.env`를 읽지 않으며 종료할 때 컨테이너를 제거한다.
- 한 작품의 25회차 자동 분석 run. 선행 24회차는 실제 `prepareInput → seal → completeAutomaticApplication → succeed`로 빈 변경 기록을 완료한다.
- 현재 회차의 고정 입력에 과거 인물 한 명의 합성 `historyFacts` 배열을 넣는다. 배열의 UTF-8 크기가 약 1.21MB가 되도록 사실 값, 서사 시점, 근거를 반복 생성한다. 이력의 실제 생성 과정이나 운영 JSON 구조별 분포를 재현한 것으로 해석하지 않는다.
- 캐릭터는 발견 후보 1개와 같은 임시 인물의 설정 후보 534개다. 동일 anchor를 공유하지만 모든 후보 ID와 원문 근거는 보존한다. 일치하지 않는 반려 정책 한 개를 고정 입력에 넣어 발견 후보 조회의 요청 내 재사용도 검증한다.
- 세계관은 같은 큰 입력에서 후보 35개를 새 임시 대상 하나에 연결한다. 첫 후보가 등록한 대상을 같은 요청의 나머지 후보가 실제로 참조할 수 있는지 검증한다.
- 별도 캐릭터 조건은 고정 입력을 작게 두고 현재 회차 journal에 같은 `historyFacts` 경로의 누적 배열 57개를 실제 `appendValidatedChanges`로 저장한다. 각 배열은 최대 32개 항목이며 원문·시점을 포함한 합성 journal 약 0.9~1.05MB를 사용한다. 준비 뒤 최종 32개 이력의 canonical hash와 58개 journal 변경(인물 등록 1개 포함)을 모두 확인한다.

총 후보 수만 사고와 맞춘 합성 조건이다. 운영의 535개 후보가 가진 상태·인물 수·반려 정책·시점 분포와 동일하지 않으므로, 운영의 SELECT 1,029회를 정확히 재생한 테스트가 아니다. localhost DB 지연도 운영 네트워크 지연과 다르다.

## 검증 항목

1. 작은 입력에서 새 임시 인물 등록이 같은 준비 안의 후속 후보에 보이고, 다음 요청에서 준비를 반복해도 journal에 중복 등록하지 않는다.
2. 535개 후보 준비와 재준비의 SQL 수·경과 시간을 각각 측정한다. run-chain 조회는 4회 이하, 단건 anchor 조회는 2회 이하를 요구한다.
3. 실제 HTTP claim의 클라이언트 제한을 30초로 두고 200 응답, 설정 534개 전체의 값·JSON·인용·입력 hash를 확인한다. 스키마 조회도 후보 수와 무관하게 한 번만 실행하는지 검증한다.
4. 세계관 주체 해소 35건의 SQL 수·경과 시간과 새 대상의 후속 참조를 확인한다.
5. 모든 경로 뒤에 고정 입력 전체의 canonical hash를 비교하여 과거 이력·시점·근거가 변경되지 않았는지 확인한다. JSONB 왕복에서 `LongNode`가 `IntNode`로 바뀔 수 있으므로 Java 노드 클래스 동등성은 사용하지 않는다.
6. 큰 고정 입력과 큰 현재 journal을 분리해 측정한다. 후자는 반복 조회에 따른 큰 JSON 로딩과 journal 복원 비용을 함께 포함하며 마지막 배열의 내용이 그대로인지 별도로 검사한다.

`StatementInspector`는 측정 대상 호출 동안의 SELECT만 집계한다. fixture 저장과 사후 검증 조회는 제외하고, HTTP 응답까지의 경과 시간에는 실제 트랜잭션과 응답 직렬화를 포함한다. 쿼리별 서버 실행 시간이나 CPU 시간은 별도로 계측하지 않는다. 최초 실행과 반복 실행의 부가 조회가 달라질 수 있으므로 결과를 각각 보존한다.

## 실행

Docker가 실행 중이어야 한다. Docker가 없으면 테스트 클래스가 생략되므로 `BUILD SUCCESSFUL`만으로 실제 DB 검증 완료를 판단하지 않는다. 테스트 XML의 실행 건수도 확인한다. 성능 측정 중에는 다른 Gradle/부하 테스트를 병렬 실행하지 않는다.

```sh
GH294_PERFORMANCE_OUTPUT=/absolute/path/to/after-results \
./gradlew test --tests '*CharacterPreparationPostgresPerformanceIntegrationTest' --no-daemon --console=plain
```

기준 소스는 GH294 작업 시작 HEAD `d26bb5358921c1c22ddcdf9baf4218eba885eb1e`다. 동일 테스트를 기준 소스의 별도 checkout에 복사하고 아래 측정 모드로 실행한다. 이 모드는 반복 조회 상한만 면제하며, 상태·결과 검증과 HTTP 30초 제한은 유지한다. 제품 설정을 바꾸지 않는다.

```sh
GH294_MEASURE_BASELINE=true \
GH294_PERFORMANCE_OUTPUT=/absolute/path/to/baseline-results \
./gradlew test --tests '*CharacterPreparationPostgresPerformanceIntegrationTest' --no-daemon --console=plain
```

각 실행은 `GH294_PERFORMANCE` 접두사 로그와 단계별 JSON에 후보 수, 고정 입력 byte 수, 경과 밀리초, 전체 SELECT·run-chain·단건 anchor·schema 조회 수를 기록한다. 추가 journal 조건에는 `journalBytes`도 기록한다. 출력 디렉터리는 실행마다 분리한다.

## 관련 정합성 회귀

성능 테스트의 전체 입력 보존 외에도 `AnalysisHumanRejectionPolicyTest`, `CharacterDiscoveryRejectionTest`, `CharacterNarrativeTimeRollbackTest`, `CharacterGroupExclusionIntegrationTest`, `AutomaticAnalysisIntegrationTest`로 정확한 원문 반려, 동명이인 구분, 이력·서사 시점, 사용자 최종 선택과 자동 분석 경계를 함께 확인한다. 성능 개선을 위해 이력·근거를 생략하거나 오류를 무시하지 않는다.

## 2026-10-29 localhost 측정

기준은 `d26bb535`, 변경 후는 같은 checkout의 GH294 변경이다. 최초 네 테스트를 같은 순서로 각각 실행한 뒤, 큰 journal 테스트를 기준/변경 후에서 각각 한 번 더 실행했다. 총 5개 테스트가 양쪽 모두 실패·생략 없이 통과했다. 기준 실행에서만 위의 명시적인 측정 모드로 반복 조회 상한을 면제했다.

환경은 Java 21.0.11, PostgreSQL 16.14, Docker Engine 29.6.2다. 호스트 논리 CPU 15개·메모리 48GiB, Docker 메모리 약 7.75GiB, 테스트 JVM 최대 heap 1GiB를 사용했다. 다른 Gradle/부하 테스트를 동시에 실행하지 않았다.

| 측정 조건 | 변경 전 SELECT | 변경 후 SELECT | 변경 전 경과 시간 | 변경 후 경과 시간 |
| --- | ---: | ---: | ---: | ---: |
| 535개 후보 준비, 고정 입력 1,211,073 bytes | 1,707 | 104 | 15,373ms | 244ms |
| 같은 상태에서 다시 준비 | 1,637 | 33 | 15,529ms | 108ms |
| 535개 후보의 실제 HTTP claim, 같은 큰 입력 | 2,248 | 109 | 16,067ms | 571ms |
| 세계관 35개 주체 해소, 같은 큰 입력 | 99 | 62 | 572ms | 155ms |
| 535개 후보 준비, 고정 입력 541 bytes + journal 925,513 bytes | 1,668 | 57 | 8,856ms | 203ms |

주요 반복 조회의 변화는 다음과 같다.

| 경로 | run-chain 조회 | 단건 anchor 조회 | 스키마 조회 |
| --- | ---: | ---: | ---: |
| 캐릭터 준비·재준비 | 1,070 → 2 | 534 → 1 | 0 → 0 |
| 캐릭터 HTTP claim | 1,071 → 3 | 534 → 1 | 535 → 1 |
| 세계관 주체 해소 | 39 → 2 | 0 → 0 | 0 → 0 |
| 큰 journal의 캐릭터 준비 | 1,070 → 2 | 534 → 1 | 0 → 0 |

첫 호출·반복 호출의 전체 SQL 수에는 초기 연관 엔티티 조회 등 부가 비용 차이가 있다. 같은 조건의 run-chain·anchor 반복은 위 표처럼 제거됐지만, 전체 SELECT가 모두 없어졌다는 의미는 아니다. 스키마나 같은 인물의 원문 근거를 생략하지 않고 요청 안에서 재사용한 결과다.

이 값은 각 조건의 로컬 단일 실행 관측이며 p95/p99나 운영 지연 보장이 아니다. HTTP의 30초 제한을 올리지 않고 같은 대규모 합성 fixture를 통과했고, 운영 네트워크·동시 부하에 대한 재측정은 별도다. 큰 고정 입력과 큰 현재 journal은 서로 다른 테스트 조건이므로 두 비용을 합친 운영 시간으로 환산하지 않는다.

## 최종 전체 회귀

같은 제품 코드에서 `./gradlew test bootJar`도 성공했다. 4,123개 중 3,865 passed / 258 skipped / 실패·오류 0이며, 이 성능 클래스의 5개 테스트는 모두 실행됐다. 위 전후 비교표는 다른 부하 없이 별도로 측정한 값이며 전체 회귀 실행 중의 시간으로 덮어쓰지 않는다.
