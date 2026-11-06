# 회차별 인물 관계도 계약 v1 (GH323)

## 제품 의미와 범위

관계도는 선택한 회차까지 독자가 알게 된 관계를 보여준다. 사건이 실제로 발생한 과거 시점의 복원이 아니다. 5화에서 새로 드러난 관계는 1화 화면에 소급하지 않는다. 서로의 감정, 함께 행동하는 관계, 사회적 관계를 구분하며 방향이 다른 관계를 같은 선의 내용으로 합치지 않는다. 종료된 관계도 상태 `ENDED`로 남고 화면은 점선과 종료 표시를 사용한다.

가계도는 기존 규칙 기반 가족 도메인과 API를 그대로 사용한다. 기존 캐릭터/세계관 프롬프트·비교·자동 반영 contributor 목록은 변경하지 않는다. 관계 추출과 관계 비교만 독립 호출하며 `RELATIONSHIP_EXTRACTION`, `RELATIONSHIP_COMPARISON` 토큰 목적을 사용한다. 제공자 호출의 권한·quota·lease 검사는 기존과 동일하다.

불분명한 관측은 내부 `HOLD`로 보관하고 검토함을 만들지 않는다. 검증되지 않은 관측이 화면에서 확정 관계인 것처럼 나타나지 않는다. 관련 인물이 새 근거에 다시 등장하면 과거 HOLD를 비교에 제공하며, 새 관측과 함께 해결한 HOLD는 다시 제시하지 않는다. 다만 이름이 없는 1인칭/대명사를 실제 캐릭터와 안전하게 연결하는 별도 근거는 현재 없다. `name=null`인 관측을 주인공이라고 추정해 연결하거나 현재 프로필 이름으로 치환하지 않으며, 이 보류가 자동으로 모두 해결된다고 보장하지 않는다.

이름이 원문에 확인되지만 프로필이 없는 인물은 독립 노드가 될 수 있다. 모델에 DB UUID를 보내지 않는다. 노드 행과 event는 관계 분석 시점의 이름을 보존하고 이름 매칭 키로 쓴다. 캐릭터 등록 여부도 연결된 회차 이후에만 true다. 공개 관계도의 표시 이름은 선택 회차에 프로필이 등록된(`profileRegistered`) 노드면 서버가 현재 캐릭터 프로필 이름을, 미등록 노드면 관측 이름을 내려준다(GH382). 프론트는 여전히 프로필/사진 API를 따로 호출해 덧씌우지 않고 서버 응답만 쓴다. 이름은 그 회차까지 DB에 확정된 프로필 대표 이름·확정 인물 발견 후보의 별칭과 정확히 같을 때 먼저 해소하고, 없으면 캐릭터 추출과 같은 포함 규칙(정규화 2글자 이상, 한쪽이 다른 쪽을 포함, 겹치는 프로필이 한 명)으로 해소한다(GH385). 대명사·placeholder는 비교하지 않고, 둘 이상 프로필에 걸리거나 같은 이름의 노드가 다른 프로필에 연결돼 있으면 보류한다. 동일 이름·대명사의 어려운 해소는 보수적으로 보류하며 이 기능은 캐릭터 추출의 identity 정책을 대체하지 않는다.

기존 미등록 노드도 뒤 회차의 관계 반영 때 유일하게 확인된 프로필에 연결한다. 이번 작업의 확정 후보와 prepare 당시 고정된 인물 ID를 사용하며, 동명 노드가 여럿이거나 두 근거의 ID가 다르면 연결하지 않는다. 그 프로필에 이미 노드가 있으면 새로 연결하지 않고 그 노드로 합친다(`merged_into_node_id` redirect, GH382). 최초 등장 회차는 유지하고 프로필 연결 회차는 이번 회차로 기록하므로, 1화에서 미등록이었던 인물이 2화에 연결돼도 1화는 미등록으로 남는다. 관계 반영 후 수동으로 확정한 프로필은 다음 관계 분석의 고정 입력으로 연결하며, 조회는 등록 노드의 표시 이름만 현재 프로필에서 읽고 연결·관계 이력은 갱신하지 않는다.

수동 분석에서 뒤 회차의 prepare가 먼저 실행돼도, 앞 회차 원문에 같은 정확한 이름이 확인되고 작품 전체의 해당 노드가 하나라면 기존 ID를 재사용한다. 최초 관측 회차만 앞당기고 프로필 연결 회차는 유지한다. 현재 고정 인물 ID와 충돌하거나 동명 노드가 여러 개면 추측 병합하거나 새 중복 노드를 만들지 않고 보류한다. 이번 원문에 없는 미래 이름은 비교 문맥에 넣지 않으며, 기존 관계 ID·사용자 정정·삭제 억제는 유지하며, 일반 회차의 후발 반영만으로 후행 문맥을 무효화하지 않는다(GH367). 이미 서로 다른 UUID로 저장된 동명 노드를 자동 합치는 데이터 보정은 하지 않는다. 같은 프로필로 확정된 노드는 반영 때 `merged_into`로 합치고, 이미 저장된 데이터의 edge·event 물리 통합은 아래 1회 정리(GH391)로 한다.

### 확정 별칭과 같은 프로필 노드 병합 (GH382)

- **별칭 조회 범위**: 일반 회차 분석(ordered 포함)은 관측 이름이 있으면 작품 전체에서 원문 회차가 선택 회차 이하인 확정 `CHARACTER_DISCOVERY` 후보의 `entity_name → matched_character_id`를 scalar 조회 한 번으로 읽는다(GH385: 포함 규칙이 모든 이름과 비교하므로 관측 이름으로 거르지 않는다). 설정집 출처·보관 회차·다른 작품 프로필·보관(`ARCHIVED`) 프로필은 제외하고, 후보·분석 Job 엔티티와 후보별 파기 확인을 하지 않는다. ordered 입력 상태의 `aliases`는 별칭별 확정 회차가 없어 쓰지 않고 대표 이름→`actualCharacterId`만 쓴다. 현재 대시보드(`ACTIVE`) 프로필의 대표 이름은 (id, name) scalar 조회 한 번으로 읽어 충돌 확인에만 쓰고 `WorkCharacter` 엔티티는 올리지 않는다(GH385). 관계 전용 백필은 기존 의미(종류 무제한, 후보를 만든 Job 원문과 현재 회차의 hash·key·version 일치, 파기 요청 없음)를 같은 scalar 조회 한 번으로 유지하고 보관 프로필만 뺀다.
- **재사용과 보류**: 이름이 한 프로필로만 정해지고 그 프로필의 노드(병합되지 않은 노드)가 하나면 그 노드를 다시 쓴다. 같은 이름이 두 프로필에 걸리거나, 한 프로필에 노드가 이미 둘 이상(분리 데이터, 1회 정리 뒤에도 다른 사람일 수 있는 노드는 남는다)이면 보류한다. 같은 이름의 기존 노드가 다른 프로필에 연결돼 있으면 기존처럼 보류한다.
- **이름 포함 규칙 (GH385)**: 캐릭터 추출은 "비요른"처럼 짧은 이름을 포함 규칙으로 기존 인물에 바로 붙여 별칭 기록을 남기지 않으므로, 관계 노드도 같은 규칙으로 해소한다(`RelationshipNameRules`, AI `character_name_resolver`·`ordered_character_subjects`와 같은 정규화·제외 단어·포함 판정). 해소 근거는 확정 이름(위 조회의 별칭·대표 이름, ordered 대표 이름)이고, 현재 프로필 대표 이름은 회차 정보가 없어 혼자서는 해소하지 않는다.
  - 정확 일치가 먼저다. 정규화 결과(앞뒤 공백·따옴표·괄호 제거, 공백 한 칸, 소문자)가 같은 확정 이름이 있으면 여기서 끝난다. 그 프로필이 하나여도 같은 이름의 현재 프로필이 다른 프로필이면 보류한다. 관측되지 않은 확정 이름에도 같은 판정을 해 문맥 노드의 고정 인물 정보가 ordered 작업과 같다.
  - 정확 일치가 없는 관측 이름만 확정 이름·현재 프로필 이름과 포함으로 비교한다(둘 다 2글자 이상이고 한쪽이 다른 쪽을 포함). 겹치는 프로필이 둘 이상이면 보류(노드 없음)하고, 하나이며 그 프로필의 확정 이름이 겹쳤을 때만 해소한다. 현재 이름만 겹치면 지금처럼 미연결 새 노드다. 대명사·placeholder·조사 붙은 지칭어는 양쪽 모두 비교하지 않는다.
  - 해소 결과는 위의 재사용·동결 `knownCharacterId`·반영 때 병합을 그대로 탄다. 같은 이름의 미연결 노드가 이미 보이면 그 회차는 그 노드에 고정 인물 정보만 남기고, 반영 때 발견 확정 없이도 프로필 노드로 합친다. 반영(`linkConfirmedCharacters`)은 포함 규칙을 다시 쓰지 않고, 이번 관측에 없는 과거 HOLD 이름에도 적용하지 않는다.
