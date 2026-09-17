# 세계관 대표 이미지 도감 — GH194

번호 안내: 2026-09-16 main 통합으로 미배포 GH194 migration을 V55~V60에서 **V57~V62**로 이동했다. 아래 계약은 최종 번호로 표기하며, 이전 검증 실행 당시에는 각 번호가 2씩 작았다. SQL 내용과 기존 사용자 선택은 보존했다.

세계관 대상은 처음에는 분류 기본 이미지를 표시한다. 작가는 같은 분류의 공용 도감에서 이름·별칭으로 검색해 이미지를 선택하거나 기본 이미지로 되돌릴 수 있다. 이미지 선택은 설정 내용과 별도로 저장한다. 작품의 실제 외형을 확정하는 정보가 아니며 Front에는 `공용 예시 이미지`로 표시한다.

## 현재 범위

개인 이미지 업로드·선택은 [개인 세계관 이미지](private-world-images.md)의 V59 계약으로 확장한다. `world_setting_images`는 공용 도감과 개인 이미지 중 하나만 참조하며, API에 `privateImageId`·`vaultId`와 `PRIVATE` 출처가 추가된다. 공용 도감의 기존 범위와 공개 자산 계약은 아래와 같다.

- 기존 공용 대표 이미지 333종: 종족 33, 세력 40, 장소 30, 몬스터 60, 마법·능력 50, 규칙·역사 40, 중요 아이템 80.
- V62에서 장르 도감 130종을 더해 개별 463종 + 기본 7종 = 470행이다. 최초 V58은 도감 340행·별칭 905개였다. `전체` 이미지는 Front의 분류 탐색용이며 대상 이미지 도감에는 넣지 않는다.
- 이름·별칭의 NFC/소문자/공백·구분자 정규화 검색. 별칭은 이미지 검색용 관련 표현이며 작품 대상의 동일성을 판정하거나 이름을 바꾸지 않는다.
- LLM 프롬프트는 유지한다. V63부터 확정/수정 시 규칙으로 이미지 ID를 저장한다. 기존 대상은 운영자 보정 전에는 장르 기본 그림을 사용한다. [자동 연결과 보정](automatic-subject-images.md)을 따른다.

## 저장 구조

| 테이블 | 역할 |
| --- | --- |
| `world_image_catalog` | 안정적인 문자열 ID, 분류, 표시 이름, 검색 문자열, 기본/활성 여부, 두 이미지 SHA |
| `world_image_aliases` | 도감 ID별 검색 별칭. `(catalog_id, alias)` PK |
| `world_setting_images` | 대상당 최대 1행. 선택 도감 ID, `MANUAL` 출처, 별도 `version`, 수정 시각 |

`world_settings` 삭제 시 선택 행도 FK cascade로 지운다. 기본 이미지 선택은 행 삭제 대신 `catalog_id=null`, `selection_source=DEFAULT`로 저장하고 버전을 올린다. 오래된 버전의 재요청을 거절하기 위해서다. 첫 선택도 대상 행에 비관적 잠금을 걸어 직렬화하며, 새 UUID 선택 Entity는 `Persistable`로 첫 persist를 구분한다.

분류 변경은 같은 대상 잠금 아래 기존 선택의 분류를 확인하고 필요하면 해제한다. 동일 이미지를 다시 저장하면 버전은 그대로다. 이미지 선택은 대상의 `properties`, 대상 `version`/`updated_at`, 분석 실행 상태를 변경하지 않는다. 자동 확정 경로에도 같은 규칙 저장을 적용하며 LLM 이미지 분류 호출은 추가하지 않는다.

## API

| 메서드·경로 | 계약 |
| --- | --- |
| `GET /api/v1/world-image-catalogs` | 인증 필요. `category` 필수, `q` 최대 100자, 0-based `page`, `size` 1–60/기본 18. 활성 상태의 해당 분류 일반 이미지만 `PageResponse.content`에 반환 |
| `PATCH /api/v1/works/{workId}/world-settings/{worldSettingId}/image` | 작품 소유자만 변경. `{catalogId: "location-forest", version: 0}`. `catalogId: null`은 기본 이미지 복귀 |
| `GET /api/v1/world-image-assets/{sha}.webp` | 인증 없이 공용 이미지 bytes 제공. 활성 도감 또는 테마 슬롯에 등록된 64자리 SHA만 허용. 임의 S3 key/원고 파일 접근 불가 |

