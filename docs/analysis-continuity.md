# 파일 교체·재분석·늦은 검토의 연속성

[Java #301](https://github.com/catchhole-soma/catchhole-backend-java/issues/301) · 2026-10-27

정상적인 근거 파기와 재생성 때문에 분석이나 사용자 확정이 중단되는 구현 결함을 제거한다.
사용자가 저장한 최종 선택을 존중하고, 추가 확인 단계나 새 검토 대기를 만들지 않는다.
Java·AI·Front의 `fix/gh-301-analysis-continuity` 작업을 이 이슈 하나에서 추적한다.

## 변경한 동작

| 상황 | 종전 결함 | 수정 후 |
| --- | --- | --- |
| 현재 시점을 제공한 회차 파일 교체 | 파기된 옛 인용이 비어 있어 새 PRESENT 시점 선택도 실패 | 검증된 새 관측으로 대표 시점을 갱신한다. 옛 인용을 되살리지 않고 기존 확정값을 보존한다. |
| 같은 원문으로 새 분석 | 청크 UUID 전체 교체로 확정값·이력의 시점 참조가 끊김 | AI가 본문·순서·offset·문단 경계가 같은 청크의 ID와 임베딩을 재사용한다. Java는 이미 끊긴 보존 관측도 원문 검증으로 읽는다. |
| 비교 종료 후 부모의 progress | 종료된 같은 입력의 비교가 재예약되고 결과 초기화 | 배치·입력 hash가 같은 성공/실패 비교를 반복 인계하지 않는다. 명시 재시도와 실제 입력 무효화는 허용한다. |
| 여러 회차의 신규 인물 후보에 중복 제외가 섞임 | dismiss가 operation을 지운 뒤 승격 목록에 다시 포함해 전체 롤백 | 상태를 바꾸기 전에 승격·제외 목록을 확정하여 승인한 그룹을 한 트랜잭션으로 처리한다. |
| 중복 제외가 새 관측 시점을 제공 | 신규 인물 귀속 또는 finalizer 호출 누락 | 승인한 제외 후보를 생성된 인물에 연결하고 기존 인물의 전부 제외 그룹에도 시점 확정을 실행한다. |
| 완료된 순차 세계관 후보의 늦은 확정 | 실제로 수행하지 않을 옛 범위 이동을 화면에서 약속 | 실제 확정 경로와 유지할 기존 위치를 기존 확정 버튼 앞에서 안내한다. 일반 직접 검토의 실제 이동 안내는 유지한다. |

## 원문과 시점의 수명

파일 교체는 원문 인용을 파기하지만 작가가 이미 확정한 설정값을 삭제하지 않는다.
새 PRESENT 후보의 referenceTime과 근거가 유효하고 기존 `expectedCurrent`와 연결되면
인용이 파기된 옛 대표 시점에서도 새 대표 시점으로 진행한다.
과거·가정·불명·실패·보류를 PRESENT로 추정하지 않는다.

청크 ID는 회차의 처리 자료 식별자다. AI는 기존 청크 전체와 새 분할 결과의
`episode_id`, `chunk_index`, `chunk_text`, `start_offset`, `end_offset`,
`paragraph_start_index`, `paragraph_end_index`가 같으면 기존 ID·임베딩을 보존한다.
metadata는 이번 실행 값으로 갱신한다. 하나라도 달라지면 기존 교체 절차를 사용한다.
같은 Job checkpoint 재개와 ordered 저장의 lease·원문 검사도 유지한다.

예전 재분석으로 이미 청크 ID가 사라진 자료는 Java가 읽을 때 복구한다. 원 Job이 성공했거나
후보가 이미 검토된 회차 자료에 한해, 원 Job에 고정한 원문 manifest·hash·인용·정확한 Unicode offset과
시점 구조를 먼저 검증한다. 그 청크 UUID가 어느 회차에도 존재하지 않는 경우에만 옛 관측을
보존한다. 남아 있는 다른 회차의 청크, 미완료 신규 추출, 설정집 청크에는 적용하지 않는다.
현재값·이력·대표 시점의 출처를 다른 후보로 바꾸거나 운영 DB를 일괄 수정하는 작업은 없다.

## 반복 요청과 사용자 승인

부모 progress와 사용자의 명시적 재시도는 의미가 다르다. progress는 종료된 동일 입력을
다시 시작하지 않고, 사용자의 retry·연결 변경은 기존 사용량·활성 작업 검사를 거쳐 다시 시작한다.
새 후보가 생겨 hash가 바뀌면 새 비교를 만든다. `H1={C1} → H2={C1,C2} → H1={C1}`처럼
재분석으로 후보가 삭제되어 예전 hash로 돌아와도 `RECOMPARISON_REQUIRED`이면 다시 비교한다.
취소된 작업만 있는 입력은 성공/실패 이력으로 취급하지 않는다.

신규 인물 그룹에서는 중복 제외 후보가 승격 목록에 다시 들어가지 않도록 승인 목록을 먼저 고정한다.
실제로 생성한 인물에 같은 승인 그룹의 유효한 시점 선택을 연결해 다른 원본 Job의 선택도
`expectedCurrent` 연결 순서로 완료한다. 제외 자체로 새 Fact를 만들지는 않는다.
기존 인물의 전부 EXCLUDE 그룹은 시점만 갱신할 수 있다. 미등록 인물의 전부 제외 그룹과
사용자의 직접 반려는 인물이나 시점을 생성하지 않는다.
비교 후 인물을 보관한 경우에는 제외 승인을 완료하면서 보관된 인물의 시점·버전을 유지한다.
추가 검토나 오류로 승인을 중단하지 않는다.

늦은 일반 확정은 기존 후행 현재값·수동 수정·삭제 보호를 유지한다. 명시적 현재 반영 선택은
기존 GH219 계약대로 실제 선택 대상에 반영한다. 완료된 다른 회차의 분석을 다시 실행하거나
과거 AI가 제안했던 세계관 부수 이동을 소급 실행하지 않는다. Front는 이를 기존 한 번의
그룹 확정 흐름에서 설명하며 추가 확인 클릭이나 새 비교 요청을 만들지 않는다.

## 검증 기록

2026-10-27 로컬 미커밋 작업 기준이며 운영 배포·실제 모델 호출은 수행하지 않았다.

| 범위 | 결과 |
| --- | --- |
| Java `./gradlew test bootJar` | 최종 3,675 passed / 236 skipped / 실패 0, 빌드 성공, 3분 59초 |
| AI `.venv/bin/python -m pytest -q` | 4,456 passed / 134 skipped / 기존 Starlette 경고 1, 20.39초 |
| Front 세계관 Playwright | 전체 34 passed, 표시 컴포넌트 정리 후 핵심 3개 재통과 |
| Front typecheck / build / lint | 통과, 기존 lint 경고 2개 |
| Front Knip / Doctor | 기준 `65fcf6f`와 같은 기존 진단. Knip 미사용 파일 2개, Doctor 오류 2·경고 228. 새 진단 0개. |

새 회귀는 `CharacterNarrativeEvidenceLifecycleTest`, `CharacterCurrentSupportingEvidenceCaptureTest`,
`CharacterComparisonHandoffIntegrationTest`, `CharacterGroupExclusionIntegrationTest`,
AI `test_episode_chunk_service.py`, Front `world-setting.spec.ts`에 있다.
파일 파기 후 새 시점, 이미 사라진 청크의 현재·이력·대표 시점, 입력이 되돌아오는 재분석,
여러 회차 신규 그룹과 전부 제외 그룹, 반복 확정, 사용자 직접 반려, 표시 경로와 한 번의 확정 요청을 확인한다.
추가 교차 검토에서 보관한 인물의 전부 제외 승인이 시점을 바꾸는 재현을 먼저 실패시킨 뒤,
제외 성공·시점/버전 보존·반복 승인·Fact 미생성을 검증했다. 첫 전체 Java 실행은 3,674 passed /
236 skipped였고, 이 보완 뒤 관련 37개와 최종 전체 테스트를 다시 통과했다.
실제 DB 조회와 확정 트랜잭션은 Java H2 통합 테스트로, Front 요청·재조회는 브라우저 API fixture로 검증했다.
이 결과는 실제 모델의 추출 정확도나 운영 전체 실패율을 측정한 결과가 아니다.

원본 작업 디렉터리와 별개 작업 트리에서 검증했으며 기준 코드는 Java
`76c32b451f323fb41533096cd71e4057a1eb065b`, AI `fa87321444af3724548f50c73c9694bd88d92817`,
Front `65fcf6f`다. 제품·회귀 코드는 아래 커밋으로 고정했다. 검증은 커밋 전에 수행했으며 당시 파일 hash와
커밋 내용을 대조해 동일함을 확인했다. main 미반영·운영 미배포 상태이며 머지 후 최종 main SHA와
배포 결과는 별도 기록한다.

| 저장소 | 제품 커밋 | 제품·회귀 복원 커밋 |
| --- | --- | --- |
| Java | `3ffacfe2e9e341733a3614ea11e13c326bddcba2` | `678c1791c4ed24c45792e2dd190480f3a04628c7` |
| AI | `7f74f9ed4c76b6a135d2d2c99bc516e6acce738b` | `16fbeb2c05fd008c28f98ec6b68fd9c061b55fdd` |
| Front | `16b2b21a3730d710a33de688aa7a3da35e973698` | `c59325f0f559b95c6b66253b5c07aec66349aad8` |

## GH323 일반 인용 표시 정책 — 2026-10-29

신규·기존 일반 설정 인용 모두 offset이 있는 span만 원문의 해당 Unicode 구간과 정확히 비교한다.
offset이 없으면 원문 검색을 하지 않고 숨긴다. 범위·부분 위치·인용 불일치는 해당 span만 제외하며
정상 span은 보존한다. 남은 span이 없으면 `UNAVAILABLE`과 빈 `evidenceSpans`를 전달하고
후보값과 분석을 유지한다. reason은 `OFFSETS_UNAVAILABLE`, `INVALID_OFFSETS`,
`EVIDENCE_UNAVAILABLE`로 구분하며 기존 legacy/파기 reason은 유지한다.

원본 후보와 원시 AI 응답은 수정하지 않는다. 공개 후보·Fact·세계관 근거와 새 비교 후보에는
정제된 span만 노출하고, 공개 raw payload의 중첩 인용도 숨긴다. 이미 고정된 입력과 hash는
재작성하지 않으며 AI의 provider 투영에서 위치 없는 과거 인용을 숨긴다. 목록은 scalar manifest와
요청 내 캐시를 사용하고 원문을 이미 읽은 상세는 재사용한다. 원문 hash/출처/권한/lease와
별도 동일인·서사 시점 증명의 엄격 검증은 이 표시 정책과 분리해 유지한다.

Worker 단건·묶음 비교 후보는 `GeneralEvidenceProjection.forProcessingCandidate`를 사용한다.
검증 가능한 위치가 없으면 저장소를 읽지 않으며, 위치가 있는 개별 인용의 오류만 제외한다.
반면 원문 저장소 실패는 `ANALYSIS_CURRENT_EVIDENCE_SOURCE_UNAVAILABLE`,
고정 원문 hash·출처 연결·불완전 manifest 오류는 `ANALYSIS_CURRENT_EVIDENCE_INVALID`로
전달해 빈 근거의 정상 비교로 진행하지 않는다. 공개 미리보기의 빈 결과 캐시와 처리용 검증 성공
캐시를 분리한다. 출처 기록 자체가 없는 legacy 후보는 기존처럼 근거 없음으로 유지한다.

검증은 일반 span 필터·CURRENT capture·공개 mapper·단건/배치 비교·동결 입력 claim·
출처 hash 실패·동일인/시점 증명 경계를 대상으로 실행했다. 관련 Java 테스트 472건 중
471 passed / 1 skipped / 실패·오류 0이며 `./gradlew bootJar`도 성공했다.
스킵은 별도 실제 Python→PostgreSQL·가짜 Discord 연결 선택 항목이다.
이후 격리 로컬 복사본에서 기존 10화 Job을 재개해 1~10화가 모두 SUCCEEDED/SEALED,
관계 분석이 모두 APPLIED인 것을 확인했다. 기존 203개 작업 상태와 원본 후보·근거는 변경되지 않았다.
10화까지 공개된 관계 원문 근거 159개에서 인용·회차·노드 정합성 오류는 없었으나,
이 수치는 관계 의미 정확도나 누락 없는 추출을 보장하는 평가가 아니다.

### 최신 main 통합 검증 — 2026-10-30

`origin/main`의 `afbb8b95e5cbb9c835c4313b56fa8ba9fcbb0686`을 관계도 작업 브랜치에
병합하고, 운영 미배포 관계도 migration을 V101로 옮겼다. 검수 실행·검토 종료 기능과
관계도 작업을 모두 보존했으며 관계 API의 요청·응답 필드는 바꾸지 않았다.
OpenAPI의 일반 이름 `Evidence` 충돌은 관계 근거 스키마에
`RelationshipGraphEvidence`라는 고유 이름을 부여해 해소했다.

전체 `./gradlew test bootJar --console=plain`은 5분 31초에 성공했다.
JUnit XML 기준 4,365건 중 **4,065 passed / 300 skipped / 실패·오류 0**이다.
처음 통합 실행에서 드러난 기존 공개 인용 fixture의 가짜 위치·누락된 출처 manifest를
정상 원문과 hash로 맞추고, 미확인 인용은 숨긴다는 승인된 정책에 맞게 공개 응답 기대값을
갱신한 뒤 전체 검증을 다시 수행했다. 원본 후보 보존 검증은 유지했다.

새 Testcontainers PostgreSQL에 Flyway 101개를 V101까지 적용하고 JPA validate를 통과했다.
관계도 통합은 H2 12건과 PostgreSQL 12건, 관계 규칙 6건이 모두 통과했다.
최신 main의 대규모 캐릭터 준비·claim PostgreSQL 성능 경계 5건과
검수 OpenAPI 계약 2건도 통과했다. 명시적으로 실행하는 브라우저 fixture 생성 테스트는
이번 전체 실행에서 생략했다. 앞선 실제 브라우저 검증 결과를 새 운영 배포 검증으로 간주하지 않는다.

기존 V92 관계도 migration을 적용한 개인 로컬 DB와 실행 중인 로컬 서버는 변경하거나
재시작하지 않았다. 이번 빌드 산출물은 서버가 사용하는 별도 실행 JAR와 분리되어 있다.
운영 적용 순서와 이전 로컬 스키마 주의 사항은 [관계도 계약](episode-relationships.md)을 따른다.

## GH304 기존 인용 확인 불가와 새 비교 — 2026-10-28 (아래는 당시 기록)

과거에 확정한 일반 quote가 원문과 다를 때 현재 설정값을 삭제하거나 확정 근거를 고치지 않는다.
확정 Fact·이력·발견의 해당 source만 `UNAVAILABLE/QUOTE_NOT_FOUND`와 빈 복사 근거로 전달하고
다른 정상 source와 검증된 시점을 유지한다. 근거 부족 시 정상 `REVIEW_REQUIRED`로 저장할 수 있다.
빈 인용·파기된 근거·없는 legacy manifest는 각각 기존 확인 불가 의미를 유지한다.

신규 인용은 exact·공백 검색 뒤 `U+0022/U+201C/U+201D` 차이만 허용해 유일한 원문 구간을 찾고
실제 slice와 Unicode code point offset을 함께 저장한다. 숫자·부정·단어·그 밖의 문장부호와
모호한 반복은 보정하지 않는다. 신규 근거·시점·원문 hash/manifest·작품/Job 연결·lease·문맥 token
검증은 그대로 엄격하다. hash 불일치는 typed 409, 원문 읽기 장애는 503으로 구분한다.

합성 회귀는 emoji offset·다른 정상 출처·파기·재시도·현재값 보존을 검증한다.
실제 PostgreSQL EPISODE HTTP claim부터 retained UNAVAILABLE context와 정상 검토 완료까지
실행해 실패 outbox가 없음을 확인하며, 별도 실제 Python HTTP 오류 보고와 localhost 가짜 Discord도
검증한다. 상세 계약·호환성·명령은 [실패 알림 문서](analysis-failure-notifications.md)를 따른다.
기존 운영 Job을 테스트로 다시 실행하거나 사용자 근거를 백필하지 않는다. 실제 모델 품질은 미측정이다.
