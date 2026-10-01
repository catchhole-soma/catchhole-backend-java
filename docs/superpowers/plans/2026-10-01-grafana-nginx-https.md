# Grafana Nginx HTTPS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 독립적인 조사와 최종 리뷰는 보조 에이전트에게 맡긴다. Steps use checkbox (`- [ ]`) syntax for tracking. 2026-10-01 사용자가 계획과 Computer Use 실행을 승인했고 active goal로 전환했다. 실제 서버 작업과 독립적인 파일 구현을 병행한다.

**Goal:** 팀원이 SSM 터널 없이 `https://monitoring.catchhole.com`에서 기존 Grafana 계정으로 로그인하고 운영 대시보드를 조회한다.

**Architecture:** 모니터링 EC2의 기존 Docker Compose에 Nginx를 추가하여 HTTPS를 종료하고 Docker 내부 `grafana:3000`으로 전달한다. Let’s Encrypt 인증서는 일회성 Certbot 컨테이너의 webroot 방식으로 발급하고, EC2의 systemd timer로 갱신과 Nginx 반영을 자동화한다. 설정 배포는 수동이며 Prometheus의 메트릭 수집과 기존 영속 데이터는 유지한다.

**Tech Stack:** Docker Compose, Nginx, Certbot/Let’s Encrypt, systemd, 기존 Grafana 13.2.2·Prometheus v3.13.3, AWS EC2·보안 그룹·SSM, 가비아 DNS.

**Spec:** 2026-10-01 현재 대화의 grilling 합의. Nginx·HTTPS·`monitoring.catchhole.com`, 어디서든 Grafana 계정으로 접근, 화면의 짧은 중단 허용, 저장소부터 AWS·DNS·실제 검증까지 우리가 진행, 수동 설정 배포와 자동 인증서 갱신.

## Global Constraints

- Grafana 로그인과 기존 사용자·권한을 유지한다. 익명 접속·회원가입은 계속 비활성화한다.
- 외부 진입은 TCP 80·443이다. 80에서는 ACME challenge와 HTTPS 이동만 제공한다. Grafana 3000·Prometheus 9090·Actuator 8081을 인터넷에 공개하지 않는다.
- IP allowlist를 추가하지 않는다. HTTPS 로그인 화면은 어디서든 접근할 수 있다.
- 모니터링 접속은 API 서버의 프록시를 경유하지 않는다. 기존 API Caddy 설정은 이번 변경 대상이 아니다.
- 기존 Compose 프로젝트 이름 `catchhole-monitoring-prod`, `grafana_data`·`prometheus_data`, 이미지 버전, 수집 설정을 유지한다. `down -v`를 사용하지 않는다.
- Grafana 화면의 짧은 중단은 허용하되 수집 중인 Prometheus를 접속 전환 때문에 재시작하지 않는다.
- 인증서 발급·자동 갱신·반영까지 완료한다. GitHub Actions 배포 자동화와 새로운 알림 시스템은 이번 범위에 추가하지 않는다.
- 실제 환경변수, 인증서 개인키, ACME 계정, AWS·DNS 인증 정보는 저장소와 작업 출력에 넣지 않는다. 실제 Compose 설정 검사는 `config --quiet`로 한다.
- Nginx `nginx:1.30.5-alpine3.24`와 Certbot `certbot/certbot:v5.8.0`을 고정했다. 공식 이미지 manifest와 대상 x86_64 지원을 확인했고 대상 서버에서 pull·설정 검사를 통과했다. 이후 업데이트도 검증한 고정 버전으로 적용한다.
- 사용자 승인에 따라 운영 적용을 진행한다. Computer Use의 보안 공개·비용·약관 확인은 해당 실행 직전에 구체적인 변경 범위로 처리한다.

## 확인된 사실과 실행 전 입력

2026-10-01 **사전 계획 시점**의 확인 결과다. 이후 로그인 연결·파일 구현·운영 변경의 현재 상태는 실행 단계 기록과 체크박스로 구분한다.

- `catchhole.com`의 권한 NS는 `ns.gabia.co.kr`, `ns.gabia.net`, `ns1.gabia.co.kr`이다.
- `monitoring.catchhole.com`은 NXDOMAIN이며 A·AAAA·CNAME 레코드가 조회되지 않았다.
- 현재 운영 Compose의 Grafana·Prometheus UI는 각각 호스트 localhost 3000·9090에 바인딩한다. Grafana 데이터 소스는 `http://prometheus:9090`이다.
- 모니터링 Nginx·Certbot 설정이나 자동 배포 workflow는 없다.
- 로컬 AWS CLI와 dig는 설치돼 있으나 AWS 프로필·자격 증명은 없고, 기존 브라우저에도 AWS 콘솔 탭이 없다. EC2·보안 그룹·서브넷·공인 IP 상태는 아직 확인하지 못했다.