- **반영 때 병합**: 미연결 노드가 이미 노드가 있는 프로필로 확정되면 그 노드 행에 `merged_into_node_id`(프로필 노드)와 `merged_at_episode_no`를 기록한다. 병합된 행은 지우지 않는다. 동결 binding·event·edge가 그 UUID를 가리키기 때문이다. 이름·`character_id`(null)·edge·event·canonical key·정정 설명·삭제 억제·version은 바꾸지 않는다. 이번 Job의 연결·병합은 결정을 적용하기 전에 반영하므로, 같은 반영에서 합쳐지는 노드의 ADD도 프로필 노드의 삭제 억제를 따른다.
- **조회 해소**: 문맥·공개 조회는 작품 노드를 한 번 읽은 메모리 색인으로 redirect를 해소한다. 병합된 노드의 이름은 프로필 노드로 이어지고, 그 노드의 옛 edge도 같은 N ref와 같은 공개 노드로 보인다. 해소 뒤 양 끝이 같아지는 옛 선(예: "비요른"↔"비요른 얀델")은 숨긴다(상세 404, `totalEdgeCount` 제외). GH359 실패 쌍과 쌍별 확인 회차도 해소한 노드 쌍으로 계산한다. 과거 HOLD 끝점은 이름으로 먼저 다시 찾는다. 이번 문맥이 모르는 이름(이미 보이던 프로필 노드에 별칭으로 묶여 행이 남지 않은 이름)이면, 원 분석이 동결한 노드 binding을 해소해 그 노드가 이번 문맥에 있을 때 그 ref를 쓴다.
- **의존성 hash**: 기존 동결 hash(`dependencyRevision`·`manualDependencyRevision` 등)의 입력과 계산은 raw id 그대로 둔다. 새 문맥이 별칭 노드의 edge를 프로필 노드 ref로 투영하면, 그 raw edge 쌍과 사용자 정정·삭제 hash를 `aliasDependencyPairs`·`aliasDependencyRevision`(`aliasDependencyVersion=1`)으로 따로 저장하고 조회·완료·반영 때 함께 검사한다. 필드가 없는 옛 문맥은 그대로 통과한다.
- **미래 이름 금지**: 프로필 노드의 `first_episode_no`는 병합으로 앞당기지 않는다. 노드가 문맥에 보이는 회차는 병합 그룹에서 가장 이른 회차이고, 그 회차의 문맥 이름은 그때까지 알려진 이름(자기 이름이 아직이면 별칭)을 쓴다. 프로필 노드가 아직 보이지 않는 앞 회차에서 별칭으로 다시 쓰면, 그 별칭을 미연결 redirect 행(이름=별칭, 첫 회차=그 회차)으로 남겨 뒤 문맥에서도 노드가 보이게 한다.
- **사용자 정정·삭제**: 정정·삭제 억제는 edge 행에 있으므로 병합 뒤에도 유지된다. 삭제된 관계는 문맥에 없어서 모델이 프로필 노드 쪽으로 같은 종류를 다시 ADD할 수 있다. 이때 병합 그룹 어느 노드 쌍의 같은 slot에 삭제 억제가 있으면 새 edge를 만들지 않는다.
- **표시 이름**: 선택 회차에 등록된 노드가 그 프로필의 유일한 등록 노드이면 현재 프로필 이름(`ACTIVE`)을 조회 때 (id, name) scalar 조회 한 번으로 읽어 쓰고, `WorkCharacter` 엔티티는 올리지 않는다. 미등록 노드와, 한 프로필에 등록 노드가 둘 이상인 경우(1회 정리 뒤에도 남는 분리 데이터)의 노드는 그 회차까지 최근 event의 관측 이름이다. 프로필 개명은 다음 조회에 바로 반영되고(revision 변경), 보관(`ARCHIVED`)되거나 삭제로 연결이 끊긴 프로필의 노드는 관측 이름으로 돌아간다. `node.name`·event 이름·Worker 문맥 이름은 바꾸지 않는다. 선택 회차 이전 원문에 아직 나오지 않은 전체 이름이 등록 노드의 표시 이름으로 보일 수 있다.
- **동결 binding**: 병합 전에 옛 노드 UUID로 준비한 PREPARED/COMPLETED 문맥은 edge 집합·정정·억제가 바뀌지 않으므로 409 없이 완료·반영한다. finalize는 binding id를 해소하지 않으며, 그 Job이 옛 노드에 만든 edge도 조회에서는 프로필 노드로 보인다. 옛 노드 edge를 UPDATE한 event의 이름 방향은 해소한 끝점으로 맞춘다.
- **1회 정리**: 이 절의 규칙 이전에 저장된 데이터는 아래 GH391 1회 정리로 정리한다. 정리 뒤에도 같은 프로필에 연결된 노드가 정당하게 남으므로(다른 사람일 수 있는 이름) `(work_id, character_id)` unique index는 넣지 않는다.

### 1회 정리 (GH391)

#382·#385 이전 규칙으로 저장된 관계도를 작품 단위로 한 번 정리한다. 별칭·짧은 이름 노드를 다음 반영과 같은 규칙으로 프로필 노드에 연결·병합하고, 같은 프로필에 분리 연결된 노드는 이름이 그 프로필로만 해소될 때만 합친 뒤, 선을 해소한 노드로 옮겨 같은 slot을 하나로 합친다.

