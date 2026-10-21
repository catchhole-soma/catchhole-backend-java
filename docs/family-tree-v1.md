# 가계도 요약 조회 v1 — GH261

가계도는 [가족 관계 모델](family-relationships-v1.md)의 유효한 확정 관계와 현재 프로필에 이름이 명시된 미등록 인물을 구분해 표시한다. 확정 관계는 `RelationshipGraphReader`, 미등록 인물은 `RelationshipMentionReader`가 읽기 시점의 현재성을 검사한다. 이름만으로 서로 다른 언급을 합치거나 캐릭터를 새로 등록하지 않는다. 기존 PostgreSQL에 V86의 명시 조상·세대 차이와 V87의 사용자 지정 미등록 인물 묶음을 저장한다. LLM 호출·추출 프롬프트는 변경하지 않고 Neo4j를 추가하지 않는다. 기존 후보 검토·관계 상세·근거 API는 유지한다.

## 조회 계약

`GET /api/v1/works/{workId}/family-tree`는 JWT와 작품 소유권을 확인하고 `CommonResponse<FamilyTreeResponse>`를 반환한다. OpenAPI operationId는 `getFamilyTree`다. 다른 소유자의 작품과 없는 작품은 기존 `WORK_NOT_FOUND` 404이며, 다른 작품·보관·없는 초점 인물은 `CHARACTER_NOT_FOUND` 404다.

| 쿼리 | 기본값 / 의미 |
| --- | --- |
| `includeHistorical` | `false`. 기본은 유효한 확정 관계와 표시 가능한 언급의 `CURRENT`; true이면 두 출처의 `HISTORICAL`도 추가한다. `UNKNOWN`은 포함하지 않는다. |
| `focusCharacterId` | 생략 시 작품 전체. 지정 시 같은 작품의 활성 인물에서 확정 관계·표시 가능한 언급으로 연결된 가족 전체를 조회한다. 미등록 인물 ID는 초점 캐릭터 ID로 사용하지 않는다. |
| `limit` | 간선 수 상한, 기본 100, 최소 1, 최대 200. 잘못된 상한은 400이다. |

| 응답 | 의미 |
| --- | --- |
| `nodes[]` | `{id, name, nodeType, characterId, candidateId, candidateIds, mentionGroupId, mentionGroupVersion, image}`. `nodeType=CHARACTER`의 id는 UUID 문자열이고 characterId와 현재 프로필 이미지 응답을 가진다. 개별 `MENTION`의 id는 `mention:<후보 UUID>`이며 candidateId로 근거를 연다. 명시 묶음은 `mention-group:<그룹 UUID>`이며 candidateId=null, 표시된 구성원은 candidateIds로 제공한다. 두 MENTION 형태 모두 characterId와 image는 null이다. 미연결 초점은 한 노드로 유지한다. |
| `edges[]` | 기존 관계 필드에 `sourceType`, `relationshipId`, `candidateId`, `subjectNodeId`, `objectNodeId`를 추가한다. `RELATIONSHIP`의 id는 관계 UUID 문자열, `MENTION`의 id는 `mention:<후보 UUID>`다. 미등록 끝점의 characterId는 null이므로 배치는 항상 nodeId를 사용한다. `CHILD_OF`는 두 출처 모두 부모→자녀 `PARENT_OF`로 반환한다. `subjectRole`/`objectRole`은 아래 끝점 호칭 계약을 따른다. |
| `totalNodeCount`, `totalEdgeCount` | 시점·초점 필터 이후, 표시 상한 적용 이전의 전체 수다. 작품의 모든 캐릭터 수가 아니다. |
| `pendingCandidateCount` | 작품 전체의 `PENDING` 후보 수. 미등록 인물로 표시 중인 후보도 포함하며 초점·시점·표시 상한에 영향받지 않는다. |
| `optionalLinkCount` | 작품 전체에서 미등록 인물 표시 자격을 갖춘 CURRENT·HISTORICAL 후보 수. 초점·시점 옵션·상한과 무관하다. 캐릭터 연결이 필수 검토인 것처럼 표시하지 않는다. |
| `truncated` | 상한 때문에 일부 간선을 생략했는지 여부. true인 부분 그림을 전체 가족 관계로 설명하지 않는다. |
| `limit`, `includeHistorical`, `focusCharacterId` | 적용한 요청 조건. |
| `revision` | 같은 범위의 내용 변경을 감지하는 SHA-256 값. 권한·낙관적 쓰기 버전·페이지 커서가 아니다. |