실행자는 AWS 인증, 가비아 DNS 수정 접근, 모니터링 EC2 식별, 현재 서버 구성, ACME 등록 연락처를 확보한다. 비밀번호나 access key를 채팅으로 받지 않고 기존 로그인·프로필을 이용한다. 실제 EC2가 사설 서브넷이라 직접 공개할 수 없으면 리소스 추가나 네트워크 변경을 임의로 실행하지 않고 필요한 대안과 비용을 먼저 구체화한다. 기존 EIP가 있으면 재사용하고, 없으면 고정 공인 주소를 확보할 필요와 비용을 실제 현황에 맞춰 확인한다.

실행 단계에서 추가 확인한 사실:

- AWS·가비아의 기존 로그인과 모니터링 서버의 SSM 관리 경로를 연결했다. 서울 리전의 모니터링 EC2는 x86_64 Ubuntu이며 퍼블릭 서브넷의 활성 IGW 라우팅을 사용한다. 기존 공인 주소는 자동 할당이고 해당 서버의 EIP는 없었다. 다른 서버에 연결된 기존 EIP는 재사용하지 않는다.
- 모니터링 SG의 기존 인바운드는 없고 호스트 방화벽·포트 점유 검사에서 80·443 진입에 사용할 기존 서비스가 없음을 확인했다. Docker Compose·systemd와 디스크 여유도 확인했다.
- 기존 Grafana·Prometheus 버전, localhost 바인딩, Compose 프로젝트·영속 볼륨과 scrape 성공을 확인했다. 설정 원본·실제 env·targets와 일관된 Grafana SQLite 백업을 서버의 제한된 경로에 보관했고 무결성 검사를 통과했다. 기존 Grafana 로그인·대시보드 API와 목록도 확인했다.
- 가비아의 기존 레코드와 신규 모니터링 레코드 부재를 확인했다. 기존 API·루트·www·인증용 레코드는 유지한다.
- Nginx·갱신 스크립트·unit·운영 문서 구현, 대상 서버의 설정 검사와 격리된 HTTP/HTTPS·인증·Live 연결 검증을 완료했다. 격리 검증은 실제 도메인의 인증서 발급이나 공개 접속 완료를 뜻하지 않는다.
- 사용자는 공개 80·443·도메인 변경, 신규 EIP의 비용, Let's Encrypt 약관에 동의하고 ACME 연락처를 제공했다. 실제 연락처는 서버의 `monitoring.env`에서 관리하며 공개 계획에는 넣지 않는다. 실제 EIP·SG·DNS 적용, 인증서 발급·갱신과 공개 로그인 검증은 아래 Task 4의 완료 증거로 따로 확인한다.

실제 운영 적용·검증 결과:

- 모니터링 EC2에 신규 EIP를 연결하고 SG에 IPv4 TCP 80·443만 추가했다. 가비아의 모니터링 A 레코드를 저장하고 기존 레코드는 보존했다. 외부 Mac과 별도 AWS CloudShell 네트워크에서 기본 DNS·신뢰되는 TLS로 HTTPS 응답을 확인했다.
- Let's Encrypt 인증서를 발급했고 만료 시각은 **2026-12-30 12:31:05 KST**다. 실제 인증서·갱신 설정·ACME 계정의 영속 볼륨과 Nginx의 읽기 전용 마운트를 확인했다. HTTPS 설정과 Grafana 외부 URL·secure cookie를 적용한 뒤 Nginx 검사·reload가 성공했다.
- 실제 HTTPS의 로그인·오류 계정 차단·secure/HttpOnly cookie·대시보드 API·데이터 소스·Live WebSocket·로그아웃 세션 무효화를 검증했다. 기존 사용자·권한은 백업과 동일하고 대시보드 목록도 유지됐다. Chrome에서 경고 없이 로그인한 뒤 실제 대시보드의 Spring CPU 사용률 패널과 `application=catchhole-backend`, `environment=prod`, `job=catchhole-backend` 시리즈가 렌더링되는 것을 확인했다.
- Prometheus 프로세스 시작 시각과 기존 볼륨을 유지했고 scrape `up=1`, 최근 한 시간의 지표 이력과 기본 CPU 지표를 확인했다. 모니터링 3000·9090·8081과 API 8081의 외부 연결은 차단됐고 호스트 localhost 바인딩도 유지됐다.
- 인증서 dry-run이 성공했고 최종 `--no-random-sleep-on-renew` 설정을 서버에 반영했다. 실제 oneshot은 `Result=success`, `ExecMainStatus=0`으로 종료했으며 Nginx 검사·reload도 성공했다. timer는 enabled·active이고 검증 시점의 다음 실행은 2026-10-01 15:25:13 KST다. Docker·컨테이너·timer의 부팅 후 실행 설정을 확인했으며 수집 프로세스를 보존하기 위해 실제 서버 재부팅은 수행하지 않았다. 갱신 대상이 없어 운영 인증서는 재발급하지 않았다.

