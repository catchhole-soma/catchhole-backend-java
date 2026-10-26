# #288 개선 전 측정 검증

검증일: 2026-10-24. GPT-6 Astra 역할로 코드·원자료를 읽고 독립 재계산했다. 제품 코드는 수정하지 않았다.

**결론: fixture v2의 canonical BEFORE 검증 통과.** 현재 `evidence/before`를 정식 비교 기준으로 사용한다. 후보 enum을 STRING/UNRESOLVED로 교정하기 전 결과는 `before-attempt2`에 보존했다. 이전 count 기반 목록의 측정 시간이 무효라는 뜻은 아니며, 유효한 도메인 입력으로 비교 자료를 교체했다.

## 산출물과 실제 실행 출처

원자료 루트는 workspace `output/analysis-list-optimization-20261024/evidence/before/`다. manifest, summary, pages/api/resources JSONL, API/GC 로그, SQL/EXPLAIN 및 fixture readback을 확인했다. 독립 집계는 `accounting.json`에 저장했다.

현재 worktree에는 구현 변경이 있으므로 현재 소스가 pristine이라는 설명은 사용하지 않는다. 실제 실행한 보존 산출물을 검증했다.

- Backend 기준: `0bf469df9c6db274ca12ce6a88f7d10115515899`. 실행 파일은 `runtime/baseline.jar`, SHA-256 `addbba49f034045ede11b6d6541a234f5d41c51602d12e5ee82fc29b370ae13e`다.
- Front 기준: `49a1112fb1b6a6f9692b403e3d6482c56b25ffba`. 실제 서빙 디렉터리는 `runtime/frontend-before`다.
- JAR와 모든 Front asset의 실제 해시가 `before-attempt2/manifest.json`의 제품 변경 전 해시와 일치한다. `artifactSourceProvenance`의 원본 manifest도 직접 대조했다.
- runner SHA는 `a4459ea74d879108bb659d4daefd9cb5046d930407f9956c7474a68b7843ab0a`이며 이전 corrected collector와 동일하다. fixture generator 및 시작 script 해시도 현재 파일과 manifest가 일치한다.

## Fixture 및 원자료 검사

fixture v2의 명시적 enum을 해당 Java enum 27종과 대조했다. STRING, UNRESOLVED뿐 아니라 회원·작품·업로드·회차·분석·후보의 입력 상수도 정의된 값이다. 부모 확인과 별도로 전용 PostgreSQL에서 `BEGIN READ ONLY`로 다시 읽고 `fixture-readback.json`에 저장했다.

| 검증 | 결과 |
| --- | --- |
| DB 개수 | 회차 60, 배치 2, 작업 60, 캐릭터 후보 398, 세계관 후보 409 |
| 교정 enum | STRING 398개, UNRESOLVED 398개 |
| 분석 JSON | 15,460,388 bytes, MD5 `0b24b03a1e8b94054e78635a4d79ebc0`; fixture manifest와 일치 |
| 화면 상태 | 1–30화: 18 완료·12 취소 / 31–60화: 2 완료·1 실패·27 대기 |
| 화면 준비 | 후보 342/56 및 296/113, 정확한 상태·숫자·활성 primary button을 browser 내부에서 검사하고 2 RAF 후 재검사 |
| 페이지 표본 | warmup 3 + navigation 20 + reload setup 5 + double reload 10 = 38행, 모두 성공 |
| API 표본 | 최초 요청 1 + warmup 3 + measured 30 = 34행, 모두 200·성공 |
| 네트워크 | 각 페이지 Playwright 13요청 + CDP OPTIONS 6요청; 총 494+228개 모두 200·완료 |
| 수집 품질 | collector error 0, 미완료 요청 0, navigation timeOrigin 38개 모두 서로 다름 |
| 런타임 | API 로그 PID 66669, baseline.jar, e2e를 확인. GC 시작 02:03:02.792와 API 시작 02:03:03.289가 같은 기동에 대응 |
| SQL | parameter가 모두 적용된 실제 PostgreSQL SELECT 16개, EXPLAIN 16개. 오류 없음 |

SQL capture는 timing 종료 뒤 별도로 수행했다. API 로그에서 ERROR/OOM은 발견하지 못했다. 이 결과는 합성·직렬 시험 구간의 관측이며 운영 OOM 해소의 증명이 아니다.

## 독립 통계 재계산

JSONL에서 measured navigation 20개와 measured API 30개를 선택해 median, nearest-rank p95, max를 다시 계산했다. summary.json과 모두 일치한다. warmup·reload setup·연속 새로고침은 이 표에 섞지 않았다.

| 지표 | median | p95 | max |
| --- | ---: | ---: | ---: |
| 카드·버튼 ready | 446.800ms | 463.300ms | 499.000ms |
| window load | 62.250ms | 65.600ms | 69.700ms |
| batches API 전체 본문 수신 | 128.229ms | 136.446ms | 136.612ms |

페이지 실패 0/20, API 실패 0/30, double reload 실패 0/10이다.