- **실행 수단**: 내부 API 키 엔드포인트 `POST /api/internal/v1/relationship-graph/cleanups`, body `{"workId": "...", "apply": false}`. `apply`를 생략하거나 false면 dry-run이다. 호출 전까지 아무 일도 하지 않으므로 별도 플래그가 없다. 응답에는 노드·프로필 이름이 있으므로 공개 이슈·PR에 그대로 붙이지 않는다. 로그는 workId와 건수만 남긴다.
- **기준 회차(cutoff)**: 공개 세대(`relationship_backfill_works.published_generation`, 행이 없으면 0)의 마지막 APPLIED 회차. APPLIED가 없으면 아무것도 읽거나 쓰지 않는다. 연결·병합 회차(`character_linked_episode_no`·`merged_at_episode_no`)는 이 회차로 기록해 과거 화면에 프로필 이름을 소급하지 않는다.
- **확정 이름**: cutoff 이하 확정 `CHARACTER_DISCOVERY`(ACTIVE 프로필)를 scalar 조회 한 번으로 읽는다. cutoff 분석을 만든 Job이 `ORDERED_PROVISIONAL`이면 런타임 ordered prepare처럼 현재 ACTIVE 대표 이름도 확정 근거로 넣고, 아니면 대표 이름은 충돌 확인에만 쓴다. 작품의 가장 최근 회차 Job이 아니라 cutoff 분석의 Job을 보는 이유는 실패한 뒤 회차 재분석(다른 모드)이 저장된 그래프의 해소 방식을 바꾸지 않기 때문이다.
- **같은 프로필 연결 노드(먼저)**: ACTIVE 프로필에 병합되지 않은 연결 노드가 둘 이상이면 대표 이름과 정규화 결과가 같은 노드(없으면 첫 회차·연결 회차·생성 시각·id가 가장 이른 노드)를 대표로 남긴다. 나머지는 ① 이름이 작품에서 유일하고 ② `RelationshipNameRules.resolveStrictly`(정확 일치에서 멈추지 않고 정확∪포함 프로필을 모두 모으며 확정 이름이 뒷받침해야 함)가 정확히 그 프로필이고 ③ 이름이 대표 이름의 짧은/긴 형태일 때만 합친다(`MERGE_LINKED`: `character_id`를 비우고 `merged_into`를 기록, 대표 노드의 첫 회차·연결 회차는 유지). 아니면 사유를 남긴다(`HELD_COLLISION`: 다른 ACTIVE 프로필과 정확히 같거나 그 이름에 포함, `OTHER_PROFILE`, `UNRESOLVED`, `NOT_REPRESENTATIVE_FORM`: 확정 별칭이어도 대표 이름과 무관, `DUPLICATE_NAME`, `AFTER_CUTOFF`). 보관 프로필에 연결된 노드는 합치거나 다시 연결하지 않고 `ARCHIVED_PROFILE`(이름이 다른 ACTIVE 프로필 하나로 해소되면 `OTHER_PROFILE`)로 보고만 한다.
- **미연결 노드(다음)**: 첫 회차 순으로 `RelationshipNameRules.resolve`(런타임과 같은 정확 일치 우선)를 적용한다. 한 프로필로 해소되고 이름이 유일하면 그 프로필의 연결 노드가 없을 때 연결(`LINK`), 하나면 병합(`MERGE_UNLINKED`), 둘 이상이면 `SPLIT_PROFILE`로 건너뛴다. 여러 프로필이면 `HELD_COLLISION`이다. 프로필 노드가 없는 같은 프로필의 별칭 둘은 첫째가 연결되고 둘째가 그 노드로 합쳐진다.
- **선**: 모든 선의 끝점을 해소한 노드로 바꾸고, 방향은 저장 canonical key를 세 방향으로 다시 계산해 맞는 것으로 복원한다(event가 없는 선도 처리, event 방향과 다르거나 맞는 방향이 없으면 작품 전체를 409로 중단). 새 key가 같은 선은 하나만 남긴다. 생존 선은 이미 대표 끝점인 선, 없으면 첫 event 회차가 가장 이른 선(동률은 생성 시각·id)이다. 생존 선은 삭제 억제를 OR로, 정정 설명은 자기 것을 우선해 잇고(둘 다 다르면 `CORRECTION_CONFLICT` 경고), version은 둘보다 크게 올려 옛 화면의 PATCH/DELETE가 409가 된다. 합쳐지는 선의 event는 생존 선으로 옮기고, 같은 분석의 event가 이미 있으면 근거만 합친다(같은 인용은 한 번, 설명·상태·방향은 생존 선 event 유지). 끝점 순서가 생존 선과 반대인 event는 source/target 이름을 맞바꿔 미등록 노드의 관측 이름이 뒤바뀌지 않게 한다. 해소 뒤 자기 자신을 잇는 선은 event와 함께 지우고 보고한다.
- **범위 밖**: 같은 두 노드·axis·kind의 `DIRECTED` 선이 양방향(A→B, B→A)으로 따로 있는 것은 canonical slot이 달라 계약상 서로 다른 관계이므로 합치지 않는다. 정리 뒤 겹침은 canonical slot 기준 0이며, 순서 없는 쌍 기준으로는 이런 양방향 선이 남는다.
- **게이트(apply만 409, dry-run은 `blockers`로 보고)**: `ANALYSIS_IN_PROGRESS`(PENDING·PREPARED·COMPLETED 분석), `CONTEXT_FROZEN`(문맥이 남은 INVALIDATED 분석: GH367 재개 때 의존성 hash가 raw 끝점으로 선을 다시 거르므로), `BACKFILL_ACTIVE`(관계 백필 캠페인이 이 작품을 완료하지 않음). 적용은 prepare·finalize·사용자 정정/삭제와 같은 Work 잠금(`findByIdForUpdate`) 아래에서 한다. 게이트를 통과한 작품에서는 정리 뒤 만들어지는 모든 문맥이 정리된 선·대표 노드로 binding된다.
- **보존하는 것**: 노드 행은 지우지 않는다(APPLIED 분석의 `bindings.nodes`·`relationships`가 옛 UUID를 가리키고, 메모리 색인이 redirect로 해소한다). APPLIED 분석의 문맥·bindings는 바꾸지 않는다. 노드 이름·첫 회차, event의 `analysis_id`(세대 필터)도 그대로다. 합쳐져 사라진 선 id의 상세·PATCH·DELETE는 404다.
- **적용 순서와 검증**: 노드·생존 선·근거 합치기는 JPA로 바꿔 flush하고, `updatable=false`인 선 끝점·key와 event의 선·이름은 JDBC 50건 batch로 바꾼다(같은 분석 event 삭제 → 이름 맞바꾸기·event 이동 → 선 삭제 → 생존 선 끝점·key 갱신, 문마다 바뀐 행 수를 계획과 대조). event에는 `edge_id` 색인이 없으므로 event 문은 모두 `work_id`로 범위를 좁힌다. 같은 트랜잭션에서 다시 읽어 병합 노드 끝점·자기 연결·key 재계산 불일치·slot 중복·병합 행 연결·행 수가 0/일치이고 다시 계획해도 할 일이 없어야 하며, 어긋나면 409로 전부 되돌린다. 두 번째 적용은 바꿀 것이 없다(`applied=false`). 분석·설정 후보·프로필·분석 Job 엔티티는 올리지 않는다(노드·선 엔티티, event 위치 투영, 근거를 합칠 event만 읽는다).
- **보고서**: `apply`·`applied`·`cutoffEpisodeNo`·`orderedAnchors`·`blockers`, 건수(`nodesLinked`·`nodesMergedUnlinked`·`nodesMergedLinked`·`nodesHeld`·`nodesSkipped`·`edgesMoved`·`edgesMerged`·`edgesDeleted`·`eventsMoved`·`eventsNamesSwapped`·`eventsEvidenceMerged`·`eventsDeleted`), 정리 전후 행 수(dry-run은 예상), 노드별 결정(이름·단계·사유·해소된 프로필), 합친 선 묶음(삭제 억제·정정 보존 여부), 자기 연결 선, 경고. 관계 설명과 원문 근거는 넣지 않는다.

#### 운영 실행 절차

1. PR 머지 → `Publish Backend Image` → `Deploy API EC2` 자동 배포와 `/actuator/health`를 확인한다.
2. 관계 백필 캠페인 상태를 확인한다(아래 B). 진행 중이면 그 작품은 `BACKFILL_ACTIVE`로 막히므로 캠페인 완료를 기다린다. 새 claim을 멈춰야 하면 `RELATIONSHIP_BACKFILL_ENABLED=false`.
3. 사전 점검 SQL로 대상 작품을 정한다(RDS 허용 IPv4에서 psql, 읽기 전용).
4. 대상 작품의 node·edge·event 백업 표를 만든다.
5. 작품마다 dry-run 보고서를 검토한다(특히 `LINK`, `HELD_COLLISION`·`SKIPPED` 사유, `CORRECTION_CONFLICT`, `blockers`).
6. 작품마다 apply → 사후 점검 SQL → 보고서 건수와 대조한다.
7. 며칠 뒤 백업 표를 지우고, 건수만 이 문서의 검증 기록에 남긴다.

호출은 API EC2의 SSM 세션 안에서 하고 키·응답을 디스크에 남기지 않는다. 배포 경로·사용자는 `deploy-api-ec2.yml`의 `API_EC2_DEPLOY_PATH`(기본 `/opt/catchhole`)·`API_EC2_DEPLOY_USER`이고, API 컨테이너는 호스트 `127.0.0.1:8080`에 열려 있다.