## File Structure

모든 경로는 백엔드 저장소 루트 기준이다. 서버 배포 경로는 기존 `/opt/catchhole-monitoring`을 사용한다.

| 파일 | 역할 |
| --- | --- |
| `deploy/compose.monitoring.prod.yml` | Nginx·일회성 Certbot, 포트·마운트·Grafana 외부 URL 설정 |
| `deploy/monitoring.env.example` | Nginx 설정 파일 선택과 ACME 연락처의 비밀값 없는 예시 |
| `deploy/monitoring/nginx/bootstrap.conf` | 인증서가 없어도 실행되는 HTTP challenge 전용 설정 |
| `deploy/monitoring/nginx/grafana.conf` | challenge·HTTP 이동·HTTPS·Grafana 프록시·WebSocket 설정 |
| `deploy/monitoring/renew-certificate.sh` | Certbot 갱신 성공 → Nginx 검사 → reload; 실패 시 nonzero 종료 |
| `deploy/monitoring/systemd/catchhole-monitoring-certbot.service` | 서버에서 갱신 스크립트를 실행하는 oneshot unit |
| `deploy/monitoring/systemd/catchhole-monitoring-certbot.timer` | 하루 두 번 갱신 시도, 실행 분산과 missed-run 처리 |
| `deploy/MONITORING_DEPLOYMENT.md` | 사전 점검·최초 발급·수동 배포·갱신·검증·롤백·팀원 안내 |
| `AGENTS.md` | HTTPS 접속과 인증서 운영 규칙의 단일 출처 갱신 |

## Review Focus

1. 인증서가 없는 첫 배포: bootstrap Nginx가 시작되고 HTTP에 Grafana 로그인 화면을 제공하지 않는다.
2. 인증서 갱신·재부팅: 계정·renewal 설정·인증서가 유지되고 실제 인증서 파일이 Nginx에 반영된다.
3. 갱신 실패·잘못된 Nginx 설정: 실패가 로그·종료 코드에 남고 기존 서비스 설정을 무리하게 반영하지 않는다.
4. Grafana 연결: HTTPS 리다이렉트·보안 쿠키·WebSocket·컨테이너 재생성 후 upstream 연결이 올바르다.
5. 전환·롤백: 기존 대시보드와 메트릭 이력이 유지되고 공개 메트릭 포트가 없으며 HTTP SSM 복구에는 이전 Grafana URL·쿠키 설정 복원이 필요하다.

## Task 1: 서버·DNS 사전 점검과 변경 기록

**Files:** `deploy/MONITORING_DEPLOYMENT.md`의 운영 적용 절차. 실제 리소스 값은 비공개 운영 기록으로 보관한다.

**Interfaces:** AWS 서울 리전의 기존 모니터링 EC2, 현재 Docker Compose 프로젝트·볼륨, 가비아 `catchhole.com` DNS.

- [x] AWS 인증을 연결한 뒤 대상 인스턴스의 이름·역할·서울 리전을 확인한다. API·Worker 인스턴스와 구분한다.
- [x] 서브넷·IGW 라우팅·공인 IP/EIP·보안 그룹·호스트 방화벽·80/443 포트 점유·Docker·systemd·디스크 여유를 읽기 전용으로 확인한다. 직접 외부 진입이 가능한지 판정한다.
- [x] 배포된 Compose와 Grafana 환경변수·계정 접속·대시보드·실제 볼륨 이름·현재 scrape 상태를 확인한다. 설정 원본과 Grafana DB/볼륨의 복구 가능한 백업을 확보하고 보안 그룹·DNS 변경 전 값을 기록한다.
- [x] 같은 호스트의 다른 서비스를 건드리지 않는 80/443 진입 계획과 고정 주소 계획을 확정한다. 기존 3000/9090/8081의 제한을 확인한다.
- [x] 가비아 DNS 수정 접근과 ACME 연락처를 확인한다. 다른 도메인·메일 레코드는 변경하지 않는다.

