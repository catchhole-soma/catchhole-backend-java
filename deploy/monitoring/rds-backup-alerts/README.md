# RDS 자원·백업 알림 규칙 생성

`generate.py`는 Grafana 13.2.2 HTTP API용 규칙 JSON과 조회식을 로컬에 생성한다. AWS·Grafana·Discord에 접속하지 않으며 webhook 비밀값을 받지 않는다. 기존 Grafana contact point **이름**을 지정한다. 생성 결과는 기본적으로 모두 paused다. 기본은 기존 LRT 3개이며 `--include-extended`를 지정하면 metadata·자동 스냅샷·자원·각 수집 상태를 포함한 **총 14개**를 생성한다.

2026-10-26 기존 3개는 운영 활성화·모의 입력 15단계·실제 Discord 발생/복귀 수신을 확인했다. **추가 11개는 구현·검토 단계이며 이 기록만으로 운영 적용이나 검증 완료를 주장하지 않는다.** 최신 완료 증거는 [운영 문서](../../RDS_BACKUP_MONITORING.md)의 점검표를 따른다.

```sh
python3 deploy/monitoring/rds-backup-alerts/generate.py \
  --db-instance-identifier catchhole-prod-postgres \
  --region ap-northeast-2 \
  --folder-uid REVIEWED_FOLDER_UID \
  --contact-point catchhole-infra-discord \
  --include-extended --max-connections 181 --reserved-connections 5 \
  --output-dir deploy/monitoring/rds-backup-alerts/generated
```

`generated/rule-group.json`은 `PUT /api/v1/provisioning/folder/{folderUID}/rule-groups/catchhole-rds-backup` 본문이고, file provisioning용 JSON이 아니다. 복귀 대기는 HTTP API 필드 `keep_firing_for: 0s`로 지정한다. 기존 폴더와 contact point가 있어야 한다. 이 경로는 전용 그룹 전체를 갱신하므로 적용 전 기존 그룹을 조회·보관하고 다른 규칙이 섞여 있지 않은지 확인한다. 이 생성기는 DB 하나만 대상으로 한다. 적용 자동화·권한·실제 채널 연결은 포함하지 않는다. `--reserved-connections`는 실제 `reserved_connections + superuser_reserved_connections` 합계이며 현재 2+3=5다. DB 설정 변경 시 다시 생성해야 한다. **기존 3개가 활성인 그룹을 모두 paused인 출력으로 덮어쓰지 않는다.** 기존 UID·활성 상태를 보존한 검토본을 적용하거나 검증 후 `--activate`를 명시한다.

## 적용할 규칙과 전송 기준

| 규칙 | 조건 | 지속 시간 | 데이터 없음 / 쿼리 오류 |
|---|---|---|---|
| 복원 가능 시각 주의 | 정상 수집 상태에서 지연 > 600초가 연속 2분 | PromQL 이력 확인, Grafana `for=0s` | 이전 상태 유지 |
| 복원 가능 시각 긴급 | 지연 > 900초 | 추가 대기 없음 | 이전 상태 유지 |
| LRT 수집 상태 | LRT 건강 판정 0 또는 수집 자료 누락 | 2분 | Alerting |
| 자동 백업 비활성 | 보존 0일 | 없음 | 이전 상태 유지 |
| 보존 정책 미달 | 보존 1~6일 | 없음 | 이전 상태 유지 |
| 보존 정책 초과 | 보존 >7일, info | 없음 | 이전 상태 유지 |
| metadata 수집 상태 | API/metadata 유효성·관측 120초 조건 실패 | 2분 | Alerting |
| 일일 스냅샷 완료 확인 지연 | 최근 마감 백업 창 시작 이후 생성된 available 스냅샷 없음 | 창 종료+1시간 마감, 추가 대기 없음 | 이전 상태 유지 |
| 최신 자동 스냅샷 실패 | 프로세스가 관측한 최신 완료 상태 available/failed 중 failed | 없음 | 이전 상태 유지 |
| 스냅샷 수집 상태 | 전체 페이지 유효성·관측 900초 조건 실패 | 2분 | Alerting |
| CPU 압박 | 1분 Average >80% | PromQL 연속 5분, Grafana `for=0s` | 이전 상태 유지 |
| 공간 부족 | 1분 Minimum <max(할당량×20%, 5GiB), 현재 <6GiB | PromQL 연속 5분, Grafana `for=0s` | 이전 상태 유지 |
| 연결 포화 접근 | 1분 Maximum ≥(181−5)×80%=140.8, 정수 ≥141 | PromQL 연속 5분, Grafana `for=0s` | 이전 상태 유지 |
| 자원 수집 상태 | CPU/공간/연결 중 하나의 조회·원천 최신성 조건 실패 | 2분 | Alerting |

