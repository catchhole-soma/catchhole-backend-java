# 분석 목록 로컬 합성 측정

Backend #288. 운영 요청·운영 원문·운영 자격 증명을 사용하지 않는다. 이 폴더의 명령은 전용 `analysis_list_benchmark` localhost DB만 허용한다. 기존 `gh221-local-postgres:15432`는 사용하지 않는다.

환경: Java21, Node24, Python3 + psycopg/bcrypt, Docker PostgreSQL16(pgvector0.8.2), 저장소의 Playwright. 의존성을 고정한 `npm ci` 후 production frontend를 `VITE_API_BASE_URL=http://127.0.0.1:28080 npm run build`하고 `npm run preview -- --host 127.0.0.1 --port 3300 --strictPort`로 연다. npm이 PATH에 없는 경우 runtime의 npm11.6.2 CLI를 Node로 실행했다. 자세한 실제 버전·실행 jar·flags·asset hash는 결과 manifest를 따른다.

1. 전용 PostgreSQL: `docker run -d --name analysis-list-benchmark-20261024-postgres --cpus 2 --memory 1g -p 127.0.0.1:25432:5432 -e POSTGRES_USER=analysis_bench -e POSTGRES_PASSWORD=analysis_bench_local_only -e POSTGRES_DB=analysis_list_benchmark pgvector/pgvector:0.8.2-pg16 postgres -c timezone=Asia/Seoul`.
2. `./gradlew bootJar` 후 `run-api.sh <absolute measurement root> before <absolute baseline.jar>`로 실행한다. e2e profile/Flyway가 schema를 만든다. 기본 compose와 `.env` import, worker·주요 scheduler를 끄고 heap은768MiB로 고정한다.
3. `fixture.py --dsn postgresql://analysis_bench:analysis_bench_local_only@127.0.0.1:25432/analysis_list_benchmark --credentials <private credentials.json> --manifest <fixture.json>`. 첫 실행은 일회용 비밀번호를0600 권한 파일에 저장한다. 실제 login API를 사용하며 회원가입/이메일 발송은 없다. 전용 DB의 회원·종속 합성 행을 초기화하므로 다른 용도의 DB를 넣지 않는다. 두 번 실행해 manifest가 같은지 확인한다.
4. frontend `scripts/analysis-list-benchmark.mjs --credentials <private file> --output <empty phase directory>`를 실행한다. `--smoke true`는 준비 확인용이며 본측정에서 제외한다. 본측정은 initial cold endpoint1회, warmup3회, fresh context 페이지20회, 새 context당 진입 후 연속reload2회×5세트, APIwarmup3회+30회다. 고정 1280×800, cache disable, 인증은 측정 전에 준비한다.
5. 별도 프로세스의 `sample-resources.py <owned Java PID> <resources.jsonl> <phase/summary.json>`로0.5초 간격RSS/jstat를 수집하고 runner가 summary를 쓰면 자동 종료한다. raw 표본은 보존하며, 비교 집계는 sampler 시작부터 summary.json mtime까지로 고정한다. `manifest.py --root <measurement root> --phase before --pid <PID> --jar <actual jar> --frontend-build-dir <actual served artifact directory>`가 실제 JVM flags·DB/호스트 제약·fixture·sources·assets를 기록한다. 보존된 기준 artifact를 dirty worktree에서 실행하면 `--artifact-source-manifest <original baseline manifest>`로 원본 JAR와 프런트 asset hash의 동일성을 검사한다. before는 `runtime/frontend-before`, after는 `runtime/frontend-after` 보존본을 실제 서빙한다.
6. 본측정이 끝난 뒤에만 `capture-sql.py --credentials <private file> --dsn <dedicated dsn> --output <phase/sql>`을 실행한다. 로컬 DB SQL duration logging을 잠시 켜서 실 API batches/all-jobs/episodes를 조회하고 `--routes batches`는 실제 화면에 남는 배치 경로만 별도로 수집하고, `--routes all-jobs,episodes` 호환 API 비교는 다른 디렉터리에 보존한다. 실제 bind를 넣은 SELECT와 `EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON)`을 저장한다. 종료 시 SQL logging을 원복한다. SQL 로그 활성 구간을 성능 샘플에 섞지 않는다.

페이지 ready는 두 회차 카드의 상태·모든 숫자·활성 primary button을 브라우저 내부에서 확인한 뒤 2RAF 뒤 재검사한 `performance.now()`다. window load는 NavigationTiming의 별도값이다. ready/load 중 늦은 시각+2초까지 시작한 요청을 최초부하 집합으로 정하고 최대 60초까지 완료를 기다린다. 실패·censored·ready 이후 완료를 보존한다. 네트워크 idle을 ready로 사용하지 않는다. 버튼 click-through·polling·320px는 별도 correctness 검사다.

`request.sizes()`의 responseBodySize는 인코딩된 body bytes, responseHeadersSize는 별도 header bytes다. CDP에서 관측한 CORS OPTIONS는 별도 배열이며 encodedDataLength=0을 실제 전송 0으로 해석하지 않는다. 전체 wire 전송량이라고 부르지 않는다. 외부 jsDelivr 요청 2개는 차단하지 않고 양측 동일 정책으로 기록하므로 CDN 변동이 pageReady/windowload에 영향을 줄 수 있다.

JSON 논리 크기는 세 JSONB 컬럼 `::text`의 UTF-8 합계 15,460,388 bytes다. 고정 PRNG의 유효 JSON 난수 payload로 과도한 압축을 피했다. 실제 원문이나 JSON 내용을 복제한 데이터는 아니다. JVM retained heap, DB 물리 저장 크기, HTTP payload 크기와 다르다. CPU는 DB만 2개로 제한하고 API와 preview는 네이티브 호스트에서 실행한다. before/after 조건을 동일하게 맞추되 운영 성능이나 메모리 상한을 증명하지 않는다.

`analyze-results.py --phase <phase directory>`는 warmup을 분리한 요청별 표와 자원 측정 구간을 집계한다. 연속 새로고침은 각 ready/drain이 끝난 뒤 다음 reload를 시작하는 직렬 조건이다. 동시 새로고침 부하나 과거 운영 OOM 재현을 통과했다고 확대하지 않는다.
