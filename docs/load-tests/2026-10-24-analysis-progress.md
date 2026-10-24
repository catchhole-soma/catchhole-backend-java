# 분석 진행 상태 조회 최적화 (#291)

30개 작업의 상태 확인을 개별 상세 GET에서 scalar 묶음 GET으로 바꾸고, 진행 화면의 세계관 후보 전체 조회를 세 count 집계로 바꿨다. 같은 로컬 합성 데이터의 65초 대기 구간에서 작업 요청은 매번 630개에서 2개로 줄었다. 새 작업/비교 진행 SQL은 큰 분석 JSON을 선택하지 않는다. 초기 회차 목록의 기존 JSON 읽기와 재시도 쓰기 비용은 남는다.

## 변경 및 검증 자료

- 계약과 배포 순서: [분석 진행 API](../analysis-progress.md).
- 재현 명령·격리 guard·관측 정의: [benchmark README](../../scripts/load-tests/analysis-progress/README.md).
- 원자료: [sanitized packet](results/2026-10-24-analysis-progress/SANITIZATION.md), [checksums](results/2026-10-24-analysis-progress/SHA256SUMS.json).
- 독립 검증: [BEFORE](analysis-progress-baseline-verification.md), [AFTER](analysis-progress-after-verification.md), [코드 검토](analysis-progress-code-review.md). 최종 코드 검토는 추가 findings 없이 Ready to merge: Yes다.
- [테스트 집계](results/2026-10-24-analysis-progress/verification-summary.json), [실제 인증 브라우저 smoke](results/2026-10-24-analysis-progress/frontend-live-smoke.json).

기존 개별 상세와 설정집 전용 경로를 유지했다. 새 계약을 제공하는 Backend를 먼저 적용한 뒤 Frontend를 적용해야 한다. 아래 결과는 배포 전 로컬 측정이며, PR·CI·운영 적용 상태는 이슈 #291과 연결 PR에서 관리한다.

## 동일 조건

BEFORE는 BE `79c2b0ee3e872bf38c8da57480736de238184491`, FE `74d10ece56698233ecad000025918f9648e4f29c`의 깨끗한 tracked baseline이다. 제품 수정 전에 JAR/production frontend와 main·active·SQL 증거를 보존했다. AFTER는 이 branch의 구현 working tree를 빌드했다. 각 실제 JAR와 37개 frontend asset hash는 [before manifest](results/2026-10-24-analysis-progress/before/manifest.json), [after manifest](results/2026-10-24-analysis-progress/after/manifest.json)에 있다.

| 조건 | 값 |
| --- | --- |
| 실행 | macOS27.0.1 arm64, 18 logical CPU, 48GiB host RAM |
| Java | Microsoft OpenJDK21.0.12.1, native JVM, Xms256MiB/Xmx768MiB |
| Node/browser | Node24.19.0, Chromium149.0.7827.55, 1280×800 |
| DB | 전용 pgvector0.8.2-pg16 / PostgreSQL16.14, 2CPU/1GiB |
| 주소 | localhost DB26432/API28480/preview3340 |
| 외부 작업 | worker/scheduler/추적/SQL debug 비활성; 측정 중 빌드·테스트·진단 요청 없음 |
| API/preview 제한 | host native 프로세스, 별도 CPU/RSS 제한 없음 |
| cache | fresh browser context, HTTP cache 비활성, service worker 차단; OS/DB cache 초기화 주장은 하지 않음 |

fixture는 30회차/30작업, 성공22+실패1+선행 실패 뒤 PENDING7, 캐릭터 후보410/세계관 후보404다. 성공22개는 ordered `SEALED`와 `AUTOMATIC` 반영 latch가 있고 실패는 `INCOMPLETE`여서 실제 대기/재개 UI를 통과한다. 세 JSONB::text 합계18,161,746bytes(약17.32MiB), 성공22개17,301,914bytes(약16.50MiB), 최대1,803,551bytes(약1.72MiB)다. 중첩 character/world-setting 구조와 짧은 evidence를 고정 PRNG로 만들었다. DB 텍스트 크기는 Java heap/HTTP 크기와 다르다.