그룹 평가는 60초 간격이다. exporter의 AWS 조회 60초, Prometheus scrape 15초와는 별도 주기다. 목표 초과와 실제 디스코드 도착 사이에는 이 주기들과 전송 시간이 추가된다. 최초 알림 그룹 대기는 0초지만 `for: 0s`가 디스코드 즉시 도착을 보장하지는 않는다.

LRT 수집 정상 판정에는 `up=1`, API 성공, 유효한 LRT, 마지막 유효 관측이 120초 이내, 미래 시각이 아님을 모두 요구한다. 이 조건을 만족하지 않으면 지연을 0으로 바꾸지 않고 결과 자체를 없앤다. 주의·긴급 규칙은 KeepLast로 이전 상태를 유지하고, 별도 수집 상태 규칙이 관측 불가능을 알린다. Prometheus 쿼리 오류도 수집 상태 규칙에서 Alerting으로 처리한다. Grafana·모니터링 서버 전체 중단은 이 구성만으로 알릴 수 없다.

주의의 2분 지속 판정은 Grafana Pending 대신 PromQL에서 수행한다. Grafana 13.2.2의 KeepLast는 이미 Pending인 규칙에서 시간이 경과하면 NoData 중에도 Alerting으로 바꿀 수 있기 때문이다. PromQL 이력에서는 수집 실패·누락을 0으로 취급하여 연속 조건을 끊는다. 15초 해상도의 왼쪽 열린 `2m15s` 구간에 최소 2분을 가로지르는 9개 지점이 모두 정상·초과여야 한다. 첫 초과 후 1분 45초에는 알리지 않고 정확히 2분 이상 관측한 뒤 조건을 만족한다. subquery 정렬과 그룹 평가 시점에 따른 추가 지연은 있을 수 있다.

현재 값이 유효하고 600초 이하이면 복귀 판단용 값을 반환한다. 현재 값이 없거나, 높은 값이 돌아왔지만 연속 이력이 아직 부족하면 최종 결과를 비워 이전 상태를 유지한다. 따라서 최초 한 번의 초과 뒤 조회가 실패한 상황에서 새 주의 알림을 만들지 않고, 이미 주의가 발생한 상태에서 수집이 끊겼다가 높은 값으로 돌아온 상황도 성급한 정상 복귀로 바꾸지 않는다. PromQL 이력의 0은 이 조건 판정에만 쓰이며 최종 지연값 0으로 노출하지 않는다.

확장 규칙도 원천 신뢰 조건을 먼저 검사한다. metadata는 API 성공·`metadata_valid=1`·유효 관측 120초와 보존/할당/백업 창 범위를 요구하고, LRT가 없어도 독립적으로 보존 0일을 판정한다. 스냅샷은 전체 페이지 조회 성공·`valid=1`·유효 관측 900초를 요구한다. 자원은 **지표마다** API 성공·valid·유효 조회 나이 120초·CloudWatch 원천 나이 300초를 모두 요구하고, 공간 규칙은 할당량 metadata도 요구한다. API 200과 신선한 데이터 확보를 같은 것으로 취급하지 않는다.

