# GH296 설정 후보 검토 조회 전후 측정

같은 합성 데이터와 Java 최대 힙 768MiB에서 캐릭터 후보 목록 median은 **436.82 → 17.92ms**, 세계관 후보 목록은 **363.63 → 13.75ms**였다. 기존 세계관 `size=1` 호출은 첫 준비 요청에서 Java heap OOM으로 중단됐고, 새 summary는 준비 3회와 측정 30회를 모두 성공했다. 두 목록의 **전체 `data` JSON은 전후 동일**하다.

이번 결과는 개발 머신의 격리된 localhost 측정이다. 운영 요청·배포·commit·push는 수행하지 않았다. 측정의 원자료, 실제 SQL과 bind, 실행 계획, 응답, fixture/readback hash를 이 디렉터리에 보관했다.

## Java에서 무거웠던 조회

캐릭터 목록은 `size=20`이어도 먼저 조건에 맞는 후보 전체를 읽고 Java에서 캐릭터 그룹을 만든 뒤 페이지를 잘랐다. 기존 EntityGraph는 각 후보의 `AnalysisJob` 전체 엔티티를 함께 읽었다. 따라서 응답에 없는 `automatic_input_state`, `run_base_state`, `state_journal`도 후보 행마다 SQL 결과에 반복됐다. 이번 123개 대기 후보는 9개 그룹이라 첫 페이지에 모두 들어간다. 페이지를 20개 그룹으로 자르는 것만으로는 이 반복 로딩을 없앨 수 없다.

캐릭터 화면의 세계관 건수 요청은 상태 필터 없이 `world-setting-candidates?page=0&size=1`을 사용했다. 한 그룹을 응답해도 세계관 후보 637개와 연관 엔티티를 먼저 읽었다. 새 `/world-setting-candidates/summary`는 같은 출처 범위의 집계만 반환한다.

목록 전용 조회는 Job 전체 fetch를 제거하고 필요한 상태·출처·비교 hash를 scalar 메타데이터로 한 번에 읽는다. 캐릭터 비교 배치도 주 조회에서 함께 읽어 이번 fixture의 추가 배치 조회를 줄였다. 세계관 목록은 페이지에 속한 그룹만 DTO로 변환한다. 단건·변경 경로와 기존 `/progress` 계약은 유지한다.

## 같은 조건의 API 결과

각 API는 **별도의 새 JVM**, 준비 3회 뒤 순차 측정 30회를 사용했다. p95는 nearest-rank 방식이다. 오류·검증 실패가 발생하면 즉시 멈추고 남은 표본을 채우지 않았다. SQL 로그·브라우저·빌드·테스트는 timing과 분리했다.

| API | before median / p95 | after median / p95 | 요청 결과 | JSON 응답 본문 bytes |
| --- | ---: | ---: | --- | ---: |
| 캐릭터 대기 목록, size20 | 436.82 / 488.82ms | 17.92 / 26.54ms | 양쪽 측정 30/30 성공, 오류 0 | 280,296 → 280,296 |
| 세계관 대기 목록, size20 | 363.63 / 422.79ms | 13.75 / 23.94ms | 양쪽 측정 30/30 성공, 오류 0 | 143,582 → 143,582 |
| 구 세계관 무필터 size1 → 새 summary | 측정 불가: 첫 준비 요청 OOM | 7.71 / 9.41ms | before 1회 시도 중 1회 실패·측정 0/30; after 측정 30/30 성공 | before HTTP 응답 없음 → after 671 |

캐릭터 median/p95는 각각 95.90%/94.57%, 세계관 목록은 96.22%/94.34% 줄었다. 목록 HTTP 본문 크기는 같으므로 개선은 응답 필드 삭제로 설명할 수 없다. **구 size1에는 latency 향상 배수·비율을 계산하지 않는다.**

구 size1의 첫 준비 요청은 2,259.11ms 뒤 `RemoteDisconnected`로 끝났고 서버 로그에 `Terminating due to java.lang.OutOfMemoryError: Java heap space`가 남았다. `ExitOnOutOfMemoryError`로 JVM이 종료됐으며 같은 무거운 요청을 재시도하지 않았다. baseline 실행기는 자식 exit code를 저장하지 않았으므로 추정하지 않는다. [실패 표본](evidence/before/summary/samples.json), [OOM 로그](evidence/before/oom.log), [after 원자료](evidence/after/summary/samples.json)를 함께 보존했다.

## 실제 SELECT가 바뀐 지점

