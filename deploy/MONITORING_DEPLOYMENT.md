# 운영 Prometheus·Grafana 배포 준비 (#205)

이 문서는 저장소 설정을 운영에 적용하는 절차다. 설정 파일 작성·로컬 검증과 실제 EC2 생성·보안 그룹 변경·운영 배포는 구분한다. 현재 작업으로 AWS 리소스가 생성되거나 보안 그룹이 변경되지는 않는다.

## 연결 구조

| 요청 | 경로와 제한 |
| --- | --- |
| 일반 API·Worker | 기존 API 서버 8080. 기존 인증과 Worker SG 접근 유지 |
| 공개 메트릭 요청 | Caddy가 `/actuator/health`를 제외한 `/actuator` 경로를 404로 차단 |
| 운영 메트릭 수집 | 모니터링 EC2 → API EC2 사설 IPv4:8081 → `/actuator/prometheus` |
| 기존 health | 8080의 `/actuator/health`를 Actuator 전체 health group `/healthz`로 내부 전달. 정상 200, 비정상 503 유지 |
| Grafana → Prometheus | 같은 Compose 네트워크의 `http://prometheus:9090` |
| 관리자 화면 | 모니터링 EC2의 `127.0.0.1:3000`·`:9090`을 SSM 포트 포워딩으로 접속 |

8081에는 별도 사용자 비밀번호를 두지 않고 사설망·보안 그룹으로 수집 서버를 제한한다. 같은 API 서버의 관리자나 Docker 네트워크 내부는 신뢰 경계에 포함된다. API 서버에 연결된 **모든** 보안 그룹을 확인해 8081의 넓은 허용 규칙이 없도록 한다. 기존 Worker의 8080 접근 권한이 메트릭 접근 권한으로 이어지지 않도록 포트를 나눈다.

## 1. API 서버 준비

`application-prod.yml`은 관리 포트를 8081로 분리한다. local 프로파일은 기존처럼 8080에서 메트릭을 제공한다. 운영 기본 메트릭의 공통 label은 `application=catchhole-backend`, `environment=prod`다. `up`은 Prometheus가 만드는 수집 상태 지표여서 application·environment label이 자동으로 붙지 않는다.

API Compose의 8081 게시 주소는 기본 `127.0.0.1`이다. 기존 `api.env`에 새 변수가 없어도 API 배포·health 확인은 동작하며, 원격 메트릭 수집은 아직 열리지 않는다.

모니터링 EC2 생성 후 다음 순서로 적용한다.

1. API 서버와 사설 통신 가능한 VPC·서브넷에 모니터링 EC2를 배치하고 전용 SG를 붙인다. 인스턴스 크기·EBS 용량·비용은 생성 전에 정한다.
2. API SG에 **TCP 8081 / 소스: 모니터링 SG**만 추가한다. Worker SG 또는 인터넷 전체를 8081에 허용하지 않는다. 모니터링 SG의 아웃바운드도 실제 수집·이미지 다운로드·SSM 연결 요구에 맞춘다.
3. `/opt/catchhole/api.env`에 `API_METRICS_BIND_ADDRESS=<API EC2 사설 IPv4>`를 설정한다. `0.0.0.0`은 사용하지 않는다.
4. 같은 변경 버전의 Spring 이미지·API Compose·Caddyfile을 함께 적용한다. 자동 배포 workflow는 Caddyfile을 명시적으로 reload한다. 수동 배포도 아래 reload를 수행한다.

```bash
cd /opt/catchhole
docker compose --env-file api.env -f compose.api.prod.yml config --quiet
docker compose --env-file api.env -f compose.api.prod.yml up -d
docker compose --env-file api.env -f compose.api.prod.yml exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS https://api.catchhole.com/actuator/health
```

## 2. 모니터링 서버 파일 준비

검토한 동일 Git 버전의 다음 파일을 모니터링 EC2의 `/opt/catchhole-monitoring`에 보관한다.

```text
/opt/catchhole-monitoring/
├── compose.monitoring.prod.yml
├── monitoring.env                 # 실제 값, chmod 600, 커밋 금지
└── monitoring/
    ├── prometheus.yml
    ├── targets/catchhole-backend.json
    └── grafana/provisioning/datasources/prometheus.yml
```

저장소에서는 `deploy` 디렉터리가 위 디렉터리에 대응한다. `monitoring.env.example`을 `monitoring.env`로 복사한 뒤 Grafana 관리자 비밀번호를 채우고 파일 권한을 600으로 설정한다. 비밀번호가 비어 있으면 Compose 검증·실행이 실패한다. 실제 환경값을 출력하는 `docker compose config` 대신 `config --quiet`를 사용한다.

`monitoring/targets/catchhole-backend.json.example`을 같은 폴더의 `catchhole-backend.json`으로 복사하고 `replace-with-api-private-ip:8081`을 실제 주소로 바꾼다. 실제 파일은 커밋하지 않는다. Prometheus YAML 안의 `${...}`를 환경변수처럼 치환하지 않고, `file_sd_configs`로 이 JSON을 읽는다. `PROMETHEUS_TARGETS_FILE`로 파일 경로를 변경할 수도 있다. 파일이 없으면 bind mount가 실패하도록 구성했다.

