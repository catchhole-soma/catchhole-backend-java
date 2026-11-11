# 제품 사용 지표

제품 사용 지표는 **결과가 준비된 설정 추출 요청**과 작품 생성 수를 PostgreSQL에 누적하고, 기존 Prometheus → Grafana 경로로 표시한다. 작품 삭제·회원 탈퇴 후에도 날짜·월별 합계는 유지한다. [현재 상태·요청 기준 서비스 지표](service-usage-metrics.md), GA4의 브라우저 방문·전환 지표와 목적이 다르다.

## 화면과 계산 기준

운영 현황(`catlfln`)의 제품 사용 영역과 제품 사용 상세(`catchhole-product-usage`)를 사용한다. 상세 화면의 시간대는 `Asia/Seoul`이다. 아래 기간은 한국 날짜 기준으로 고정되며 Grafana 시간 선택으로 바뀌지 않는다.

| 항목 | 집계 기준 |
| --- | --- |
| 오늘 분석한 회원 | 오늘 00:00 이후 완료한 서로 다른 회원 |
| 최근 7일·30일 분석한 회원 | 오늘을 포함한 7개·30개 날짜에 완료한 서로 다른 회원 |
| 이번 달 분석한 회원 | 이번 달 1일 00:00 이후 완료한 서로 다른 회원 |
| 완료 요청 수 | 한 요청의 모든 대상 결과가 준비되면 1건. 오늘·이번 달 표시 |
| 확정 반복 요청 수 | 회원의 첫 완료 이후 추가로 완료한 요청. 다른 작품 분석도 포함 |
| 과거 이력 부족 | 수집 전 가입한 회원에게 과거 완료 증거가 없어 첫 관측을 분류할 수 없는 요청 |
| 새로 만든 작품 | 수집 시작 이후 생성에 성공한 작품. 현재 보유 작품 수와 별도 |

대상은 회차·설정집 `SETTING_EXTRACTION`이다. 검수·내부 후보 비교·채팅은 제외한다. 여러 회차를 한 번에 요청해도 완료 요청은 1건이다. 실패·취소는 완료가 아니고, 동일 요청의 재시도·재관측으로 수치가 늘지 않는다. 성공·부분 성공의 준비 시각을 사용하며 ordered 실행은 SEALED, 자동 검토는 자동 반영 완료까지 확인한다.

기존 회원의 첫 관측을 근거 없이 첫 사용으로 단정하지 않는다. 과거 완료가 확인되면 새 완료를 반복으로 분류하고, 확인되지 않으면 첫 관측은 이력 부족, 그 이후는 반복이다. 늦게 발견한 더 이른 완료는 기존 첫 완료의 분류와 날짜·월 합계를 보정한다. 확정 반복 수와 이력 부족 수를 따로 표시하며 완전한 반복 사용률로 해석하지 않는다.

## 보존과 수집 범위

수집 시작 시각은 DB에 한 번 기록한다. **이 시각 이후 최초 접수한 요청**만 완료 합계에 포함한다. 기존 요청은 가능한 범위에서 과거 완료 판정에만 사용한다. 작품 생성은 시작 이후 생성분부터 기록한다. 과거 전체 사용량을 소급 복원하지 않는다.

일별·월별 합계에는 날짜와 숫자만 남긴다. 회원별 최초 완료 요약·활동 날짜는 중복 제거에 사용하며 탈퇴 때 모두 지운다. 작품별 처리 기록은 작품 파기 때 지운다. 원문·이메일·이름·분석 결과를 집계 테이블에 복사하지 않고 회원·작품·요청 ID를 Prometheus label이나 GA로 보내지 않는다.

회원 수는 일별 수치를 더하지 않는다. 활동 날짜가 각 1일·7일·30일 창과 달에 새로 들어갈 때만 합계를 올린다. 활동일로부터 29일 뒤까지의 창 합계를 미리 저장하되 미래 날짜는 노출하지 않는다. 이로써 회원별 기록을 삭제한 뒤에도 합계를 유지할 수 있다.

합계는 DB에 보존하고 현재 화면은 최근 **60개 날짜·12개 달**을 제공한다. 이 범위를 벗어나도 DB 합계는 지우지 않는다. Prometheus의 60일 보존과 별개로 월별 합계를 다시 읽을 수 있다. 수집 시작 이전은 0으로 만들지 않으며 첫날·첫달·아직 충분히 지나지 않은 창은 `수집 시작 이후`로 표시한다.

## 수집과 장애 처리

`ProductUsageService`가 60초마다 미처리 요청·작품 생성 기록을 직렬화된 트랜잭션으로 합산한다. Work 생성 경로는 최소 생성 기록만 같은 트랜잭션에 저장한다. 원문 무효화·작품 파기 직전에는 준비 상태를 최종 확인하고 정산한다. 준비 판정·정산·원본 변경/파기는 같은 트랜잭션이며 실패하면 기존 파기 재시도 흐름으로 돌아간다.

잠금은 기존 Member → Work → Job → 집계 상태 순서다. polling은 사업 행을 잠그지 않는다. 집계 테이블의 사업 ID에는 FK를 두지 않아 집계 잠금에서 사업 행으로 역행하지 않는다.