간선은 최대 200개, 양 끝 인물은 최대 400개다. 관계가 없는 초점을 조회하면 간선 0개·노드 1개를 반환한다. 초점이 없으면 유효 관계에 참여하지 않는 인물은 그림에 만들지 않는다. 이름이 명시되지 않은 부모·배우자를 채우거나 형제 관계에서 부모를 역산하지 않는다. 현재 설정에 실제 이름이 있는 미등록 인물은 기본적으로 후보별로 표시하고, 사용자가 명시적으로 묶은 후보만 한 도형을 공유한다. 한 쌍에 서로 다른 세부 유형·시점·기간의 관계가 있으면 각각의 간선을 보존한다.

기본 노드·간선 순서는 도형 ID 문자열 오름차순이다. 초점이 있으면 모든 유효 가족 관계를 방향과 무관하게 연결 범위로 탐색하고, 초점에서 가까운 간선을 먼저 선택한 뒤 간선 ID로 순서를 고정한다. 이 탐색 방향은 화면 범위를 정하는 데만 사용하며 `PARENT_OF`의 부모→자녀 방향을 바꾸지 않는다. 시점 필터를 먼저 적용하므로 과거 배우자 연결만으로 이어지는 가족은 과거 포함을 켰을 때만 초점 범위에 들어온다.

## 유효성·스냅샷·변경 감지

철회·보관·현재 근거 소실·자동 후처리 대기/실패·순환 검토 상태는 확정 도형의 간선으로 반환하지 않는다. 제한된 미등록 후보는 아래 규칙을 통과하면 `MENTION` 출처로만 반환하며 확정 관계로 취급하지 않는다. 자동 관계는 최신 작품 입력 세대에서 재검증된 유효 지지가 있어야 하며, 별개의 유효 수동 지지는 기존 계약대로 유지한다. 과거 포함 옵션도 `VALID` 조건을 우회하지 않는다. GraphReader가 전체 부모·조상 관계에 적용하는 보수적인 순환 판단을 이 API에서 다시 해석하지 않는다.

서비스는 읽기 전용 `REPEATABLE_READ` 트랜잭션으로 소유권·인물·관계·현재 근거·후보 수를 읽는다. PostgreSQL의 한 MVCC 스냅샷 안에서 노드·간선·집계를 만든다. 여러 페이지의 목록 응답을 합치거나, 쓰기 잠금으로 후처리를 막아 일관성을 확보하지 않는다. 조회 중 수동 확정이 커밋돼도 기존 간선과 새로운 후보 수를 섞지 않으며 다음 요청에서 새 상태를 읽는다.

`revision`에는 작품 ID/관계 입력 세대, 조회 조건, 상한 적용 전 범위의 인물 ID·이름, 관계·언급의 ID·버전·끝점·출처 유형·세부 유형·시점·기간·끝점 호칭과 작품 전체 PENDING 수·optionalLinkCount를 넣는다. 필드마다 길이를 포함해 해시 경계를 구분한다. 수동 확정·근거 추가·철회는 작품 입력 세대가 같아도 관계 내용/버전 변화로 감지된다. 표시 상한 밖의 관계 변화도 포함하지만 초점 범위 밖의 모든 검토 편집을 표현하는 전역 감사 버전은 아니다. 후보 편집 내용이나 원문 가용성만 바뀌면 이 값이 그대로일 수 있으므로 열린 후보·근거 상세는 기존 자체 조회와 버전/토큰 검증을 유지한다.

이 API는 원문·전체 근거·현재 PROFILE 값·출처 토큰을 응답하지 않는다. 확정 간선 선택은 기존 `GET /relationships/{relationshipId}` 또는 `/relationships/{relationshipId}/evidence`, 미등록 인물·간선 선택은 `GET /relationship-candidates/{candidateId}`, 원문 선택은 기존 Fact evidence API를 사용한다. 도형용 `mention:` 문자열을 관계 상세 경로에 전달하지 않는다. 응답 `version`을 그대로 읽었다는 이유로 철회를 자동 실행하지 않으며 기존 사용자 명시 결정과 동시 변경 계약을 유지한다.

`limit`는 네트워크 응답과 화면 표시 상한이다. 현재 GraphReader는 작품의 전체 관계와 필요한 지원 근거를 읽어 유효성과 순환을 검증하고 MentionReader는 대기 후보와 현재 프로필의 상충을 검사한다. 초점 조회도 이 검증을 재사용하므로 서버 조회량·메모리·응답 시간의 상한을 보장하지 않는다. 큰 작품의 실제 비용을 측정한 뒤 전용 projection/인덱스 또는 별도 범위 제한을 결정하며, 일부만 읽은 그래프를 전체 검증 결과라고 표시하지 않는다.

## 중간 계보가 없는 조상·후손

