# 캐릭터·세계관 이미지 자동 연결 저장 — GH194

2026-09-17. V63과 Java/Front를 함께 반영한다. 이미지 매칭은 확정/수정 트랜잭션에서 수행하며 조회에서는 저장된 이미지 ID를 사용한다. LLM 호출·추출/비교 프롬프트 변경은 없다.

## 확정과 수정

- 캐릭터: 단건 승격, 기존/신규 캐릭터 그룹 승격, 회차 자동 반영, 수동 현재 설정 수정에서 최종 확정 snapshot을 읽는다. PROFILE의 species/race 전체 값과 도감 이름·별칭이 하나로 일치할 때만 저장한다. 이름·직업·설명·미확정 후보로 외형을 추측하지 않는다.
- 세계관: 직접 생성, 단건/그룹 확정, 회차 자동 반영, 대상 이름·분류 수정에서 저장한다. 같은 분류이면서 현재 장르 추천에 포함된 도감만 자동 후보로 사용한다. 전체 이름·별칭 일치를 먼저 적용한다. 이후 단어 경계가 있는 접미사 또는 허용한 지형어(숲·동굴·미궁 등)의 접미사를 확인하고 가장 긴 단일 후보만 선택한다. 동률·미일치에는 기본 그림을 사용한다. 속성 설명은 이번 규칙의 입력에 포함하지 않는다.
- 작품 장르 변경은 자동 상태인 세계관 이미지에만 다시 적용한다. 수동 도감·개인 이미지·기본 이미지 고정은 유지한다. 캐릭터의 명확한 종족 매칭은 기존처럼 장르와 무관하다.
- Work → 대상 잠금 아래 기존 확정 저장과 같은 트랜잭션으로 처리하므로 실패 시 함께 rollback한다. 미확정·제외·검토 필요 후보는 이미지를 만들지 않는다. 이미지의 변경만으로 설정 version·snapshotVersion·분석 상태를 변경하지 않는다.

## 저장 상태와 조회

`AUTO`는 자동 매칭 결과다. catalog가 null인 AUTO도 정상 처리 완료로 기록하여 미일치를 조회마다 반복 판단하지 않는다. `MANUAL`, `PRIVATE`, `DEFAULT`는 작가가 선택한 상태이며 자동 갱신보다 우선한다. 기존 분류 변경 정책상 분류가 맞지 않게 된 수동 세계관 도감만 해제 후 새 분류에서 자동 연결하며 개인 이미지·기본 고정은 유지한다.

최초 자동 결과는 이미지 version 0으로 저장한다. 이후 결과/선택 방식이 바뀔 때 이미지 version만 증가하며 같은 결과 재저장은 멱등이다. 자동 선택 복귀는 그 요청에서 한 번 매칭해 저장한다. 세계관 PATCH에 `useAutomatic=true`를 추가하며 기존의 catalog/private 모두 null 요청은 분류 기본 이미지 고정으로 유지한다. 캐릭터는 기존의 모두 미지정 요청이 자동 복귀다.

조회에서는 자동 Matcher·종족 별칭 조회를 호출하지 않는다. 현재 페이지의 선택/이미지는 한꺼번에 조회하며, 세계관에서 저장된 결과가 없거나 자산이 비활성이면 장르 기본 그림을 적용한다. 이것은 분류 슬롯 조회이며 이름 매칭이 아니다. 공용 파일/S3 캐시 정책과 개인 암호화 프로토콜은 유지한다.

## 기존 데이터 보정

V63은 AUTO 저장을 허용하는 제약을 추가한다. 기존 세계관 선택 해제 행의 null source를 DEFAULT로 명시하여 작가의 기본 이미지 복귀를 보존한다. 기존 캐릭터 null source와 선택 행이 없는 대상은 보정 대상이며, 서버 기동 migration에서는 대상별 매칭을 실행하지 않는다. 배치 전 기존 미선택 캐릭터도 공통 기본 그림을 사용하며, 조회에서 매칭으로 임시 우회하지 않는다.

운영자 전용 `POST /api/v1/admin/world-images/backfill`:

