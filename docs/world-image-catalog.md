# 세계관 대표 이미지 도감 — GH194

세계관 대상은 처음에는 분류 기본 이미지를 표시한다. 작가는 같은 분류의 공용 도감에서 이름·별칭으로 검색해 이미지를 선택하거나 기본 이미지로 되돌릴 수 있다. 이미지 선택은 설정 내용과 별도로 저장한다. 작품의 실제 외형을 확정하는 정보가 아니며 Front에는 `공용 예시 이미지`로 표시한다.

## 현재 범위

- 공용 대표 이미지 333종: 종족 33, 세력 40, 장소 30, 몬스터 60, 마법·능력 50, 규칙·역사 40, 중요 아이템 80.
- 분류 기본 이미지 7종을 포함해 도감 340행, 검색 별칭 905개. `전체` 이미지는 Front의 분류 탐색용이며 대상 이미지 도감에는 넣지 않는다.
- 이름·별칭의 NFC/소문자/공백·구분자 정규화 검색. 별칭은 이미지 검색용 관련 표현이며 작품 대상의 동일성을 판정하거나 이름을 바꾸지 않는다.
- LLM 추출·확정 프롬프트, 자동 이미지 유형 선택, 기존 대상의 자동 일괄 매칭은 포함하지 않는다. 기존 대상은 데이터 수정 없이 분류 기본 이미지로 표시된다.

## 저장 구조

| 테이블 | 역할 |
| --- | --- |
| `world_image_catalog` | 안정적인 문자열 ID, 분류, 표시 이름, 검색 문자열, 기본/활성 여부, 두 이미지 SHA |
| `world_image_aliases` | 도감 ID별 검색 별칭. `(catalog_id, alias)` PK |
| `world_setting_images` | 대상당 최대 1행. 선택 도감 ID, `MANUAL` 출처, 별도 `version`, 수정 시각 |

`world_settings` 삭제 시 선택 행도 FK cascade로 지운다. 선택 해제는 행 삭제 대신 `catalog_id`/출처를 null로 바꾸고 버전을 올린다. 오래된 버전의 재요청을 거절하기 위해서다. 첫 선택도 대상 행에 비관적 잠금을 걸어 직렬화하며, 새 UUID 선택 Entity는 `Persistable`로 첫 persist를 구분한다.

분류 변경은 같은 대상 잠금 아래 기존 선택의 분류를 확인하고 필요하면 해제한다. 동일 이미지를 다시 저장하면 버전은 그대로다. 이미지 선택은 대상의 `properties`, 대상 `version`/`updated_at`, 분석 실행 상태를 변경하지 않는다. 자동 확정 경로에도 이미지 분류 호출을 추가하지 않는다.

## API

| 메서드·경로 | 계약 |
| --- | --- |
| `GET /api/v1/world-image-catalog` | 인증 필요. `category` 필수, `q` 최대 100자, 0-based `page`, `size` 1–60/기본 18. 활성 상태의 해당 분류 일반 이미지만 `PageResponse.content`에 반환 |
| `PATCH /api/v1/works/{workId}/world-settings/{worldSettingId}/image` | 작품 소유자만 변경. `{catalogId: "location-forest", version: 0}`. `catalogId: null`은 기본 이미지 복귀 |
| `GET /api/v1/world-image-assets/{sha}.webp` | 인증 없이 공용 이미지 bytes 제공. 활성 도감에 등록된 64자리 SHA만 허용. 임의 S3 key/원고 파일 접근 불가 |

목록과 상세 응답에 `image: {catalogId, name, thumbnailUrl, imageUrl, source, version}`를 추가했다. 목록은 분류 기본 이미지와 현재 페이지 선택을 각각 batch 조회하므로 카드마다 상세 API나 선택 쿼리를 추가하지 않는다. 검색 aliases는 Hibernate batch fetch를 사용한다.

400: 다른 분류/잘못된 입력, 404: 접근할 수 없는 작품·대상·이미지, 409: `WORLD_IMAGE_VERSION_CONFLICT`. 선택 API는 현재 이미지 버전을 사용하며 세계관 설정 버전과 혼용하지 않는다. Front는 409에서 초안을 보존하고 사용자가 최신 이미지를 확인한 다음 다시 저장한다.