목록과 상세 응답에 `image: {catalogId, name, thumbnailUrl, imageUrl, source, version}`를 추가했다. 목록은 분류 기본 이미지와 현재 페이지 선택을 각각 batch 조회하므로 카드마다 상세 API나 선택 쿼리를 추가하지 않는다. 검색 aliases는 Hibernate batch fetch를 사용한다.

400: 다른 분류/잘못된 입력, 404: 접근할 수 없는 작품·대상·이미지, 409: `WORLD_IMAGE_VERSION_CONFLICT`. 선택 API는 현재 이미지 버전을 사용하며 세계관 설정 버전과 혼용하지 않는다. Front는 409에서 초안을 보존하고 사용자가 최신 이미지를 확인한 다음 다시 저장한다.

이미지 응답은 상대 API 경로다. Front는 API base URL과 결합한다. 공용 자산 응답은 `image/webp`, SHA ETag, `Cache-Control: public,max-age=31536000,immutable`을 사용한다. 비공개 S3 버킷 설정은 유지하고 Backend의 기존 `ObjectStorage`로 읽는다. CDN/버킷 공개 정책은 추가하지 않는다. 브라우저 캐시 미스는 Backend를 통과하므로 별도 CDN 도입은 후속 성능 검토 대상이다.

## 자산 제작·등록·배포 순서

### 제작 도구와 서버의 역할

Python 파일은 운영자가 필요할 때 직접 실행하는 자산 제작·운영 도구다. Java 빌드·서버 기동·이미지 조회에서는 Python을 실행하지 않는다. 이미지 ID·별칭·SHA를 서버와 같은 저장소에서 관리하여 제작 결과와 DB 등록 SQL을 함께 검토한다.

```text
scripts/world-images/
├── build_catalog.py
├── build_themes.py
├── upload_catalog.py
├── backfill.py
└── manifests/
    ├── catalog-v1.json
    └── themes-v1.json
```

| 파일 | 입력과 역할 | 출력·변경 대상 |
| --- | --- | --- |
| `build_catalog.py` | 승인된 원본과 `ASSET_INDEX.json`을 Pillow로 변환 | WebP, `catalog-v1.json`, V58 seed SQL |
| `build_themes.py` | 기존 catalog manifest와 장르별 `THEME_PLAN.json`·원본을 조합 | 추가 WebP, `themes-v1.json`, V62 seed SQL |
| `upload_catalog.py` | manifest에 등록된 WebP의 SHA·크기를 확인하고 지정 버킷에 업로드 | S3 공용 이미지와 업로드 검증 보고서. DB는 변경하지 않음 |
| `backfill.py` | 작품·대상 종류별 ADMIN API 호출. 기본은 미리보기 | `--apply`일 때 Java가 기존 대상의 자동 이미지 연결을 저장. 직접 SQL이나 LLM은 실행하지 않음 |

JSON 두 개는 원본 경로·별칭·이미지 SHA·크기·테마 관계를 담은 **생성된 제작 자료**다. Python 빌더와 업로드 도구가 사용하며 Java는 읽지 않는다. `scripts/`는 Gradle 기본 리소스 경로 밖이므로 manifest와 Python은 배포 JAR에 포함되지 않는다. 서버는 `src/main/resources/db/migration`의 SQL로 등록한 DB 도감·테마를 조회하고, 이미지 파일은 S3에서 읽는다. 따라서 SQL은 배포 리소스에 유지한다.

실행 명령은 백엔드 저장소 루트 기준이다. 제작·업로드에 사용할 별도 Python 환경에서 의존성을 준비한다. `backfill.py`는 Python 표준 라이브러리만 사용한다.

```sh
python3 -m venv /path/to/world-images-venv
. /path/to/world-images-venv/bin/activate
python3 -m pip install 'Pillow==12.3.0' boto3
```

