# 운영 Prometheus·Grafana 배포와 HTTPS 접속 (#205)

팀원은 SSM 터널 없이 `https://monitoring.catchhole.com`에서 기존 Grafana 계정으로 로그인한다. 모니터링 EC2의 Nginx가 HTTPS를 종료하고 같은 Docker Compose의 Grafana로 전달한다. Prometheus 수집과 기존 데이터는 유지하며 설정 배포는 수동, 인증서 갱신은 systemd timer로 자동화한다.

이 문서는 재현 절차다. 파일 작성·로컬 검사만으로 실제 AWS·DNS 적용이나 운영 완료를 판정하지 않는다. 아래 운영 검증과 인증서 갱신 검증 결과는 적용 시점의 별도 기록으로 확인한다.

## 연결 구조

| 요청 | 경로와 제한 |
| --- | --- |
| 일반 API·Worker | 기존 API 서버 8080. 기존 인증과 Worker SG 접근 유지 |
| 공개 메트릭 요청 | Caddy가 `/actuator/health`를 제외한 `/actuator` 경로를 404로 차단 |
| 운영 메트릭 수집 | 모니터링 EC2 → API EC2 사설 IPv4:8081 → `/actuator/prometheus` |
| 기존 health | 8080의 `/actuator/health`를 Actuator 전체 health group `/healthz`로 내부 전달. 정상 200, 비정상 503 유지 |
| Grafana → Prometheus | 같은 Compose 네트워크의 `http://prometheus:9090` |
| 팀원 Grafana 접속 | `https://monitoring.catchhole.com` → 모니터링 EC2 Nginx:443 → Docker 내부 `grafana:3000`; 기존 계정 로그인 |
| HTTP 진입 | Nginx:80은 ACME challenge 제공·고정 HTTPS 주소 이동. 최초 bootstrap은 challenge 외 요청에 404 |
| 직접 관리 조회 | Grafana·Prometheus의 호스트 `127.0.0.1:3000`·`:9090` 유지. SSM은 관리·Prometheus 조회·복구에 사용 |

8081에는 별도 사용자 비밀번호를 두지 않고 사설망·보안 그룹으로 수집 서버를 제한한다. 같은 API 서버의 관리자나 Docker 네트워크 내부는 신뢰 경계에 포함된다. API 서버에 연결된 **모든** 보안 그룹을 확인해 8081의 넓은 허용 규칙이 없도록 한다. 기존 Worker의 8080 접근 권한이 메트릭 접근 권한으로 이어지지 않도록 포트를 나눈다.

## 0. 접속 전환 전 점검과 백업

- 서울 리전의 모니터링 EC2를 API·Worker EC2와 구분한다. 같은 VPC에서 API 사설 IP:8081에 도달하고, 인터넷에서 80·443으로 들어올 수 있는 공인 주소·IGW 라우팅·보안 그룹·호스트 방화벽을 확인한다. DNS는 실제 사용할 안정적인 공인 주소에 연결하며 일시적인 자동 할당 IP를 운영 문서에 고정하지 않는다.
- 80·443의 기존 서비스 점유, Docker Compose·systemd 사용 가능 여부, 디스크 여유, 현재 컨테이너·이미지·볼륨 이름과 Grafana 사용자·대시보드·Prometheus 수집 상태를 기록한다. 기존 프로젝트는 `catchhole-monitoring-prod`이며 데이터 볼륨을 새 프로젝트로 옮기지 않는다.
- 현재 Compose·`monitoring.env`·실제 targets JSON을 서버의 권한 제한된 백업 경로에 보관한다. 이전 Grafana 외부 URL·쿠키 환경변수도 이전 Compose와 함께 복원할 수 있어야 한다. env 원문·비밀번호·개인키를 작업 출력이나 저장소에 넣지 않는다.
- Grafana의 짧은 중단이 허용된 시간에 해당 서비스만 정지하고 `/var/lib/grafana` 전체의 일관된 백업을 확보한다. SQLite DB를 쓰는 경우 실행 중인 파일을 단순 복사하지 않는다. 정지한 컨테이너에서 `docker cp -a` 등으로 백업한 뒤 **기존** Compose·env로 Grafana만 `up -d --no-deps grafana`하여 다시 실행한다. Prometheus를 정지하거나 볼륨을 삭제하지 않는다.
- DNS·보안 그룹 변경 전 상태와 백업 경로·복원 방법을 기록한다. 가비아 DNS 수정 접근과 `ACME_EMAIL`로 쓸 운영 연락처를 준비한다.