이미지 응답은 상대 API 경로다. Front는 API base URL과 결합한다. 공용 자산 응답은 `image/webp`, SHA ETag, `Cache-Control: public,max-age=31536000,immutable`을 사용한다. 비공개 S3 버킷 설정은 유지하고 Backend의 기존 `ObjectStorage`로 읽는다. CDN/버킷 공개 정책은 추가하지 않는다. 브라우저 캐시 미스는 Backend를 통과하므로 별도 CDN 도입은 후속 성능 검토 대상이다.

## 자산 제작·등록·배포 순서

1. 원본 PNG와 개별 `ASSET_INDEX.json`은 상위 workspace의 `design-assets/world-subjects/...`에 보존한다. 선택된 원본의 경로·SHA, ID·이름·별칭·최종 S3 key는 `src/main/resources/world-images/catalog-v1.json`에서 확인한다.
2. Python/Pillow 12.3.0으로 승인된 v1 자산을 빌드한다. 480×320/quality78 썸네일, 960×640/quality82 상세 WebP이며 원본은 수정하지 않는다.

```sh
python scripts/world-images/build_catalog.py --workspace /path/to/catchhole --output /path/to/artifacts/world-images
```

3. 원하는 AWS 프로필/버킷을 명시해 업로드한다. boto3가 필요하다. metadata만 비교하지 않고 S3에서 실제 bytes를 읽어 SHA와 content type을 검증한다. 기존 key는 덮어쓰지 않으며 신규 업로드도 `IfNoneMatch=*`를 사용한다. 원고·설정 데이터는 업로드하지 않는다.

```sh
AWS_PROFILE=catchhole python scripts/world-images/upload_catalog.py --root /path/to/artifacts/world-images --bucket YOUR_BUCKET --region ap-northeast-2
```

4. 해당 환경의 서비스 IAM이 `world-image-catalog/v1/*`를 읽을 수 있는지 확인한 후 Java를 배포해 V55(테이블)·V56(seed)를 적용한다. 다른 버킷을 쓰는 환경은 같은 manifest의 680개 자산을 그 버킷에도 먼저 업로드한다.
5. 새 OpenAPI에서 Front SDK를 생성하고 Front를 배포한다. 정상/없는 SHA, 도감 검색, 선택 저장·새로고침·복귀를 점검한다. 이미지 404/네트워크 오류 시 Front는 번들 분류 기본 이미지로 복귀한다.

SHA로 파일 경로가 정해지므로 이미지 교체는 새 자산·새 SHA와 새 migration으로 한다. 적용된 V56은 수정하지 않는다. 빌더는 기존 seed와 결과가 달라지면 덮어쓰기를 거절한다. 배포 롤백 시 테이블·자산을 삭제할 필요는 없다. 이전 Front/API는 추가 필드·테이블을 사용하지 않는다.

## 2026-09-15 검증

- 기존 `catchhole` AWS 프로필의 계정과 기존 개발 버킷 소유 관계를 `ExpectedBucketOwner`로 검증. WebP 680개 신규 업로드 후 전체 bytes/SHA 대조 완료: 51,147,688 bytes. 버킷 정책/ACL 변경 없음.
- 로컬 PostgreSQL `catchhole_gh194`에서 V55·V56 적용 및 Hibernate schema validate 기동 성공. 도감 340행, 기본 7행, 별칭 905행.
- Java 전체 테스트 1,162개 중 1,097 통과, 기존 조건부 65개 건너뜀. 이미지 통합 테스트: 첫 선택/목록·상세/해제/중복 저장/오래된 버전/분류 변경/별칭/권한/공용 자산 허용 범위.
- Front live E2E는 기존 인증 계정으로 테스트 작품을 만들고 실제 API에 저장한 뒤 재조회·새로고침·충돌·복귀 및 320px 화면을 확인하며, 테스트 작품은 정리한다.
- 로컬은 기존 원고·워커와 함께 파일 저장소를 사용한다. 공용 자산을 같은 key로 복사해 동일한 API를 검증했다. 개발 S3 업로드는 완료했으며 운영 DB migration·배포는 이 작업에서 실행하지 않았다.
