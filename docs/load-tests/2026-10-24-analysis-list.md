# 분석 목록 카드 요약 조회: 실제 로컬 검증

[Backend #288](https://github.com/catchhole-soma/catchhole-backend-java/issues/288), [연동 Frontend PR #99](https://github.com/catchhole-soma/catchhole-front/pull/99). 동일 합성 데이터에서 페이지 준비 중앙값은 **446.80→129.35ms**, 배치 API는 **128.23→8.52ms**로 줄었다. 분석 목록 API 호출은6→3회, 관측 API 응답 body는124,223→7,887bytes다. window load p95/max는 증가했으며 아래 표에 그대로 남긴다. 운영 배포·운영 재시작·운영 부하는 실행하지 않았다.

## 바뀐 조회 경계

기존 화면은 배치 요약 뒤 작품 전체 작업으로 출처와 이동 대상을 다시 판별하고, 부모 화면은 회차 전체를 읽었다. 설정집 카드는 별도 상세 hook으로 작업·본문·후보 API까지 조회했다. 배치 후보 수 자체는 이미 SQL 집계였다. 개선은 그 집계를 새로 만들었다는 주장이 아니라, 최신 작업 선별까지 DB로 옮기고 목록의 불필요한 상세 요청을 제거한 것이다.

```text
기존 분석 목록
  auth/me + works
  batches → 전체 AnalysisJob/대상 엔티티 → Java 최신 작업·표시 상태 집계
  analysis-jobs → 전체 작품 작업 → 출처·이동 판별
  episodes → 부모 원고 목록의 작업 상세 조회
  setting-books → 설정집 출처 보완
  설정집 카드 → 개별 작업/설정집 본문/후보 API

새 분석 목록
  auth/me + works
  batches → 페이지 batch IDs → SQL 최신 선택·상태·집계 + 작은 이동 IDs
          → episodeJobGroups/episodeStatus + settingBooks 카드 요약
  진행·검토·원문 클릭 후 기존 상세 화면이 조회
```

기존 top-level `jobGroups/status`와 endpoint는 보존했다. 회차용 그룹의 `currentAnalysisJobIds`에는 회차 분석 작업 ID만, 설정집에는 최신 origin Job·실제 batch·원본 revision과 정규화 상태·후보 수만 제공한다. 자동/순차/토큰 중단·legacy 복수 대상·retry·mixed 상태와 설정집의 기존 완료 기준을 유지한다. 설정집 `SUCCEEDED+SEALED`라도 자동 반영 latch가 없으면 진행 중이며, 회차 집계의 실패 판정과 구분한다. 원고 탭 재진입·재분석 모달의 회차 조회는 유지하고 분석 탭에서는 해당 조회와 polling을 비활성화했다.

## SQL 전문과 실행 계획

[변경 전 SQL/EXPLAIN 목록](results/2026-10-24-analysis-list/before/sql/index.json), [변경 후 실제 목록 batches SQL/EXPLAIN 목록](results/2026-10-24-analysis-list/after/sql-list/index.json)을 보존했다. 각 SQL 옆의 동일 번호 `*-explain.json`은 실제 PostgreSQL16에서 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 실행한 결과다. 타이밍 측정 후 별도 수집하고 SQL logging을 원복했다.

|조사 API|변경 전 전문|변경 후|
|---|---|---|
|batch 페이지 선택|[02-batches.sql](results/2026-10-24-analysis-list/before/sql/02-batches.sql)|페이지 metadata에 uploadType scalar 추가|
|batch 현재 작업|[03-batches.sql](results/2026-10-24-analysis-list/before/sql/03-batches.sql)|[03 이동 IDs](results/2026-10-24-analysis-list/after/sql-list/03-batches.sql), [04 DB 그룹 집계](results/2026-10-24-analysis-list/after/sql-list/04-batches.sql), [08 설정집 요약](results/2026-10-24-analysis-list/after/sql-list/08-batches.sql)|
|전체 작품 작업|[09-all-jobs.sql](results/2026-10-24-analysis-list/before/sql/09-all-jobs.sql)|분석 목록에서 호출 제거|
|부모 회차 집계 작업|[15-episodes.sql](results/2026-10-24-analysis-list/before/sql/15-episodes.sql)|분석 탭에서 호출 제거|

기존 SELECT에는 `automatic_input_state`, `run_base_state`, `state_journal`와 회차·배치·대상 엔티티 컬럼이 포함됐다. 새 쿼리는 다음 의미를 DB에서 처리한다. 작업별 scalar 이력을 Java로 옮겨 다시 집계하지 않는다.

```sql
-- 새 쿼리 핵심. 전문은 위 링크를 따른다.
row_number() over (
  partition by batch_id, job_type, source_kind, target_id
  order by created_at desc, id desc
) as latest
-- snapshot targets > episode_id; 대상 없는 fallback은 실제 대상이 전혀 없을 때만 선택
-- ordered 표시 상태, 같은 run/generation/type의 선행 실패에 막힌 pending, token checkpoint 정규화
select batch_id, job_type, scope, count(*), sum(case when ... then 1 else 0 end)
from scoped group by batch_id, job_type, scope;
```

배치 API 단독 SQL 수는 **7→9로 늘었다**. 대신 각 쿼리는 큰 분석 JSON을 선택하지 않는다. 조사 대상3개 API 집합은 기존 `batches7 + all-jobs4 + episodes5 =16개`에서 새 `batches9개`로 줄고, 분석 JSON을 선택하는 쿼리는3→0이다. 이것은 **전체 페이지 SQL 총수**가 아니며 me/works의 별도 SQL은 이 집계에 포함하지 않았다. 후보 count SQL은 유지하고 설정집만 선택 origin IDs별 다건 집계를 추가했다. 실제 계획에서 새 인덱스 필요성이 드러나지 않아 migration/index는 추가하지 않았다.

[호환 API의 별도 SQL](results/2026-10-24-analysis-list/after/sql-compat/index.json)은 all-jobs/episodes를 의도적으로 호출해 보존한 비교 자료다. 이 경로의 JSON SELECT2개는 새 분석 화면이 실행한 SQL로 세지 않는다. EXPLAIN 시간에는 JDBC 결과 전송·JSON 역직렬화·HTTP 완료 비용이 포함되지 않으므로 API 시간과 동일시하지 않는다.

## 동일 조건 측정

기준 backend `0bf469d`, front `49a1112`; 변경 후 backend `184ff78`, front `e6ccfd7`. 측정 전용 worktree에서 빌드했다. 원래 저장소와 기존 localhost15432 DB를 변경하지 않았다. 유효 enum fixture로 재측정할 때는 보존한 baseline JAR와 `runtime/frontend-before`를 실행하고 원래 artifact hash가 같은지 검사했다. dirty source와 실행 중인 보존 binary를 혼동하지 않도록 [원본 빌드 출처](results/2026-10-24-analysis-list/provenance/original-build-manifest.json)와 canonical [before manifest](results/2026-10-24-analysis-list/before/manifest.json), [after manifest](results/2026-10-24-analysis-list/after/manifest.json)를 연결했다.

[fixture](results/2026-10-24-analysis-list/fixture.json)는60회차/60작업/2배치, 캐릭터398개(342/56), 세계관409개(296/113)다. 첫 배치는18완료/12취소, 둘째는2완료/1실패/27차단대기다. 고정 PRNG 유효 JSON을 사용했고 before/after fixture 전체 cmp/hash가 같다. `JSONB::text` UTF-8 합계는15,460,388bytes이며 운영 원문을 복제하지 않았다. 후보 enum은 STRING/UNRESOLVED 등 실제 domain enum과 대조했다.

이15,460,388bytes는 DB JSON 논리 크기다. 기존 브라우저 `/batches` body는4,237bytes, 전체 `/analysis-jobs`는92,725bytes, `/episodes`는26,464bytes였다. 큰 JSON의 **서버 선택·Java 객체화 비용**과 **추가 HTTP 응답 비용**은 서로 다른 절감 근거다. 원시15MB가 그대로 브라우저에 전송됐다고 설명하지 않는다.

Java21 native API `-Xms256m -Xmx768m`, 동일 PostgreSQL16.14/pgvector0.8.2 Docker(2CPU/1GiB), 실제 계정 login, production frontend build/preview, Chromium149,1280×800을 사용했다. API/preview는 별도 CPU·RSS 제한이 없는 호스트 프로세스다. `.env` import·기본 Compose·worker·scheduler를 끄고 타이밍 중 SQL debug/외부 tracing을 끈다. 실제 실행 JAR/프런트 artifact 경로와 hash가 manifest에 있다.

준비3회 뒤 fresh context20회 직렬 탐색(HTTP cache disable, React Query cache 초기화), API 별도3회 준비+30회다. ready는 카드2개의 상태·숫자·활성 버튼을 브라우저 내부에서 확인한 뒤2RAF 후 재검사한 시각이며 window load와 분리했다. `max(ready,load)+2초`까지 시작한 요청을 최대60초까지 drain했다. 연속 새로고침은 각 ready/drain이 끝난 뒤 다음 reload를 시작하는 **직렬2회×5세트**다. 동시 부하 시험이나 과거 운영 OOM 재현으로 해석하지 않는다. DB/JVM warm이며 OS/DB cache를 비웠다고 주장하지 않는다. 측정 동안 추가 Gradle/npm/Playwright 검사를 실행하지 않았다.

### 시간: 중앙값 / p95 / 최대 (ms)

|측정|변경 전|변경 후|중앙값 변화|
|---|---:|---:|---:|
|페이지 ready · 20회|446.80 / 463.30 / 499.00|129.35 / 184.30 / 290.60|감소 71.05%|
|window load · 20회|62.25 / 65.60 / 69.70|64.25 / 119.20 / 224.70|증가 3.21%|
|batches API · 30회|128.23 / 136.45 / 136.61|8.52 / 13.19 / 13.51|감소 93.36%|

window load **p95 65.60→119.20ms, 최대69.70→224.70ms 증가**를 보존한다. 느린 외부 CSS 응답과 겹친 표본이 있지만 CDN을 단독 원인으로 단정하지 않는다. 외부 jsDelivr asset2개는 양측 모두 차단하지 않았다. initial cold endpoint1회는228.43→90.40ms였으며 반복 표본 통계에 섞지 않았다.

### 요청과 bytes

아래는 실제 navigation20회에서 페이지당 호출 수 / encoded response body bytes 중앙값이다. Playwright `request.sizes()` 기준이며 headers bytes는 [accounting](results/2026-10-24-analysis-list/after/accounting.json)에 별도로 있다.

|경로|변경 전 횟수 / bytes|변경 후 횟수 / bytes|
|---|---:|---:|
|`GET /auth/me`|1 / 321|1 / 321|
|`GET /works/:id/analysis-jobs/batches`|1 / 4,237|1 / 7,206|
|`GET /works/:id/setting-books`|1 / 116|0 / 0|
|`GET /works`|1 / 360|1 / 360|
|`GET /works/:id/episodes`|1 / 26,464|0 / 0|
|`GET /works/:id/analysis-jobs`|1 / 92,725|0 / 0|
|API 합계|6 /124,223|3 /7,887|

관측 API body 합계는 **93.65% 감소**했다. 새 batches 응답 자체는 하위호환 필드와 source별 카드 그룹을 함께 제공해 커졌다. 브라우저 전체 정적 자산 bytes와 API만의 bytes는 raw/accounting에서 분리한다. CDP CORS OPTIONS는 별도 배열로 남겼고 encodedDataLength0을 실제 wire bytes0으로 해석하지 않는다. 전체 wire bytes를 측정했다고 주장하지 않는다.

### 자원과 실행 표본

|항목|변경 전|변경 후|
|---|---:|---:|
|관측 RSS 최대 MiB|951.875|895.859|
|관측 heap 사용량 최대 MiB|450.386|348.577|
|jstat Young GC 증가|264|1|
|jstat GCT 증가 seconds|0.409|0.004|
|full GC 증가|0|0|
|자원 비교 표본|199|167|

RSS는 heap 밖 메모리를 포함한다.0.5초 표본의 관측 최고값은 실제 순간 최고값이나 운영 memory bound가 아니다. GC GCT는 jstat collector 시간이고 API latency가 아니다. 두 비교 구간은 sampler 시작부터 runner summary 파일 생성 epoch까지로 고정했다. before raw343표본 중 뒤 idle144개는 삭제하지 않고 비교에서 제외했다(raw peak RSS951.953/heap466.519MiB); after는 summary 생성 시 sampler가 자동 종료됐다. 정확한 epoch와 raw-window/measurement-window 값은 각 accounting/manifest에 있다.

양측38개 page sample(3warmup+20navigation+5setup+10reload),34개 API sample(cold1+warmup3+measured30)을 모두 보존했다. 페이지/API/새로고침 실패0, collector error0, censored0이고 measured timeOrigin20개가 각각 다르다. 전체38개 page sample에서 ready 뒤 끝난 최초부하 요청(`finishedAfterReady`)은 before0개, after38개이며 drain 후 완료까지 모두 집계했다. OOM·5xx를 관측하지 않았으나 운영 OOM이 해결됐다는 증명은 아니다.

## 정확성 검증과 전달 순서

- focused PostgreSQL29개와 기존 배치 API7개 실제 통과, skipped0. ordered null/incomplete/invalidated/미반영, 다른 generation, blocked pending, token 두 checkpoint, UUID tie, snapshot 우선·legacy 복수 대상, targetless, page/소유권, 엔티티/JSON 미조회·쿼리수 일정, mixed/book 최신 origin·revision·후보 범위를 검증했다.
- 전체 `./gradlew test`:3,792개 중3,556개 실행 통과,236개 스킵, 실패/오류0. 기존 opt-in PostgreSQL/worker/fixture 환경 테스트의 스킵과 새 PG29개의 실제 실행을 구분한다. 이후 nullable enum OpenAPI 오류를 RED→GREEN으로 고쳤고 schema1+배치API7을 다시 통과했다. `bootJar` 성공. [전체 집계/소스 hash](results/2026-10-24-analysis-list/verification/full-tests-summary.json), [후속 schema 집계](results/2026-10-24-analysis-list/verification/schema-tests-summary.json).
- 프런트 lint/typecheck/build, 관련107개 및 마지막 summary16개 회귀 테스트 통과. [실제 인증 smoke](results/2026-10-24-analysis-list/verification/frontend-live-result.json)는 설정집 완료 검토·이력·뒤로가기·frozen revision200, mixed book 중단 progress와 회차 IDs-only progress, 초기 heavy API0, book-only/mixed batches 각각1회, HttpOnly refresh cookie, cross-work job/revision404를 확인했다. 임시 correctness work는 정리하고 perf fixture/hash를 복원했다.
- [Astra 제품 코드 검토](analysis-list-code-review.md), [baseline 검증](baseline-verification.md), [after 원자료 최종 검증](after-verification.md), [측정 사전 기준](analysis-list-measurement-review.md)을 함께 남긴다.

#272 세계관 검토 페이지네이션은 후보 검토의 별도 read path다. 이 변경은 `findReviewList`를 리팩터링하지 않았고 분석 목록 요청/집계에 한정한다. 설정집 성능의 별도 전후 시간은 측정하지 않았으며, pure summary 응답·불필요 요청 제거와 실제 이동을 정확성 검사로 확인했다.

배포할 경우 새 카드 계약을 제공하는 **백엔드를 먼저 적용·확인한 뒤 프런트**를 적용한다. 기존 응답 필드는 유지해 이전 소비자와 호환한다. 이 보고서는 운영 배포 전 로컬 검증 결과다.

## 재현과 원자료

[재현 명령/guard](../../scripts/load-tests/analysis-list/README.md), [checksum 목록](results/2026-10-24-analysis-list/SHA256SUMS.json), [before summary](results/2026-10-24-analysis-list/before/summary.json), [after summary](results/2026-10-24-analysis-list/after/summary.json), [before accounting](results/2026-10-24-analysis-list/before/accounting.json), [after accounting](results/2026-10-24-analysis-list/after/accounting.json).

각 phase에는 pages/API/resources JSONL, GC, manifest와 실제 SQL/EXPLAIN이 있다. 토큰·쿠키·auth header 값·비밀번호·본문·API 로그·전체 테스트 로그는 커밋 자료에서 제외했다. workspace/home 절대 경로는 치환했고 비밀값 scan을 통과했다. 원본과 준비/교정 이전 attempt는 workspace의 `output/analysis-list-optimization-20261024/evidence/`에 보존한다. 초기 enum·수집기 교정 전 값은 공식 표에 합치지 않았다. 로컬 합성 결과를 운영값으로 확대하지 않는다.