실제 환경값이 포함된 `docker compose config`는 출력하지 않고 `config --quiet`를 사용한다. 아래 명령은 모니터링 EC2에서 실행하며 권한이 필요한 경우 해당 서버 운영 규칙에 따라 `sudo`를 사용한다.

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

## 1.1 기존 Actuator로 CPU 구분

CPU 패널은 기존 `catchhole-backend` job에서 수집하는 Actuator Gauge를 사용한다. Spring Boot JVM은 `100 * process_cpu_usage`, 현재 API EC2 전체는 `100 * system_cpu_usage`이며 기존 `application="catchhole-backend"`, `environment="prod"` 라벨을 유지한다. 모두 JVM이 보고하는 최근 관측값을 직접 조회하고 5분 rate·평균으로 계산하지 않는다. 별도 수집기 설치·9100 SG 규칙·새 Prometheus job·수집 설정 reload는 필요하지 않다.

2026-10-01 실제 운영 컨테이너에서 CPU quota·cpuset 제한 없음(`cpu.max=max 100000`, 허용 CPU `0-1`)과 JVM의 `system_cpu_count=2`를 확인했다. 현재 EC2와 JVM 모두 2 vCPU를 인식하므로 CPU 1개를 계속 꽉 쓰면 약 50%, 2개를 모두 꽉 쓰면 약 100%다. JVM 패널은 Java 프로세스의 요청 처리·GC·JIT를 포함하고, 전체 패널은 현재 실행 환경의 다른 프로세스·커널도 포함한다. Docker CPU 제한·cpuset·JVM CPU 설정을 바꾸면 `system_cpu_usage`가 컨테이너 기준이 될 수 있으므로 그때 패널 설명과 분모를 다시 확인한다. CloudWatch CPUUtilization과 정확히 같은 값으로 해석하지 않는다.

## 2. 모니터링 서버 파일 준비

검토한 동일 Git 버전의 다음 파일을 모니터링 EC2의 `/opt/catchhole-monitoring`에 보관한다.

```text
/opt/catchhole-monitoring/
├── compose.monitoring.prod.yml
├── monitoring.env                 # 실제 값, chmod 600, 커밋 금지
└── monitoring/
    ├── prometheus.yml
    ├── targets/catchhole-backend.json
    ├── grafana/provisioning/datasources/prometheus.yml
    ├── nginx/bootstrap.conf
    ├── nginx/grafana.conf
    ├── renew-certificate.sh
    └── systemd/
        ├── catchhole-monitoring-certbot.service
        └── catchhole-monitoring-certbot.timer
```

저장소에서는 `deploy` 디렉터리가 위 디렉터리에 대응한다. 최초 서버 준비 때는 `monitoring.env.example`을 `monitoring.env`로 복사한 뒤 Grafana 관리자 비밀번호를 채우고 파일 권한을 600으로 설정한다. 기존 서버에서는 실제 env와 targets JSON을 보존하고 필요한 새 키만 추가한다. 비밀번호가 비어 있으면 Compose 검증·실행이 실패한다.

`MONITORING_NGINX_CONFIG=./monitoring/nginx/bootstrap.conf`가 인증서 발급 전 설정이며 Compose 기본값도 bootstrap이다. `ACME_EMAIL`에는 최초 ACME 계정 등록 연락처를 기록한다. HTTPS 전환 후 활성 설정은 `MONITORING_NGINX_CONFIG=./monitoring/nginx/grafana.conf`로 바꾼다.

`acme_webroot`는 Nginx·Certbot의 `/var/www/certbot`, `letsencrypt_data`는 `/etc/letsencrypt` **전체**를 공유한다. Nginx는 두 경로를 읽기 전용으로 사용한다. Certbot의 `live/`가 참조하는 `archive/` 심볼릭 링크와 갱신 설정·계정을 보존하기 위해 도메인별 `live/` 폴더만 마운트하지 않는다.

