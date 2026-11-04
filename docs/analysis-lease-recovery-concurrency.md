# 만료 lease 회수 경합 보정

관련 이슈: [#282](https://github.com/catchhole-soma/catchhole-backend-java/issues/282)

## 문제와 재현 근거

여러 Worker가 만료 Job을 회수할 때, 먼저 재claim된 Job의 새 lease까지 뒤따른 요청이 다시 회수할 수 있었다. 새 실행의 claim 횟수가 3이면 아직 유효시간이 남았는데도 `WORKER_LEASE_EXPIRED`로 실패시켰다.

운영과 같은 Java `34804254`, PostgreSQL 16.14 / READ COMMITTED / Hibernate 7.2.12.Final을 사용한 격리 시험에서 다음 상황을 재현했다.

| 동시 실행 순서 | 기존 결과 | 필요한 동작 |
| --- | --- | --- |
| 만료 후보 선정 뒤 다른 Worker가 두 번째 실행을 claim | 299초 남은 lease를 다시 회수하여 세 번째 실행으로 배정 | 새 두 번째 실행 유지 |
| 만료 후보 선정 뒤 다른 Worker가 세 번째 실행을 claim | 299초 남았는데 즉시 FAILED | 새 세 번째 실행 유지 |
| 만료 후보 선정 뒤 별도 트랜잭션이 expiry를 연장 | 599초 남았는데 FAILED | 연장된 실행 유지 |

기존 세 시험은 모두 안전성 assertion에 실패했고, 잠금 후 상태·만료 조건을 재검사하는 진단 변경에서는 모두 통과했다. 세 번째는 Repository 수준의 expiry 변경 시험이며, 이미 만료된 lease로 heartbeat API가 성공한다는 의미는 아니다.

실제 PostgreSQL에서 기존 JPA의 fetch join + `PESSIMISTIC_WRITE`는 다음 두 SQL로 나뉘었다.

```sql
-- 후보 선정 시점에는 만료됐던 작업
select ... from analysis_jobs ...
where status = ? and ...
  and (lease_token is null or lease_expires_at is null or lease_expires_at <= ?);

-- 이후 해당 ID를 잠글 때에는 최초 만료 조건이 없다.
select ... from analysis_jobs tbl
where tbl.id in (?) for no key update of tbl;
```

두 statement 사이에 다른 Worker가 재claim을 커밋하면, 후속 잠금에서 새 claim 횟수·lease를 읽어도 처음 선정한 후보 목록에는 그대로 남는다. 기존 서비스는 현재도 회수 가능한지 재검사하지 않고 예약 해제·재대기·최종 실패를 수행했다.

운영의 새 실행 시각 직후 실패 기록은 이 재현과 일치하지만, 과거 운영 SQL의 정확한 동시 실행 순서를 소급 증명한 것은 아니다. 별도의 긴 관계 준비·heartbeat timeout과 이 회수 결함을 구분한다.

## 회수 경계

최신 main의 메모리 개선 #375 위에서 `AnalysisJobLeaseRecoveryRepository`가 후보 선정과 잠금 후 검증을 담당한다.

1. 기존 허용 Job 종류·만료 조건·scan 한도 20건으로 Job ID와 Work ID만 조회한다. 일반 Worker 회수에서 관계 전용 `RELATIONSHIP_EXTRACTION`은 제외한다.
2. Work를 일관된 순서로 `SKIP LOCKED` 잠그고 최신 상태를 읽어 ACTIVE인지 검사한다.
3. 해당 Work의 Job을 일관된 순서로 `SKIP LOCKED` 잠그고 최신 상태를 읽는다. 같은 영속성 컨텍스트에 이전 값이 있더라도 DB 상태로 판단한다.
4. 현재 `RUNNING`, 허용 종류, token/expiry 누락 또는 실제 만료 조건을 충족하는 Job만 반환한다.
5. 서비스는 검증된 Job에만 예약 토큰 해제, 비교 묶음 종료, 후보 복구·실패, 회차 실패, 재대기·최종 실패를 수행한다.

Work → Job 순서를 기존 claim·자동 반영과 맞춘다. 다른 요청이 사용 중인 행은 다음 claim에서 다시 회수할 수 있도록 건너뛴다. 잠금 획득 뒤 최신 상태를 읽는 작업은 예약·후보 등의 부수효과 전에 끝내며, 한 Work를 중복 refresh하지 않는다. 요청 안에 미반영한 변경이 있다면 refresh 전에 flush하여 해당 변경을 잃지 않도록 한다.

반환 순서는 원래 후보의 만료 순서를 유지한다. scan 상위 20건이 계속 잠겨 있으면 뒤의 만료 Job이 늦게 회수될 수 있는 유한 scan의 한계는 남는다. lease 5분·최대 claim 3회·checkpoint 보존·이전 token 거절 규칙은 그대로다. 세 번째 실행의 유효한 lease는 정상이며, 실제 만료됐을 때에만 한도 정책으로 실패한다.

## 검증

실제 PostgreSQL Testcontainers 회귀는 `AnalysisLeaseRecoveryPostgresIntegrationTest`에서 후보 SELECT와 행 잠금 경계를 latch로 제어한다. 각 Worker와 상태 변경은 별도 트랜잭션으로 커밋하며, 모든 Future와 latch에 제한 시간을 둔다. 우연한 sleep에 의존하지 않고 lease token 원문도 로그에 남기지 않는다.

검증 대상:

- 설정 추출·캐릭터 비교·세계관 비교의 새 두 번째/세 번째 lease 보호
- Job·Work의 1차 캐시, expiry 연장, terminal 상태 및 Work 삭제 전이
- 현재 실행의 예약·후보·비교 묶음 보존, 이전 lease 보고 거절과 현재 실행 완료
- 실제 세 번째 lease 만료와 token/expiry 누락 시 정상 회수
- 잠긴 Work/Job을 건너뛴 다른 작품의 claim 및 관계 전용 회수 제외

```shell
./gradlew test --tests '*AnalysisLeaseRecoveryPostgresIntegrationTest' --tests '*AnalysisJobWorkerServiceCharacterBatchRecoveryTest'
./gradlew test bootJar
```

Docker가 없어 생략된 경우는 PostgreSQL 검증 성공으로 계산하지 않는다. 최신 소스의 실행 결과는 관련 PR에 기록한다. 운영 DB·실제 LLM·운영 장애 주입은 이 회귀의 대상이 아니다.

## 범위

이 수정은 새 실행을 잘못 회수하는 상태 전이 오류를 해결한다. 관계 문맥 준비의 비용·heartbeat 잠금 범위, Worker 통신 재시도(#279), 무중단 배포(#280), 강제 종료 후 복구 훈련(#178)은 별도 과제다. 이미 실패한 운영 Job의 재실행이나 운영 배포 완료를 의미하지 않는다.
