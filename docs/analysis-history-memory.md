# 누적 회차의 과거 Job 조회와 입력 복사 (#374)

## 문제와 변경 범위

다음 자동 회차를 준비할 때 과거 Job의 상태·출처만 필요한 여러 경로가 Job 엔티티를 읽었다.
`automatic_input_state`, `run_base_state`, `state_journal`은 JSON 엔티티 필드이므로,
Job을 읽으면 그 큰 본문까지 JDBC 결과와 Hibernate 관리 상태에 들어온다.
확정 근거의 `candidate.analysisJob` 또는 `fact.extractedByJob`에서 원문 key/hash를 읽는 것도 같은 문제가 된다.
회차 목록은 최신 상태를 표시하기 위해 과거 Job들을 읽은 뒤 Java에서 최신을 골랐다.

이 변경은 조회 범위와 중간 메모리 표현만 바꾼다. 모델·프롬프트·후보 판단·분석 결과·저장 이력 형식은 유지한다.
운영 데이터 삭제·v1 journal 일괄 변환·인프라 설정·자동 재시도는 포함하지 않는다.

## 다음 회차의 입력 준비

1. 기존 claim 잠금과 원문 세대 검증을 수행한다.
2. 자동 분석의 선행 run 구성은 scalar 출처/state view로 확인한다. 순서·predecessor 연결·작품·출처 종류·현재 원문,
   선행 `SUCCEEDED + SEALED + automaticAppliedAt` 조건은 그대로 유지한다.
3. 현재 캐릭터·세계관 설정과 모든 사실·근거·이력을 수집한다. 이 내용은 분석에 필요한 정보이므로 줄이지 않는다.
   근거 조립에 필요한 과거 Job의 식별자·원문 hash/key/version·설정집 revision은 scalar view로 대체한다.
   같은 capture 안에서는 출처를 재사용하며 저장소 간이나 요청 간 공유 캐시를 만들지 않는다.
4. 미확정 참고에서는 과거 Job의 최신 시도를 기존 정렬·선택 규칙으로 고른 후,
   각 Job의 `PENDING_REVIEW` 후보만 DB에서 읽는다. 최신 실패/취소/원문 변경 때문에 제외되던 시도를
   과거 성공 시도로 되돌려 선택하지 않는다. CLOSED·확정·무시 후보와 원문 없는 참고의 처리 규칙도 보존한다.
   사용자 반려 내역은 별도로 계속 입력에 포함하며, 반려 후보의 출처 확인도 작은 metadata로 수행한다.
   반려 fingerprint·근거·출처 동일성 검사는 유지한다.
5. 입력 조립 중 읽기만 하는 캐릭터·세계관 노드는 중간 복사를 생략하고, `captureAutomaticInput`에서 저장용 복사본을 만든다.
   후속 병합이 있는 references는 독립된 조립 영역을 유지한다. 수동 실행의 시작 상태도 저장 경계에서 복사한다.
6. hash/검증 내부에서는 자동 입력을 읽기 전용으로 사용한다. 공개 `getInputState`와 projected 결과는 저장 입력과 분리하며,
   요청 내 `PreparedState`의 방어 복사와 append 실패 시 원자성을 유지한다.

캐릭터 저장 근거의 DB 검증은 그대로다. 파기 표시·현재 원문 결속·파기 대기·manifest·소유권 검증을 projection 전환으로
우회하지 않으며, 검증할 수 없는 과거 인용을 현재 원문으로 대신하거나 S3 재조회 fallback을 추가하지 않는다.
세계관 시점의 null fallback은 scalar 출처 종류로 동일하게 판단한다.

## 같은 hash를 작은 버퍼로 계산

이전 hash 계산은 중첩 노드별 canonical 문자열을 만들고 전체 문자열을 합친 다음 전체 UTF-8 byte 배열을 만들었다.
이제 같은 순서로 canonical 값을 출력하면서 고정 크기의 Writer 버퍼를 통해 SHA-256에 전달한다.
전체 canonical 문자열이 필요한 기존 API는 단일 StringBuilder를 사용한다.

- 객체 key 정렬, 배열 순서, 문자열 escaping/Unicode와 숫자의 최소 십진수 표현은 동일하다.
- 비유한 숫자와 지원하지 않는 JSON 노드는 계속 거절한다.
- 기존 Python 공유 v1/v2 fixture의 canonical 문자열·SHA-256과 큰 Unicode 인용을 검증한다.
- 저장 hash·Worker 계약·journal 저장 버전은 변경하지 않는다.

## 회차 목록과 관계 근거 표시

회차 목록과 제목 수정 응답은 권한 확인 후 작품/현재 batch/회차별 최신 Job의 작은 요약 projection을 읽는다.
생성 시각이 같은 Job의 새 UUID 우선순위를 만들지 않으며, 기존 상태·자동 반영 대기·검토 보류 판정을 같은 mapper로 적용한다.
입력·기준 상태·journal은 SQL 선택 열에 포함하지 않는다.

관계도의 원문 접근 가능 여부도 과거 Job의 key/version만 scalar view로 읽는다.
관계 이력 자체와 최신 세대 선정·근거 표시 정책은 변경하지 않는다.

## 기존 개선과의 관계

| 범위 | PR #325 | PR #366 | #374 |
| --- | --- | --- | --- |
| 비교 후보 반복문의 상태 재생·복사 | PreparedState 재사용 | 유지 | 유지 |
| 비교 후보 행마다 Job JSON fetch | 기존 유지 | 6개 조회에서 제거 | 유지 |
| 새 캐릭터 이력의 중복 저장 | 기존 유지 | APPEND_HISTORY | 기존 v1/v2 보존 |
| 과거 Job의 상태·출처·목록 조회 | 전체 엔티티 | 기존 유지 | scalar projection |
| 미확정 참고 후보 조회 | 전체 조회 후 선별 | 기존 유지 | DB 상태 필터 |
| hash 중간 문자열·UTF-8 전체 복사 | 기존 유지 | 기존 유지 | 버퍼 출력 |

수동 순차 실행의 이력 재생, 현재 Job의 쓰기 잠금, 분석에 필요한 전체 고정 입력,
근거 자체와 최종 Worker 응답의 크기는 여전히 비용이 있다. 모든 Job 조회를 제거하거나 무제한 회차를 보장하는 변경은 아니다.
전후 측정·동등성 증거와 재현 한계는 [메모리 검증 기록](load-tests/gh374-analysis-history-memory.md)에 둔다.