```json
[
  { "targets": ["10.0.1.10:8081"] }
]
```

위 IP는 예시다. 실제 API 사설 IP를 사용하며 `localhost`, `host.docker.internal`, 공개 API 도메인을 넣지 않는다.

## 3. 설정 검사와 실행

```bash
cd /opt/catchhole-monitoring
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml config --quiet
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml pull
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml up -d
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml ps
```

- Prometheus `v3.13.3`, Grafana `13.2.2`를 사용하며 `latest`로 바꾸지 않는다.
- 15초 간격, timeout 5초로 수집한다. 보존은 기본 7일·5GB 중 먼저 도달한 제한에 따라 과거 TSDB 블록을 삭제한다. **5GB는 전체 디스크 상한이 아니다.** WAL·head·Grafana·OS·journal 여유 공간을 확보하고 실제 사용량에 따라 조정한다.
- Prometheus와 Grafana 데이터는 각각 전용 named volume에 저장한다. `down`은 데이터를 유지하지만 `down -v`는 삭제하므로 운영 종료·업데이트에 사용하지 않는다.
- 로그는 기존 운영 컨테이너 규칙에 따라 journald를 사용한다. 모니터링 EC2에도 journal 보존 제한을 적용한다.
- Grafana 데이터 소스는 파일에서 자동 등록되며 UID는 `catchhole-prometheus`다. UI에서 임의로 수정하는 대신 저장소 설정을 갱신한다. 대시보드·PromQL 구성은 #205의 다음 단계다.
- API 요청량·오류율·지연 대시보드에서는 `/actuator.*`와 `/healthz`를 제외해 수집·상태 확인 요청을 사용자 트래픽에 섞지 않는다.
- Grafana 관리자 환경변수는 새 DB의 초기 계정을 만든다. 영속 볼륨이 이미 있으면 환경변수 변경만으로 기존 비밀번호가 갱신되지 않으며 Grafana의 비밀번호 변경 절차를 따른다.

## 4. 관리자 접속

SSM 관리 인스턴스로 등록되고 Session Manager 권한·플러그인이 준비된 환경에서 실행한다. 3000·9090을 인터넷에 열 필요가 없다. 로컬 프런트의 3000과 충돌하지 않도록 로컬 포트 13000을 사용한다.

```bash
aws ssm start-session \
  --target '<모니터링 EC2 instance ID>' \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["13000"]}'
```

터널을 유지한 채 `http://127.0.0.1:13000`에서 Grafana 관리자 계정으로 로그인한다. 익명 접속과 회원가입은 비활성화한다. Prometheus UI가 필요하면 별도 터널에서 `portNumber=9090`, `localPortNumber=19090`을 사용한다.

## 5. 운영 검증

1. 공개 `/actuator/prometheus`, 그 하위 경로와 `/actuator`가 404이고 공개 `/actuator/health`는 정상인지 확인한다.
2. API 8080의 `/actuator/prometheus`가 메트릭 본문을 반환하지 않는지 확인한다. 인증·오류 처리에 따라 4xx일 수 있다.
3. 모니터링 EC2에서 API 사설 IP:8081의 메트릭을 조회할 수 있고, Worker EC2와 허용하지 않은 네트워크에서는 연결할 수 없는지 확인한다. 이 검증은 실제 SG 적용 후 수행한다.
4. Prometheus의 `up{job="catchhole-backend"}`이 1인지, `process_cpu_usage{job="catchhole-backend",environment="prod"}` 등 기본 지표가 조회되는지 확인한다. `up=1`은 scrape 성공이며 분석 기능 전체의 정상 동작을 보장하지 않는다.
5. Grafana 데이터 소스가 정상이고 정상 운영 요청에 따라 HTTP count·histogram이 보이는지 확인한다. 호출되지 않은 API 지표가 없거나, 유휴 연결 풀 active가 0인 것은 수집 실패와 구분한다.
6. 컨테이너 재시작 후 지표 이력·Grafana 설정이 유지되는지 확인하고 배포 버전, 수집 대상, 검증 결과를 #205에 남긴다. 운영 장애를 일부러 발생시키지 않는다.

## 업데이트·롤백

- 설정을 변경할 때 `config --quiet`와 `promtool check config`를 먼저 실행한다. targets JSON을 교체한 경우 bind mount가 새 파일을 읽도록 `up -d --force-recreate prometheus`를 실행한다. Grafana provision 설정은 Grafana 재시작 후 확인한다.
- 이미지를 업데이트하기 전에 기존 버전·설정과 영속 데이터의 복구 방법을 기록한다. Grafana DB migration 이후에는 단순 이미지 downgrade가 안전하다고 가정하지 말고 해당 버전의 복구 절차·백업을 사용한다.
- 수집 접근을 닫으려면 API의 `API_METRICS_BIND_ADDRESS`를 `127.0.0.1`로 복원하고 backend를 재생성한 뒤 8081 SG 규칙을 회수한다. 기존 API 8080·health 경로는 유지된다.
- API 애플리케이션을 변경 전 버전으로 롤백할 때는 해당 버전의 이미지·Compose·Caddyfile을 함께 복원하고 health를 확인한다. 모니터링 볼륨은 삭제하지 않는다.
