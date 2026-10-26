# #291 AFTER 독립 근거 검증

2026-10-24 GPT-6 Astra. 저장된 파일만 검증했다. 테스트·빌드·브라우저·API·DB·프로세스 명령을 실행하지 않았고 제품/측정 도구를 수정하지 않았다. 이 문서만 작성했다. 원자료는 workspace `output/analysis-progress-optimization-20261024/evidence/`이며 공개 사본은 `results/2026-10-24-analysis-progress/`다.

## 검증 상태

BE/FE 변경본의 main 및 active 성능 원자료, SQL, fixture, 실행 artifact, 테스트 결과와 최종 결과 보고서를 독립 대조했다. **로컬 측정 근거는 검증 통과**이며 수치·비교 조건의 차단 이슈는 발견하지 않았다. 메모리 설명의 한 문장 교정은 아래 최종 확인에 기록한다.

## 동일 조건과 원자료 신뢰 범위

- BEFORE/AFTER manifest의 Java21.0.12.1, PostgreSQL16.14, DB2CPU/1GiB, API native 실행, 768MiB max/256MiB initial heap, viewport1280×800, fixture, runner/fixture/run-api script hash가 동일하다. 실제 JVM flags도 heap 제한과 G1 조건이 같다. API의 CPU/RSS 제한은 별도로 없다.
- `fixture-readback.json`은 양쪽 byte-identical이다. fixture 최초·복원 manifest도 동일하다. 30작업의22완료/1실패/7대기, run/generation/sequence/predecessor·target·latch와17.32MiB 합성 JSON 조건을 유지했다. fixture hash는 전체 DB 모든 열의 hash가 아니며 auth/bcrypt 등을 제외한다.
- AFTER manifest에 기록된 실제 `after.jar`와 served frontend-after의 모든 자산 SHA-256을 다시 계산해 일치함을 확인했다. Git HEAD만으로 dirty 변경본을 식별하지 않고 이 artifact를 측정 대상으로 본다.
- 양쪽16개 page row(3warmup/10navigation/3polling),68개 API row(각 경로 첫1/warmup3/measured30)를 읽었다. API latency 통계, page ready, latest initial API completion, 정확한 steady window 요청·bytes, 해당 구간 resource 표본과 GC delta를 원자료로 다시 계산해 summary/accounting과 일치함을 확인했다.
- SQL 파일 생성 시점은 양쪽 main summary 이후다. 최종 테스트/빌드 로그의 수정 시점은 AFTER capture 시작보다 앞선다. 실행자는 경쟁 테스트/빌드 프로세스를 종료했다고 보고했다. 저장된 로그 시점과 선언은 일치하지만 지속적인 OS process inventory가 없어 모든 외부 부하의 부재까지 독립 증명하지는 않는다.

## 실제 측정 결과

| 지표 | BEFORE | AFTER |
|---|---:|---:|
| job 조회 round 중앙/p95 ms |141.39 /158.38|9.84 /12.17|
| 비교 상태 조회 중앙/p95 ms |1318.79 /1452.86|9.71 /11.14|
| navigation ready 중앙/p95 ms |327.50 /476.90|151.50 /266.20|
| 최초 관련 API 마지막 완료 중앙/p95 ms |1556.14 /1928.03|324.60 /458.87|
| window load 중앙/p95 ms |63.85 /194.50|82.10 /203.60|
| 최초 API 요청 수 |34|5|
| 최초 encoded API body bytes |67,022|33,971|
| 각65초 job polling 요청 |630|2|
| 각65초 encoded job body bytes |1,044,288|39,322|

각65초 값은3개 window에서 동일하다. `observationStartMs <= startMs <= observationCutoffMs`를 적용해 최초 진입과 분리했다. 이 fixture에서 steady 비교 polling은 양쪽0이다. 전체 초기 API는 auth/me·work·episodes 및 job/comparison reads를 포함한다. AFTER도 episodes 초기 GET은 남는다. job round는 BEFORE30개 병렬 GET 전체 완료, AFTER1개 bulk 완료의 비교이며 개별 요청 latency와 혼동하지 않는다. 브라우저 encoded bytes와 직접 API decoded bytes는 다르다.

