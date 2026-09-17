# 캐릭터 대표 이미지 — GH194

번호 안내: 2026-09-16 main 통합으로 미배포 GH194 migration을 V55~V60에서 **V57~V62**로 이동했다. 아래 계약은 최종 번호로 표기하며, 이전 검증 실행 당시에는 각 번호가 2씩 작았다. SQL 내용과 기존 사용자 선택은 보존했다.

캐릭터 목록과 상세는 `image`를 함께 반환한다. 기존 이름·현재 설정·첫 등장 회차·근거 응답은 유지한다. 이미지 선택은 `worldimage` 도메인의 `character_images`에서 관리하며 기존 종족 도감과 작품별 개인 이미지 보관함을 재사용한다.

## 표시 우선순위

1. 작가가 선택한 개인 이미지.
2. 작가가 직접 선택한 종족 도감. 종족 설정이 바뀌어도 덮어쓰지 않는다.
3. `useDefault=true`로 직접 선택한 공통 기본. 경로를 비워 Front 번들 그림을 사용한다.
4. 직접 선택이 없으면 현재 확정 프로필의 `profile.species`와 호환 키 `species`, `profile.race`, `race`만 읽는다. NFC·소문자·공백/구분자 정규화 후 활성 종족 도감 이름·별칭과 전체 값이 일치하는 단일 결과만 `AUTO`로 반환한다. 이름·직업·설명·스킬에서 부분 문자열을 찾지 않는다. 종족 값이 없거나 서로 다르거나 여러 그림이 일치하면 경로 없는 `AUTO`로 공통 기본 그림을 표시한다.

자동 연결은 V63부터 확정/수정 시 계산해 character_images에 AUTO로 저장한다. 기존 미처리 캐릭터는 별도 운영자 보정 배치 대상이다. `CharacterSnapshotAccessor`로 현재 snapshot의 envelope와 legacy 값 형식을 읽는다. 설정·snapshotVersion·updatedAt·분석 실행은 변경하지 않고 LLM 호출도 추가하지 않는다. 목록은 페이지의 저장된 선택만 일괄 조회하고 도감 별칭 재조회나 재매칭을 하지 않는다. [저장·보정 계약](automatic-subject-images.md)을 따른다.

## 선택 API

`PATCH /api/v1/works/{workId}/characters/{characterId}/image`

```json
{"catalogId":"race-elf","privateImageId":null,"useDefault":false,"version":0}
```

- `catalogId`, `privateImageId`, `useDefault=true` 중 최대 하나만 지정한다. 셋 다 비어 있으면 자동 연결로 돌아간다. `useDefault` 생략은 false다.
- 활성 종족 일반 도감만 선택한다. 세계관용 `race-default`는 캐릭터 공통 기본과 다르므로 허용하지 않는다.
- 작품 소유권을 확인하고 작품 → ACTIVE 캐릭터 순으로 잠근다. 개인 이미지는 동일 작품에서만 선택한다.
- 이미지 전용 version을 확인하고 실제 선택 변화가 있을 때만 증가시킨다. 같은 선택은 멱등이며 해제 뒤에도 version 행을 보존한다. 409는 최신 이미지를 확인한 후 명시적으로 재시도한다.
- 400 입력/분류 오류, 401 인증 필요, 404 작품/활성 캐릭터/이미지 없음, 409 선택 버전 충돌.
- 공통 `WorldSettingImageResponse`를 재사용하고 캐릭터에서 `AUTO` 출처가 추가된다. null 경로는 번들 공통 그림을 뜻하며 개인 이미지의 잠금 상태와 구분한다.

## 개인 이미지·삭제·마이그레이션

기존 `/private-image-vaults`, `/works/{workId}/private-world-images` API와 CHI1 암호화 계약을 그대로 사용한다. 같은 작품의 세계관·캐릭터에서 동일 이미지를 선택할 수 있다. 삭제는 두 선택 테이블을 모두 검사하며 보관된 캐릭터의 선택도 사용 중으로 취급한다. 캐릭터 보관·복구는 선택을 유지한다. 작품 영구 삭제 시 캐릭터 FK cascade로 선택을 정리하고 기존 개인 이미지 purge 경로를 따른다.

V60은 `character_images`만 추가한다. 적용된 V57~V59를 수정하지 않는다. Python Worker와 LLM 추출·비교 프롬프트는 변경하지 않는다.

통합 테스트는 자동 연결/미확인/중복 별칭/상충 값, 목록·상세 일치, 직접 선택 우선, 기본 고정·자동 복귀, 버전 충돌, 소유권·보관 상태·분류 경계, 개인 이미지 공유·삭제 방지를 검증한다. 실제 로컬 PostgreSQL 적용과 Front live E2E도 검증하며 운영 배포는 별도다.

2026-09-16 검증: 전체 Java 테스트 1,171개 중 1,106 통과·조건부 65 건너뜀, 실패 없음. 별도 빈 PostgreSQL DB에서 V1~V60 Flyway 적용·JPA validate·health UP을 확인한 뒤 임시 DB를 폐기했다. 실제 로컬 API 연결 Front E2E도 별도 계정으로 통과했다.