`monitoring/targets/catchhole-backend.json.example`을 같은 폴더의 `catchhole-backend.json`으로 복사하고 `replace-with-api-private-ip:8081`을 실제 주소로 바꾼다. 실제 파일은 커밋하지 않는다. Prometheus YAML 안의 `${...}`를 환경변수처럼 치환하지 않고, `file_sd_configs`로 이 JSON을 읽는다. `PROMETHEUS_TARGETS_FILE`로 파일 경로를 변경할 수도 있다. 파일이 없으면 bind mount가 실패하도록 구성했다.

```json
[
  { "targets": ["10.0.1.10:8081"] }
]
```

위 IP는 예시다. 실제 API 사설 IP를 사용하며 `localhost`, `host.docker.internal`, 공개 API 도메인을 넣지 않는다.

## 3. 최초 발급과 HTTPS 전환

### 3.1 DNS·포트와 bootstrap 준비

모니터링 EC2의 외부 TCP 80·443을 인터넷 전체에 허용하고 가비아에서 `monitoring.catchhole.com` A 레코드를 해당 서버의 안정적인 공인 주소로 연결한다. 다른 DNS·SG 규칙은 유지한다. AAAA가 있다면 실제로 도달 가능한 IPv6와 일치하는지 확인한다. 3000·9090·8081을 인터넷에 허용하지 않는다.

`monitoring.env`의 `MONITORING_NGINX_CONFIG`를 bootstrap으로 둔 뒤 실행한다. 기존 Grafana·Prometheus 전체를 `up -d`하여 새 Grafana URL을 먼저 적용하지 않는다.

```bash
cd /opt/catchhole-monitoring
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml config --quiet
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml --profile maintenance pull nginx certbot
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml up -d --no-deps nginx
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml exec -T nginx nginx -t
dig +short monitoring.catchhole.com
```

bootstrap은 `/.well-known/acme-challenge/`의 webroot 파일을 제공하고 그 외 요청은 404로 처리한다. 검증용 파일을 webroot에 넣어 **외부 HTTP**에서 같은 내용이 반환되는지 확인한 뒤 제거한다. 루트 경로에서 Grafana 로그인 화면이 나오면 bootstrap 설정부터 확인한다.

### 3.2 최초 인증서 발급

DNS가 실제 서버를 가리키고 외부 challenge 접근이 확인된 뒤 발급한다. Certbot은 `maintenance` profile의 일회성 서비스이며 이 명령은 Grafana·Prometheus를 시작하거나 재생성하지 않는다. 최초 등록에는 기록한 운영 연락처와 Let's Encrypt 약관 동의를 사용하고 기존 ACME 계정이 있으면 재사용한다.

```bash
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml --profile maintenance run --rm --no-deps --entrypoint sh certbot -c '
  : "${ACME_EMAIL:?ACME_EMAIL is required for first issuance}"
  exec certbot certonly --webroot --webroot-path /var/www/certbot \
    --email "$ACME_EMAIL" --agree-tos --non-interactive \
    --cert-name monitoring.catchhole.com -d monitoring.catchhole.com
'
```

발급 성공과 `/etc/letsencrypt/live/monitoring.catchhole.com/`의 체인·개인키 존재를 확인한다. 개인키 내용을 출력하지 않는다. 실패했다면 bootstrap과 기존 Grafana 설정을 유지해 SSM으로 계속 조회하고 DNS·challenge·발급 오류를 해결한다. 인증서 없이 HTTPS 설정을 시작하지 않는다.

### 3.3 Nginx·Grafana 전환

`monitoring.env`에서 `MONITORING_NGINX_CONFIG=./monitoring/nginx/grafana.conf`로 바꾼다. Nginx는 설정 파일 하나를 bind mount하므로 활성 파일을 바꾸거나 파일을 교체한 뒤에는 재생성한다. Grafana에는 `GF_SERVER_DOMAIN=monitoring.catchhole.com`, `GF_SERVER_ROOT_URL=https://monitoring.catchhole.com/`, `GF_SECURITY_COOKIE_SECURE=true`가 적용되고 내부 protocol은 HTTP다.