```bash
sudo runuser -u "$DEPLOY_USER" -- bash -lc '
KEY=$(sed -n "s/^INTERNAL_API_KEY=//p" /opt/catchhole/api.env)
curl -sS -X POST http://127.0.0.1:8080/api/internal/v1/relationship-graph/cleanups \
  -H "X-Internal-Api-Key: $KEY" -H "Content-Type: application/json" \
  -d "{\"workId\":\"<WORK_UUID>\",\"apply\":false}"'
```

사전 점검 SQL:

```sql
-- A. 진행 중·재개 가능 분석(대상 작품은 0이어야 apply 가능)
select work_id, status, count(*) from relationship_graph_analyses where status in ('PENDING','PREPARED','COMPLETED') group by 1,2;
select work_id, count(*) from relationship_graph_analyses where status='INVALIDATED' and context is not null and evidence_purged=false group by 1;
-- B. 관계 백필
select state, count(*) from relationship_backfill_works group by 1;
select count(*) from relationship_backfill_items where state in ('WAITING','RUNNING');
select count(*) from analysis_jobs where job_type='RELATIONSHIP_EXTRACTION' and status in ('PENDING','RUNNING');
-- C. 대상 후보(병합·미연결 노드, 같은 프로필 분리 연결)
select work_id, count(*) filter (where merged_into_node_id is not null) merged,
       count(*) filter (where character_id is null and merged_into_node_id is null) unlinked, count(*) total
from relationship_graph_nodes group by 1 order by merged desc, unlinked desc;
select work_id, character_id, count(*) from relationship_graph_nodes
 where character_id is not null and merged_into_node_id is null group by 1,2 having count(*)>1;
-- D. 저장 key 무결성(mismatched 0, 복수 방향 0)
select e.work_id,
       count(*) filter (where e.canonical_key <> encode(sha256(convert_to(
         (case when ev.direction='DIRECTED' or e.source_node_id::text < e.target_node_id::text
               then e.source_node_id::text||'|'||e.target_node_id::text
               else e.target_node_id::text||'|'||e.source_node_id::text end)
         ||'|'||e.axis||'|'||e.kind||'|'||ev.direction,'UTF8')),'hex')) mismatched,
       count(*) total
from relationship_graph_edges e
join lateral (select direction from relationship_graph_events x where x.work_id=e.work_id and x.edge_id=e.id limit 1) ev on true
group by 1;
select edge_id from relationship_graph_events group by 1 having count(distinct direction)>1;
```

백업(정리는 새 행을 만들지 않고 id를 보존하므로 백업 복원이 완전한 롤백이다):

```sql
create table relationship_graph_nodes_gh391_bak  as select * from relationship_graph_nodes  where work_id in (:works);
create table relationship_graph_edges_gh391_bak  as select * from relationship_graph_edges  where work_id in (:works);
create table relationship_graph_events_gh391_bak as select * from relationship_graph_events where work_id in (:works);
```

사후 점검 SQL(작품별 `:w`):

```sql
select count(*) from relationship_graph_edges e join relationship_graph_nodes n on n.id in (e.source_node_id, e.target_node_id)
 where e.work_id=:w and n.merged_into_node_id is not null;                                              -- 0
select count(*) from relationship_graph_edges where work_id=:w and source_node_id=target_node_id;       -- 0
-- D의 식을 :w로 제한해 mismatched = 0
with recursive canon as (select id, id root, 0 d from relationship_graph_nodes where work_id=:w and merged_into_node_id is null
  union all select n.id, c.root, c.d+1 from relationship_graph_nodes n join canon c on n.merged_into_node_id=c.id where c.d<8)
select count(*) from (
  select case when ev.direction='DIRECTED' then cs.root::text||'|'||ct.root::text
              else least(cs.root::text,ct.root::text)||'|'||greatest(cs.root::text,ct.root::text) end k, e.axis, e.kind, ev.direction
  from relationship_graph_edges e join canon cs on cs.id=e.source_node_id join canon ct on ct.id=e.target_node_id
  join lateral (select direction from relationship_graph_events x where x.work_id=e.work_id and x.edge_id=e.id limit 1) ev on true
  where e.work_id=:w group by 1,2,3,4 having count(*)>1) g;                                              -- 0
select (select count(*) from relationship_graph_edges where work_id=:w) edges_now,
       (select count(*) from relationship_graph_edges_gh391_bak where work_id=:w) edges_before,           -- 차이 = edgesMerged+edgesDeleted
       (select count(*) from relationship_graph_events where work_id=:w) events_now,
       (select count(*) from relationship_graph_events_gh391_bak where work_id=:w) events_before,         -- 차이 = eventsEvidenceMerged+eventsDeleted
       (select sum(jsonb_array_length(coalesce(evidence,'[]'))) from relationship_graph_events where work_id=:w) quotes_now,
       (select sum(jsonb_array_length(coalesce(evidence,'[]'))) from relationship_graph_events_gh391_bak where work_id=:w) quotes_before; -- 같은 인용 중복분만 감소
select count(*) filter (where suppressed) sup, count(*) filter (where corrected_summary is not null) cor
from relationship_graph_edges where work_id=:w;            -- 백업 대비 감소분 = 두 선 모두 억제/정정이던 묶음 수
select count(*) from relationship_graph_nodes where work_id=:w and merged_into_node_id is not null and character_id is not null; -- 0
```

롤백(작품별, 정리 뒤 새 분석이 APPLIED되기 전에만. 그 사이 APPLIED가 있으면 수동 판단):

```sql
begin;
select id from works where id=:w for update;
select count(*) from relationship_graph_analyses where work_id=:w and status='APPLIED' and updated_at > :cleanup_at; -- 0이어야 계속
delete from relationship_graph_events where work_id=:w;
delete from relationship_graph_edges  where work_id=:w;
delete from relationship_graph_nodes  where work_id=:w;
insert into relationship_graph_nodes  select * from relationship_graph_nodes_gh391_bak  where work_id=:w;
insert into relationship_graph_edges  select * from relationship_graph_edges_gh391_bak  where work_id=:w;
insert into relationship_graph_events select * from relationship_graph_events_gh391_bak where work_id=:w;
commit;
```

#### 검증 기록

- 2026-11-06 로컬 운영 사본(100화 시험 작품, ordered, 노드 85·선 220·event 391, 정정·삭제 0): dry-run은 행을 바꾸지 않았고 apply 보고서와 같았다. 노드 `LINK` 1·`MERGE_UNLINKED` 9·`MERGE_LINKED` 2, 미연결 보류 3·건너뜀 3, 같은 프로필 연결 노드 보류 4·건너뜀 6(보관 프로필 연결 노드 보고 5 포함). 선 이동 140·합침 22(묶음 18)·자기 연결 0, event 이동 43(이름 맞바꿈 1)·근거 합침 3. 노드 85(대표 85→74)·선 220→198·event 391→388, 인용 수는 같은 인용 중복 2만 감소. 사후 점검 SQL 전부 0, 100화 화면 노드 69→58, canonical slot 중복 0, 순서 없는 쌍 겹침 22→24(전부 양방향 `DIRECTED`). 두 번째 apply는 변경 0, 백업 롤백은 원래 행과 일치했다.

## 활성화와 작업 수명

`RELATIONSHIP_GRAPH_ENABLED`(기본 true)는 신규 claim의 기능 활성화를 제어한다. Worker는 claim에 `supportsRelationshipAnalysis: true`를 광고해야 한다. 서버는 활성화한 EPISODE/SETTING_EXTRACTION Job에만 `relationshipAnalysisContract: {formatVersion: 1}`을 반환한다. 메타데이터가 없으면 Worker는 관계 API를 호출하지 않는다. 설정집/비교 전용 작업에는 기능을 붙이지 않는다. 한 번 활성화된 Job 재시도는 지원 Worker만 claim할 수 있다.