`ANCESTOR_OF` 간선은 조상→후손으로 반환한다. `generationGap`에 명시된 N대손의 N을 넣고 미상은 null이다. `DESCENDANT_OF`는 후보 입력 방향이며 가계도에는 반환하지 않는다. `subjectRole`/`objectRole`은 null이다. 부모 호칭을 붙이거나 중간 N−1명의 인물을 만들어 부모 선으로 연결하지 않는다. 원문·현재 설정 문구는 sourceType에 맞는 기존 상세 API에서 확인한다.

등록된 조상은 CHARACTER, 이름만 명시된 조상은 기존 후보별 MENTION으로 표시한다. 명시적인 조상 관계도 CURRENT/HISTORICAL 필터와 초점 연결 범위에 참여한다. 세대 수는 화면의 비어 있는 행 수를 강제하는 값이 아니며 압축 배치를 하더라도 원래 수치를 보존해야 한다. generationGap도 구조 revision에 포함한다. 알려진 부모 경로와 모순된 세대 수는 검토 상태이므로 가계도에 확정 선으로 표시하지 않는다.

## 등록 인물의 프로필 이미지

등록된 인물의 `image`는 캐릭터 목록·상세와 같은 `WorldSettingImageResponse`다. 저장된 수동 도감·자동 도감·개인 이미지 선택을 그대로 사용한다. 기본 선택 또는 일치하는 도감 이미지가 없는 경우에는 `thumbnailUrl`/`imageUrl`이 null이며 Front는 기존 기본 아이콘을 유지한다. 미등록 `MENTION`은 항상 image=null이고 이름이나 상대방의 사진으로 이미지를 만들어 붙이지 않는다.

`FamilyTreeServiceImpl`은 범위·상한을 정한 뒤 실제 응답에 표시되는 등록 인물만 기존 `CharacterImageService.getCharacterImages`로 한 번에 조회한다. 관계와 같은 읽기 전용 스냅샷에서 조회하며 인물마다 상세 API·이미지 조회를 반복하거나 이미지를 재매칭하지 않는다. 관계가 없는 초점 인물도 같은 경로를 사용한다. 개인 이미지는 기존 작품 인증 파일 경로와 ID만 응답하고 공개 자산 URL·파일명·스토리지 경로를 만들지 않는다. Front는 기존 인증 SDK와 세션별 Blob 이미지 표시 계약을 재사용한다.

응답 `revision`은 기존 구조 revision에 실제 표시된 등록 인물의 이미지 source·version·ID·경로·이름을 길이 구분한 SHA-256으로 합친다. 사진 선택·변경·기본 복귀만으로도 revision이 바뀌지만 관계 입력 세대·후처리·후보·간선·노드 순서는 바뀌지 않는다. 표시되지 않는 인물의 사진 변경은 이 revision에 포함하지 않는다. 이미지 정보는 family projector의 범위·순서·배치 입력에 섞지 않으며, Front도 사진 변경만으로 캔버스 배치나 확대 위치를 초기화하지 않는다.

## 부모·자녀 선의 양 끝 호칭

`PARENT_OF` 간선은 `subjectRole`에 부모 쪽, `objectRole`에 자녀 쪽의 호칭을 반환한다. enum은 `PARENT`, `FATHER`, `MOTHER`, `CHILD`, `SON`, `DAUGHTER`다. 구체적인 호칭이 명시되지 않았거나 충돌하면 각각 `PARENT`/`CHILD`이며 배우자·형제 관계는 두 값 모두 null이다. 등록 관계와 MENTION에 같은 규칙을 적용한다.

| 현재 설정 소유자와 문장 | 부모 끝점 | 자녀 끝점 |
| --- | --- | --- |
| 비요른: `얀델의 아들` | 얀델 · PARENT | 비요른 · SON |
| 비요른: `아버지: 얀델` | 얀델 · FATHER | 비요른 · CHILD |
| 얀델: `딸: 아이나르` | 얀델 · PARENT | 아이나르 · DAUGHTER |
| 비요른: `아버지: 얀델`, 얀델: `아들: 비요른` | 얀델 · FATHER | 비요른 · SON |

`ExplicitFamilyRelationshipParser`는 지원 문법에서 실제로 일치한 호칭과 그 호칭이 가리키는 주체/상대 끝점을 보존한다. `FamilyTreeRoleResolver`는 이를 읽기 전용 표시 정보로만 사용한다. 관계 후보·관계 row·canonical/request hash·LLM 입력과 출력은 변경하지 않는다. 같은 관계의 호칭이 다르더라도 자동 처리의 기존 requestId 중복 방지로 후보와 관계를 추가 생성하지 않는다.

