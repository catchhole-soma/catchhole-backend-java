# 비교 준비의 조회량과 이력 저장량 (#365)

## 바꾸는 문제와 보존하는 결과

세계관 비교 준비의 후보 조회가 같은 AnalysisJob의 큰 JSON을 후보마다 JOIN 결과에 반복했다. 캐릭터 설정 변경은 `historyFacts`에 항목을 추가할 때 기존 배열 전체를 journal의 새 값으로 저장했다. 두 비용이 겹치면 LLM 호출 이전에 Java 힙이 고갈될 수 있다.

이 변경은 후보 행에서 Job 전체 fetch를 제거하고, 새 캐릭터 이력은 추가된 항목만 journal에 기록한다. 모델·프롬프트·비교 판단·원문 인용·fact·사용자 확정/반려 정책은 바꾸지 않는다. 정보의 중복 표현을 줄이며 과거 근거를 자르거나 최신 이력만 남기지 않는다.

## #325와의 관계

#294 / PR #325는 같은 요청 안에서 입력과 journal을 반복 조회·복사·재생하던 작업을 `PreparedState`로 재사용했다. `OrderedWorldSettingWorker.claimBatch`도 그때 변경됐다. 이번 수정은 같은 함수 안에서 남은 후보 조회의 결과 크기를 줄인다. 요청 내 상태 재사용은 유지한다.

| 비용 | #325 | #365 |
| --- | --- | --- |
| 후보마다 동일 상태를 처음부터 재구성 | 요청 안에서 재사용 | 기존 개선 유지 |
| 후보 조회 한 번에 Job JSON이 행마다 반복 | 변경하지 않음 | 동일 Job의 비교 조회 6개에서 Job fetch 제거 |
| 변경마다 전체 캐릭터 이력 배열을 journal에 저장 | 저장 형식 유지 | 새 항목만 추가하는 저장 연산 도입 |
| 작품 전체 고정 입력·상태 복원 | 유지 | 유지. 장기적인 부분 조회 최적화는 별도 |

## 조회 경계

세계관의 주체 연결 후보·비교 그룹·처리 중 묶음 조회와 캐릭터의 비교 그룹·처리 중 묶음·완료 후보 조회에서 `analysisJob`(및 `analysisJob.batch`) EntityGraph 경로를 제거한다. 후보의 FK, 필터, 정렬, PESSIMISTIC_WRITE 선언은 그대로다.

Worker는 같은 트랜잭션에서 먼저 작업 소유권과 Job 잠금을 확인한다. Job을 다시 사용할 때는 영속성 컨텍스트의 같은 엔티티를 사용한다. 이 변경은 필요한 Job 데이터를 없애는 것이 아니라 후보 SQL의 각 결과 행에 그 본문이 붙지 않게 한다. Job이 아직 로딩되지 않은 다른 트랜잭션에서는 정상적인 지연 로딩이 가능하다. 여러 과거 Job을 조회하는 공개 검토 API는 일괄 변경하지 않는다.

## 추가 이력의 저장과 재생

예를 들어 처음 이력이 `[A, B]`이고 두 결정이 각각 C와 D를 추가한다고 하자.

```text
기존 journal: REPLACE [A, B, C] → REPLACE [A, B, C, D]
새 journal:   APPEND_HISTORY [C] → APPEND_HISTORY [D]
최종 상태:   [A, B, C, D] (순서·출처·값 모두 동일)
```

`AnalysisStateChange`의 기존 생성자는 `REPLACE`다. `APPEND_HISTORY`는 `characters/<ref>/historyFacts` 배열의 끝에 새 항목을 붙이는 전용 연산이며 실제 ADD/UPDATE/REMOVE/HISTORY_ONLY 같은 도메인 판단은 별도 `operation`에 유지한다. 전체 배열을 복원해야 하는 기존 충돌 복구는 `REPLACE`로 남긴다.

`AnalysisStateJournal`이 두 연산을 원래 순서대로 재생한다. 동일 event/내용은 한 번 적용하고, 동일 event의 다른 값·연산은 충돌로 거절한다. 새 변경의 검증은 복사된 상태에서 끝난 뒤 journal과 projection에 반영한다. 캐릭터의 보류 기여 복원처럼 journal을 직접 읽는 경로도 두 표현을 해석한다. 원문 파기 시 추가 항목 속 근거도 기존 재귀 파기를 거친다.

## 저장 호환성과 배포 제한

- 기존 저장 journal v1과 전체 배열 `REPLACE`는 계속 읽는다. 기존 완료 기록의 대량 변환이나 원문 재분석은 하지 않는다.
- 새 `APPEND_HISTORY`를 실제로 저장할 때 해당 envelope의 `formatVersion`을 2로 올린다. 이전 기록과 새 기록이 함께 있는 작업도 순서대로 재생한다.
- Worker의 `analysisContext.formatVersion`은 1로 유지한다. 이 값은 DB journal 저장 버전과 구분한다. Python 모델은 journal JSON을 매핑할 뿐 새 저장 연산을 해석하지 않는다.
- 개발용 Python 독립 재생 도구는 v2를 읽어야 하므로 [AI PR #125](https://github.com/catchhole-soma/catchhole-backend-ai/pull/125)에서 reader와 공통 fixture를 함께 보완했다. 운영 `app` 변경 없이 과거 v1과 새 v2를 같은 canonical/hash 규칙으로 확인한다.
- 구 Java는 v2 envelope를 거절하므로 추가 배열을 전체 배열로 잘못 덮어쓰지 않는다. **v2 기록 생성 후 구 Java로 코드만 롤백하면 해당 기록의 재생/재개는 지원되지 않는다.** 모든 Java 읽기 경로가 새 버전을 이해하는 상태로 배포해야 하며, 구버전 롤백이 필요하면 별도 호환 배포 또는 검증된 역변환 계획이 필요하다.
- DB 컬럼·제약 변경과 Worker API 변경은 없다. lease·원문 버전·입력 hash·source coverage·seal 검증은 유지한다.

## 측정과 한계

운영에서 확인한 고정 입력 1,496,423bytes와 journal 5,723,520bytes는 PostgreSQL `jsonb::text`의 UTF-8 크기다. 60개 후보에 그 값이 반복될 때 약 413MiB라는 계산은 JSON 결과량 추정이며, 실제 wire bytes나 Java 힙 사용량 측정값이 아니다. 7.2MB 전체가 LLM 프롬프트라는 의미도 아니다.

실제 PostgreSQL 합성 검증, 전후 SQL·응답 시간·힙·할당·GC, 실행 조건과 한계는 [성능 기록](load-tests/gh365-comparison-memory.md)에 따로 기록한다. 운영 원문과 식별자는 fixture나 공개 보고서에 포함하지 않는다. 기존 큰 journal을 새 코드로 읽는 조건과 새 이력을 증분 저장하는 조건을 구분해 두 변경의 효과를 혼동하지 않는다.

이 변경 이후에도 전체 고정 입력과 준비 상태는 메모리에 존재하며, JSON 방어 복사·해시·journal 재생 비용은 남는다. 메모리 무제한 확장이나 모든 운영 부하의 무실패를 보장하는 변경은 아니다. 운영 재시도·재시작·인프라 변경은 이 PR과 별도다.

후속 [#374 누적 회차 조회 개선](analysis-history-memory.md)은 최초 회차 준비의 과거 Job 메타데이터 조회,
미확정 참고 선별, 목록 조회와 hash 중간 복사를 다룬다. 이 문서의 #365 비교 묶음 claim 측정과 구분한다.