관계 상태는 기존 checkpoint와 별개로 `PENDING → PREPARED → COMPLETED → APPLIED`를 사용한다. 추출/비교 의미 처리 실패는 `FAILED`, 입력의 재분석·파기로 무효가 된 결과는 `INVALIDATED`다. PREPARED에는 관측, 비교 문맥, 실제 ID 매핑이 저장되며 재시작은 재추출하지 않는다. COMPLETED에는 검증된 결정이 저장되고 기존 핵심 설정 반영 후 같은 트랜잭션에서 APPLIED가 된다. PENDING/PREPARED인 채 원 Job을 완료할 수 없다. 관계가 FAILED이면 핵심 설정 완료를 허용한다.

인증, lease, quota, 원문 충돌, 저장 장애를 의미 처리 실패로 바꿔 우회하지 않는다. 원문 S3 읽기는 DB 트랜잭션 밖에서 실행하고 읽기 전후 lease와 고정 manifest를 확인한다. 내부 변경은 Work→Job 순서로 잠근다.

ORDERED_PROVISIONAL은 기존 회차 실행 순서를 따른다. 일반 SETTING_EXTRACTION의 관계 판단은 각 회차에서 확정한 독립 이력이다(GH367). 앞 회차가 늦게 완료되거나 일반 회차를 다시 분석해도 후행 관계를 일괄 무효화하거나 자동 유료 재분석하지 않는다. 이미 준비한 관측·관계 문맥은 앞 회차의 자동 반영만으로 교체하지 않으며, 완료 시 같은 회차의 event를 추가한다. 조회는 저장 시각보다 회차 번호를 우선하므로 늦게 저장한 14화 결과가 22화 결과를 덮어쓰지 않는다. 같은 슬롯의 후행 ADD/UPDATE/MERGE/END 판단은 그대로 유지한다. MERGE도 해당 회차의 PRESENT 판단으로 저장되므로 앞 회차 의미 변경을 후행 문장에 소급 전파하지 않는다. 사용자 정정·삭제, 원문·세대·lease 보호는 별도이며 변경이 확인되면 기존 충돌 경계를 유지한다.

일반 재분석은 같은 회차의 이전 관계 상태만 무효화하며, 다른 회차의 유효한 APPLIED 이력은 유지한다. 공개 조회도 일반 회차의 INVALIDATED 표시만으로 뒤의 모든 이력을 제외하지 않는다. 실제 원문 출처가 바뀌었거나 파기되어 사용할 수 없는 구간의 후행 차단은 유지하며, 비공개 백필 세대는 기존 무효화 경계를 따른다. 원문 hash/key/version/회차 번호·보관·파기는 계속 검사한다. 원문 파기 시 복사된 인용 제거와 후행 evidence_purged 처리는 그대로 유지하므로 삭제된 자료를 복원하지 않는다. GH332 관계 전용 백필의 세대 구축·순서 보정·공개 조건은 별도 계약을 유지한다([실행 계약](relationship-cohort-backfill.md)). 일반 분석의 후행 유료 호출이나 과거 백필을 새로 시작하지 않는다.

## 기존 실패 Job의 재개 (GH367)

앞 회차 반영으로 INVALIDATED됐던 일반 Job은 같은 Job 재시도에서 현재 원문·세대·최신 회차 작업·파기 여부를 검증하고 저장된 추출 관측으로 관계 비교 문맥을 복구한다. 원문 삭제/교체, 다른 세대, 새 Job으로 대체된 작업을 되살리지 않는다. 이미 정상인 새 계약의 PREPARED/COMPLETED 상태는 그대로 재사용한다. 구 계약을 재준비하면 해당 회차의 관계 비교 호출은 다시 필요할 수 있지만, 원문 관계 추출이나 후행 회차 재분석은 추가하지 않는다. 이 변경은 운영 Job을 자동 재시도하거나 사용자 토큰을 충전하지 않는다.

기존에 완료된 뒤 구 정책으로 INVALIDATED된 과거 행을 일괄 APPLIED로 바꾸는 데이터 보정은 포함하지 않는다. 완료 당시 단계와 파기/대체 이유를 확인하지 않고 상태만 바꾸면 과거 결과를 잘못 되살릴 수 있기 때문이다.

## 내부 Worker API

공통 인증: `X-Internal-Api-Key`, `X-Worker-Lease-Token`. 응답은 기존 `CommonResponse.data` 봉투다.

| 메서드 | 경로 | 의미 |
|---|---|---|
| GET | `/api/internal/v1/analysis-jobs/{jobId}/relationship-analysis` | PENDING/미저장은 204. PREPARED/COMPLETED/FAILED는 저장한 Context. sourceHash 검증 후 재개 |
| POST | 위 경로 + `/prepare` | 원문 인용 검증 후 관계 비교 입력 동결; 동일 요청 재전송은 동일 Context |
| POST | 위 경로 + `/complete` | contextToken과 결정 검증 후 저장. 동일 완료 요청은 멱등; 다른 결과로 덮어쓰기 금지 |
| POST | 위 경로 + `/fail` | 관계 의미 처리 실패 기록. prepare 전 contextToken은 null |

Java DTO 단일 출처는 `RelationshipGraphContract`이며 OpenAPI는 실제 MVC `/v3/api-docs`에서 생성한다.

```json
{"formatVersion":1,"sourceHash":"<sha256>","observations":[{"ref":"O1","source":{"name":"아라","mention":"아라"},"target":{"name":"보라","mention":"보라"},"summary":"함께 여행한다","evidence":[{"startOffset":0,"endOffset":18,"quote":"아라와 보라는 함께 길을 떠났다."}]}],"rejectedObservationCount":0}
```

위 인용 offset은 예시 문자열의 실제 Unicode code point 길이에 맞춰 계산해야 한다. 실제 입력은 SHA-256과 quote/offset이 원문과 정확히 일치해야 한다. NFC 정규화, 공백 제거, 줄바꿈 변경을 하지 않는다. start 포함/end 제외이며 회차 전체 기준이다. Java UTF-16 char index가 아니다.

Context는 `formatVersion, sourceHash, contextToken, status, sourceEpisodeNo, observations, nodes, relationships, heldObservations`를 가진다. `N*`는 인물 참조, `R*`는 기존 관계, `O*`는 이번 관측, `H*`는 관련 과거 보류다. 관측에는 `sourceNodeRefs,targetNodeRefs,sourceEpisodeNo`가 추가된다. 관계에는 `knownFromEpisodeNo,lastChangedEpisodeNo,status`가 있어 미래 관계가 섞이지 않는다. 모델에 보내는 nodes는 실제 O/R/H가 참조하는 범위만 포함한다.

완료는 `{formatVersion:1,contextToken,decisions:[...]}`. 결정 필드는 `observationRefs,operation,relationshipRef,sourceNodeRef,targetNodeRef,axis,kind,label,summary,direction,temporalScope,reason`이다.

| 작업 | 허용 내용 |
|---|---|
| ADD | 기존 slot이 없는 관계 추가. relationshipRef=null |
| MERGE | ACTIVE 관계의 새 근거 추가. label/summary/방향/주체/종류는 기존과 동일 |
| UPDATE | 설명/label 갱신 또는 ENDED 관계 재활성화. 기존 slot 참조 |
| END | ACTIVE 관계 종료. 과거 근거/상태 보존 |
| EXCLUDE | 관계로 사용하지 않는 관측. reason 필수, 관계/노드/type/value 필드는 null |
| HOLD | 내부 보류. EXCLUDE와 같은 null 규칙; 공개 관계 생성 없음 |

slot은 두 노드 + axis + kind + direction이다. DIRECTED는 순서를 보존하고 MUTUAL/UNDIRECTED는 대칭이다. 한 관측으로 친구 END와 적대 ADD처럼 서로 다른 slot을 바꿀 수 있다. 같은 slot을 두 번 쓰거나 같은 관측을 HOLD/EXCLUDE와 쓰기 결정 양쪽에 넣을 수 없다. 신규 O는 모두 한 번 이상 처리해야 하며 H만으로 과거 관측을 재해석하지 않는다. H를 쓸 때도 신규 O가 필요하다. 각 관측의 인물 후보가 양쪽 모두 하나일 때만 쓰기를 허용한다. 기존 R을 사용할 때는 저장된 source/target 순서를 그대로 사용한다.

