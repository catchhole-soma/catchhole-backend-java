# 분석 목록 측정 사전 검증 기준

2026-10-24, Backend #288. 코드만 검토한 기준이며 실행·측정 결과가 아니다. 기준 Backend `0bf469d`, Front `49a1112`. 개선 전후 같은 판정을 사용한다.

## 1. 준비 완료의 정확한 조건

회차-only 60작업 fixture는 card DOM 순서 대신 `.analysis-batch-card` 내부 `.analysis-batch-card__title`의 회차 범위로 식별한다. 정렬상 31–60화가 먼저 나올 수 있다.

| 카드 | 상태 `.analysis-status` | 그룹 숫자 | primary button exact accessible name |
| --- | --- | --- | --- |
| 1–30화 | 분석 취소 | 18/30 완료, 12 취소 | 취소 확인 |
| 31–60화 | 일부 분석 중단 | 2/30 완료, 1 실패, 27 대기 | 실패 확인 |

각 제목에는 `30개 회차`가 있고, 후보 총계는 각각 캐릭터342/세계관296, 캐릭터56/세계관113이다. reviewed/pending은 fixture manifest의 값과 대조한다. 후보 수는 `CandidateReviewCount`가 `캐릭터 설정 후보 R/T개 검토 완료` 형식으로 표시하므로 총계만 떨어진 숫자로 검색하지 않는다. 상태와 숫자는 서버 응답뿐 아니라 DOM에도 있어야 한다.

두 카드, 정확한 상태/그룹/후보 숫자, 두 primary button의 표시와 enabled를 모두 만족하고 출처 확인중/오류 안내가 사라진 순간을 후보 ready로 잡는다. 두 animation frame 후 같은 조건을 재검사하고 그때 browser `performance.now()`를 `pageReadyMs`로 저장한다. 실제 버튼 클릭은 타이밍 샘플 밖의 별도 correctness run에서 진행/작업 ID와 목적이 맞는지 검증한다. 버튼이 다른 overlay에 가려지거나 강제 클릭만 가능한 실행은 준비 성공으로 보지 않는다.

Playwright `expect` 재시도 완료 후 Node 시각을 ready로 삼으면 폴링 간격·IPC 오버헤드가 포함된다. 가능하면 `page.addInitScript`에서 document observer/RAF loop를 설치해 browser 안에서 조건과 시각을 기록하고 runner는 저장된 시각을 읽는다. product 코드에 benchmark instrumentation은 넣지 않는다. `page.goto()`의 기본 load 대기를 마친 뒤 처음 ready를 검사하면 window load보다 먼저 준비된 화면을 놓칠 수 있으므로 `waitUntil: 'commit'` 또는 사전 설치 observer를 사용한다. 성능용 selector는 현재 before DOM과 after DOM에 동일하게 적용한다.

## 2. 시계와 window load

`pageReadyMs`와 `PerformanceNavigationTiming.loadEventEnd`는 모두 해당 navigation의 `performance.timeOrigin`에서 경과한 ms다. `performance.now()`에서 다시 timeOrigin을 빼지 않는다. 저장 항목은 `timeOriginEpochMs`, `pageReadyMs`, `loadEventEndMs`이며 `loadEventEnd=0`은 미완료다. load event 내부 즉시 읽으면 0일 수 있으므로 다음 task에서 재조회한다. 리다이렉트로 로그인 페이지가 뜬 sample은 실패다. reload는 새로운 timeOrigin을 사용한다.

Playwright `request.timing().startTime`은 epoch ms, `responseEnd`는 그 request 시작 기준 ms다. 완료된 요청의 navigation 기준 시각은 `startTime - timeOriginEpochMs`, 종료는 그 값 + `responseEnd`다. `Date.now()`, Node monotonic clock, browser `performance.now()`를 그대로 빼지 않는다. raw timing도 함께 저장하고 음수/미완료 값은 성공 시간으로 집계하지 않는다.

## 3. 네트워크 수집 범위와 종료