fixture manifest 내부 hash는 `586ec0a7cefd73e6807c43649ab4bd632dc3b587a67e89a6bb42fa63c8275d08`, 분석 JSON MD5는 `47214e1c4a6a5ec4fcf0a97186dc4fb7`, 세계관 payload MD5는 `f7a300fb09e5f69dc18788cf31c5a6de`다. 동일 seed/script/status/latch/counts와 명시한 payload를 비교했으며 DB 모든 값의 의미적 checksum은 아니다. 별도 [before](results/2026-10-24-analysis-progress/before/fixture-readback.json)/[after](results/2026-10-24-analysis-progress/after/fixture-readback.json) Job metadata/target readback이 일치한다. active 시나리오 후 다시 seed한 [manifest](results/2026-10-24-analysis-progress/fixture-restored-after.json)도 원본과 byte 단위로 같다. bcrypt salt와 인증 session은 비교에서 제외한다.

BEFORE JAR SHA256은 `6e14ef0f4f4994e4dedb5f66ebd3ccb1deff8a56c3bfef7844957a0c273d511a`, AFTER는 `21ad9922e4932d31da40beced21b4f7619a56eb6881878728f173543c5c1c5ca`다. 양쪽 main runner SHA256은 `364cad8209b8163a0a61bcabd039626a1b5349f9e43c9d7bf742e7ad128e7bae`다. manifest의 navigation 설명은 실제10회에 맞게 교정했고 before에 metadataCorrection을 기록했다. 원 runner/표본은 바꾸지 않았다.

## 브라우저와 API

첫 fresh-JVM API round는 별도 보존하고 아래 API 통계에서 제외했다. 양쪽3회 준비 후 fresh context 탐색10회, 대기65초×3회, API 준비3회+측정30회다. 30-job round는 BEFORE30개 동시 상세 GET 전체 완료, AFTER 같은30ID의 묶음 GET1개 완료다. 비교 API는 별도로 순서대로 측정했다. p95는 nearest-rank라 navigation10회의 p95는 최대값과 같다.

| 지표 (ms) | BEFORE median / p95 | AFTER median / p95 |
| --- | ---: | ---: |
| 30-job API round (n=30) | 141.39 / 158.38 | 9.84 / 12.17 |
| 세계관 비교 API (n=30) | 1,318.79 / 1,452.86 | 9.71 / 11.14 |
| 진행 행/버튼 ready (n=10) | 327.50 / 476.90 | 151.50 / 266.20 |
| 마지막 초기 관련 API 응답 완료 (n=10) | 1,556.14 / 1,928.03 | 324.60 / 458.87 |
| window loadEventEnd (n=10) | 63.85 / 194.50 | 82.10 / 203.60 |

ready는30행·22자동 반영 완료/1비교 미완료/7앞 회차 대기·제목·활성 재개 버튼을2RAF 뒤 확인한 시점이다. 세계관 집계 완료는 ready의 조건이 아니므로 별도의 마지막 API 완료 지표도 함께 보고한다. window load는 SPA의 데이터 완료를 뜻하지 않는다. 양쪽 warmup/navigation/polling/API 실패0, collector 오류0, censored0이다. [전체 accounting](results/2026-10-24-analysis-progress/after/accounting.json)에 요청 시작/종료와 정확한 구간이 있다.

| 최초 탐색 API (1회당) | BEFORE 요청 / encoded body bytes | AFTER 요청 / encoded body bytes |
| --- | ---: | ---: |
| auth/me | 1 / 327 | 1 / 327 |
| work | 1 / 358 | 1 / 358 |
| episodes | 1 / 13,417 | 1 / 13,417 |
| job 상태 | 30 / 49,728 | 1 / 19,661 |
| 세계관 집계 | 1 / 3,192 | 1 / 208 |
| 합계 | 34 / 67,022 | 5 / 33,971 |

표의 encoded body는 브라우저 request.sizes 값이며 응답 header와 OPTIONS/정적 자산을 제외한다. 별도 API runner의 decoded body는 job round49,698→19,632bytes, 비교3,191→207bytes다. HTTP 전송 절감만으로 Java JSON hydration 제거를 추정하지 않고 아래 실제 SQL/통계로 확인했다.

| steady 관측 | BEFORE | AFTER |
| --- | ---: | ---: |
| quiescent65초, window0 | 개별 Job630 / 비교0 | bulk2 / 비교0 |
| quiescent65초, window1 | 개별 Job630 / 비교0 | bulk2 / 비교0 |
| quiescent65초, window2 | 개별 Job630 / 비교0 | bulk2 / 비교0 |
| 각65초의 상태 encoded body | 1,044,288bytes | 39,322bytes |
| active15초 | 개별 Job124 / 후보 전체3 | bulk4 / scalar count4 |