`axis`: SOCIAL, ATTITUDE, INTERACTION.
`kind`: FRIENDSHIP, COMPANIONSHIP, COOPERATION, MENTORSHIP, ROMANCE, HOSTILITY, TRUST, DISTRUST, AFFECTION, ACQUAINTANCE, OTHER.
`direction`: DIRECTED, MUTUAL, UNDIRECTED.
`temporalScope`: PRESENT, PAST, HYPOTHETICAL, UNKNOWN. PRESENT는 현재 회차에서 새로 드러난 지식이다. PRESENT만 ADD/MERGE/UPDATE/END할 수 있다.

실패 요청은 `{formatVersion:1,sourceHash,contextToken,failureCode,failureDiagnostics?}`다. optional 진단은 공통 v1의 허용 코드·검사·승인된 짧은 구절만 정제해 첫 실패에 보존하며, 구 Worker의 4필드 요청도 허용한다. failureCode는 INVALID_RESPONSE, EXTRACTION_FAILED, COMPARISON_FAILED, INPUT_TOO_LARGE다.

한 prepare 최대 신규 관측 384개, 관측당 인용 1~12개, 한 인용 480 code point 이하, 관측당 인용 총 4000 code point 이하, 이름/mention 100 이하, 관측 summary 300 이하. 관계 설명 summary는 500 이하. 완료 결정 최대 512개, label 60 이하, reason 500 이하. legacy는 한쪽 인물이라도 겹치는 과거 R/H를 포함한다. 새 partial Context는 양쪽 인물이 각각 하나로 해소된 신규 O의 정확한 무방향 쌍에 필요한 R/H만 먼저 투영한다. 미해소·다른 쌍의 H는 저장에 남기고 이 입력에서는 제외한다. 투영 후 관련 H가 384개 또는 비교 문맥의 노드/관계가 각각 1536개를 초과하면 자르지 않고 FAILED/INPUT_TOO_LARGE Context를 저장해 반환한다. `rejectedObservationCount`는 검증 탈락을 성공한 빈 결과로 숨기지 않기 위한 비음수 개수다.

### 기존 단일 완료 계약의 비교 입력 상한과 실패 진단 (GH332)

전체 고정 비교 문맥이 64,000 추정 입력 토큰 이내이면 기존 Terra/medium 단일 호출과 prompt/schema를 그대로 사용한다. 상한을 넘을 때만 O/H의 `sourceNodeRefs × targetNodeRefs`가 만드는 가능한 무방향 인물 쌍을 기준으로 나눈다. 가능한 쌍이 겹치는 관측은 같은 묶음이며, 과거 H가 여러 묶음을 잇거나 H끼리 이어지는 경우도 모두 합친다. 가능한 쌍 전체를 메모리에 생성하지 않고 endpoint 집합의 교집합으로 겹침을 검사한다. 방향·axis·kind가 다른 같은 인물 쌍을 분리하지 않는다. 인물 후보가 없는 관측을 이름 추측으로 해소하지 않는다.

각 묶음에는 해당 쌍의 기존 R 전체, 해당 O/H, 이들이 참조하는 N을 원래 ref·순서·인용·내용 그대로 제공한다. O가 없는 H 묶음은 재해석하지 않으며 저장된 보류도 그대로 남는다. 독립 묶음은 입력 상한 안에서 합쳐 호출 횟수를 줄인다. 모든 묶음의 입력 크기와 전체 최대 16호출을 첫 모델 호출 전에 검사하고, 쪼갤 수 없는 단일 묶음이 초과하면 원문을 자르지 않고 INPUT_TOO_LARGE로 실패한다. 호출은 순차 실행하며 기존 heartbeat·계량·서버 예산 검사를 유지한다. 출력 오류를 재질문하는 repair는 추가하지 않는다.

각 O는 정확히 한 묶음에 배정하지만 기존 계약처럼 그 O를 서로 다른 관계 결정에 여러 번 사용할 수 있다. 묶음별 결과를 모두 받은 뒤 원래 전체 문맥으로 결정 최대 512개·전체 O coverage·참조·시점·중복 슬롯을 다시 검증한다. 검증 전에는 부분 결과를 Spring에 저장하지 않는다. 다른 인물 쌍의 문맥이 줄어들 수 있으므로 이 분할은 상한 초과 경로에만 적용하며, 실제 모델 의미 품질 개선을 자동으로 보장하지 않는다.

진단은 공통 `relationship-failure-diagnostics-v1.json`의 고정 코드만 사용한다. stage는 `RELATIONSHIP_EXTRACTION`/`RELATIONSHIP_COMPARE`이며, JSON/schema·관측 coverage·순서·슬롯·MERGE 내용 변경·인물 해소·입력 상한 등을 구분한다. 전체 원문·전체 모델 응답·예외 원문은 기록하지 않는다. 사용자 승인된 짧은 문제 구절은 GH357의 최대 3개·각 120 code point 제한을 따른다. 관계 pipeline은 원본 실패의 분류와 안전한 진단을 관계 fail API 및 전용 Worker에 전달하고, 상위 runner에는 고정 메시지만 노출한다. 일반 캐릭터/세계관 분석에서 관계 실패를 격리하는 의미는 유지한다.

### 인물 쌍별 부분 완료 (GH359)

`formatVersion=1`을 유지하며 claim의 `supportsRelationshipPartialResults=true`와 서버가
새 Job에 동결한 `relationshipAnalysisContract.supportsPartialResults=true`, Context의
`supportsPartialResults=true`가 모두 있을 때만 다음 추가 계약을 사용한다. 기존 고정 v1
작업은 새 Worker가 재선점해도 승격하지 않는다. 새 복구 Job은 보존된 추출 관측으로 새
Context를 준비하면서 활성화할 수 있다. 구 Worker는 능력이 동결된 Job 및 현재 공개/생성
세대에 유효한 부분 실패가 있는 작품의 후속 관계 작업을 claim하지 않는다.

모델 입력은 인물 쌍별 그룹으로 구성하고 같은 두 인물의 방향·axis·kind는 한 그룹에서 판단한다.
이미 연결된 두 인물의 참조는 모델에게 다시 작성시키지 않는다. 이름이 미확정인 새 O는 내부
HOLD로 보존하고, 미확정 또는 다른 쌍의 H는 모델 입력에서 제외하되 원래 보류를 삭제·소비하지
않는다. 명확하지 않은 지속 관계를 추측하기보다 직접 입증된 상호작용만 표현한다. 확정 이름과의
정확 일치·포함 규칙(GH385) 외의 동일성 추정 병합이나 사용자에게 보류 목록을 검토시키는 기능은
추가하지 않는다.

구조/검증 실패는 해당 쌍에 한 번만 제한적으로 다시 묻는다. 참조나 JSON을 읽을 수 없어
영향 범위를 특정할 수 없으면 해당 호출의 그룹 전체가 대상이다. 초기 최대 16 batch와 실패
그룹의 repair 최대 16 batch, 총 최대 32 provider 호출이며 repair 입력 여유도 미리 검사한다.
비교 batch는 출력에도 여유를 둔다. 쌍당512토큰을 계획값으로 잡고 출력의20% 이상을
추론·변동 여유로 남기며 batch당최대24쌍이다. 기본 출력16,000에서는384쌍도16batch에
들어간다. 512는 보장 상한이나 응답 절단 기준이 아니며, 한 쌍의 전체 O/H를 쪼개거나
출력 추정만으로 해당 쌍을 실패시키지 않는다. 실제 출력 절단은 기존 실행 실패로 남는다.
한 쌍의 입력 또는 전체 호출 상한 초과는 `RELATIONSHIP_INPUT_TOO_LARGE`로 보존한다.
최종 512개 결정 상한을 넘기는 쌍은 `RELATIONSHIP_RESPONSE_SCHEMA_INVALID`로 보존한다.
provider HTTP/timeout/output truncation, quota, lease, 권한, 고정 문맥·원문·저장 오류는 이
복구나 부분 완료로 숨기지 않는다. 추가 호출도 기존 예약/정산과 사용자·서비스 부담 정책을 따른다.