아래 SQL은 **실제 캡처의 발췌**다. 읽기 쉽게 합성 UUID literal만 `:workId`, `:batchId`로 표기하고 생략한 컬럼·조건을 주석으로 표시했다. 전체 컬럼·JOIN·원래 bind literal과 EXPLAIN은 연결된 `.sql`/`index.json`에 있다. 진단 소요 시간은 위 API 통계에 넣지 않았다.

기존 캐릭터 주 조회: [before/sql-character/03.sql](evidence/before/sql-character/03.sql), 123행.

```sql
SELECT sc1_0.id,
       aj2_0.automatic_input_state,
       aj2_0.run_base_state,
       aj2_0.state_journal,
       sc1_0.raw_ai_result_json,
       sc1_0.raw_comparison_json
       -- 나머지 후보·Job·회차·캐릭터·작품 컬럼 생략
FROM setting_candidates sc1_0
JOIN analysis_jobs aj1_0 ON aj1_0.id = sc1_0.analysis_job_id
LEFT JOIN episodes e1_0 ON e1_0.id = sc1_0.episode_id
LEFT JOIN analysis_jobs aj2_0 ON aj2_0.id = sc1_0.analysis_job_id
LEFT JOIN characters mc1_0 ON mc1_0.id = sc1_0.matched_character_id
JOIN works w4_0 ON w4_0.id = sc1_0.work_id
WHERE sc1_0.work_id = :workId AND aj1_0.batch_id = :batchId
  -- sourceAnalysisJobId 미지정 EPISODE 범위, PENDING_REVIEW 및 matchStatuses 조건
ORDER BY e1_0.episode_no, sc1_0.created_at, sc1_0.id;
-- LIMIT/OFFSET 없음
```

필터용 `aj1_0`과 Job 엔티티 로딩용 `aj2_0`가 별도로 JOIN됐다. 개선 뒤 [주 조회 03.sql](evidence/after/sql-character/03.sql)은 후보 123행을 그대로 읽지만 Job 전체를 SELECT하는 두 번째 JOIN이 없다. 이어 [메타데이터 04.sql](evidence/after/sql-character/04.sql)에서 23개 Job의 필요한 scalar만 읽는다.

```sql
SELECT sc1_0.id, sc1_0.analysis_job_id,
       ccb1_0.analysis_job_id, ccb1_0.analysis_context_snapshot_json
       -- 후보 본문·비교 배치·캐릭터·회차·작품 컬럼 생략; Job JSON 컬럼 없음
FROM setting_candidates sc1_0
JOIN analysis_jobs aj1_0 ON aj1_0.id = sc1_0.analysis_job_id
LEFT JOIN episodes e1_0 ON e1_0.id = sc1_0.episode_id
LEFT JOIN character_fact_comparison_batches ccb1_0
       ON ccb1_0.id = sc1_0.character_comparison_batch_id
-- 캐릭터·작품 JOIN 및 같은 필터/정렬 생략
;

SELECT aj1_0.id, aj1_0.work_id, aj1_0.batch_id, aj1_0.job_type,
       aj1_0.analysis_mode, aj1_0.review_mode, aj1_0.status,
       aj1_0.journal_status, aj1_0.automatic_applied_at,
       aj1_0.source_kind, aj1_0.character_comparison_input_hash
       -- 출처 scalar·현재 원본 여부·최신 origin 판정 생략; 세 Job JSON 없음
FROM analysis_jobs aj1_0
-- 회차·설정집 파일/revision LEFT JOIN 생략
WHERE aj1_0.id IN (...);
```

세계관 목록도 [before 03.sql](evidence/before/sql-world/03.sql)의 Job 전체 로딩을 [after 03.sql](evidence/after/sql-world/03.sql)과 [메타데이터 07.sql](evidence/after/sql-world/07.sql)로 분리했다. 주 조회의 후보 행수는 62로 같다.

구 size1 SQL은 [before/sql-summary-small-json/03.sql](evidence/before/sql-summary-small-json/03.sql)에서 후보 637행과 세 Job JSON을 SELECT했다. 반복 OOM을 피하려고 이 **SQL 형태 확인만 별도 small JSON fixture**에서 실행했다. 새 [summary 03.sql](evidence/after/sql-summary/03.sql)은 아래처럼 같은 범위의 집계 1행을 반환한다.