**완료 증거:** 대상과 접근 경로·현재 배포 버전·볼륨·백업·수집 상태·변경 전 네트워크 상태가 기록된다. AWS 현황 미확인을 배포 성공으로 처리하지 않는다.

## Task 2: Nginx·Grafana 접속 설정과 최초 발급 경로

**Files:** Compose, env example, 두 Nginx conf, 배포 문서, `AGENTS.md`.

**Interfaces:** `MONITORING_NGINX_CONFIG`는 두 conf 중 활성 파일을 선택한다. `ACME_EMAIL`은 최초 계정 등록 연락처다. `acme_webroot`·`letsencrypt_data` named volume을 Nginx와 Certbot이 공유하고 Nginx에서는 읽기 전용으로 마운트한다. Certbot 서비스는 `maintenance` profile의 일회성 실행으로 두고 Docker socket을 마운트하지 않는다.

`acme_webroot`는 두 컨테이너의 `/var/www/certbot`에, `letsencrypt_data`는 `/etc/letsencrypt` 전체에 마운트한다. 인증서 `live/`가 참조하는 `archive/`의 심볼릭 링크를 보존하기 위해 특정 도메인의 `live/` 디렉터리만 마운트하지 않는다. Certbot webroot 경로는 `/var/www/certbot`으로 고정한다.

- [x] 두 Nginx 설정을 작성한다. bootstrap은 `monitoring.catchhole.com`의 `/.well-known/acme-challenge/`만 제공하고 나머지는 404로 처리한다. HTTPS 설정은 challenge를 유지하고 일반 HTTP를 고정 HTTPS 주소로 이동한다.
- [x] HTTPS 설정은 인증서 체인·개인키 경로를 `/etc/letsencrypt/live/monitoring.catchhole.com/` 아래에 두고 `grafana:3000`으로 전달한다. 원래 Host·외부 HTTPS 정보를 전달하고 Grafana Live의 HTTP/1.1 WebSocket Upgrade를 지원한다. 잘못된 Host로 임의 도메인 리다이렉트가 만들어지지 않게 한다.
- [x] Compose에 Nginx의 외부 80/443, 두 공유 볼륨, profile에 포함된 Certbot을 추가한다. 기존 프로젝트·서비스·볼륨 이름은 유지한다. conf 파일 선택을 바꿀 때 Nginx를 재생성하여 single-file bind mount 교체를 확실하게 반영한다.
- [x] Grafana에 `GF_SERVER_DOMAIN=monitoring.catchhole.com`, `GF_SERVER_ROOT_URL=https://monitoring.catchhole.com/`, `GF_SECURITY_COOKIE_SECURE=true`를 설정한다. 내부 protocol은 HTTP를 유지한다. 실제 적용은 인증서가 준비된 이후 해당 서비스만 재생성한다.
- [x] 임시 값·격리 볼륨으로 `docker compose ... config --quiet`와 bootstrap `nginx -t`를 실행한다. 임시 로컬 인증서로 HTTPS `nginx -t`도 검사한다. 개인키와 실제 비밀값을 출력하지 않는다.
- [x] 격리 환경에서 challenge 요청 200, 일반 bootstrap 요청 404, HTTP→HTTPS 이동, HTTPS Grafana 로그인 화면 접근, 인증 전 대시보드 접근 제한을 확인한다. `/api/live/`는 유효한 로그인 세션에서 WebSocket 연결을 검증한다.
- [x] 배포 문서와 `AGENTS.md`에 두 단계 발급 순서와 HTTPS 접근 규칙을 반영한다. 팀원 안내는 URL과 Grafana 로그인으로 작성한다.

Grafana 외부 URL·secure cookie는 인증서 발급 뒤 실제 운영 컨테이너에 적용했다. 실제 HTTPS 로그인 응답의 secure·HttpOnly cookie를 확인했고 Prometheus를 재생성하지 않았다.

**완료 증거:** Compose·Nginx 검사가 통과하고 최초 인증서가 없는 상태에서도 bootstrap이 정상 실행된다. 인증서가 준비되지 않았는데 HTTPS Nginx부터 시작하는 순서를 만들지 않는다.