완료 요청은 기존 `decisions`와 `failures:[{observationRefs:["O1","O2"],reasonCode:"RELATIONSHIP_ENDPOINTS_UNGROUNDED"}]`를
함께 보낸다. 실패는 양쪽 인물이 유일한 같은 무방향 인물 쌍의 새 O 전체여야 하며, 성공과
실패의 출처는 겹치지 않고 전체 새 O를 덮어야 한다. 정상 결정의 주체·근거·시점·중복 slot·
MERGE 불변 검증은 유지한다. reason은 기존 RELATIONSHIP 진단 중 CONTEXT_INVALID를 제외한
코드와 `RELATIONSHIP_PREDECESSOR_INCOMPLETE`만 허용한다. 임의 오류 문자열은 받지 않는다.

Spring은 정상 결정과 failures를 원자로 저장한다. 부분 계약의 terminal Context는 빈 배열을
포함해 `failures`를 항상 반환하므로 완료 직전 재시작이 실패를 지우거나 provider를 재호출하지
않는다. graph는 APPLIED에서도 failures와 `failure_code=PARTIAL_COMPARISON_FAILED`를
보존한다. 실행 Job의 SUCCEEDED는 결과의 완전 성공이 아니며 전용 회차 항목은 PARTIAL이다.

앞 회차 실패 쌍은 Context의 local N 번호가 아닌 저장된 node UUID로 연결한다. 현재 새 O에
필요한 쌍만 `blockedPairs:[{sourceNodeRef,targetNodeRef,firstFailedEpisodeNo}]`로 동결한다.
이 쌍의 후속 O는 provider에 보내지 않고 PREDECESSOR_INCOMPLETE로 남겨 누락 상태 위에서
UPDATE/END하지 못하게 한다. 독립 쌍은 진행한다. 일반 앞 회차 복구가 기존 후행 판단을 자동으로 다시 실행하지는
않는다. 이후 새로 준비하는 문맥은 복구된 상태를 읽으며, 이미 저장된 후행 실패는 그대로 남는다.
GH332 전용 백필의 순서 복구와 기존 자동 복구 예산은 유지한다. 원문 추출 전체 실패는 FAILED이고
전용 백필은 BLOCKED다. 거절된 추출 관측처럼 실패 쌍을 모르면 기술 확인 범위를 보수적으로
표시하되 새로운 전역 실행 차단으로 확대하지 않는다.

APPLIED의 새 기술 실패는 V111 trigger가 기존 outbox에 RELATIONSHIP_COMPARISON으로
기록한다. V107의 서버 생성 실행 시도 ID와 발송 묶음을 재사용하고, V109의 검사·발췌 정제는
유지한다. 이 새 사건은 허용 reason별 인물 쌍 개수와 안전한 ID만 저장하며 후보/실패 작업 수와
구분해서 표시한다. 선행 실패의 파급은 반복 알림하지 않는다. 동일 완료 재전송은 같은 completion hash를 요구하며 event·알림을 중복 생성하지 않는다.

공개 Graph의 `updateIncomplete`와 `incompleteEpisodeNos`는 선택 회차/공개 세대의 기술적
누락만 표시한다. 기존 `partial`에는 의미상 HOLD도 포함되므로 기술 실패로 해석하지 않는다.
Edge의 `lastVerifiedEpisodeNo`는 해당 쌍의 기술적 완료가 이어진 마지막 회차다. 변경이 없는
정상 회차에서도 진행하고 첫 쌍 실패 또는 위치 미상의 전체 누락 앞에서 멈춘다. 관계 변경 회차와
다르며 서사 의미의 절대 정확성 보장은 아니다. 0은 확인 가능한 완료 구간이 없다는 뜻이다.
미완료인 이전 edge를 현재까지 다시 확인한 사실처럼 표시하지 않고, 전체 실패 때 빈 그래프를
완료로 표시하지 않는다. 내부 보류·실패 refs·응답 전문은 공개하지 않는다.

배포 순서는 Java(V111·능력 gate) → AI(pair-group 계약) → Front(미완료 표시)다. 운영 데이터
변경·유료 재실행·기존 복구 count 초기화는 이 변경에 포함하지 않는다.

## 공개 API

작품 소유자 인증이 필요하다. 쿼리 `episodeNo`를 생략하면 가장 최근 유효 APPLIED 회차를 선택한다. 지정한 회차가 없거나 무효이면 404이며 다른 회차로 몰래 대체하지 않는다.

| 메서드 | 경로 | operationId |
|---|---|---|
| GET | `/api/v1/works/{workId}/relationship-graph?episodeNo=N` | getEpisodeRelationshipGraph |
| GET | 위 경로 + `/edges/{edgeId}?episodeNo=N` | getEpisodeRelationshipEdge |
| PATCH | 위 경로 + `/edges/{edgeId}` | updateEpisodeRelationshipEdge |
| DELETE | 위 경로 + `/edges/{edgeId}?expectedVersion=N` | deleteEpisodeRelationshipEdge |

Graph: `selectedEpisode,availableEpisodes,revision,nodes,edges,truncated,totalEdgeCount,analysisStatus,partial,updateIncomplete,incompleteEpisodeNos`.
Episode: `id,episodeNo,title`. 프론트 회차 선택은 스포일러를 줄이기 위해 N화만 사용한다.
Node: `id,name,description,profileRegistered`. `name`은 선택 회차에 등록된 노드가 그 프로필의 유일한 등록 노드면 현재 프로필 이름, 아니면 관측 이름이다(GH382). 1회 정리(GH391) 뒤에도 남는 분리 데이터처럼 한 프로필에 등록 노드가 둘 이상이면 같은 프로필 이름을 반복하지 않고 각자의 관측 이름을 쓴다. v1 description은 null. 같은 프로필로 합친 노드는 프로필 노드 id 하나로 내려준다.
Edge: `id,sourceNodeId,targetNodeId,axis,kind,label,summary,direction,status,version,evidenceCount,lastVerifiedEpisodeNo,updateIncomplete`.
Detail: `edge,sourceNode,targetNode,evidence,version`. 근거는 `episodeId,episodeNo,episodeTitle,quote,startOffset,endOffset`이며 선택 회차 이후 근거를 보내지 않는다.

analysisStatus는 NOT_ANALYZED, PROCESSING, COMPLETED, PARTIAL, FAILED, INVALIDATED다. partial은 거절된 관측/미해결 보류/실패 또는 빠진 관계 분석이 있다는 뜻이며 사용자 검토 요청이 아니다. 최신 분석이 실패하면 기존 성공 회차 그래프를 보여주더라도 FAILED를 유지한다. 공개 그래프는 200개 edge까지 반환하고 초과 시 truncated=true와 실제 totalEdgeCount를 반환한다. 상세 API에는 이 표시 제한을 적용하지 않는다.

일반 회차의 새 의존성 계약은 자동으로 추가된 선행 회차/관계 event와 사용자 직접 정정·삭제를 분리한다(GH367). partial Context는 신규 O의 실제 stable node UUID 쌍의 정정·삭제만 검사하며, 같은 쌍의 다른 세대 edge도 포함한다. 자동 event에 따라 증가하는 edge version이나 선행 Job의 APPLIED 전이만으로 비교를 거절하지 않는다. 실제 사용자 정정·삭제와 원문·세대 변경 보호는 유지한다. 이전 계약의 frozen hash는 선행 상태와 섞여 있어 그대로 새 hash와 비교하거나 무검증 승격하지 않는다. 구 상태를 복구할 때는 현재 원문·파기 여부·세대·동일 회차의 최신 Job을 검증하고 저장된 새 관측 O로 관계 문맥을 다시 준비한다. 관측 ref/순서/인용/offset을 보존하며 관계 추출은 반복하지 않는다. 새 문맥의 N/R/H 매핑을 이전 결정과 혼합하지 않는다. 신규 binding은 manualDependencyVersion=1/manualDependencyRevision과 실제 R/H가 사용한 sourceDependencies를 저장한다. 출처 검증은 원문 manifest만 읽는 단일 집계 SQL이며 대형 Job 입력·journal JSON을 복제하지 않는다. 내부 저장 계약만 바뀌며 Worker formatVersion=1과 partial capability는 유지한다.