```sql
SELECT count(wsc1_0.id),
       coalesce(sum(CASE WHEN wsc1_0.review_status='CONFIRMED' THEN 1 ELSE 0 END),0),
       coalesce(sum(CASE WHEN wsc1_0.review_status='DISMISSED' THEN 1 ELSE 0 END),0)
       -- 직접 확인·처리 중·비교 대기/실패/토큰 중단 등의 기존 집계 생략
FROM world_setting_candidates wsc1_0
JOIN analysis_jobs aj1_0 ON aj1_0.id=wsc1_0.analysis_job_id
WHERE wsc1_0.work_id=:workId AND aj1_0.batch_id=:batchId
  AND ((NULL IS NULL AND aj1_0.source_kind='EPISODE') OR aj1_0.id=NULL);
```

| 실제 HTTP 요청의 전체 SELECT | before → after | 세 Job JSON을 SELECT한 쿼리 | 주 후보 결과 행 |
| --- | ---: | ---: | ---: |
| 캐릭터 목록 | 30 → 8 | 1 → 0 | 123 → 123 |
| 세계관 목록 | 7 → 8 | 1 → 0 | 62 → 62 |
| 구 size1 → summary | 7 → 6 | 1 → 0 | 637개 후보 → 집계 1행 |

세계관 목록은 scalar 메타데이터 쿼리 추가로 SELECT 수가 1개 늘었다. 쿼리 개수만 줄인 최적화는 아니다. after에서 캡처한 **22개 SELECT 전체 텍스트 어디에도 세 Job JSON 컬럼이 없다.** 모든 bind 해석과 EXPLAIN이 성공했다. [SQL 검증 결과](evidence/after/sql-verification.json)

## 데이터·환경·검증 범위

- 같은 합성 fixture: 30개 분석 Job/회차, 캐릭터 585개(대기 123개·9그룹·23개 source Job), 세계관 637개(대기 62개). 중첩 character/world/slot/provenance/evidence를 고정 PRNG로 생성했다. 운영 원문을 복사하지 않았다.
- 세 Job JSON의 작업별 합계는 32,201,769bytes(30.71MiB). 후보 행수만큼 합한 논리적 `JSONB::text` 크기는 캐릭터 대기 101,550,000bytes, 세계관 대기 65,053,538bytes, 세계관 전체 679,387,149bytes다. 이는 **실제 JDBC wire bytes·HTTP bytes·Java heap 사용량과 다른 측정**이다.
- 동일 18 logical CPU/48GiB macOS arm64 호스트, Java 21.0.12.1, PostgreSQL 16.14, Xms256m/Xmx768m, 동일 DB/container 제한을 사용했다. 요청·통계 루프인 `run.py`와 JVM 실행 옵션의 `run-api.sh` SHA256은 전후 동일하다. `measure.py`는 after에서 종료 후 `process.json`에 PID/exit code를 쓰는 1줄만 추가됐고 timing 루프는 같다. 이 1줄을 제거한 파일 hash가 before manifest의 hash와 일치하는 diff 증거를 보존했다. 준비 중 추가한 browser/correctness helper 전체가 동일하다고 주장하지 않는다. CPU·native process 메모리 제한은 추가하지 않았고 OS/DB 캐시를 비웠다고 주장하지 않는다. [환경 manifest](evidence/after/character/manifest.json), [runner 검증](evidence/runner-provenance.json), [실제 diff](evidence/measure-runner-diff.patch)
- primary fixture hash `c5b56934b0b58c13995388d0c8d4a3ad1641acd2fbd65d30daad2309ad5f75e4`; 관련 테이블 모든 컬럼 readback hash `0b85fbeef1b703ad3f8b55530e572c7eb0e1c549b52271e058e1ab17633927a9`. 측정 전·후와 최종 복원이 동일하다. bcrypt salt와 인증/session 행만 hash 비교에서 제외했다.
- before JAR `e4c60a0fc604b49e567b6bd9f8b5c2f6379ac89d5dfc67aa90098fafbc4eb1fc`(BE `64eda0e3…`); final after JAR `e73f4a5587450c772028e7adb274fb4f819c9df8ab6ad245b7e8cfeaebbf89ff`. FE before `36a14339…`; after 파일 manifest hash `97dedbf9d3a9440d37eb74819fabd04138754407435e1f8b4c02df0f55e93caf`.
- 캐릭터·세계관 목록은 envelope timestamp를 뺀 전체 `data`를 비교했다. 새 summary는 성공한 small JSON before size1 응답에서 `groups`만 제거한 **모든 필드**와 동일하며, 그 before metadata도 primary 대기 세계관 목록 metadata와 동일함을 확인했다. [전체 동등성](evidence/after/full-data-equality.json), [small fixture 동등성](evidence/after/correctness-equality.json)

