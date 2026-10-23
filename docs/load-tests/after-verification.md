# #288 개선 후 성능 검증

검증일: 2026-10-24. GPT-6 Astra 역할로 before/after 원자료와 산출물 해시를 독립 확인하고 통계를 재계산했다. 추가 부하 테스트나 제품 수정은 수행하지 않았다.

**결론: canonical fixture v2에 대한 개선 전후 비교 검증 통과.** 카드·버튼 준비 시간과 API 지연·응답량이 감소했다. window load는 증가했으며 그 결과도 그대로 보존한다. 이 합성 로컬 측정으로 운영 OOM 해소나 운영 지연을 보장하지 않는다.

## 비교 조건과 실행 출처

증거 루트는 workspace `output/analysis-list-optimization-20261024/evidence/`다.

- before/after의 fixture 전체 객체, JSON MD5·15,460,388 logical bytes, runner SHA, 시작 script SHA, Java/Node/DB 버전, PostgreSQL 2CPU/1GiB 제한, JVM Xms256m/Xmx768m, viewport, cache·scheduler·계측 정책이 일치한다.
- runner SHA: `a4459ea74d879108bb659d4daefd9cb5046d930407f9956c7474a68b7843ab0a`.
- before는 보존한 baseline.jar와 frontend-before를 실행했다. after는 `runtime/after.jar`와 `runtime/frontend-after`이며 manifest의 실제 JAR 및 모든 asset 해시가 파일과 일치한다.
- after 제품 기준은 Backend `184ff78b3694f08adaa94e4db6109c847fd7fb3b`, Front `e6ccfd7834699f9fad7b4b576bfc2577790be425`다. Front manifest의 남은 diff는 문서뿐이다.
- after API PID 76304와 실행 after.jar는 API 시작 로그에서 확인했다. GC 시작 02:24:43.637과 API 시작 02:24:44.172가 대응한다.
- 전용 PostgreSQL에 직접 읽기 전용 조회를 실행해 현재 작업 60개, JSON 15,460,388 bytes와 MD5 `0b24b03a1e8b94054e78635a4d79ebc0`, STRING/UNRESOLVED 후보 398개도 확인했다.

## 표본과 수집 완결성

양쪽 모두 page JSONL 38행(warmup 3, navigation 20, reload setup 5, double reload 10), API JSONL 34행(최초 1, warmup 3, measured 30)이다. 모든 페이지·API가 성공했다. 측정 통계에는 navigation 20개와 measured API 30개만 사용했다.

before는 494 Playwright 요청과 OPTIONS 228개, after는 380 Playwright 요청과 OPTIONS 114개다. 모두 HTTP 200으로 끝났고 미완료·collector 오류는 0이다. after의 ready 이후 완료된 요청 38개는 폰트 요청이며 모두 drain하여 기록했다. UI 준비 직후 context를 닫아 늦은 요청을 누락하지 않았다.

카드 준비 판정은 같은 두 회차 카드, 상태·완료/실패/취소/대기 숫자, 후보 수와 활성 `취소 확인`·`실패 확인` 버튼을 browser 내부에서 검사하고 2 RAF 뒤 다시 확인한다. 입력·화면 숫자를 바꿔 성능을 높인 비교가 아니다.

## 독립 재계산 결과

각 raw JSONL에서 median, nearest-rank p95, max를 다시 계산했으며 양쪽 summary와 모두 일치한다.

| 지표 | before median / p95 / max | after median / p95 / max | median 변화 |
| --- | ---: | ---: | ---: |
| 카드·버튼 ready | 446.80 / 463.30 / 499.00ms | 129.35 / 184.30 / 290.60ms | 71.05% 감소 |
| batches API 본문 수신 완료 | 128.229 / 136.446 / 136.612ms | 8.517 / 13.192 / 13.514ms | 93.36% 감소 |
| window load | 62.25 / 65.60 / 69.70ms | 64.25 / 119.20 / 224.70ms | 2.00ms 증가 |

ready p95는 60.22%, API p95는 90.33% 감소했다. window load p95는 53.60ms 증가했으므로 모든 로딩 지표가 개선됐다고 설명하면 안 된다.

### window load 증가에서 관측한 내용

외부 CDN 정책은 양쪽에서 같고, 같은 jsdelivr CSS와 폰트를 요청한다. after navigation 14는 load 224.7ms이며 CDN stylesheet가 navigation 8.6→195.5ms에 걸쳐 완료됐다(약 186.95ms). navigation 19는 load 119.2ms이며 같은 CSS가 10.1→99.7ms에 완료됐다(약 89.61ms). before의 같은 CSS 요청 소요 시간은 median 35.81ms, max 41.68ms이고 after median은 36.98ms다.

