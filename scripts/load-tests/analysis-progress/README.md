# 분석 진행 로컬 합성 측정 (#291)

전용 localhost PostgreSQL과 native Java/production Vite preview만 사용한다. 운영 요청·원문·계정·재시도는 사용하지 않는다. 기존 `gh221-local-postgres:15432`와 #288 컨테이너는 변경하지 않는다. fixture/capture guard는 `127.0.0.1:26432/analysis_progress_benchmark`, API28480, preview3340만 허용한다.

## 환경과 준비

Java21, Node24/npm11, Python3+psycopg3.3.2/bcrypt5, 저장소 Playwright를 사용한다. `run-api.sh`, `sample-resources.py`, `manifest.py`, `capture-sql.py`는 이전 analysis-list 도구의 동일한 수집 방식을 전용 progress 포트·경로에 적용했다.

`manifest.py`는 PATH의 `node`를 사용하며 다른 설치 위치는 `NODE_BINARY`로 지정한다. 공개 준비에서 개인 절대 경로만 이 설정으로 치환했으며 저장된 측정 결과와 실행 artifact는 바꾸지 않았다.

```sh
docker run -d --name analysis-progress-benchmark-20261024-postgres --cpus 2 --memory 1g \
  -p 127.0.0.1:26432:5432 -e POSTGRES_USER=progress_bench \
  -e POSTGRES_PASSWORD=progress_bench_local_only -e POSTGRES_DB=analysis_progress_benchmark \
  pgvector/pgvector:0.8.2-pg16 postgres -c timezone=Asia/Seoul
./gradlew bootJar
```

BE79c2b0e/FE74d10ec에서 before JAR와 frontend production build를 보존하고 SHA256을 기록한 뒤 제품 코드를 바꾼다. FE는 양쪽 모두 `VITE_API_BASE_URL=http://127.0.0.1:28480 npm run build`, preview3340으로 보존된 artifact를 직접 제공한다. API는 `sh run-api.sh <root> <phase> <actual.jar>`로 실행한다. JVM은 Xms256m/Xmx768m이며 e2e/Flyway, `.env` import·기본 Compose·외부 worker·scheduler·tracing/SQL debug 비활성이다. API/preview CPU·RSS는 native host 제한 없음, PostgreSQL만2CPU/1GiB다.

```sh
python fixture.py --dsn postgresql://progress_bench:progress_bench_local_only@127.0.0.1:26432/analysis_progress_benchmark \
  --credentials <private credentials.json> --manifest <fixture.json>
```

fixture는 전용 DB의 합성 회원과 종속 행만 초기화한다. 다른 work가 있으면 거절하며 두 번 seed한 manifest가 동일해야 한다. 일회용 로그인 비밀번호는0600 파일에만 보존하고 이메일/회원가입을 호출하지 않는다. 30회차/30Job/410캐릭터 후보/404세계관 후보, 22SUCCEEDED+SEALED+자동 반영 latch/1FAILED+INCOMPLETE/7선행 실패 뒤 PENDING이다. 중첩 characters/worldSettings와 다수의 짧은 evidence를 고정 PRNG로 생성하며 세 JSONB::text 합은18,161,746bytes(약17.32MiB), 성공22개는17,301,914bytes(약16.50MiB), 최대1,803,551bytes(약1.72MiB)다. Java heap/DB 물리 크기/HTTP 응답 크기와 다르다.

## 측정

각 phase 디렉터리를 먼저 만들고 resource sampler를 시작한다. `sample-resources.py <owned Java PID> <phase/resources.jsonl> <phase/summary.json>`은0.5초 RSS/jstat 표본을 수집하고 runner summary 생성 시 종료한다. `manifest.py --root <root> --phase <phase> --pid <PID> --jar <actual.jar> --frontend-build-dir <actual served directory>`는 환경·실행 artifact·fixture hash를 기록한다.

```sh
node scripts/analysis-progress-benchmark.mjs --credentials <private file> --output <phase> --phase before
node scripts/analysis-progress-benchmark.mjs --credentials <private file> --output <phase> --phase after
```

동일 runner로 첫 fresh-JVM API round1회, 준비3회, fresh-context navigation10회, 각 fresh context의65초 quiescent polling3회, API 준비3회+측정30회를 수행한다. job-round는 같은30ID를 before에는30개 동시 GET, after에는 묶음1개로 읽는 전체 완료 시간이다. comparison API는 별도 집계한다. 첫 표본은 측정 API 통계에 넣지 않는다. 운영 OOM을 강제로 재현하지 않는다.

ready는30개 행(22자동 반영 완료/1비교 미완료/7앞 회차 재개 대기), 제목과 활성 재개 버튼을2RAF 뒤 재확인한 시각이다. 세계관 집계 완료는 ready의 조건이 아니다. window load와 마지막 관련 초기 API 응답 완료를 각각 기록한다. 최초 관측은 ready/load 중 늦은 시각+2초까지 시작한 요청을 최대60초 drain한다. steady request는 raw의 observationStartMs~cutoff에 시작한 요청만 세어 초기 탐색을 분리한다. HTTP cache 비활성, service worker 차단,1280×800, Chromium149이며 외부 CSS/asset을 양쪽 동일 정책으로 허용한다. DB/JVM warm이며 OS/DB cache를 비웠다고 주장하지 않는다. 측정 중 다른 빌드·테스트·SQL debug를 실행하지 않는다.

활성 시나리오는 같은 fixture의23번째 Job만 RUNNING, 세계관 후보1개만 PROCESSING으로 바꾸고 `analysis-progress-active-benchmark.mjs`를 양쪽15초씩 실행한다. 타이밍 후 fixture를 다시 seed하고 hash/metadata를 대조한다. 활성 표본은 quiescent 통계와 합치지 않는다.

본측정 이후 `capture-sql.py --phase before|after --credentials <private file> --dsn <dedicated DSN> --output <phase/sql>`을 실행한다. 진단 job-one,30ID round, comparison, episodes를 분리하고 실제 SELECT+bind+EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON)을 보존한다. SQL logging을 켠 구간은 타이밍에서 제외하고 finally에서 원복한다. job-one은 별도 진단이므로 전체167SELECT를 한 페이지 비용이라고 부르지 않는다.

`analyze-results.py --phase <phase>`는 초기 경로별 encoded body/headers, 초기 API 완료, 정확한65초 steady request, 전체 main 구간과 matched65초 구간의 자원을 각각 accounting.json으로 저장한다. jstat GC 시간은 collector 시간이며 전체 정지 시간·응답 시간과 같지 않다.0.5초 최고값은 관측값이다. API/API body/정적 자산/CDP OPTIONS를 구분하고 실패·censored·collector 오류를 숨기지 않는다.

## 보존과 정리

before/after fixture의 JSON·후보 payload hash와 별도 Job metadata/target readback을 대조한다. hash가 DB 전체의 의미적 checksum이라는 주장은 하지 않는다. 인증 session/bcrypt salt는 비교 대상에서 제외한다. runtime credentials·token·cookie값·auth header·API/DB 원본 로그는 공개 packet에 넣지 않는다. SQL·timing·자원·sanitized manifests·검증 집계와 checksum만 보고서에 포함한다.

완료 후 이 작업이 시작한 API/preview PID와 `analysis-progress-benchmark-20261024-postgres`만 정지하며, 재현을 위해 데이터/빌드/원자료는 보존한다. 운영 배포·병합은 별도다.