## Task 3: 인증서 자동 갱신과 반영

**Files:** `renew-certificate.sh`, service·timer, 배포 문서와 `AGENTS.md`의 갱신 규칙.

**Interfaces:** 서버의 `/opt/catchhole-monitoring`에서 실제 `monitoring.env`와 기존 Compose를 사용한다. Certbot 서비스의 기본 명령과 갱신 스크립트는 `renew --cert-name monitoring.catchhole.com --non-interactive --no-random-sleep-on-renew`를 사용한다. 실행 분산은 systemd timer 한 곳에서 담당하고 Certbot의 추가 지연을 꺼 oneshot의 10분 제한을 소모하지 않도록 한다. Certbot 성공 후에만 실행 중 Nginx의 `nginx -t`와 `nginx -s reload`를 순서대로 실행한다.

- [x] 갱신 스크립트를 작성한다. Certbot 실패나 Nginx 검사 실패는 nonzero로 종료하고 후속 reload를 하지 않는다. systemd는 이미 실행 중인 oneshot의 중복 실행을 만들지 않는다.
- [x] 최소 구성을 위해 `renew`가 성공하면 갱신 대상이 없었던 경우에도 graceful reload한다. 성공 코드만으로 이번에 인증서가 갱신됐다고 보고하지 않는다. 프로덕션 인증서를 강제로 재발급하는 테스트를 만들지 않는다.
- [x] timer **파일**에 `OnCalendar=*-*-* 03,15:00:00 Asia/Seoul`, `RandomizedDelaySec=30m`, `Persistent=true`를 설정한다. oneshot은 root로 서버 측 Docker 명령을 실행하고 stdout/stderr는 journald에 남긴다. 실패 확인 방법은 `systemctl status`·`journalctl`로 문서화한다. 운영 timer의 설치·활성화는 Task 4에서 별도로 검증한다.
- [x] `bash -n`과 대상 Linux의 `systemd-analyze verify`로 스크립트·unit을 검사한다. 격리 환경의 대체 Docker 명령으로 Certbot 실패 시 reload 없음, Nginx 검사 실패 시 reload 없음, 정상 순서를 확인한다.
- [x] 실제 운영 적용 후 Certbot `renew --dry-run`으로 staging 검증을 통과시킨다. 실행 분산을 timer에 모으도록 최종 갱신 명령과 이후 dry-run에는 `--no-random-sleep-on-renew`를 적용한다. 별도로 oneshot을 정상 실행해 Nginx 반영 경로를 검사한다. `systemctl list-timers`에서 다음 실행 시각을 확인한다.

**완료 증거:** 실패가 조용히 성공으로 처리되지 않고, dry-run·실행 경로·timer 등록이 검증된다. 새 외부 알림 채널은 생성하지 않는다.

## Task 4: DNS·AWS 적용, HTTPS 전환과 운영 검증

**Files:** 배포된 Task 2·3 파일, 비공개 운영 적용 기록, 저장소 배포 문서의 재현 절차.

**Interfaces:** Task 1에서 확인한 모니터링 EC2와 고정 공인 주소, Task 2 bootstrap·HTTPS conf, Task 3 갱신 timer.

- [x] 정확한 검토 버전의 배포 파일을 `/opt/catchhole-monitoring`에 반영하고 현재 secret·targets JSON을 보존한다. 최종 갱신 옵션을 포함한 파일 반영과 `config --quiet`를 확인한다.
- [x] 기록한 범위대로 EC2의 외부 TCP 80·443만 허용하고 가비아에 `monitoring.catchhole.com` A 레코드를 설정한다. AAAA가 있다면 도달 가능한 IPv6와 일치하는지 확인한다. `dig`로 대상 주소를 확인한다.
- [x] bootstrap 설정으로 Nginx만 `--no-deps` 실행하고 외부 challenge 접근을 확인한다. 기존 Grafana·Prometheus를 일괄 재생성하지 않는다.
- [x] Certbot webroot로 도메인과 동일한 cert-name `monitoring.catchhole.com`의 최초 인증서를 발급한다. 기존 ACME 계정이 있으면 재사용하고, 새 계정의 연락처·서비스 약관 처리는 발급 시 실제 계정 기준으로 확인한다.
- [x] 발급 성공 후 활성 conf를 HTTPS로 바꿔 Nginx를 재생성하고 Task 2의 외부 URL·secure cookie를 Grafana에 적용한다. Grafana 재생성 뒤 Nginx 검사·reload로 upstream 주소를 다시 해석한다.
- [x] SSM 터널을 닫은 브라우저에서 신뢰할 수 있는 HTTPS 인증서·로그인·대시보드·로그아웃·Live 연결을 확인한다. 잘못된 계정은 접근할 수 없어야 한다. 다른 외부 네트워크에서도 HTTPS에 도달하는지 확인한다.
- [x] 서버 설정과 보안 그룹에서 3000·9090·8081의 외부 비공개를 확인한다. 기존 Grafana 대시보드·사용자와 Prometheus 이력·`up{job="catchhole-backend"}=1`을 확인한다.
- [x] Task 3의 timer를 설치·활성화하고 dry-run과 oneshot 검증을 수행한다. 배포 버전·DNS·보안 그룹 변경·인증서 만료일·갱신 검증·데이터 보존 결과를 기록한다.