등록 관계의 호칭은 `RelationshipGraphReader.isSupporting`을 통과한 현재 지지 근거만 사용한다. Fact의 소유자가 관계 끝점이어야 하며, 문장의 상대명이 나머지 끝점의 현재 이름과 정확히 일치하고 작품 안의 활성 동명이인이 없어야 한다. 명제의 방향·세부 유형·시점·기간·단정 여부도 저장 관계와 일치해야 한다. MENTION은 현재 근거의 소유자·상대 언급·방향·세부 유형·시점을 해당 후보와 비교한다. 수동 수정·정정 후 이전 문장이 새 대상이나 방향을 뒷받침하지 않으면 그 문장에서 호칭을 가져오지 않는다.

서로 다른 현재 지지 근거가 양 끝 호칭을 각각 명시하면 함께 표시할 수 있다. 같은 끝점에 FATHER/MOTHER 또는 SON/DAUGHTER가 충돌하면 그 끝점만 중립 호칭으로 되돌린다. 이름이나 인물의 성별 프로필을 읽어 호칭을 보충하지 않으며 한쪽이 SON이라고 반대편을 FATHER로 바꾸지 않는다. 미지원 자유문·추측·부정·오래된 근거·차단된 자동 지지는 호칭 출처가 아니다. 원문 인용이 없어도 현재 설정과 출처 토큰이 유효하면 명시된 호칭을 표시할 수 있다.

끝점 호칭도 표시 상한 전 전체 범위의 `revision`에 포함한다. 같은 간선 ID·버전에서 현재 지지 근거의 구성이 달라져 호칭만 변하거나, 상한 밖 간선의 호칭이 바뀌어도 조회 변경을 감지한다. Front는 `subjectRole`/`objectRole`을 배치 입력과 캐시 키에 포함해야 하며, 묶인 연결의 한 호칭을 다른 인물에게 복사하지 않는다.

## 이름만 명시된 미등록 인물과 선택적 연결

예를 들어 비요른의 현재 프로필이 `얀델의 아들`이고 얀델 캐릭터가 없어도 `얀델(미등록 인물) → 비요른`을 표시한다. 얀델을 누르면 해당 후보의 현재 설정과 원문 가용 여부를 보여주며 캐릭터 모달을 열지 않는다. 이후 사용자가 후보 수정에서 기존 캐릭터를 선택하고 저장·확정하면 기존 API가 자기 연결·순환·중복·버전·출처를 검증한다. 성공하면 언급 도형 대신 등록 캐릭터의 확정 관계가 표시된다. 캐릭터가 늦게 등록되거나 이름이 같아져도 자동 연결하지 않는다.

`RelationshipMentionReader`는 가계도와 후보 응답의 `displayableAsMention`이 공유하는 단일 판정 경계다. 다음 조건을 모두 만족해야 한다.

- `CURRENT_SETTING_RULE`에서 생성된 `PENDING` 후보이고 objectCharacter가 없다. 저장된 상태를 CONFIRMED로 바꾸거나 관계 row를 추가하지 않는다.
- 주체가 활성이고 이름이 명시되어 있으며 ASSERTED, CURRENT 또는 HISTORICAL이다. 대명사·미상·가족 호칭만 있는 대상, 주체 자신의 현재 이름, 보관된 캐릭터와 같은 이름은 제외한다.
- 자동 보류 이유는 없거나 UNRESOLVED_TARGET뿐이다. 모호한 대상·추측·비유·부정·불명 시점은 포함하지 않는다.
- 1~20개 근거 모두 현재 프로필·provenance에 남아 있고 sourceToken이 일치해야 한다. 출처가 하나만 남아도 유지하는 확정 관계의 독립 지지 규칙과 구분한다.
- 같은 주체의 현재 프로필에 동일 이름·관계·시점의 호환 subtype 부정/충돌 또는 반대 부모 방향의 명시적 주장이 있으면 표시하지 않는다. 미등록 상대는 자동처리의 등록 인물 모순 검사에 들어가지 않으므로 읽기에서 현재 문장을 별도로 확인한다.

기본 미등록 인물 식별자는 후보 ID다. 서로 다른 후보에 같은 이름이 나와도 자동으로 합치지 않으며, 아래 명시 묶음 API를 사용한 경우에만 공유 표시 ID를 쓴다. 실제 캐릭터에 일괄 연결하지 않는다. 선택한 후보 하나만 기존 수정·확정 흐름으로 연결한다. 대상 캐릭터가 이미 연결된 후보는 보관 여부와 무관하게 미등록 도형으로 되돌리지 않는다. 제외·확정·출처 변경·주체 보관은 다음 읽기에서 반영하며 오래된 설정으로 미등록 인물을 계속 표시하지 않는다. 후보의 `PENDING`은 저장 상태이고 `displayableAsMention=true`는 현재 표시 가능성이다. UI에서는 선택적 인물 연결과 실제 확인이 필요한 사유를 구분한다.