Ready는 카드30개·정확한 상태·활성 재개 버튼을 확인하는 시간으로 비교 count 응답 완료를 요구하지 않는다. 따라서 마지막 관련 API 완료를 별도로 기록한 것이 맞다. load-event 중앙/p95는 증가했으며 개선 수치로 숨기지 않는다. navigation10개의 nearest-rank p95는 최대값과 같아 작은 표본 한계를 가진다.

Main 양쪽 page/API 성공, collector errors0, censored0이며 raw의 모든 완료 요청에 실패/400이상 응답이 없음을 확인했다. 최초 첫 API 표본은 본측정에 섞지 않는다.

### 메모리·GC

전체 capture 표본은554→450, 실제 표본 구간 약278.28→226.23초로 길이가 다르다. API가 빨라졌기 때문에 단순 누적 GC 감소율을 동일 시간 부하의 감소율로 설명하지 않는다. 전체 observed heap max766.68→689.81MiB, RSS1185.06→1177.61MiB다. 큰 RSS 감소를 주장할 근거는 없다.

Matched65초 구간의 young GC55–59→0–1, full GC1–2→0, GCT0.179–0.201→0–0.001초를 직접 재계산했다. 이 구간 RSS는 BEFORE1073.97–1140.69MiB, AFTER1119.83–1120.69MiB로 여전히 약1.1GiB다. heap/RSS는0.5초 관측값이며 실제 순간 peak를 보장하지 않는다. jstat GCT는 collector 시간으로 HTTP latency나 전체 STW 시간과 동일하지 않다.

### 별도 active15초

동일 runner hash와15,000ms bounds를 확인했다. job 상세124회+full world page3회가 bulk job4회+scalar comparison4회로 바뀌었다. encoded API body는214,902→79,424bytes다. 양쪽 success=true/errors0이며 해당 구간 실패·censored가 없다. 응답 이후 polling 예약과 window 경계로 비교 호출 수3→4가 된 사실을 그대로 남긴다.

이는 미리 준비된 RUNNING/INCOMPLETE 작업과 PROCESSING 비교의 표본1회다. Worker의 실제 실패→재개→완료 전이 시험이나 repeated latency 통계는 아니다. 원래 fixture 복원 manifest는 byte-identical이며 actual DB restore는 실행자가 남긴 reseed 근거에 따른다.

## 실제 SQL 경계

| 별도 capture 호출 | BEFORE SELECT/분석 JSON 포함 | AFTER SELECT/분석 JSON 포함 |
|---|---:|---:|
| job-one |5/1|4/0|
| jobs-thirty |150/30|4/0|
| comparison |7/1|4/0|
| episodes |5/1|5/1|

BEFORE167/33, AFTER17/1은4종 별도 호출의 합계이며 전체 페이지 SQL 총수는 아니다. job-one은30개 round와 중복되는 비교 표본이다. 실제 새 job SQL은 scalar fields+target episode metadata만 선택한다. 비교 endpoint는 work/batch 소유권 확인과 기존 scalar aggregate만 선택한다. 큰 analysis JSON, summary JSON, episode content 또는 candidate payload를 두 progress endpoint가 SELECT하지 않음을 전문에서 확인했다. 기존 episodes SELECT는 여전히 analysis JSON을 읽으므로 페이지 전체 JSON load0이라고 주장하면 틀리다. 모든 bind가 치환됐고 EXPLAIN 실패0이다. SQL execution time은 전송·Hibernate 변환·HTTP 완료 비용을 포함하지 않는다.

## 검증 명령 결과의 정확한 표현