자원 5분은 15초 해상도의 왼쪽 열린 `[5m15s:15s]` 구간 21개 지점으로 검사한다. 누락은 연속 조건을 끊고, 높은 값 재수집 직후 이력이 부족하면 결과를 비워 기존 경보를 유지한다. 현재 유효값이 정상 범위로 돌아오면 복귀값을 반환한다. 15초 scrape가 15초마다 새로운 CloudWatch 표본을 뜻하지는 않는다. 분 경계 이전의 최신 1분 통계값을 반복 관측하며 평가 시 원천 나이를 별도로 제한한다.

일일 스냅샷 계산은 UTC 백업 창과 종료 후 1시간 여유로 **가장 최근 마감된 창**을 선택한다. 현재 KST 01:35~02:05 창의 마감은 03:05다. available 상태와 그 스냅샷의 생성 시각을 함께 검사하며 생성 시각을 완료 시각으로 설명하지 않는다. 다음 날짜나 다음 백업 창 시작만으로 이전 미완료 경보를 해제하지 않는다. `creating/copying/deleting`은 생성 시각이 없어도 진행 상태로 유효하지만 기존 failed를 해제하지 않는다. 수집기는 관측한 최신 완료 상태와 생성 시각을 메모리에 보존하므로 실패 항목이 사라져 오래된 available만 남거나 빈 목록을 받아도 실패를 유지한다. 실패보다 **엄격히 더 늦은 생성 시각의 available**을 확인해야 해제하며 같은 시각은 해제 근거가 아니다. 프로세스 재시작 시 이 기억은 사라지고 관측 사이에 사라진 실패도 놓칠 수 있다. 이 경보는 모든 과거 이벤트의 영속 원장이 아니고 일일 완료·LRT 조건과 함께 판단한다.

주의·긴급은 독립 규칙이다. 긴급으로 넘어가도 주의를 강제로 정상 처리하지 않는다. 따라서 두 알림이 함께 유지될 수 있다. 각각 신뢰할 수 있는 지연이 자기 임계값 이하가 되면 정상으로 돌아온다. 긴급 해제는 10분 주의까지 해제되었다는 뜻이 아니다. 별도 복귀 대기·히스테리시스는 이 구성에 없다.

알림 인스턴스는 고정 DB 하나로 집계하고 DB·리전·환경·severity를 정적 label로 지정한다. 수집 target의 일시 누락이 다른 인스턴스 생성/삭제나 정상 복귀로 바뀌는 것을 피하기 위한 구성이다. Prometheus target은 `job=catchhole-rds-backup,environment=prod`를 가져야 하고 exporter 지표는 추가로 `region,db_instance_identifier`를 제공해야 한다.

전송 기준은 전용 규칙의 `notification_settings`에만 지정하며 전역 notification policy를 덮어쓰지 않는다.

| 설정 | 값 | 의미 |
|---|---|---|
| `receiver` | 실행 시 지정한 contact point 이름 | 운영 수신처는 `catchhole-infra-discord` |
| `group_wait` | `0s` | 최초 발생을 모으기 위한 추가 대기 없음 |
| `group_interval` | `1m` | 이미 알린 그룹의 발생·복귀 변경을 다시 보낼 수 있는 간격 |
| `repeat_interval` | `15m` | 계속 발생 중인 상태의 재알림 간격 |
| mute/active time intervals | 빈 목록 | 야간을 포함한 24시간 전송 |

복귀 메시지는 contact point의 resolved 전송을 켜야 한다. 생성기는 contact point 자체를 수정하지 않는다. 실제 수신처·전송 설정과 합성 발생/복귀 전달을 확인한 뒤에만 `--activate`로 다시 생성하고 적용한다. paused 규칙을 삭제·수정·정지하여 발생한 상태 변화는 백업 정상 복귀의 증거가 아니다. 설정 파일 생성·검증과 실제 운영 적용 완료는 별도로 기록한다.

## 검증

```sh
python3 -m unittest discover -s deploy/monitoring/rds-backup-alerts -p 'test_*.py' -v
```