fresh context를 만든 직후, page 생성/첫 실제 navigation 전에 context request/response/requestfinished/requestfailed listener와 auth initScript를 모두 설치한다. localStorage accessToken은 navigation 전에 initScript로 넣고 로그인/fixture 준비 요청은 측정 밖 별도 단계로 처리한다. token은 로그에 쓰지 않는다. 측정에는 `/auth/me`, 작품 공통 API, batches 및 뒤따르는 jobs/episodes/books/detail/candidate 요청을 전부 포함한다. API만 집계한 표와 문서/JS/CSS/이미지 등 전체 페이지 표는 분리한다. 외부 asset을 차단했다면 before/after 동일 정책과 실패 요청 수를 명시한다.

`response` 이벤트는 헤더 도착이므로 요청 완료가 아니다. requestfinished 또는 `response.finished()` 이후 `request.timing()`과 `request.sizes()`를 읽는다. 비동기 event handler 안의 수집 Promise를 배열/Set으로 추적하고 모두 settle한 뒤 파일을 flush한다. 요청 URL의 query/식별자는 필요한 합성 값만 허용하며 authorization/cookie/raw body는 저장하지 않는다.

pageReady가 된 순간 context를 닫지 않는다. ready 시점에 진행 중이던 before의 불필요한 API를 빠뜨리면 제거 효과를 과소기록한다. 권장 고정 프로토콜: `max(pageReady, windowLoad) + 2초`까지 시작한 요청을 first-load 집합으로 확정하고, 그 집합의 완료를 navigation 시작부터 최대60초까지 기다린다. 집합에 든 요청의 종료가 ready 뒤여도 끝까지 기록하며 `finishedAfterReady=true`, ready 시점 진행중 개수, 최초부하 전체 완료 시각을 별도로 출력한다. 60초까지 안 끝난 요청은 timeout/censored로 남기고 성공표에서 조용히 빼지 않는다. 집합 확정 뒤 시작한 요청은 background로 따로 기록한다. first-load drain 시간은 pageReady에 더하지 않는다.

상태 fixture는 CANCELED/PARTIALLY_FAILED라 정상적으로 batches 10초 polling을 시작하지 않는다. polling 검증은 별도의 IN_PROGRESS correctness fixture와 고정 관측 구간으로 수행한다. `networkidle`을 UI 준비 완료나 API 성공 판정으로 쓰지 않는다. 원고 탭에서 넘어오는 재사용 context 검증도 fresh-navigation 성능표와 분리한다.

## 4. bytes의 의미

- `dbJsonLogicalBytes`: 세 JSONB 컬럼의 `octet_length(column::text)` 합, 목표15,460,388. 문자열 논리 크기이며 heap/TOAST 저장 크기/HTTP 응답 크기가 아니다.
- `responseBodySize`: Playwright request.sizes의 encoded response body bytes. gzip 등의 전송 인코딩 영향을 받는다.
- `responseHeadersSize`: HTTP 응답 헤더 bytes. 합계가 필요하면 body와 명시적으로 더한다.
- `decodedResponseBytes`: 별도로 정말 필요할 때만 response.body Buffer 길이로 기록한다. 원문을 파일에 남기지 않는다. encoded body와 혼용하지 않는다.
- 브라우저 ResourceTiming은 cross-origin Timing-Allow-Origin이 없으면 제한되므로 0 transferSize를 전송 없음이라고 해석하지 않는다. request.sizes를 기준으로 한다.

## raw 수령 후 확인할 항목

20개 navigation sample과 실패/timeout 개수, 30개 API sample, warmup 제외 여부, 매번 다른 context와 timeOrigin, fixture hash/DB bytes, 동일 resource/서버 옵션, 카드별 assertion 결과, 정확한 endpoint 요청 수, headers-only 조기 종료가 없는 timing, ready 뒤 완료 요청 누락, 모든 async collector settle, payload/DB bytes 라벨 구분을 확인한다. raw와 summary의 median/p95/max는 직접 다시 계산한다. 아직 이 검증을 실행했다고 기록하지 않는다.