이는 긴 window load 표본에서 외부 CSS 요청도 길었다는 직접 관측이다. CSS 하나가 증가분 전부를 설명한다고 단정하지 않는다. 같은 표본의 local JS 완료 시각은 각각 20.5ms/24.5ms였다. ready와 load를 별도 지표로 유지하며 불리한 표본을 제외하지 않았다.

## 요청 및 응답량

측정 navigation마다 실제 API 요청은 6개에서 3개가 됐다. after에는 `/auth/me`, `/works`, `/analysis-jobs/batches`만 있다. 전체 작업·회차·설정집 목록, 개별 상세 및 후보 목록 요청은 없다.

| API 응답 body bytes (Playwright 관측) | before | after |
| --- | ---: | ---: |
| 전체 API 합계 | 124,223 | 7,887 |
| batches 단독 | 4,237 | 7,206 |

전체 API 응답 body는 93.65% 감소했다. batches 자체는 source별 카드 요약 추가로 커졌다. 이를 batches DTO 크기 감소로 설명하지 않는다. removed jobs 92,725 bytes·episodes 26,464 bytes·setting-books 116 bytes가 없어지면서 페이지 전체 API 응답량이 감소한 것이다. OPTIONS의 encodedDataLength=0은 실제 전송 0 bytes가 아니며 완전한 wire bytes로 합산하지 않는다.

## SQL·projection 확인

before `sql/index.json`은 batches 7개, all-jobs 4개, episodes 5개 SELECT를 기록했다. after `sql-list/index.json`은 실제 남은 batches 경로의 9개 SELECT다. after의 9개 모두 parameter가 적용된 SQL과 EXPLAIN이 있고 큰 분석 JSON 3컬럼의 선택은 0이다.

**단일 batches SQL 수는 7→9로 증가했다.** 대신 전체 엔티티 대신 DB 최신 선택·집계와 작은 이동 ID 결과를 읽는다. 제거된 all-jobs/episodes 경로까지 포함한 조사 범위는 16→9다. 이 숫자는 auth/me·works·setting-books까지 포함한 페이지 전체 SQL 개수라고 주장하지 않는다.

after ID projection은 batch/type/scope/job ID/createdAt의 작은 행 120개(ALL/EPISODE scope), group 집계는 4개다. 작업별 상세 metadata 20여 필드를 다시 화면 DTO로 옮기는 형태가 아니다. SQL 텍스트에서 automatic_input_state, run_base_state, state_journal이 모두 없음을 직접 확인했다.

PostgreSQL focused test XML은 29개 실행, skipped/failure/error 0이며 `작업 수가 늘어도 JSON이나 분석 엔티티를 읽지 않고 쿼리 수를 유지한다` 테스트가 통과했다. 테스트 코드는 2→10작업에서 SELECT 수가 같고, AnalysisJob/Episode/UploadFile/양쪽 Candidate entity load count가 0임을 검사한다. 실제 SQL capture와 이 회귀 테스트를 함께 근거로 삼았다.

EXPLAIN 실행 시간은 출력행 전송·I/O conversion 및 JDBC/객체 변환 시간을 포함하지 않는다. API 시간에서 EXPLAIN을 빼서 JSON 변환 비용이라고 단정하지 않는다. 자세한 측정 경계는 baseline-verification.md에 명시했다.

## 동일 정의의 리소스 구간

양쪽 모두 첫 resource 샘플부터 runner의 summary 최초 저장 시각까지를 집계했다. 종료 epoch는 accounting에 고정되어 있어 파일 복사 후 mtime을 다시 쓰지 않는다.

| 지표 | before | after |
| --- | ---: | ---: |
| 비교 구간 샘플 | 199 | 167 |
| 종료 epoch seconds | 1792775216.1044834 | 1792776417.7367404 |
| 관측 peak RSS | 951.875MiB | 895.859375MiB |
| 관측 peak heap used | 450.385645MiB | 348.576660MiB |
| jstat YGC 증가 | 264 | 1 |
| jstat GCT 증가 | 0.409s | 0.004s |
| Full GC 증가 | 0 | 0 |

같은 요청 수의 실행 시간이 달라 표본 수·구간 길이는 다르다. before의 측정 후 idle 144샘플은 raw에 보존하되 비교에서 제외했다. heap은 S0U+S1U+EU+OU, RSS는 ps 값을 사용해 accounting과 독립 재계산 결과가 일치했다. 표본 peak는 실제 순간 최대나 retained heap을 보장하지 않는다. 두 API 로그에서 ERROR/OOM은 발견하지 못했다.

## 최종 판단

동일한 유효 합성 데이터와 실제 인증·실앱에서 페이지 준비·API 응답 지연 및 응답량 감소를 확인했다. source별 표시·이동·권한의 기능 검증은 별도 analysis-list-code-review.md와 기존 테스트/live 증거를 따른다. 이번 성능 검증은 운영 부하·운영 OOM 복구나 동시 사용자 용량 검증으로 확대 해석하지 않는다.
