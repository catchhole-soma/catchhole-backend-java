# 분석 목록 요약 조회 코드 리뷰

검토일: 2026-10-24. 검토자: GPT-6 Astra. 최종 제품 기준: Backend `184ff78`, Frontend `e6ccfd7`. 코드와 기존 실행 증거를 읽어 검토했으며 운영 접근이나 추가 부하 테스트는 수행하지 않았다.

## 검토 범위와 결론

분석 목록의 DB scalar 집계, 최신 작업 선택, 회차·설정집 출처 분리, 설정집 후보 집계와 프런트 카드·이동·polling·캐시 갱신을 검토했다. 최신 작업의 시각/UUID 정렬, legacy 복수 대상과 distinct 이동 ID, targetless fallback, ordered 실패·차단 대기·무효화·자동 반영 조건을 기존 구현 및 회귀 테스트와 대조했다. 설정집 최신 원본의 실제 batch/revision 이동과 원본별 후보 집계도 확인했다. 큰 분석 JSON과 상세 엔티티를 목록에서 읽지 않는 쿼리 구조를 확인했다.

**최종 제품 기능 리뷰 통과. 미해결 기능 결함은 없다.** 세계관 후보 검토 조회 최적화(#272)는 이번 검토 범위에 포함하지 않았다.

## 발견 사항과 수정 확인

- 최초로 표시한 설정집이 이미 완료 상태이면 설정 캐시를 무효화하지 않는 회귀를 발견했다. 기존 상세 hook은 최초 완료 확인에도 갱신했으나 새 목록은 진행→완료 전이만 처리했다. 완료 원본별 Set으로 최초 완료와 전이를 모두 처리하도록 수정했으며 RED→GREEN 증거를 확인했다.
- 완료 캐시 동기화가 방금 받은 현재 목록까지 다시 요청하는 중복을 마지막으로 제거했다. `invalidateAppliedSettings`의 선택적 `excludeQueryKey`는 `exact: true`로 찾은 현재 Query 객체만 제외한다. 다른 목록 페이지·캐릭터·설정집 캐시는 무효화되며 옵션을 사용하지 않는 기존 호출자의 동작은 유지된다.
- nullable `episodeStatus`와 `failureCode`는 기존 Swagger helper에 등록하여 실제 OpenAPI가 `anyOf(enum, null)`을 제공한다. 재생성 SDK에 맞춰 프런트는 `episodeStatus`/`episodeJobGroups`를 직접 사용한다.

## 직접 확인한 검증 증거

증거 경로는 작업 산출물 루트 `output/analysis-list-optimization-20261024/` 기준이다.

| 증거 | 확인 결과 |
| --- | --- |
| `evidence/focused-tests/*.xml` | PostgreSQL 29개와 API 7개 실행, 실패·오류·건너뜀 0. 설정집 상태·혼합 출처·원본별 후보·리비전·페이지 범위 포함 |
| `evidence/full-tests/summary.json`, `runtime/backend-full-test.log` | suite 수치를 독립 합산: 총 3,792개, 실행 3,556개, 건너뜀 236개, 실패·오류 0. BUILD SUCCESSFUL |
| Backend 최종 OpenAPI/API XML, `runtime/backend-openapi-green.log`, `runtime/implementation-openapi.json` | nullable 수정 뒤 OpenAPI 1개와 API 7개 통과, 실제 enum/null union 확인 |
| `runtime/frontend-focused-final.log` | nullable SDK 반영 뒤 관련 E2E 107개 통과 |
| `runtime/frontend-current-list-green.log` 및 해당 테스트 소스 | 마지막 중복 제거 뒤 16개 통과. 최초 완료 요청 1회, 진행→완료는 최초+polling 2회. 실제 QueryClient에서 다른 페이지·캐릭터·설정집 invalidated=true, 현재 페이지=false 확인 |
| `runtime/frontend-typecheck.log`, `runtime/frontend-lint.log`, `runtime/frontend-build-after.log` | typecheck 및 build 성공, lint 오류 0·기존 경고 2개 |
| `evidence/frontend-live/result.json`, `runtime/frontend-live-smoke.mjs`, `runtime/frontend-live-smoke.log` | 실제 로컬 인증으로 설정집·혼합 목록 2건 성공. 최종 최초 batches 요청 각각 1회, 목록 상세/전체 데이터 요청 없음. 검토·이력·복귀, frozen revision GET 200, 혼합 출처별 진행 이동과 응답 200, 다른 작품 Job/revision 접근 404 확인 |

전체 테스트의 236개 건너뜀을 성공 실행으로 계산하지 않았다. 마지막 프런트 수정 뒤에는 영향 범위 16개와 실제 인증 2건을 재검증했으며, 이전 107개 전체를 다시 실행했다고 주장하지 않는다.

## 성능 검증과의 경계

이 문서는 제품 기능과 실행 증거의 코드 리뷰다. 개선 전후 지연·메모리·요청량 비교, 동일 fixture/런타임 검증 및 실제 PostgreSQL SQL/EXPLAIN 수집의 완결성은 별도 baseline/after 검증이 담당한다. 이 리뷰만으로 성능 개선율이나 운영 OOM 해소를 판정하지 않는다. 실제 인증 클릭 검증은 로컬 합성 데이터 기준이며 운영 검증을 대신하지 않는다.
