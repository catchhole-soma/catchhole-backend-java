# 분석 상태의 요청 내 재사용 (#294)

세계관 대상 연결 저장과 캐릭터 비교 준비가 같은 순차 실행·입력 상태를 후보마다 다시 조회하고, 전체 변경 기록을 반복 재생하여 Worker의 30초 응답 대기를 초과했다. 큰 JSON을 반복 읽는 SQL뿐 아니라 복사·해시·변경 기록 재생을 함께 줄인다.

## 처리 방식과 보존 조건

`AnalysisRunStateService.prepareState(job)`는 Job 잠금을 보유한 처리 안에서만 쓰는 지역 객체다. 입력·선행 회차·hash를 검증하고 변경 기록을 한 번 재생한다. 캐릭터 준비/결정 저장과 세계관 대상 연결/반려 처리에서 이를 재사용한다. 다음 요청은 새 객체로 다시 검증한다.

- `projectedState()`의 읽기 전용 view는 직접 수정하지 않으며, append 뒤에는 다시 얻는다.
- 새 event만 순서대로 복사본에 적용한다. 성공한 뒤 영속 journal과 준비 상태를 교체하므로 잘못된 경로가 뒤에 있어도 앞의 변경만 부분 반영하지 않는다.
- 이미 기록된 동일 event/내용은 다시 저장하지 않는다. 같은 event의 다른 내용은 기존과 같이 충돌이다.
- `AnalysisJob`의 영속화하지 않는 `stateJournalRevision`은 실제 journal 교체·근거 파기 때 증가한다. 기존 helper가 기록을 바꾸면 다음 읽기에서 갱신한다. `getStateJournal()`의 방어 복사는 유지하며 getter 반환값의 객체 동일성을 변경 감지로 사용하지 않는다.
- lease·원문 버전·입력 hash·journal 상태의 기존 의미를 유지한다. 롤백·요청·재시도 사이에서 준비 상태를 공유하지 않는다.
- fact, 현재값, timeline, 이력 배열과 각 근거의 저장 형식은 바꾸지 않는다. 사용자 확정·반려, 개별 실패 보류, 시간 충돌의 판단도 유지한다. 충돌 시 복구 기준은 누적 결과가 아니라 원래 고정 입력이다.

사용자 반려 matcher는 최신 references를 매번 읽는다. 해당 도메인의 반려 정책이 없으면 anchor 조회를 생략하고, 있으면 작품·anchor ID별 조회 결과를 해당 처리에서만 재사용한다. 발견 후보 및 설정 schema도 같은 처리에서 재사용하며 실제 변경이나 다음 요청의 새 상태를 전역 캐시로 숨기지 않는다.

## Worker 응답 대기

AI 저장소의 `SPRING_WORKER_READ_TIMEOUT_SECONDS`를 기본 120초로 추가했다. 분석 API의 read 대기에만 적용하며 connect/write/pool, heartbeat, token 원장의 기존 30초 제한은 유지한다. 설정은 Worker Compose에도 전달된다.

120초는 응답 헤더/다음 바이트의 읽기 대기 제한이다. 전체 요청이나 분석 완료의 절대 제한이 아니며 heartbeat의 연속 실패·lease 취소가 먼저 발생할 수 있다. 일반 분석 POST 자동 재전송은 추가하지 않는다. 저장 후 응답 유실을 새로운 자동 복구 기능으로 해결한 변경은 아니다.

## 검증

- 단위 회귀는 535회 상태 읽기가 최초 replay 1회만 사용하고, 실제 변경 2개만 추가 apply하는지 검증한다. SQL 횟수만 줄고 JSON 재생은 반복되는 회귀도 잡는다.
- 신규 identity의 후속 참조, 외부 helper 변경 감지, 중복 event, 잘못된 경로의 원자성, 원문 변경·lease 만료, 사용자 반려 보존, 요청 간 cache 분리를 검증한다.
- 25개 캐릭터 후보의 claim/context/complete가 각각 schema를 한 번 읽고 다음 요청에는 다시 읽는지 확인한다.
- 실제 PostgreSQL/Flyway·HTTP 합성 성능 조건과 전후 SQL/시간은 [성능 검증 기록](analysis-character-preparation-performance.md)에 보관한다.
- Java는 `./gradlew test bootJar`에서 3,865 passed / 258 skipped / 실패 0, 빌드 성공이다. 실제 PostgreSQL 성능 테스트 5개는 생략 없이 통과했다.
- AI는 전체 4,628 passed / 140 skipped 및 실제 loopback HTTP 3개를 포함한 집중 116 passed다. 기존 한도보다 늦은 응답 수신, heartbeat 병행 및 실패 시 취소를 확인했다.

합성 로컬 측정은 운영 p95나 장애 원고의 재분석 결과가 아니다. 운영 배포·재분석 및 실제 운영 실패율 측정은 수행하지 않았다.