```json
{"workId":"작품 UUID","kind":"WORLD_SETTING","limit":100,"apply":false}
```

- `kind`: CHARACTER 또는 WORLD_SETTING. `limit`: 1~500, 생략 시 100. `apply` 생략/false는 첫 묶음 예상 결과만 반환한다.
- 응답: processed/matched/defaults/applied. apply=true를 processed=0까지 반복한다. 이미 처리한 AUTO(미일치 포함)와 사용자 선택은 다시 처리하지 않는다.
- 작품별 Work 잠금으로 동시 확정·사용자 선택·영구 삭제와 직렬화한다. 한 요청의 최대 대상 수를 제한하고 요청마다 commit한다. 보정 세션의 JDBC 쓰기는 50개씩 batch 처리한다. 운영 전체를 한 트랜잭션으로 묶지 않는다.
- 적용은 생성/확정 때의 `AutomaticImageService`와 같은 규칙을 사용한다. 일부 완료 후 실패하거나 인증이 만료되면 미처리 대상부터 재실행할 수 있다. 기존 이미지·설정 내용·설정 version은 덮어쓰지 않는다.
- 인증된 일반 작가나 Worker 키로 실행할 수 없다. ADMIN access token을 가진 운영자만 사용하며 원고·설정 내용은 실행 로그에 출력하지 않는다.

`scripts/world-images/backfill.py`는 첫 묶음 미리보기와 적용 반복을 지원한다. 토큰은 `CATCHHOLE_ADMIN_ACCESS_TOKEN`으로 주입하고 커맨드 인수/로그에 쓰지 않는다.

```bash
python3 scripts/world-images/backfill.py --api-base https://API_HOST --work-id WORK_UUID --kind WORLD_SETTING
python3 scripts/world-images/backfill.py --api-base https://API_HOST --work-id WORK_UUID --kind WORLD_SETTING --apply
python3 scripts/world-images/backfill.py --api-base https://API_HOST --work-id WORK_UUID --kind CHARACTER --apply
```

순서: 대상 환경 이미지 자산 확인 → Java V63/API → Front → 작품별 보정 미리보기·적용. 원고 AI 분석은 필요하지 않다. 운영 배치 실행은 별도이며 로컬 검증에서는 격리 fixture만 보정한다.

## 검증 기록 — 2026-09-17

- Java 전체 실행 1,190개 중 1,123 통과·66 조건부 건너뜀·기동 fixture 1실패. DB 없는 context 테스트에 새 운영자 서비스 mock을 추가하고 해당 테스트 통과를 확인했다. 이후 기존 캐릭터 보정/ADMIN 성공 경로 1개를 추가해 관련 통합 테스트 5개도 통과했다. 총 1,125개 시나리오 확인, 조건부 66개는 별도 환경이 필요하다.
- 실제 PostgreSQL의 빈 DB에 V1~V63 적용·Hibernate validate·health UP. 기존 개발 DB 백업 복제본에서 V63 적용과 업무 데이터 보존을 확인한 뒤 로컬에도 적용했다. 사용자 테스트 작품 9개·설정 63개는 유지했고 일반 기존 데이터 보정은 실행하지 않았다.
- 캐릭터 목록·상세에서 matcher와 종족 별칭 쿼리를 호출하지 않는 회귀 검증, 확정 시 저장, 이름·장르 변경, 수동/개인/기본 우선순위, 보정 미리보기·제한·반복, 일반 작가/비인증 거절을 확인했다.
- 실제 Front/Java/PostgreSQL 테마 및 캐릭터/개인 이미지 E2E 각각 통과. 세계관 자동 복귀→고블린 숲 이름 변경→AUTO 숲 ID 저장·재조회, 모바일 선택 UI를 확인하고 격리 fixture만 정리했다.
- 운영 데이터량에서의 성능 측정은 수행하지 않았다. 확정 묶음의 도감 조회는 대상마다 반복하지 않으며, 조회 시 재매칭은 없지만 저장된 선택/기본 슬롯 조회는 남는다.