```bash
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml config --quiet
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml up -d --no-deps --force-recreate nginx
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml up -d --no-deps grafana
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml exec -T nginx nginx -t
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml exec -T nginx nginx -s reload
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml ps
```

Grafana 재생성 뒤 Nginx를 검사·reload해 `grafana:3000`의 바뀐 컨테이너 주소를 다시 해석한다. Nginx가 같은 Compose 네트워크에서 연결하므로 upstream에 호스트 `127.0.0.1:3000`을 사용하지 않는다. 이 전환에서 Prometheus를 재생성하지 않는다.

## 4. 관리자 접속

팀원은 브라우저에서 `https://monitoring.catchhole.com`을 열고 기존 Grafana 계정으로 로그인한다. AWS CLI·Session Manager 플러그인·터널 명령은 일상 조회에 필요하지 않다. 익명 접속과 회원가입은 계속 비활성화한다.

SSM은 서버 설정과 복구 작업에 사용한다. Prometheus UI를 직접 조회해야 할 때는 별도 관리 터널을 사용할 수 있다.

```bash
aws ssm start-session \
  --target '<모니터링 EC2 instance ID>' \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["9090"],"localPortNumber":["19090"]}'
```

이 경우 `http://127.0.0.1:19090`을 연다. HTTPS 전환 후 Grafana의 HTTP localhost 터널 로그인은 secure cookie·외부 URL 때문에 그대로 동작한다고 가정하지 않는다. HTTP SSM 접속으로 복구해야 하면 아래 롤백에 따라 이전 환경변수를 먼저 복원한다.

## 5. 인증서 자동 갱신

`monitoring/renew-certificate.sh`는 서버의 `/opt/catchhole-monitoring`에서 실제 env·Compose로 Certbot `renew --cert-name monitoring.catchhole.com --non-interactive --no-random-sleep-on-renew`를 실행한다. Certbot 서비스의 기본 `renew` 명령도 같은 옵션을 사용한다. Certbot 성공 후에만 Nginx 설정 검사와 graceful reload를 순서대로 실행하고 실패하면 nonzero로 종료한다. 갱신할 인증서가 없어도 성공 후 reload하며, 성공 코드 자체를 새 인증서 발급 증거로 사용하지 않는다.

```bash
sudo install -m 0644 monitoring/systemd/catchhole-monitoring-certbot.service /etc/systemd/system/
sudo install -m 0644 monitoring/systemd/catchhole-monitoring-certbot.timer /etc/systemd/system/
sudo systemd-analyze verify /etc/systemd/system/catchhole-monitoring-certbot.service /etc/systemd/system/catchhole-monitoring-certbot.timer
sudo systemctl daemon-reload
sudo systemctl enable --now catchhole-monitoring-certbot.timer
```

timer는 KST 03:00·15:00에 최대 30분의 실행 분산을 적용하며 `Persistent=true`로 꺼져 있던 시간의 실행을 보완한다. 실행 분산은 timer 한 곳에서 담당하고 Certbot의 추가 지연은 `--no-random-sleep-on-renew`로 끈다. 중복 지연이 oneshot의 10분 제한을 소모하지 않도록 하며 dry-run에도 같은 옵션을 사용한다. 재부팅 후에도 timer와 Nginx가 실행되는지 확인한다. 모니터링 설정의 GitHub Actions 배포나 새 외부 알림 시스템은 추가하지 않는다.

갱신은 운영 인증서를 강제 재발급하는 대신 Let's Encrypt staging의 dry-run으로 검사하고 oneshot의 실제 Nginx 반영 경로도 별도로 확인한다.

```bash
docker compose --env-file monitoring.env -f compose.monitoring.prod.yml --profile maintenance run --rm --no-deps certbot renew --cert-name monitoring.catchhole.com --non-interactive --no-random-sleep-on-renew --dry-run
sudo systemctl start catchhole-monitoring-certbot.service
sudo systemctl status catchhole-monitoring-certbot.service --no-pager
sudo systemctl list-timers catchhole-monitoring-certbot.timer --no-pager
sudo journalctl -u catchhole-monitoring-certbot.service --since today --no-pager
```