가계도·후보 상세·후보 목록 읽기는 REPEATABLE_READ에서 이 정책을 적용한다. 쓰기 응답은 기존 작품 쓰기 잠금 안에서 현재 결과를 계산한다. 개별 언급 표시는 새 후보 상태·DB migration·LLM 책임을 추가하지 않는다. 아래 명시 묶음 저장은 별도의 V87 확장이다. 현재 프로필이 지원 문법으로 관계 후보를 만들지 못했다면 이 기능만으로 원고를 다시 읽어 도형을 만들지는 않는다.

## 사용자가 명시적으로 묶는 미등록 인물

같은 이름으로 자동 병합하지 않는다. 사용자가 서로 다른 후보의 관계와 현재 근거를 확인하고 같은 인물이라고 지정한 경우에만 `RelationshipMentionGroup`을 만든다. 한 그룹은 현재 표시 가능한 미등록 `CURRENT_SETTING_RULE` 후보 2~20개로 시작한다. 실제 캐릭터·확정 관계·새 Fact를 만들지 않고 각 후보 ID, 원래 상대 호칭, 관계 종류·세대 수·근거를 보존한다. 이름이 다른 언급도 사용자가 명시적으로 선택할 수 있으며 공통 표시 이름을 200자 이내로 지정한다.

| API | 계약 |
| --- | --- |
| POST `/relationship-mention-groups` | `{requestId, name, candidates:[{candidateId, expectedVersion}]}`. 같은 작품의 현재 표시 가능한 미등록·미그룹 후보만 허용. 중복 선택은 400, 오래된 버전·이미 묶인 후보·출처 변경·순환·세대 충돌은 409 |
| GET `/relationship-mention-groups` | 활성 그룹 목록. 가계도에서 현재 숨겨진 그룹도 검토·해제를 위해 조회 가능 |
| GET `/relationship-mention-groups/{groupId}` | `{id, version, name, active, candidateIds, reviewReasons}`. 각 후보 근거는 기존 후보 상세 API 사용 |
| POST `/relationship-mention-groups/{groupId}/ungroup` | `{expectedVersion}`으로 전체 해제. 후보·관계·근거를 삭제하지 않고 개별 언급 도형으로 복원 |

모든 경로 앞에 기존 `/api/v1/works/{workId}`를 붙인다. JWT 작품 소유권과 Work 쓰기 잠금을 사용한다. 다른 작품/소유자의 그룹은 404다. 생성 requestId와 정규화한 이름·선택 후보 ID/버전의 hash로 재전송을 처리한다. 후보 버전과 현재 소속을 다시 검사하기 전에 원래 요청 replay를 반환하므로 응답 유실 뒤 안전하게 재시도할 수 있다. 해제된 그룹의 생성 요청을 재전송해도 `active=false` 원본을 반환하며 되살리지 않는다. 다시 묶으려면 새 requestId와 최신 후보 버전이 필요하다.

새 그룹 version은 0이다. 묶기·해제는 구성원 후보 version을 한 번씩 증가시켜 이전 편집 요청이 묶음 변경을 놓치지 않게 한다. 후보의 명제·근거를 사용자가 수정하거나 확정·제외하면 그 후보만 그룹에서 떼고 그룹 version도 올린다. 남은 멤버가 하나가 되어도 그룹 ID는 안정적으로 유지한다. 전체 해제는 그룹 version을 한 번 올리고 `disbandedFromVersion`으로 같은 해제 요청을 멱등 처리한다. 이미 묶인 멤버/그룹을 묵시적으로 합치거나 기존 그룹 전체를 확장하지 않는다. 필요하면 해제한 후 명시적으로 다시 선택한다.

그룹 도형은 `nodeType=MENTION`, `id=mention-group:<group UUID>`, `candidateId=null`, `mentionGroupId`, `mentionGroupVersion`, `candidateIds`를 반환한다. `candidateIds`는 해당 응답 범위·시점·상한에서 실제 표시된 후보들이다. 그룹 상세의 candidateIds는 현재 소속 전체다. 개별 MENTION은 기존 candidateId와 후보 하나의 candidateIds를 유지하고 CHARACTER는 빈 candidateIds를 반환한다. 각 선의 ID는 계속 `mention:<candidate UUID>`이며 후보별 relation kind/generationGap/sourceType/candidateId/끝점 호칭을 보존한다. 그룹 도형 이름·버전·구성원도 revision에 포함된다. optionalLinkCount는 도형 수가 아니라 표시 가능한 연결 후보 수다.