원본 PNG·인덱스·테마 구성표와 Front 분류 이미지는 상위 workspace에 별도로 필요하다. Java 저장소만으로 원본을 다시 생성할 수는 없다. 업로드는 선택한 AWS 프로필의 자격증명을 사용하며, 기존 대상 보정의 인증·미리보기·적용 절차는 [자동 연결과 보정](automatic-subject-images.md#기존-데이터-보정)을 따른다.

### 최초 도감 (V57/V58)

1. 원본 PNG와 개별 `ASSET_INDEX.json`은 상위 workspace의 `design-assets/world-subjects/...`에 보존한다. 선택된 원본의 경로·SHA, ID·이름·별칭·최종 S3 key는 `scripts/world-images/manifests/catalog-v1.json`에서 확인한다.
2. Python/Pillow 12.3.0으로 승인된 v1 자산을 빌드한다. 480×320/quality78 썸네일, 960×640/quality82 상세 WebP이며 원본은 수정하지 않는다.

```sh
python3 scripts/world-images/build_catalog.py --workspace /path/to/catchhole --output /path/to/artifacts/world-images
```

3. 원하는 AWS 프로필/버킷을 명시해 업로드한다. boto3가 필요하다. metadata만 비교하지 않고 S3에서 실제 bytes를 읽어 SHA와 content type을 검증한다. 기존 key는 덮어쓰지 않으며 신규 업로드도 `IfNoneMatch=*`를 사용한다. 원고·설정 데이터는 업로드하지 않는다.

```sh
AWS_PROFILE=catchhole python3 scripts/world-images/upload_catalog.py --root /path/to/artifacts/world-images --bucket YOUR_BUCKET --region ap-northeast-2
```

4. 해당 환경의 서비스 IAM이 `world-image-catalog/v1/*`를 읽을 수 있는지 확인한 후 Java를 배포해 V57(테이블)·V58(seed)를 적용한다. 다른 버킷을 쓰는 환경은 같은 manifest의 680개 자산을 그 버킷에도 먼저 업로드한다.
5. 새 OpenAPI에서 Front SDK를 생성하고 Front를 배포한다. 정상/없는 SHA, 도감 검색, 선택 저장·새로고침·복귀를 점검한다. 이미지 404/네트워크 오류 시 Front는 번들 분류 기본 이미지로 복귀한다.

SHA로 파일 경로가 정해지므로 이미지 교체는 새 자산·새 SHA와 새 migration으로 한다. 적용된 V58은 수정하지 않는다. 빌더는 기존 seed와 결과가 달라지면 덮어쓰기를 거절한다. 배포 롤백 시 테이블·자산을 삭제할 필요는 없다. 이전 Front/API는 추가 필드·테이블을 사용하지 않는다.

## 2026-09-15 검증

- 기존 `catchhole` AWS 프로필의 계정과 기존 개발 버킷 소유 관계를 `ExpectedBucketOwner`로 검증. WebP 680개 신규 업로드 후 전체 bytes/SHA 대조 완료: 51,147,688 bytes. 버킷 정책/ACL 변경 없음.
- 로컬 PostgreSQL `catchhole_gh194`에서 V57·V58 적용 및 Hibernate schema validate 기동 성공. 도감 340행, 기본 7행, 별칭 905행.
- Java 전체 테스트 1,162개 중 1,097 통과, 기존 조건부 65개 건너뜀. 이미지 통합 테스트: 첫 선택/목록·상세/해제/중복 저장/오래된 버전/분류 변경/별칭/권한/공용 자산 허용 범위.
- Front live E2E는 기존 인증 계정으로 테스트 작품을 만들고 실제 API에 저장한 뒤 재조회·새로고침·충돌·복귀 및 320px 화면을 확인하며, 테스트 작품은 정리한다.
- 로컬은 기존 원고·워커와 함께 파일 저장소를 사용한다. 공용 자산을 같은 key로 복사해 동일한 API를 검증했다. 개발 S3 업로드는 완료했으며 운영 DB migration·배포는 이 작업에서 실행하지 않았다.

## 2026-09-16 장르 테마 확장 (V61/V62)

`world_image_theme_assets`: id PK, theme, purpose(OVERVIEW/DEFAULT), slot(분류 enum/ALL), name, thumbnail_sha/image_sha, created_at/updated_at. `(theme,purpose,slot)` unique, DEFAULT에는 ALL 금지. 7개 테마 × (초기 8칸 + 기본 7칸) = 105행이다.

`world_image_recommendations`: catalog_id FK + theme 복합 PK, theme 인덱스. 동일 ID/자산을 여러 테마가 참조한다. 판타지 333, 현대 공통 50, 무협 70, SF 70, 추리 61, 호러 80, 스포츠 56 = 720행. 테마별 선택 목록은 중복 ID를 만들지 않는다. 현대 공통은 로맨스·코미디·일상·기타가 공유한다.

- `GET /api/v1/works/{workId}/world-image-theme`: 소유자에게 `{theme,overview,defaults}`를 반환한다. overview 키는 7분류+ALL, defaults는 7분류다.
- `GET /api/v1/world-image-catalogs`: optional `workId`, `recommended=false` 추가. true는 workId 필수(400), 전달된 workId는 항상 소유권 검증(404). false/생략은 기존 분류 전체 검색을 유지한다. 수동 저장 시에는 장르 제한을 적용하지 않는다.
- 조회 우선순위: 개인 선택 → 같은 분류의 유효한 수동 도감 → 현재 작품 장르 기본 → 구형 분류 기본. 테마 기본 응답은 catalogId=null, source=DEFAULT다. 잠금은 개인 이미지 응답을 받은 Front에서 처리한다.
- 작품 장르 변경은 자동 상태의 세계관 이미지 결과만 다시 저장한다. 미일치에는 현재 장르 기본 그림을 표시하며, 수동·개인·기본 고정과 해당 선택의 image version은 보존한다. 설정 내용/version·추출 프롬프트는 변경하지 않는다.
- V62는 초기 카드 이미지와 혼용되던 기본 7행의 SHA를 승인된 별도 중립 기본으로 바꾼다. 대상 선택 행이나 V57~V60을 수정하지 않는다. 추가 장르 그림은 130개 도감 행으로 등록한다.

### 빌드·업로드

기존 자산은 `build_catalog.py`로 만든 바이트를 그대로 보존한다. 아래 두 빌더를 **같은 output**으로 실행하면 전체 자산을 모을 수 있다. 원본 PNG와 테마 구성표는 상위 workspace에 필요하다. Pillow 12.3.0 기준이며 적용된 V62와 seed가 달라지면 덮어쓰기를 거부한다. 이후 변경은 새 manifest/migration으로 추가한다.

```sh
python3 scripts/world-images/build_catalog.py --workspace /path/to/catchhole --output /path/to/assets
python3 scripts/world-images/build_themes.py --workspace /path/to/catchhole --output /path/to/assets
AWS_PROFILE=catchhole python3 scripts/world-images/upload_catalog.py --manifest scripts/world-images/manifests/themes-v1.json --root /path/to/assets --bucket YOUR_BUCKET
```

새 manifest는 전체 catalog + theme 슬롯과 추천 관계를 담는다. 자산 key는 SHA 기반이며 용도·테마 간 동일 파일은 중복 업로드하지 않는다. 업로드 도구는 기존 객체를 덮어쓰지 않고 1,010개 고유 WebP의 실제 바이트를 검증한다. 다른 환경의 버킷에도 **자산 → Java V61/V62/API → Front** 순서로 배포한다.

### 검증

- 개발 버킷 소유권 확인 후 신규 330개 추가, 전체 1,010개/70,140,720 bytes SHA·content type 확인. ACL/버킷 정책 유지.
- 빈 PostgreSQL V1~V62, Hibernate validate, health UP. 로컬 기존 DB V62 적용 후 470/105/720행 확인.
- Java 전체 1,174개: 1,109 통과·조건부 65 건너뜀. 장르 기본/수동 보존·추천 공유/별칭·소유권·공용 SHA 허용 범위와 개인 선택 보존 통합 테스트 통과.
- 실제 Front→Java→PostgreSQL에서 10장르/7테마, 초기 8칸/기본 7칸, 공용 숲 동일 자산, 타 장르 객잔 수동 선택·장르 변경 보존·기본 복귀 검증. 테스트 작품 정리.
- 운영 DB/서비스 배포는 수행하지 않았다.

## V63 저장 시 자동 이미지 연결

캐릭터와 세계관의 자동 이미지를 확정/수정 시 저장하고 조회 재매칭을 제거했다. AUTO/직접 선택/기본 고정을 분리하며 [운영자 보정 배치](automatic-subject-images.md)는 배포 후 별도 실행한다. 이전 검증의 조회 시 연결·미선택 유지 설명은 V62까지의 기록이다.