모든 steady 수치는 raw `observationStartMs ≤ startMs ≤ observationCutoffMs` 요청만 세었다. 초기 탐색을 합치지 않았다. main fixture의 비교는 모두 COMPLETED여서 대기 중 비교 poll이 양쪽0이다. 지속 비교 poll은 별도 active fixture(23번째 Job RUNNING/INCOMPLETE, 후보1개 PROCESSING)에서 확인했다. active15초 양쪽 성공/오류0이며 동일 active runner hash다. 느린 legacy 응답과 시작 경계 때문에 BEFORE124/3, AFTER4/4가 실제 관측값이며 이론상 주기를 곱해 부풀리지 않았다. [before-active](results/2026-10-24-analysis-progress/before-active/active.json), [after-active](results/2026-10-24-analysis-progress/after-active/active.json).

## SQL과 entity hydration

타이밍 후 debug 구간을 따로 열어 실제 HTTP의 SELECT/bind를 수집하고 각 SQL에 EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON)을 실행했다. 모든 explain이 성공했다. 읽기 수는 기존 JWT 회원 조회와 작품 소유권 확인을 포함한다.

| 진단 범위 | BEFORE SELECT / 큰 JSON SELECT | AFTER SELECT / 큰 JSON SELECT | AFTER EXPLAIN execution 합계 ms |
| --- | ---: | ---: | ---: |
| Job1개 | 5 / 1 | 4 / 0 | 0.133 |
| Job30개 round | 150 / 30 | 4 / 0 | 0.123 |
| 세계관 집계 | 7 / 1 | 4 / 0 | 0.316 |
| 초기 episodes | 5 / 1 | 5 / 1 | 0.139 |

BEFORE 전체 진단167 SELECT/큰 JSON33회에는 별도 Job1개 진단이 포함된다. 그것을 페이지 한 번의 비용으로 부르지 않는다. 페이지가 쓰는 세 경로(Job30+비교+episodes)의 합계는162→13 SELECT, 큰 JSON32→1회다. 별도 auth/me/work HTTP는 이 합계 밖이다. EXPLAIN 시간은 PostgreSQL 계획 실행만이고 JDBC 전송·Java 역직렬화·동시 round 비용을 포함하지 않아 API 시간과 동일하지 않다. 원 SQL/계획은 [before index](results/2026-10-24-analysis-progress/before/sql/index.json), [after index](results/2026-10-24-analysis-progress/after/sql/index.json)에 있다.

새 Job 경로는 두 scalar constructor projection(작업/대상 회차)이다. 비교 경로는 scalar 배치 존재 확인과 기존 countReviewSummary aggregate다. 별도 실제 PostgreSQL 테스트는 flush/clear 후1개/30개 모두4 SELECT, AnalysisJob/Episode/WorldSettingCandidate entity load0, 큰 JSON/원문 컬럼0을 검증했다. snapshot 대상 우선·ARCHIVED·다중/단일/대상 없음, checkpoint 전/중/후, run/latch와 공개 failure mapping도 실행했다.

## Heap/RSS/GC

0.5초 RSS/jstat 관측이다. jstat collector 시간은 전체 stop-the-world 시간이나 HTTP latency가 아니다. full main은 비교 API30회가 빠르게 끝나면서 전체 길이가 다르므로 matched65초 구간을 함께 제시한다.

| full main 관측 | BEFORE | AFTER |
| --- | ---: | ---: |
| 첫~마지막 resource 표본 길이 | 278.28초 (554표본) | 226.23초 (450표본) |
| heap 관측 최고 | 766.68MiB | 689.81MiB |
| RSS 관측 최고 | 1,185.06MiB | 1,177.61MiB |
| YGC / FGC / CGC delta | 996 / 76 / 436 | 52 / 1 / 24 |
| collector GCT delta | 5.785초 | 0.289초 |

| matched65초 | heap 최고 BEFORE→AFTER MiB | RSS 최고 BEFORE→AFTER MiB | YGC / FGC BEFORE→AFTER | GCT BEFORE→AFTER 초 |
| --- | ---: | ---: | --- | ---: |
| 0 | 759.36→578.12 | 1,073.97→1,120.55 | 56 / 2→0 / 0 | 0.199→0.000 |
| 1 | 739.63→575.31 | 1,140.69→1,120.69 | 59 / 1→0 / 0 | 0.179→0.000 |
| 2 | 721.97→416.86 | 1,104.36→1,119.83 | 55 / 2→1 / 0 | 0.201→0.001 |

Java heap와 GC 압력은 줄었지만 RSS가 크게 낮아진 결과는 아니다. 남은 초기 episodes 상세 조회가 같은 JSON을 읽으며, RSS에는 Java 힙 외의 실제 상주 메모리도 포함된다. 합성 로컬 단일 화면 관측으로 운영 OOM 방지나 동시 사용자 처리량을 보장하지 않는다. active 사례에는 비교 가능한 resource 표본을 수집하지 않았으므로 위 자원표에 합치지 않았다.