읽기 snapshot은 DB의 일관된 상태를 읽어 원자적 메모리 캐시에 넣는다. scrape는 DB를 조회하지 않는다. 집계 실패·조회 실패 시 success=0이고 마지막 성공 시각은 갱신하지 않는다. Grafana는 성공했고 180초 미만인 최신 인스턴스의 값만 선택한다. 여러 API의 값을 합산하거나 가장 큰 값을 고르지 않는다. 수집 전·실패·정체는 `관측 없음`, 정상 수집된 빈 기간은 0이다.

| 메트릭 | label / 의미 |
| --- | --- |
| `catchhole_product_analysis_members` | `period=1d,7d,30d,month` |
| `catchhole_product_completed_requests`, `repeat_requests`, `unknown_requests`, `work_creations` | 각 이름에 `catchhole_product_` 접두사, `period=1d,month` |
| `catchhole_product_period_complete` | 각 기간 전체를 수집했으면 1, 시작 이후 일부면 0 |
| `catchhole_product_daily_*` | 최근 60개 날짜 `date=YYYY-MM-DD`의 회원·완료·반복·이력 부족·생성·범위 상태 |
| `catchhole_product_monthly_*` | 최근 12개 달 `month=YYYY-MM`의 합계·범위 상태 |
| `catchhole_product_usage_snapshot_success` | 마지막 집계 및 snapshot 조회 성공 여부 |
| `catchhole_product_usage_snapshot_last_success_timestamp_seconds` | DB에 기록한 마지막 집계 시각 |
| `catchhole_product_usage_collection_started_timestamp_seconds` | 최초 수집 시작 시각 |

메트릭의 작품 생성 이름은 `work_creations`를 사용한다. Micrometer의 Prometheus 출력에서 예약 접미사 `_created`가 제거되므로 `works_created`를 사용하지 않는다. 두 Grafana JSON에 쓰인 모든 제품 지표 이름을 실제 exporter 출력과 대조하는 테스트를 유지한다. [Micrometer 접미사 규칙](https://github.com/micrometer-metrics/micrometer/wiki/1.13-Migration-Guide#invalid-meter-suffixes)을 따른다.

집계 cutoff는 PostgreSQL `timestamp(6)`과 같은 마이크로초로 내림해 저장한다. 나노초 반올림으로 정상 집계가 읽기 시각보다 미래로 판정되는 것을 막으며, 실제 미래 snapshot을 숨기는 검사는 유지한다.

## 배포와 활성화

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `PRODUCT_USAGE_METRICS_ENABLED` | `false` | 시작 시각 초기화와 정기 수집 활성화 |
| `PRODUCT_USAGE_METRICS_FIXED_DELAY_MS` | `60000` | 이전 수집 종료 후 대기 시간 |

V124·V125 migration은 시작 시각을 확정하지 않는다. 기능을 켠 새 API가 요청 수락 전 bootstrap에서 초기화하며 재시작 때 기존 시각을 유지한다.

1. 변경분의 CI·마이그레이션 검증 후 새 이미지로 모든 API writer를 교체한다. 최초 배포는 기본값 false로 둔다.
2. 이전 코드의 API writer가 더 이상 요청을 받지 않는지 확인한다. 여러 인스턴스의 rolling 배포에서는 이 확인 전 활성화하지 않는다.
3. 기존 `.api-deploy.lock`을 사용해 운영 `api.env`에 `PRODUCT_USAGE_METRICS_ENABLED=true`를 반영하고 새 API 컨테이너를 재생성한다. 단일 API의 Compose 교체는 이전 컨테이너가 정지한 뒤 새 컨테이너가 시작하는 순서를 확인한다. 설정 파일 전체나 비밀값을 출력하지 않는다.
4. API health, 수집 시작 시각 고정, snapshot success=1과 신선도, Prometheus target 정상 여부를 확인한다. 서버 재시작 뒤 시작 시각·합계가 유지되어야 한다.
5. 운영 Grafana JSON을 먼저 export해 보관한 뒤 제품 사용 상세를 가져오고 기존 UID의 운영 현황을 갱신한다. [대시보드 가져오기 지침](../deploy/monitoring/grafana/dashboards/README.md)을 따른다. 실제 Grafana에서 기간별 카드·일별 차트·월별 표·관측 없음 표시를 확인한다.

시작 후 enabled=false는 정기 수집을 멈출 뿐 이미 시작한 기록을 끄지 않는다. Work 생성 기록과 파기 전 정산은 DB의 시작 시각을 기준으로 계속 동작해 중단 중 누락을 막는다. 활성화 이후 옛 코드로 되돌리면 기록이 빠질 수 있으므로 구버전 writer를 재투입하지 않는다. 문제가 생기면 새 버전의 수집만 일시 중단하고 원인을 수정한다. 누락 구간을 완전한 통계로 표시하거나 시작 시각을 임의로 다시 쓰지 않는다.

## 검증

`ProductUsage*Test`는 PostgreSQL migration/JPA, 요청 전체 준비·재시도·중복, 날짜/월 경계와 지연 도착, 삭제·탈퇴 보존, 수집/파기 경합과 rollback, activation, bounded Gauge와 실제 Prometheus 형식을 검증한다. 대시보드 쿼리는 `deploy/monitoring/tests/dashboard-promql.test.json`과 promtool로 실패·정체·다중 인스턴스·관측 없음/0·기간 label을 검증한다. 설계 판단의 상세 근거는 [승인한 설계](superpowers/specs/2026-11-11-product-usage-metrics-design.md)를 따른다.