`RelationshipMentionGroupValidator`는 묶인 끝점과 현재 근거가 있는 확정 관계를 함께 검사한다. 부모·조상 순환은 ANCESTRY_CYCLE, 알려진 부모=1·조상=N 세대 차이 불일치는 GENERATION_GAP_CONFLICT로 거절한다. 미상 세대는 새 숫자로 보충하지 않는다. 이후 다른 관계의 확정·보관 복구로 그룹이 충돌하면 읽기에서도 그룹의 언급 선을 숨기고 그룹 목록/상세에 사유를 제공한다. 사용자는 목록에서 해제할 수 있다. 일부 후보의 출처만 사라졌다면 유효한 멤버만 표시하고 그룹 상세는 MENTION_GROUP_SOURCE_CHANGED를 알린다. 후보의 저장 상태나 원래 근거를 조회가 고치지 않는다.

V87은 `relationship_mention_groups`와 후보의 nullable `mention_group_id` FK를 추가한다. 그룹화 자체는 현재 PROFILE이나 관계 입력 세대를 바꾸지 않는다. 작품 영구 삭제는 evidence → candidates → mention_groups → relationships 순서로 정리한다. 그룹 생성/해제는 서버 기동·migration·이름 일치로 실행되지 않는다.

## 기존 작품 공개 전 준비

배포나 첫 화면 진입이 기존 작품 전체를 자동 백필하지 않는다. 아래는 실행하지 않은 운영 절차 제안이며 공개 전 대상·배치 크기·진행 결과를 확인한 뒤 별도로 수행한다. 프론트 노출 플래그는 Backend 자동 후처리와 권한 검증을 대신하지 않는다.

1. 현재 버전의 Backend와 기존 `relationship.processing` 스케줄러가 동작하는지 확인한다. 초기 대상은 `ACTIVE`, `relationship_input_version=0`, 처리 상태 행 없음인 작품이다. 이미 대기·실패·완료된 작품은 초기 백필에서 다시 예약하지 않는다.
2. 운영 DB에서 아래 조회로 대상 수와 제한된 ID 목록을 먼저 확인한다. 실제 접속 정보·인물·원문을 문서나 로그에 기록하지 않는다. 필요한 경우 사전에 정한 작품 ID 목록으로 조건을 더 좁힌다.
3. 한 번에 최대 100개만 Work 행 잠금 아래 0→1로 예약하고 커밋한다. 관계·후보·근거를 직접 생성하거나 `processed_version`을 조작하지 않는다. 기존 처리 큐가 기본 10초마다 최대 10개씩 실행한다.
4. 반환한 작품 ID의 입력/처리 버전, 실패 코드·재시도 시각과 공개 처리 상태 API를 확인한다. 성공 수·PENDING 후보 수·미지원 설정 수를 구분한다. 실패는 기존 1분 재시도 경로를 따르며 데이터 문제를 그대로 둔 무한 수동 재예약을 하지 않는다.
5. 다음 제한 배치를 예약한다. 같은 SQL을 재실행해도 이미 0→1로 예약한 작품은 제외된다. 이미 진행 중인 입력은 건드리지 않고, 이후 설정 변경은 기존 입력 버전 트리거가 예약한다. 중단 시 아직 예약하지 않은 작품은 그대로 남으며 이미 커밋한 대기는 기존 큐가 마무리한다.

대상 확인용 SQL:

```sql
SELECT count(*) AS initial_work_count
FROM works w
WHERE w.lifecycle_status = 'ACTIVE'
  AND w.relationship_input_version = 0
  AND NOT EXISTS (SELECT 1 FROM relationship_processing_states s WHERE s.work_id = w.id);

SELECT w.id
FROM works w
WHERE w.lifecycle_status = 'ACTIVE'
  AND w.relationship_input_version = 0
  AND NOT EXISTS (SELECT 1 FROM relationship_processing_states s WHERE s.work_id = w.id)
ORDER BY w.id
LIMIT 100;
```

제한 예약 SQL 제안 — 자동 migration이나 서버 기동 코드에 넣지 않는다:

```sql
BEGIN;
WITH batch AS (
    SELECT w.id
    FROM works w
    WHERE w.lifecycle_status = 'ACTIVE'
      AND w.relationship_input_version = 0
      AND NOT EXISTS (SELECT 1 FROM relationship_processing_states s WHERE s.work_id = w.id)
    ORDER BY w.id
    LIMIT 100
    FOR UPDATE OF w SKIP LOCKED
)
UPDATE works w
SET relationship_input_version = 1, updated_at = CURRENT_TIMESTAMP
FROM batch
WHERE w.id = batch.id
  AND w.relationship_input_version = 0
RETURNING w.id, w.relationship_input_version;
COMMIT;
```