dry-run·oneshot 종료 결과·다음 실행 시각·인증서 만료일을 적용 기록에 남긴다. 장애 때 같은 `systemctl status`·`journalctl` 경로에서 원인을 확인한다.

## 6. 운영 검증과 완료 기록

1. SSM 터널을 닫은 브라우저에서 신뢰되는 HTTPS 인증서, 기존 계정 로그인, 대시보드 조회, 로그아웃과 Grafana Live WebSocket 연결을 확인한다. 로그인 전 데이터에 접근할 수 없고 잘못된 계정으로도 들어갈 수 없어야 한다. 다른 외부 네트워크에서도 HTTPS 진입을 확인한다.
2. 일반 HTTP는 `https://monitoring.catchhole.com`으로 이동하고 ACME challenge는 HTTP로 계속 제공되는지 확인한다. 실제 DNS·80/443 SG·호스트 게시 포트를 확인하며 3000·9090·8081은 인터넷에 공개되지 않아야 한다.
3. 기존 Grafana 사용자·권한·대시보드와 영속 볼륨, Prometheus 이력이 유지되는지 확인한다. `up{job="catchhole-backend"}=1`과 기본 지표가 계속 조회되어야 한다. `up=1`은 scrape 성공이며 분석 기능 전체의 정상 동작을 보장하지 않는다.
4. dry-run, Certbot 성공 → Nginx 검사 → reload, timer 등록과 다음 실행 시각을 확인한다. 운영 버전·DNS/SG 변경·만료일·갱신 결과·백업 위치·데이터 보존 결과를 기록한다.

초기 수집 기반을 새로 구성하거나 API 메트릭 접근을 바꿨다면 다음도 검증한다.

1. 공개 `/actuator/prometheus`, 그 하위 경로와 `/actuator`가 404이고 공개 `/actuator/health`는 정상인지 확인한다.
2. API 8080의 `/actuator/prometheus`가 메트릭 본문을 반환하지 않는지 확인한다. 인증·오류 처리에 따라 4xx일 수 있다.
3. 모니터링 EC2에서 API 사설 IP:8081의 메트릭을 조회할 수 있고, Worker EC2와 허용하지 않은 네트워크에서는 연결할 수 없는지 확인한다. 이 검증은 실제 SG 적용 후 수행한다.
4. Prometheus의 `up{job="catchhole-backend"}`이 1인지, `process_cpu_usage{job="catchhole-backend",environment="prod"}` 등 기본 지표가 조회되는지 확인한다.
5. Grafana 데이터 소스가 정상이고 정상 운영 요청에 따라 HTTP count·histogram이 보이는지 확인한다. 호출되지 않은 API 지표가 없거나, 유휴 연결 풀 active가 0인 것은 수집 실패와 구분한다.
6. 컨테이너 재시작 후 지표 이력·Grafana 설정이 유지되는지 확인하고 배포 버전·수집 대상·검증 결과를 운영 적용 기록에 남긴다. 운영 장애를 일부러 발생시키지 않는다.

## 운영 데이터와 업데이트