- 실제 backend207개 XML 합계3829 tests,3593 executed,236 skipped, failures/errors0을 재계산했다. 필수 ProgressPostgres8개는 전부 실행/통과했다. full test+bootJar 로그 BUILD SUCCESSFUL과 일치한다.
- FE 최초 full run은807pass/20skip/5fail이다. 수정 후 scoped112pass와 별도 ASCII 경로3pass를 확인했다. 한 번의 clean full-suite 통과라고 표현하지 않는다. ASCII manifest432개 파일을 source와 scratch 양쪽에서 hash 재계산해 일치함을 확인했다.
- lint/typecheck/build 성공과 별개로 lint의 기존 react-refresh 경고2개를 유지한다. Knip은 기존 미사용 파일2개(`e2e/episode-source-list.ts`, `scripts/analysis-list-benchmark.mjs`)를 보고했으므로 clean pass가 아니다. Doctor exit0도 blocking=none 설정이며245개 진단과 기존 state-updater 오류2개가 남는다. 새 hook의 sequential chunking 관련 진단을 무시했다고 검증 성공으로 바꾸지 않는다.
- 저장된 actual-auth smoke는30개 카드·22/1/7 labels, 새로운10-field DTO, refreshToken HttpOnly,401/400 및 missing job/work/batch404를 기록한다. 이 파일만으로 다른 사용자의 실제 소유권 시험까지 실행됐다고 확대하지 않는다. 권한 통합 테스트와 구분한다.

## 한계

합성 로컬 읽기 경로의 개선 근거다. 운영 OOM 해결 보증, retry write-path JSON 비용 제거, 모든 화면의 상세 조회 최적화 또는 #272 세계관 pagination 해결을 주장하지 않는다. 백엔드 선배포 후 프런트 적용 순서를 유지한다.

## 최종 보고서·공개 패킷 대조

`2026-10-24-analysis-progress.md`의 시간·요청·bytes·SQL/EXPLAIN 합계·GC/heap/RSS·테스트 수치를 위 원자료와 대조했다. 162→13 SELECT는 job30+comparison+episodes 세 별도 경로 합계로 설명되고 auth/me/work가 제외됨을 명시하므로 전체 페이지 SQL로 오인시키지 않는다. load-event 증가, 남은 episodes JSON, full FE 실패/부분 재실행, Knip/Doctor 진단을 숨기지 않는다.

공개 패킷396개 체크섬 항목을 모두 재계산해 일치했다. SHA256SUMS 자체까지 포함하면 실제397개 파일이다. main/active summary·accounting·pages/API/resources·SQL index 및 fixture의 공개 사본은 원자료와 byte-identical이다. manifest의 절대 경로 치환은 sanitization 범위와 일치한다.

최종 문장의 `JVM reserved/native memory도 RSS에 포함된다`는 교정해야 한다. 단순 예약된 가상 주소 공간은 RSS가 아니며 RSS에는 실제 상주 heap/native/code 등의 페이지가 포함된다. root가 이미 교정을 요청했고 이 표현은 수치 판정에 영향을 주지 않는다. RSS 약1.1GiB가 유지된 이유를 여기서 특정 메모리 종류로 단정할 근거도 없다.

판정: 위 문구를 정확히 교정하면 보고 가능한 로컬 성능 근거다. 코드 자체 최종 검토는 별도 Astra code-review 판정에 따르며, 이 문서는 운영 효과나 retry 쓰기 비용에 대한 보증이 아니다.

최종 교정 확인(root): 본 보고서의 조건이었던 RSS 문구를 최종 성능 보고서에서 확인했다. 현재는 “RSS에는 Java 힙 외의 실제 상주 메모리도 포함된다”로 수정되어 있고, 예약된 가상 주소 공간을 RSS로 설명하지 않는다. 수치·측정 자료는 변경되지 않았다. 별도 Astra 코드 검토의 최종 판정도 Ready to merge: Yes이며 남은 지적은 없다.