현재 규칙 처리는 현재 PROFILE의 지원 문법만 읽는다. 원문/과거 Fact 재추출이나 LLM 호출을 시작하지 않는다. 이미 저장된 동일 입력의 PENDING·사용자 수정·확정·제외·철회 결정을 되돌리지 않는다. 따라서 초기 준비가 성공해도 미지원 문장이나 기존 보류 후보가 모두 자동 해결되는 것은 아니다. 특정 작품의 명시적 재처리가 필요할 때는 기존 소유자 인증 `POST relationship-processing`을 사용하고, 별도의 공개 운영 일괄 API나 회원 impersonation 경로는 추가하지 않는다.

## 검증 범위

`FamilyTreeControllerIntegrationTest`와 이를 상속한 `FamilyTreePostgresIntegrationTest`는 인증/작품 격리, 현재·과거·미상 시점, 후보 분리, 초점·독립 인물·분리된 가족, 상한·합계·안정된 선택, 철회·보관·근거 소실, 자동 후처리 대기/실패, 수동 변경의 revision, 조회 도중 확정 커밋의 스냅샷 일관성을 검증한다. PostgreSQL 테스트는 격리된 Testcontainer의 Flyway/JPA validate를 사용하며 기존 로컬·운영 DB에 연결하지 않는다.

`FamilyTreeProjectorTest`는 300개 분리 가족 관계의 200간선/400인물 상한, 초점 거리 우선순위, DB 반환 순서 독립성, 상한 밖 근거 버전의 변경 감지와 GraphReader의 순환 검토 상태 존중을 확인한다. 합성 fixture 테스트는 실제 대규모 작품 성능 측정이나 운영 백필 실행 기록이 아니다.

### 2026-10-19 Backend 요약 조회 검증

JDK 21에서 `./gradlew bootJar test --tests 'org.monitoring.catchholebackend.domain.relationship.*' --console=plain`을 실행해 관계 패키지 288건 전부 통과했다. 새 가계도 H2 17건·격리 PostgreSQL 17건·순수 표시 규칙 8건을 포함하며 실패·오류·skip은 0건이다. 기존 로컬/운영 DB의 백필이나 Front 실연동 검증을 수행했다는 의미는 아니다.

### 2026-10-19 전체 회귀 및 Front 실연동 검증

이후 `JAVA_HOME`으로 JDK 21을 지정하고 `env -u DOCKER_HOST ./gradlew test --rerun bootJar --console=plain`을 실행했다. 기본 Docker Desktop 소켓으로 Testcontainers를 사용했으며 XML 합계는 전체 3,318건 중 실행·통과 3,082건, 조건부 skip 236건, 실패·오류 0건이다. `bootJar`도 성공했다. skip은 별도 로컬 DB·HTTP Worker·AI checkout 등 실행 조건을 요구하는 기존 테스트이며 통과 건수에 포함하지 않는다. 상세 결과는 `build/test-results/test/`와 `build/reports/tests/test/`, 실행 로그 `/tmp/catchhole-gh261-java-full-docker.log`에서 확인했다.

Front 실연동 시나리오 1건은 합성 계정·작품으로 실제 HTTP와 PostgreSQL을 사용해 쿠키를 포함한 인증, 프로필 수정, 명시적 관계 자동 반영, 과거 관계 포함, 관계 철회, 인물 보관·복구와 테스트 데이터 정리까지 통과했다. 이는 해당 로컬 실연동 시나리오의 검증 기록이며, 실제 사용자 데이터의 백필·운영 배포·대규모 작품 성능 측정이나 Front 전체 테스트 통과를 뜻하지 않는다.

### 2026-10-19 미등록 인물 검증 결과

전체 테스트 실행에서 3,367건 중 신규 PostgreSQL fixture 4건만 requestHash 제약 위반으로 실패했고 236건은 조건부 건너뜀이었다. fixture를 실제 V84의 64자리 소문자 16진수 계약에 맞춘 뒤 관계 패키지 전체 337건을 다시 실행해 실패·오류·건너뜀 없이 통과했다. bootJar도 성공했다. 전체 실행의 fixture 오류와 수정 후 범위 재실행을 구분하며 전체를 다시 실행했다고 표현하지 않는다. 새 가계도 사례는 H2·PostgreSQL에서 동일하게 실행한다.