## 리소스 비교 구간 고정

원본 resources.jsonl에는 343개 샘플이 있고, runner 종료 후 약 73초의 idle 샘플 144개가 포함되어 있었다. 원자료는 그대로 보존한다. 공정한 before/after 비교에는 **sampler 시작부터 runner가 summary를 처음 저장한 순간까지**를 사용한다.

- 시작: epoch `1792775115.471715` seconds (첫 resource 샘플).
- 종료: epoch `1792775216.1044834` seconds. 원본 summary.json의 최초 생성 직후 filesystem mtime에서 읽었다. 이 값을 accounting.json에 저장했으므로 파일 복사 이후 바뀐 mtime을 다시 사용하지 않는다.
- 비교 구간: 199개 샘플, 관측 peak RSS **951.875MiB**, heap used **450.38564453125MiB**.
- 전체 raw 구간: 343개 샘플, RSS **951.953125MiB**, heap used **466.519140625MiB**. 비교 구간 값과 별도로 보존했다.

after도 같은 시작·종료 정의를 적용한다. 리소스는 0.5초 표본의 관측 peak이며 정확한 순간 최대나 retained heap이 아니다. native Java의 RSS는 Xmx 768MiB와 다른 범위다. API CPU는 호스트 스케줄링, PostgreSQL은 2CPU/1GiB이며 운영과 같은 용량을 검증한 것이 아니다.

## SQL 시간의 해석

큰 JSON 3컬럼을 읽는 쿼리는 `03-batches.sql`, `09-all-jobs.sql`, `15-episodes.sql`이다. 각 EXPLAIN actual rows는 60, execution time은 각각 0.251/0.426/0.140ms다. automatic_input_state, run_base_state, state_journal이 SELECT 목록에 모두 있는 것을 확인했다.

이 시간은 실행계획 비교용이다. PostgreSQL 16은 EXPLAIN ANALYZE에서 출력행을 클라이언트에 전달하지 않아 network transmission과 I/O conversion 비용을 포함하지 않는다. 따라서 큰 TOAST JSON의 실제 출력 변환·DB→JDBC 전송·Hibernate/Jackson 객체 생성·HTTP 응답 비용을 이번 EXPLAIN만으로 분해할 수 없다. API 시간에서 EXPLAIN 시간을 빼서 JSON 변환 시간이라고 단정하지 않는다. [PostgreSQL 16 Using EXPLAIN, Caveats](https://www.postgresql.org/docs/16/using-explain.html#USING-EXPLAIN-CAVEATS)

## 보존할 한계

- CDN jsdelivr 요청 2개가 매 페이지 포함된다. after도 같은 network/cache 정책으로 실행한다.
- OPTIONS 228개는 관측했지만 encodedDataLength=0을 전송 0 bytes로 해석하지 않는다. OPTIONS wire bytes는 완전히 관측하지 못했다.
- `initial-cold-jvm-request`는 기동·인증 뒤 첫 batches 요청이다. 완전한 OS/DB/JVM cold 측정으로 부르지 않으며 통계에서 제외한다.
- JSON 논리 bytes, HTTP 응답 bytes, TOAST 물리 저장 크기, heap 사용량은 다른 지표다.
- 이번 canonical before를 덮어쓰거나 after에 맞춰 ready 조건·표본을 변경하지 않는다.

## 구현할 카드 응답 계약 확정

기존 배치 필드는 유지하며 다음을 추가한다.

- `episodeJobGroups: List<AnalysisBatchJobGroupResponse>`
- `episodeStatus: AnalysisBatchStatus | null`: 회차/targetless 그룹이 없으면 null이며 회차 카드를 렌더링하지 않는다.
- `settingBooks: List<AnalysisBatchSettingBookResponse>`

설정집 카드는 `settingBookId`, `sourceAnalysisJobId`, 실제 origin의 `batchId`, `revisionId`(UUID), `settingBookName`, `status`, nullable typed `failureCode`, nullable `currentStep`, `contentChangedSinceAnalysis`, `historical`, `lastActivityAt`, `characterCandidates`, `worldCandidates`를 가진다. 양쪽 후보 요약은 total/reviewed/pending/directReview/processingCandidateCount(long)다. 상태 enum은 PENDING, IN_PROGRESS, COMPLETED, INTERRUPTED, FAILED, CANCELED, INVALIDATED다. origin은 최신 SETTING_EXTRACTION 기준으로 기존 이동 동작을 보존한다.

설정집 상태는 기존 `SettingBookAnalysisStatus`와 `ordered-analysis.ts` 조건을 보존한다. 예를 들어 INVALIDATED가 우선이며 ordered FAILED는 INTERRUPTED다. SUCCEEDED+SEALED라도 AUTOMATIC이고 automaticAppliedAt이 없으면 기존 설정집 UI에서는 IN_PROGRESS다. 회차 목록의 listed status가 이 경우 FAILED인 것과 구분한다. source마다 기존 상태 의미를 임의 통합하지 않고 각각 회귀 테스트로 고정한다.