PATCH 입력은 `{expectedVersion,summary}`이며 summary는 앞뒤 공백 없는 1~500 code point다. 수정/삭제는 서사의 새 사건이 아니라 분석 오류 정정이므로 모든 회차에 적용한다. 충돌은 409다. 삭제는 canonical slot 억제 기록을 유지해 다음 분석과 재분석이 같은 연결을 되살리지 못하게 한다. v1에는 이 억제를 취소하는 공개 API가 없다. 프로필 연결/직접 관계 추가도 이번 API 범위 밖이다.

## 저장과 삭제

Flyway V101이 `relationship_graph_analyses/nodes/edges/events`와 `analysis_jobs.relationship_analysis_enabled`를 추가한다. 관계 event는 analysis+edge마다 하나이며 회차별 읽기는 최신 유효 분석만 사용한다. 관계 edge와 node는 재분석 후에도 ID를 보존해 사용자 수정과 삭제를 유지한다.

Flyway V118(GH382)는 `relationship_graph_nodes`에 `merged_into_node_id`(같은 표의 자기참조 FK, NO ACTION)와 `merged_at_episode_no`, 자기 자신·프로필 연결 노드의 병합을 막는 CHECK, `(work_id, character_id)` 부분 색인을 추가한다. 1회 정리(GH391) 뒤에도 다른 사람일 수 있는 분리 연결 노드가 정당하게 남아 unique 제약은 넣지 않는다. 분리 프로필은 1회 정리 사전 점검 C로 추적한다.

작품 파기는 관계 event→edge→node→analysis 순서로 삭제한다. 노드는 작품 단위 단일 DELETE라 자기참조 FK가 있어도 순서는 바뀌지 않는다. 회차 원문 파기는 해당 회차 이후의 동결 Context/decision/failure/binding과 복사된 인용을 지우고 후행 결과를 무효화한다. 캐릭터 삭제는 노드의 profile FK만 null로 만들고 관계 이력을 보존한다. 그 노드는 미등록으로 돌아가 표시 이름도 관측 이름으로 돌아간다. 병합된 노드는 `character_id`가 null이라 영향이 없다.

## 검증

`EpisodeRelationshipGraphRulesTest`: Unicode 근거, coverage, END+ADD, 모호한 주체, 재활성화, 잘못된 참조/과거 쓰기.
`EpisodeRelationshipGraphIntegrationTest`: H2의 회차 cutoff, 멱등, 설명 수정/삭제 억제, 실패 격리, 병렬·역순 수동 작업의 노드 ID 보존과 미래 프로필 비노출, 동명·고정 인물 충돌, HOLD 재사용/소비, 원문 version 및 파기.
`RelationshipGraphCleanupRulesTest`: GH391 정리 계획(연결 노드 엄격 해소, 미연결 연결·병합·보류, key 방향 복원·중단, slot 합치기와 정정·억제 승계, 근거 합치기·이름 맞바꾸기, 자기 연결, 멱등성).
`EpisodeRelationshipGraphIntegrationTest`의 GH391 시나리오: dry-run 무변경, 적용·게이트·동결 binding 호환·엔티티 미적재·HTTP 계약.
`EpisodeRelationshipGraphPostgresIntegrationTest`: 같은 시나리오를 V1~V101 Flyway와 JPA validate를 사용하는 격리 PostgreSQL에서 검증한다(Docker 필요).

### 2026-10-29 최종 검증 기록

- `./gradlew test bootJar --console=plain`: 성공(4분 10초). Gradle HTML/XML 기준 4,139개 중 3,880개 통과, 259개 건너뜀, 실패/오류 0. 조건부 테스트를 전부 실행했다고 해석하지 않는다. 이번 신규 관계 규칙 6개, H2 통합 12개, 실제 PostgreSQL/Flyway 통합 12개는 모두 실행·통과했다.
- 마지막 공백 정돈 뒤 `./gradlew bootJar --console=plain` 재생성 성공, `git diff --check` 통과. 산출물은 `build/libs/CatchHole-Backend-0.0.1-SNAPSHOT.jar`다.
- `EpisodeRelationshipGraphBrowserFixtureTest`는 고정된 격리 로컬 DB만 사용하는 명시적 실행용 합성 fixture이며 일반 suite에서는 건너뛴다. 이 검증에서는 별도로 실행해 성공했다. 실제 Spring/JWT/SDK/브라우저/PostgreSQL 연결로 1화의 동행 1개와 2화의 관계 3개, 회차 이후 인물·종료 상태의 숨김, 원문 근거, 설명 PATCH, 연결 DELETE, 새로고침 뒤 반영을 확인했다.
- 브라우저 인증은 서버가 발급한 임시 합성 사용자 access token을 주입했다. 로그인 UI와 refresh cookie는 이번 실제 연결 검증 범위가 아니다. DB에서 DISTRUST의 `corrected_summary`와 version 증가, FRIENDSHIP의 `suppressed=true`를 별도로 조회해 저장을 확인했다.
- 검증 종료 후 전용 Spring 서버(18233), 전용 PostgreSQL 컨테이너(18232), 임시 토큰 파일을 제거했다. 기존 로컬 DB/앱은 사용하거나 수정하지 않았다. 원고와 실제 사용자 자료는 fixture에 포함하지 않는다. 화면 기록은 프론트 저장소의 `docs/screens/gh323/episode-relationships-live-episode-1.png`, `episode-relationships-live-correction.png`에 있다.

## 최신 main 통합과 배포 순서 — 2026-10-30

main의 검수 실행·검토 종료 기능이 V92~V100을 사용하므로, 운영 미배포 관계도 migration은
`V101__add_episode_relationship_graph.sql`로 옮겼다. 기존 migration 내용은 바꾸지 않았다.
이전 V92 관계도 migration을 적용한 개인 로컬 예시 DB는 이 버전의 검증 대상으로 재사용하지 않는다.
공유·운영 DB의 Flyway 이력을 수정하거나 repair로 덮지 않고 새 격리 DB에서 전체 migration을 검증한다.

배포 순서는 Java API/DB, 지원 AI Worker, Front다. Java는 지원 광고가 없는 Worker에 관계 계약을
주지 않으며, 이미 관계 분석이 활성화된 Job은 지원 Worker만 재개할 수 있다. 기능 플래그를 끄면
신규 활성화만 멈추므로, 진행 중 관계 Job이 남은 상태의 Worker 롤백은 별도 확인이 필요하다.
기존 작품 전체의 자동 유료 백필은 하지 않는다. GH332는 [고정 선정 계정의 관계 전용 캠페인](relationship-cohort-backfill.md)만 운영 배포 후 자동 시작한다. 일반 인용의 위치가 불확실한 경우에는
[일반 인용 정책](https://github.com/catchhole-soma/catchhole-backend-java/blob/main/docs/analysis-continuity.md)의 GH323 정책으로 인용만 숨기고 설정값을 유지한다.

### V110 부분 실패 알림 (GH357)

일반 회차 SETTING_EXTRACTION에서 관계만 FAILED가 되면 기존 outbox에 RELATIONSHIP_PARTIAL을 저장하고 `관계도만 실패 · 나머지 분석 계속`으로 알린다. 관계도 전용 Job은 기존 Job FAILED 알림만 사용한다. 같은 묶음에 회차 중단 기록이 있으면 전체 중단을 우선 표시한다. 알림 추가가 실패 격리·재시도·반영 정책을 변경하지 않으며, 관계 진단의 발췌도 원문 파기 시 작품 단위로 제거한다.