## 회귀 검증과 검토

| 검증 | 실제 결과 |
| --- | --- |
| Backend HTTP/PG 신규 focused | 22/22 pass, skipped0; 이 중 실제 PostgreSQL8/8 실행 |
| Backend 전체 `./gradlew test bootJar` | BUILD SUCCESSFUL, 3,829개 중3,593실행/236기존 opt-in skip, 실패/오류0 |
| FE 전체 `npm run test:e2e` 최초 | 832개 중807pass/20기존 skip/5fail |
| 수정 후 FE 관련5개 spec | 112/112 pass (신규 progress11개 포함), skip0 |
| 이전 실패 중 비관련3개 ASCII 동일 source 재실행 | 3/3 pass;432 source file SHA256 원본과 일치 |
| FE lint/typecheck/build | pass; lint 기존 react-refresh 경고2, build 기존 chunk 경고 |
| FE check:unused | 기존 unused2개로 exit1; 새 benchmark CLI는 entry 등록 |
| FE doctor | blocking none/exit0, 진단245개; 기존 WorldSettingDatabase 오류2개 포함 |
| 실제 API+production browser | 30행/22완료1미완료7대기, bulk1/count1, 상세/후보 페이지0, 쿠키 HttpOnly, 400/401/404 검증 pass |

전체 FE의 두 upload-policy 실패는 mock의 개별 endpoint가 남은 경우여서 bulk fixture로 맞추고112개 관련 재실행에 포함했다. 나머지 두 family-tree 실패는 기존 fixture의 percent-encoded Unicode pathname ENOENT, 세계관640px2x screenshot1개는 병렬 부하 중 timeout이었다. 관련 없는 테스트/제품 코드를 고치지 않고 source hash가 같은 ASCII scratch에서 세 경우만 다시 실행해 통과했다. 전체832개를 다시 실행해 모두 통과했다고 주장하지 않는다. 테스트가 생성한 기존 screenshot 변경은 복원했고 이 작업 evidence screenshot만 별도로 보존했다.

TDD에서 누락 계약 HTTP/PG/count 테스트 RED를 확인한 뒤 구현했다. 신규 FE11개는 한 묶음/3초/30초, 외부 같은-ID 재개·무효화, SEALED latch, 즉시 retry, 긴 이력 순차 chunk, 중간 실패 원자성, 취소·작품 전환 격리, 이력404, 독립 비교 poll을 검증한다. 기존 ordered/upload/token/smoke와 설정집 회귀 assertion도 유지했다.

독립 Astra 검토의 Important1건은 “restart POST 성공 후 GET 실패가 새 ID 채택을 막는 문제”였다. 해당 재현 테스트가 기존 코드에서 실패하는 것을 확인하고, 새 ID/URL을 먼저 저장한 뒤 GET 오류를 상태 재시도로 처리하도록 수정했다. 이후112개 관련 사례와 최종 nullable SDK/typecheck/build가 통과했고 그 artifact를 AFTER에서 사용했다. 최종 scoped 재검토에서 추가 findings 없이 Ready to merge: Yes를 받았다.

남은 비용은 초기 `/episodes`의 기존 전체 entity 읽기, retry 쓰기의 JSON 로딩/복사, 후보 검토 화면의 #272 상세 페이지네이션이다. 이번 범위에서 바꾸지 않았다. 새로운 timer 지연은 ordered 대기 시 최대30초이며 명시 재개·focus/reconnect/사용량 알림은 별도 즉시 갱신 경로를 유지한다.

## PR 검토 후 추가 보정

위 수치는 `after/manifest.json`의 측정 artifact에 해당한다. 이후 [Frontend #100](https://github.com/catchhole-soma/catchhole-front/pull/100) 리뷰에서 재시도 POST 성공 ID를 후속 GET 전에 저장하는 처리와, INVALIDATED이지만 실행 상태가 PENDING/RUNNING인 작업을 종료까지 계속 읽는 처리를 보정했다. 이 두 상태 전이는 위 main/active 성능 fixture에 포함되지 않으며 별도 회귀 테스트로 검증한다. 원자료·측정 수치를 다시 쓰지 않았고 최종 커밋·CI·추가 검토 결과는 PR에 기록한다. 최초 독립 검토 이후 발견된 두 건이므로 최초 검토 기록만으로 최종 머지 판단을 대신하지 않는다.