규칙 시험에는 Python 표준 라이브러리와 Docker가 필요하다. PromQL 검증은 `prom/prometheus:v3.13.3`의 실제 promtool을 외부 통신 없는 컨테이너에서 실행한다. 확장 전 시험 범위는 600/900초 경계, 실패 후 캐시 유지, exporter 중단, 오래된/미래 관측, 미래 LRT, 전체 누락, 긴급 중 주의 유지의 12개 상황과 3개 연속 시나리오를 검사한다. 시계열 검증은 첫 초과+1분 45초/1분 59초/2분 경계, 초과 후 중간 조회 실패, 정상 재수집 후 다시 2분, 기존 경보 중 누락·높은 값 재수집, 유효한 낮은 값으로 복귀를 포함한다.

2026-10-25 로컬 Grafana 13.2.2와 Prometheus 3.13.3을 Docker 내부 네트워크에 격리해 세 규칙의 API 저장·재조회, paused 상태와 KeepLast/Alerting 설정, 실제 `/api/ds/query`의 수집 누락 판정 `A=1, B=1`을 확인했다. 테스트 receiver는 로컬 폐쇄 포트였고 규칙은 모두 paused였다. AWS 수집, 운영 배포, Discord 발송, 실제 장애에서의 pending/복귀 전이는 이 검증에 포함되지 않는다.

별도로 2026-10-26 **00:34 KST FIRING / 00:36 KST RESOLVED** 합성 테스트 메시지가 운영 `#인프라-알림` 채널에 실제 도착했다. 고정된 시간 식이 1에서 0으로 바뀌어 평가 상태가 전환된 결과이며, 규칙 수정·정지·삭제로 해제한 것이 아니다. 수신과 정상 복귀를 확인한 뒤 테스트 규칙을 삭제했고, **00:38:03 KST 운영 DB용 규칙 3개를 활성화**해 `isPaused=false`를 확인했다. **00:38:40 KST 평가에서는 세 규칙 모두 Normal, health=ok, lastError=null**이었으며 Grafana 화면과도 대조했다. 실제 백업 장애나 DB 복원 시험과 구분하며, contact point·수신 증거·현재 평가 상태는 [운영 기록](../../RDS_BACKUP_MONITORING.md)을 따른다.

같은 날 **01:13:11~01:43:07 KST**, 별도 시험 label의 모의 입력으로 운영과 같은 계산식·시간 조건·scrape·평가·통지 간격의 **15단계 시험과 최종 분석 15개 검사를 모두 통과**했다. 지연 경보 발생·유지·복귀, 필수 지표 누락·timestamp 동결·HTTP 503의 수집 경보 및 정상 복귀를 평가 기록과 실제 Discord 수신으로 확인했고, **01:43:43 KST 시험 자원 정리 후 운영 target 9개·규칙 그룹·확인한 설정 파일 5개가 보존됨을 확인**했다. 첫 시도 제어기의 상태 문자열 비교 보완과 최종 증거는 [운영 기록](../../RDS_BACKUP_MONITORING.md)에 남겼으며, 실제 AWS 고장·DB 복원·RPO/RTO 달성이나 15분 재알림 실측을 의미하지 않는다.

추가 규칙은 보존 0/1/6/7/8 경계, 백업 창·날짜 경계·완료 지연과 실패/진행 상태, CPU/공간/연결 임계값·5분 연속성·수집 중단/재개, 원천 stale/future/누락을 별도 검증한다. 최종 결과가 확인되기 전에는 기존 15단계 운영 시험을 14개 전체 규칙의 성공으로 확대하지 않는다. PR CI의 `rds-monitoring` job은 Python 3.13의 exporter/SDK Stubber 시험과 운영 Prometheus 3.13.3 promtool 기반 규칙 시험을 실행하며, CI 성공 여부는 PR에서 확인한다.

공식 근거: [Grafana Alerting Provisioning API](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/http-api-provisioning/), [No Data와 Error 상태](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/nodata-and-error-states/). 저장·평가 형식은 현재 운영 버전 13.2.2로 확인했으며 향후 Grafana 업그레이드 때 API 지원을 다시 확인한다.