0.5초 RSS/jstat 관측에서 최고 RSS는 캐릭터 1,204.11 → 858.27MiB, 세계관 1,186.36 → 941.50MiB였다. 수집 시간 길이와 표본 수가 달라 **정밀한 peak/할당량/요청별 heap 개선으로 해석하지 않는다**. GC collector 누적 시간 차이는 캐릭터 0.962 → 0.006초, 세계관 0.938 → 0.004초였으며 stop-the-world pause 시간이 아니다. 원자료는 각 route의 `resources.jsonl`과 `gc.log`에 있다.

## 실제 브라우저의 요청·bytes

별도 small JSON fixture에서 두 FE production artifact를 동일하게 실행했다. candidate/Job metadata/preview는 그대로 두고 큰 input/base JSON만 줄여 세 Job JSON 합계가 13,370bytes다. 양쪽 readback hash `e5047a9b…`가 동일하다. Chromium 149의 새 context, cache/service worker 비활성, 외부 origin 차단, 캐릭터 9개 그룹 준비 후 500ms까지 시작한 요청을 drain했다.

| 실제 초기 API 요청 | before | after |
| --- | ---: | ---: |
| 요청 수 / 성공 | 5 / 모두 200 | 5 / 모두 200 |
| decoded JSON body 합계 | 332,513bytes | 287,206bytes |
| 세계관 건수 요청 | 무필터 목록 size1, 45,978bytes | summary, 671bytes |

다른 초기 API는 auth/me·작품·캐릭터 목록·world-image-theme였다. 이 비교는 **요청과 bytes 확인이며 페이지 속도 개선 측정이 아니다**. 처음 before 수집은 poll 진행 중 종료되어 censored된 기록을 별도 보존했고, cutoff/drain을 적용한 성공 기록을 양쪽 비교했다. [before](evidence/before/browser/summary.json), [after](evidence/after/browser/summary.json)

## 남아 있는 한계와 재현

제품 회귀 검증은 BE 전체 **3,641 pass/236 skip/실패 0**(합계 3,877), FE focused **17 pass**, lint/typecheck/build 통과였다. FE 전체 실행은 **826 pass/20 skip/3 fail**이었고, 제품·테스트 파일을 바꾸지 않은 동일 대상의 ASCII 경로 재검증은 **4 pass**였다. 전체 실행의 3건은 한글 경로를 파일 경로로 처리한 기존 테스트 2건과 데모 대기 타임아웃 1건이다. 전체 실행의 실패를 숨기거나 전체 무실패로 합치지 않는다. 실제 로컬 API·브라우저·DB 연동에서는 후보 1개 제외 뒤 목록/summary count와 탭 이동 시 갱신도 검증했다. 이 결과는 성능 표본과 분리한 별도 검증이다. [검증 집계](evidence/product-verification-summary.json)

캐릭터·세계관 목록은 여전히 조건에 맞는 후보 전체 엔티티를 읽고 Java에서 그룹 페이지를 만든다. 이번에도 123/62행을 읽으며 **DB에서 선택한 페이지 후보만 가져온 것은 아니다**. 후보 raw AI/추출·비교 JSON도 SELECT에 남는다. 20개보다 많은 그룹, 출처가 지정된 EPISODE/SETTING_BOOK, distinct hidden 비교 Job hash, 정책 상태·mutation 회귀는 별도 실제 PostgreSQL/서비스 테스트 범위이며 이번 9/11그룹 벤치마크만으로 그 성능을 일반화하지 않는다.

재현 도구와 명령은 [scripts README](../../../scripts/load-tests/candidate-review/README.md)에 있다. localhost PG27432/API29480/FE3440만 사용하며 `.env`·worker·스케줄러·외부 tracing/알림을 끈다. 비밀정보를 포함한 runtime credentials/JAR/FE 원본 bundle은 공개 패킷에 넣지 않았다. 파일 내용에서 로컬 절대 경로만 placeholder로 정규화했으며, 체크섬은 정규화된 공개 복사본 기준이다.

최종 primary hash 복원 뒤 이 작업이 소유한 Java/preview PID와 전용 PostgreSQL container만 정지했다. container·DB volume·로컬 원자료는 삭제하지 않았다. [정리 결과](evidence/after/cleanup.json)