실제 localhost API·PostgreSQL 브라우저 시나리오 2개도 통과했다. 직접 저장한 `얀델의 아들`에서 별도 캐릭터를 만들지 않고 언급 표시·근거 조회·수동 연결을 확인했고, 기존 관계의 과거/철회/보관/복구 흐름을 재검증했다. 합성 현재 프로필을 사용했으며 원고→LLM 추출 정확도 검증은 아니다.

### 2026-10-19 끝점 호칭 검증

끝점 호칭 변경 후 JDK 21에서 `env -u DOCKER_HOST ./gradlew bootJar test --tests 'org.monitoring.catchholebackend.domain.relationship.*' --console=plain`을 실행했다. 처음 실행은 공유 파서 fixture에 추가된 역할 필드의 기대값이 없어 해당 1건만 실패했다. fixture를 갱신한 뒤 같은 관계 패키지 테스트를 다시 실행해 383건 모두 통과했고 실패·오류·skip은 0건이다. production 소스는 첫 `bootJar` 성공 뒤 변경하지 않았다.

검증에는 가계도 H2 43건·격리 PostgreSQL 43건, 표시 projector 13건, 명시적 호칭 resolver 25건, 파서 130건이 포함된다. 한쪽만 명시된 아들/아버지, 양쪽 독립 근거, 동일 끝점의 상충, stale/소실/차단된 자동 지지, 수동 관계와 다른 대상·방향·세부 유형·시점, 동명이인·무관한 소유자, 성별 프로필 미사용, 미등록 인물, 상한 밖 호칭 revision과 기존 자동 요청 중복 방지를 확인했다. 이는 선별 관계 패키지 검증이며 이번 변경으로 Backend 전체 테스트를 다시 실행하거나 실제 사용자 데이터를 수정했다는 의미는 아니다. 결과는 `build/test-results/test/`와 `/tmp/catchhole-gh261-roles-tests-rerun.log`에서 확인했다.

### 2026-10-19 프로필 이미지 검증

JDK 21에서 `env -u DOCKER_HOST ./gradlew bootJar test --tests 'org.monitoring.catchholebackend.domain.relationship.*' --tests 'org.monitoring.catchholebackend.domain.worldimage.CharacterImageIntegrationTest' --console=plain`을 실행해 394건 모두 통과했다. 가계도 H2 46건·격리 PostgreSQL 46건과 기존 캐릭터 이미지 5건을 포함하며 실패·오류·skip은 0건이다. `bootJar`와 `git diff --check`도 성공했다.

새 사례는 화면 범위의 등록 인물만 한 번에 조회하는지, 캐릭터 상세와 수동 이미지 응답이 같은지, 기본 이미지·미등록 인물의 fallback 계약, 사진만 수정하거나 기본으로 복귀할 때 관계와 입력 세대를 유지하면서 revision이 바뀌는지, 범위 밖 사진은 revision에 영향을 주지 않는지, 관계 없는 초점의 PRIVATE 인증 파일 경로와 비공개 메타 제외를 확인한다. 이는 합성 DB 통합 검증이며 이미지 파일의 브라우저 표시나 실제 사용자 사진 변경을 수행한 기록은 아니다. 실행 로그는 `/tmp/catchhole-gh261-avatar-java.log`다.

### 2026-10-20 명시 조상 확장 검증

`./gradlew test --tests '*domain.relationship.*' bootJar --console=plain`으로 관계 패키지 565건 전부 통과했다. 실패·오류·skip은 0건이며 격리 PostgreSQL의 V86 migration/JPA validate와 API 검증을 포함한다. 5대손의 단일 관계 저장·원래 근거 문구·미상 gap·미등록 조상·전체 소유자 문구·부정/추측 제외·부모와 조상 순환·알려진 부모 경로와 세대 수 불일치·재처리 멱등성을 검증했다. 공유 JSON의 조상 문법 8개도 실제 parser fixture 테스트로 추가 검증한다. 이 테스트 실행은 사용자 작품 데이터를 수정하지 않는다.

### 2026-10-20 명시 미등록 인물 묶음 검증

`./gradlew test --tests '*domain.relationship.*'`에서 관계 패키지 579건 전부 통과했다. 실패·오류·skip은 0건이며 격리 PostgreSQL V87 migration/JPA validate 및 가계도 통합 55건을 포함한다. 명시 묶기 전 이름 미병합, 안정된 단일 도형과 개별 간선·근거 보존, 생성/해제 재전송, 소유권·후보/그룹 버전·중복 선택·현재 출처, 묶기에서 생기는 순환·세대 차이와 이후 관계 변경 재검증을 확인했다. 그룹이 실제 저장된 작품의 purge도 H2/PostgreSQL 별도 회귀 2건에서 통과했다.