실제 인증·대시보드 API·로그아웃·Live와 두 외부 네트워크의 HTTPS 접근은 통과했다. 사용자 로그인 후 Chrome에서 실제 대시보드와 운영 CPU 시리즈가 렌더링되는 것도 확인해 브라우저 검증을 완료했다.

**완료 증거:** 파일 작성뿐 아니라 실제 `https://monitoring.catchhole.com` 로그인·조회, 자동 갱신 경로, 메트릭·데이터 보존이 모두 확인된다.

## 롤백

- Certbot 최초 발급이 실패하면 bootstrap을 유지하고 기존 Grafana URL·쿠키 설정을 적용하지 않는다. 기존 SSM 접속은 그대로 사용할 수 있다.
- HTTPS 전환 이후 복구가 필요하면 SSM 서버 관리 경로로 접속해 이전 Compose와 env를 함께 복원하고 Grafana만 재생성한다. 이전 Grafana URL·쿠키 설정이 복원됐는지 확인하며, HTTPS용 secure cookie 상태에서 HTTP localhost SSM 로그인이 그대로 동작한다고 가정하지 않는다.
- Nginx·timer를 정지하고 이번에 추가한 DNS·보안 그룹 변경만 기록된 이전 상태로 되돌린다. 공용 리소스를 임의로 해제하지 않는다.
- Grafana·Prometheus·인증서 볼륨은 삭제하지 않는다. 문제가 접속 설정이면 DB·이미지 downgrade 없이 접속 설정만 복구한다.

## 계획 검토와 진행 상태

- [x] 도메인·Nginx·로그인 유지·접속 범위·중단 허용·실제 운영 적용 범위·자동화 범위 합의.
- [x] 저장소·공개 DNS·로컬 AWS 인증 상태를 읽기 전용으로 확인.
- [x] 계획 작성. 실행 단계의 검증·복구 절차와 미확인 운영 조건을 명시.
- [x] 자체 검토와 독립 리뷰 완료. 인증서 전체 경로 마운트와 rollback 조건을 확인.
- [x] 사용자와 최종 계획의 공유 이해 확인 및 Computer Use 실행·goal 승인.
- [x] AWS·DNS 접근 연결, 모니터링 EC2의 실제 운영 현황·백업과 ACME 연락처 확인.
- [x] 구현·운영 적용·검증의 전체 완료: 로그인 후 실제 브라우저 대시보드 렌더링의 최종 확인 포함.

진행 기록: `/private/tmp/catchhole-grafana-https-ops-progress.md`. 실제 공개 네트워크·DNS 전환, Let's Encrypt 발급·dry-run·갱신 timer와 Nginx 반영, Grafana 외부 URL·secure cookie, 인증·대시보드 API·Live, 기존 사용자·권한·메트릭 보존을 검증했다. 사용자 로그인 뒤 Chrome에서 실제 대시보드와 운영 CPU 시리즈가 렌더링된 최종 증거는 `/private/tmp/catchhole-grafana-logged-in.jpg`다. 이 계획의 Nginx HTTPS 전환은 완료했으며 이후 1차 6패널 구성 작업은 별도 범위로 진행한다.

## 근거

- [Grafana Nginx 프록시와 Live WebSocket](https://grafana.com/tutorials/run-grafana-behind-a-proxy/)
- [Certbot webroot·자동 갱신](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates)
- [Nginx graceful reload](https://nginx.org/en/docs/control.html#reconfiguration)
- [Nginx 공식 Docker 이미지 태그](https://github.com/docker-library/official-images/blob/master/library/nginx)
- [AWS Elastic IP와 요금 안내](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/elastic-ip-addresses-eip.html)