- Prometheus `v3.13.3`, Grafana `13.2.2`, Nginx `1.30.5-alpine3.24`, Certbot `v5.8.0`을 고정한다. 업데이트는 해당 CPU의 공식 이미지 manifest와 변경 내용을 확인하고 검증한 버전으로 한다.
- 15초 간격·5초 timeout과 기본 7일·5GB 보존 설정을 유지한다. **5GB는 전체 디스크 상한이 아니다.** WAL·head·Grafana·인증서·OS·journal 여유 공간을 확보한다.
- `catchhole-monitoring-prod` 프로젝트와 `prometheus_data`·`grafana_data`를 유지한다. 인증서 계정·renewal 설정과 전체 인증서 이력은 `letsencrypt_data`에, challenge는 `acme_webroot`에 둔다. 운영 업데이트·롤백에 `down -v`를 사용하지 않는다.
- Grafana 데이터 소스 UID `catchhole-prometheus`와 `http://prometheus:9090`은 파일 provisioning으로 관리한다. 대시보드 HTTP 집계에서는 `/actuator.*`·`/healthz`를 제외한다.
- 지표 패널 12개(상단 Stat 4개·주요 API p95 비교 그래프 1개·인프라 그래프 7개)의 구성·지표 해석·수동 복원은 [대시보드 운영 안내](monitoring/grafana/dashboards/README.md)와 [JSON 스냅샷](monitoring/grafana/dashboards/catchhole-overview.json)을 참고한다. API 비교는 원고 업로드·분석 접수·분석 상태·세계관 후보·확정 인물 검색의 HTTP p95이며 비동기 작업 완료 시간과 구분한다. 인프라는 EC2 CPU·Spring Boot JVM CPU·힙·GC·DB 연결·스레드·DB Pending이며 행 제목은 지표 수에 포함하지 않는다. 대시보드 UID는 `catlfln`이며 GUI에서 단계적으로 수정한 구성이 덮어써지지 않도록 자동 dashboard provisioning은 사용하지 않는다.
- 초기 관리자 env는 새 DB의 계정을 만드는 용도다. 기존 영속 DB의 비밀번호는 env만 바꿔 갱신되지 않으며 Grafana의 비밀번호 변경 절차를 따른다.
- 실제 env·targets JSON·인증서 개인키·ACME 계정은 커밋하지 않는다. 운영 컨테이너는 journald를 사용하고 기존 journal 보존 제한을 유지한다.
- 설정 변경은 같은 검토 버전의 파일을 수동 반영하고 `config --quiet`로 검사한다. Nginx conf 파일 교체는 `up -d --no-deps --force-recreate nginx`로 마운트를 갱신하고 `nginx -t`·reload로 확인한다. 갱신 스크립트·unit을 바꾸면 `bash -n`, `systemd-analyze verify`, daemon-reload와 oneshot 검증을 수행한다.
- targets JSON을 교체한 경우 해당 변경에 한해서 `up -d --no-deps --force-recreate prometheus`로 bind mount를 갱신한다. Grafana provisioning 변경은 해당 서비스만 재시작해 확인한다. 접속 설정만 바꾸는 배포에 이 재시작을 섞지 않는다.

## 업데이트·롤백

- 최초 인증서 발급이 실패했으면 bootstrap과 이전 Grafana 설정을 유지한다. 기존 HTTP SSM 경로로 계속 조회할 수 있으며 인증서가 준비될 때까지 외부 URL·secure cookie를 적용하지 않는다.
- HTTPS 전환 뒤 HTTP SSM 조회로 복구해야 하면 timer를 먼저 정지·비활성화하고 새 Compose에서 Nginx를 정지한다. 서버의 백업에서 **이전 Compose와 이전 `monitoring.env`를 함께 복원**한 뒤 `config --quiet`, `up -d --no-deps grafana`로 Grafana만 재생성한다. 이전 외부 URL·cookie 설정이 복원됐는지 확인한 후 SSM의 원격 3000 → 로컬 13000 터널과 `http://127.0.0.1:13000` 로그인을 검증한다. 현재 env만 복원해도 새 Compose의 HTTPS 고정값이 남으면 복구되지 않는다.
- DNS·SG는 기록한 이전 상태로 이번 변경 범위만 되돌린다. Nginx·timer를 정지하더라도 Grafana·Prometheus·인증서 볼륨은 삭제하지 않는다. 접속 설정 문제에는 DB 복원이나 이미지 downgrade를 먼저 수행하지 않는다.
- 이미지를 업데이트하기 전에 기존 버전·설정과 영속 데이터의 복구 방법을 기록한다. Grafana DB migration 이후에는 단순 이미지 downgrade가 안전하다고 가정하지 말고 해당 버전의 복구 절차·백업을 사용한다.
- 수집 접근을 닫으려면 API의 `API_METRICS_BIND_ADDRESS`를 `127.0.0.1`로 복원하고 backend를 재생성한 뒤 8081 SG 규칙을 회수한다. 기존 API 8080·health 경로는 유지된다.
- API 애플리케이션을 변경 전 버전으로 롤백할 때는 해당 버전의 이미지·Compose·Caddyfile을 함께 복원하고 health를 확인한다. 모니터링 볼륨은 삭제하지 않는다.
