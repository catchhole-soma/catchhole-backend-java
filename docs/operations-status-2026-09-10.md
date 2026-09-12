# 운영 현황 확인 — 2026-09-10 (#171)

[이슈 #171](https://github.com/catchhole-soma/catchhole-backend-java/issues/171)의 운영 현황·문서 정합성 확인 기록이다. 현재 구조의 설명과 구성도는 [Infrastructure Flow](infrastructure-flow.md), 배포 명령은 [API 배포 문서](../deploy/EC2_DEPLOYMENT.md)를 기준으로 한다.

- 확인일: **2026-09-10, Asia/Seoul(KST)**. 컨테이너 최초 조회 14:32, 잔존 파일·DB 설정 추가 조회 14:56.
- 대상: CatchHole 운영 AWS 계정의 **서울 리전 `ap-northeast-2`**, 공개 API·프론트 도메인.
- 저장소 기준: Java `74fd011`, AI 실행 이미지 `2dde183`. 실행 버전은 이 날짜의 관측값이며 다음 배포에서 바뀔 수 있다.
- 확인 방법: AWS 조회 API, 기존 SSM을 통한 Docker 조회, 기존 Worker 컨테이너에서 읽기 전용 PostgreSQL 설정 조회, 공개 DNS·HTTP 응답 조회, 같은 날 빌링온 운영 계정의 월별·일별 사용금액 조회.
- 적용 범위: 현황 확인과 문서 정리. 운영 리소스·SG·DB 설정을 변경하거나 컨테이너를 재배포하지 않았고, 신규 분석 Job·메일/SMS 발송·복구 훈련을 수행하지 않았다.
- 공개 기록에는 리소스 ID·ARN·실제 IP·RDS 엔드포인트·접속 문자열·비밀값을 넣지 않는다. 원본 조회 결과는 권한 있는 운영 계정의 콘솔·SSM 실행 기록에서 확인한다.

## 1. 운영 구성

| 대상 | 확인한 운영 상태 | 근거 |
| --- | --- | --- |
| API EC2 | `running`, `t3.medium`, `ap-northeast-2a`; gp3 30GiB, 3,000 IOPS·125MiB/s | `ec2 describe-instances`, `describe-volumes` |
| API 컨테이너 | Backend·Caddy·Redis 각 1개, 모두 `running`; Redis `healthy` | SSM에서 Docker 컨테이너 목록·선택 필드 조회 |
| Worker EC2 | `running`, `t3.medium`, `ap-northeast-2d`; gp3 30GiB, 3,000 IOPS·125MiB/s | EC2·EBS 조회 |
| Worker 컨테이너 | 설정 추출 5개 + 캐릭터 재비교 1개 + 세계관 재비교 1개, 모두 `running` | SSM에서 Docker 서비스 라벨·상태 조회 |
| 분석 동시성 | 설정 추출은 컨테이너당 Job 10개, 총 50개 슬롯; 두 재비교는 각각 1개 | 실행 컨테이너의 비밀값을 제외한 설정 조회. 처리량 실측이나 provider 전체 동시성 보장을 뜻하지 않음 |
| RDS | `available`, PostgreSQL **16.14**, `db.t4g.small`, **Single-AZ**(`ap-northeast-2d`), gp3 30GiB, 3,000 IOPS·125MiB/s | `rds describe-db-instances` |
| DB 공유 | Backend와 Worker 7개의 연결 대상이 같은 운영 RDS와 일치 | 컨테이너 내부에서 URL의 호스트만 대조하고 일치 여부만 출력 |
| pgvector·DB 시간대 | pgvector **0.8.2**, 조회 세션 `timezone=Asia/Seoul` | 읽기 전용 SQL. JVM·각 세션의 시간대 전체 검증은 별도 |
| Redis | API EC2의 Docker 네트워크 내부, 호스트에 6379 미게시 | 실제 포트 매핑 및 Backend의 `REDIS_HOST=redis` |
| 프론트 | `https://www.catchhole.com`, HTTP 200, `server: Vercel`; 공개 번들의 API 대상은 `https://api.catchhole.com` | 공개 HTML·JavaScript·응답 헤더 |
| API 진입점 | `https://api.catchhole.com/actuator/health` HTTP 200·`status=UP`, Caddy 경유 | 공개 health 응답·헤더 |
| DNS | 권한 NS는 `ns.gabia.net`, `ns.gabia.co.kr`, `ns1.gabia.co.kr`; `www` CNAME은 Vercel, `api` A 레코드는 API EC2 공개 주소와 일치 | 공개 `dig` 조회. DNS 관리 계정·변경 책임자는 미확인 |
| 가입 인증 | 실행 Backend에서 `SIGNUP_VERIFICATION_METHOD=EMAIL`, `EMAIL_PROVIDER=smtp` 확인 | 현재 설정 확인. 실제 전달·가입 재검증은 이번 점검 범위에 없음 |

Caddy는 `caddy:2.8-alpine`, Redis는 `redis:7.4.10-alpine3.21`을 실행 중이다. Backend는 `sha-74fd011`, Worker 7개는 모두 `sha-2dde183`을 사용한다. 운영 서버에 PostgreSQL 컨테이너는 없으며, 현재 DB는 외부 RDS다.

## 2. 연결 경로와 접근 허용 범위

아래는 **현재 SG·포트 설정을 관측한 결과**다. 예외의 상시 유지나 새로운 접근 허용을 결정한 정책 문서로 해석하지 않는다.

| 출발지 | 대상 | 포트·접근 범위 | 확인 결과 |
| --- | --- | --- | --- |
| 사용자 브라우저 | API EC2 Caddy | TCP 80·443, IPv4 인터넷 허용 | 실제 SG와 Docker 게시 포트 확인, 외부 HTTPS 정상 |
| Caddy | Backend | Docker 내부 `backend:8080` | 저장소 Caddy 설정과 실행 컨테이너 배치 대조 |
| Worker EC2 | API EC2 Backend | TCP 8080, Worker SG를 소스로 허용 | 모든 Worker 내부 API 설정이 API EC2 사설 주소의 HTTP 8080과 일치 |
| Backend·Worker | RDS | TCP 5432, API SG·Worker SG 허용 | 실제 RDS SG·컨테이너 연결 대상 대조, Worker에서 DB 메타데이터 조회 성공 |
| 운영 접근용 IPv4 2개 | RDS | TCP 5432, 각각 `/32` 허용 | SG 설명상 팀원 자택·SW 사무실 Wi-Fi의 DB 직접 접속용 허용. 현재 사용 여부·유지 기간은 미확인 |
| Backend | Redis | Docker 내부 TCP 6379 | 호스트 게시 포트 없음 |
| 운영 SSH 접근용 IPv4 2개 | API EC2 | TCP 22, 각각 `/32` 허용 | 실제 SG 확인. 이번 점검은 기존 SSM 사용 |
| 외부 인바운드 | Worker EC2 | 허용 규칙 없음 | Worker SG 인바운드 목록이 비어 있음 |

운영자 접속용 `/32` 소스를 두 SG에서 대조하면 **중복을 제외한 IP는 3개**다. RDS는 `SW 사무실 + 자택 A`, API EC2의 SSH는 `같은 SW 사무실 + 다른 자택 B`를 허용한다. A·B는 실제 IP를 공개하지 않기 위한 문서 내 구분이며 팀원별 실명 매핑을 뜻하지 않는다. 2026-09-10 팀 설명은 센터를 주 접속 장소로 쓰고 팀원 2명의 자택 IP도 허용했던 것으로 기억한다는 내용으로, SG 설명·중복 관계와 부합한다. 두 자택 IP가 RDS와 SSH에 모두 허용되어 있다고 해석하지 않으며, 현재 접속 사용 여부는 별도 실측하지 않았다.

RDS는 `PubliclyAccessible=true`다. 5432를 전체 인터넷에 허용한 설정은 아니며 위 SG·특정 IP 제한이 존재한다. API·Worker·RDS SG의 아웃바운드는 IPv4 전체 허용이다. 공인 IPv4가 연결된 네트워크 인터페이스는 EC2 2개와 RDS 1개, 총 3개를 확인했다.

RDS의 `/32` 허용과 API EC2의 SSH(TCP 22) 허용은 **SSM 접속을 위한 인바운드 규칙이 아니다**. [SSM Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)는 관리 대상 EC2에 접속자의 IP를 인바운드로 허용할 필요가 없다. SSM으로 API·Worker EC2에 들어간 뒤 RDS에 접속하는 경로는 해당 EC2 SG에 대한 RDS 5432 허용을 사용한다. SG 설명은 등록 당시 용도를 나타내며 현재 접속자·사용 여부의 실측 증거로 취급하지 않는다.

## 3. 백업과 DB 연결 한도

### 3.1 RDS 백업·보호

| 항목 | 실제 값 | 확인 근거·후속 작업 |
| --- | --- | --- |
| 자동 백업 보존 | **7일** | RDS `BackupRetentionPeriod` |
| 백업 윈도 | UTC **16:35–17:05**, KST **01:35–02:05** | RDS `PreferredBackupWindow` |
| 최근 복원 가능 시점 | **2026-09-10 14:55:10 KST** | 추가 조회 당시 `LatestRestorableTime`; 이후 계속 변하는 관측값 |
| 삭제 보호 | **활성** | RDS `DeletionProtection=true` |
| 스토리지 암호화 | **활성** | RDS `StorageEncrypted=true` |
| RPO·RTO 목표 / 정책 적정성 | **미합의·미검증** | [#172](https://github.com/catchhole-soma/catchhole-backend-java/issues/172)에서 팀 목표·최종 스냅샷 절차와 함께 점검 |
| 실제 백업 복구 성공·소요 시간 | **미검증** | [#175](https://github.com/catchhole-soma/catchhole-backend-java/issues/175)의 격리 DB 복구 훈련 |

7일 보존 설정과 복원 가능 시점 확인만으로 목표 데이터 손실량이나 복구 시간을 충족했다고 판단하지 않는다.

### 3.2 DB 한도와 애플리케이션 연결 예산

| 항목 | 확인값 |
| --- | --- |
| 실제 PostgreSQL `max_connections` | **181** |
| `superuser_reserved_connections` | **3** |
| `reserved_connections` | **2** |
| Backend Hikari 최대 풀 | **10** |
| 설정 추출 Worker SQLAlchemy 풀 | **5 × 3 = 15**, 각 `max_overflow=0` |
| 캐릭터·세계관 재비교 풀 | **1 + 1 = 2**, 각 `max_overflow=0` |
| 현재 애플리케이션 최대 연결 예산 | **10 + 15 + 2 = 27** |
| 조회 시점 `pg_stat_activity` 행 수 | **20**; 점검 연결·DB 내부 연결을 포함할 수 있는 단일 시점 값 |

181은 DB의 실제 설정이고 27은 현재 애플리케이션의 풀 상한이다. 두 값을 같은 한도로 기록하지 않는다. 예약 슬롯·관리·migration·다른 클라이언트 연결이 있으므로 단순 차감으로 가용 연결 수나 증설 가능량을 확정하지 않는다. 연결 수 알림 기준과 관리 여유는 [#174](https://github.com/catchhole-soma/catchhole-backend-java/issues/174)에서 관찰 결과와 함께 정한다.

SQL 조회는 기존 Worker의 연결 설정을 내부에서 사용하되 `default_transaction_read_only=on`, 연결 제한 5초·쿼리 제한 5초를 적용했다. 설정·확장 메타데이터만 읽었으며 업무 데이터는 조회하지 않았다.

## 4. 기존 통합 배포 파일·로컬 DB 상태

| 대상 | 확인 결과 |
| --- | --- |
| API 서버 `/opt/catchhole/.env`, `compose.prod.yml` | **파일 존재**. 내용·비밀값은 출력하지 않음 |
| API 서버의 현재 배포 파일 | `api.env`, `compose.api.prod.yml` 존재 |
| API 서버 PostgreSQL 컨테이너 | 실행·중지 컨테이너 전체 목록에서 **미발견** |
| API 서버 Docker 볼륨 | Caddy용 2개와 현재 Redis가 사용하는 익명 볼륨 1개만 조회됨. PostgreSQL 전용 볼륨은 미발견 |
| Worker 서버 | `worker.env`, `compose.worker.prod.yml` 존재. 이전 `.env`·통합 Compose 없음, Docker 볼륨 목록 비어 있음 |
| 과거 DB 데이터의 별도 백업·정리 이력 | **미확인**. 담당자에게 기존 볼륨 정리 여부·별도 백업 위치·잔존 파일 보존 이유 확인 필요 |

Docker 조회 범위 밖의 호스트 파일·별도 스냅샷까지 없다고 단정하지 않는다. 기존 통합 파일이 있다는 이유로 로컬 PostgreSQL을 검증된 복구 대상으로 안내하지 않는다. 잔존 파일·볼륨은 이번 작업에서 삭제하거나 변경하지 않았다.

## 5. 월 비용 — 실청구와 예상 구분

### 5.1 집계 사용금액 확인·최종 청구 미확인

2026-09-10 기존 SSO 권한으로 9월 1일~9월 9일의 서비스별 일 비용을 조회했지만, `ce:GetCostAndUsage`가 AWS 조직의 **SCP 명시적 거부**로 실패했다. `pricing:GetProducts`도 동일한 종류의 정책 제한으로 조회하지 못했다. 이후 사용자가 제공한 [빌링온 대시보드](https://www.billing-on.com/dashboard/customerDashboard)와 [비용 탐색기](https://www.billing-on.com/costExplorer/listCostExplorer)에서 **운영 AWS 계정 ID가 일치하는 계정만 선택**해 사용금액을 확인했다. 그룹 전체 합계는 CatchHole 비용으로 사용하지 않는다.

| 기간·기준 | 확인 금액(USD) | 해석 |
| --- | ---: | --- |
| 2026년 7월 | 약 **24.41** | 월별 서비스 표시액 합산 |
| 2026년 8월 | 약 **25.82** | EC2 22.08 + VPC 3.72 + S3 0.01 + Data Transfer 0.01; RDS 표시액 0 |
| 2026년 9월 조회 시점 누적 | **31.11** | 대시보드의 해당 Account 표시액. EC2 19.40, RDS 9.35, VPC 2.26, S3 0.05, Data Transfer 0.05 |

월별·일별 표의 서비스 금액은 소수 둘째 자리 표시값이므로 합산 시 반올림 오차가 있을 수 있다. 일별 표에는 9월 9일까지 나타나지만 9일의 반영 금액이 작고 집계 완료 시각은 미확인이다. 9월 누적액을 단순히 조회일까지의 날짜 수로 나눠 현재 구성의 월 비용을 계산하지 않는다.

9월 1일은 EC2 약 $0.72·VPC $0.12이고, 9월 2일부터 RDS 비용이 나타난다. 분리 구성 비용이 안정적으로 나타나는 최근 3일은 다음과 같다.

| 빌링온 표시일 | EC2 | RDS | VPC | S3 | Data Transfer | 표시액 합계 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2026-09-06 | 2.73 | 1.36 | 0.36 | 0.00 | 0.01 | **4.46** |
| 2026-09-07 | 2.73 | 1.36 | 0.36 | 0.00 | 0.01 | **4.46** |
| 2026-09-08 | 2.73 | 1.36 | 0.36 | 0.03 | 0.01 | **4.49** |

위 3일 평균은 약 **$4.47/일**이다. 같은 사용 수준을 유지한다고 가정하면 **30일 약 $134.10**, 730시간으로 환산하면 **약 $135.96**으로, 아래 공개 가격표의 기본 비용 소계 $133.50과 대체로 일치한다. 이 계산은 현재 구성의 지속 운영 규모를 비교한 것으로, 분리 전후가 섞인 9월 전체 청구액이나 빌링온 자체 예측값을 뜻하지 않는다. 소액 차이의 세부 과금 항목은 이번 서비스별 집계만으로 확정하지 않는다.

빌링온 사용금액을 할인·크레딧·세금이 반영된 최종 인보이스·원화 결제액으로 단정하지 않는다. 최종 청구 조건·외부 서비스 비용은 담당자에게 추가 확인해야 하며 담당자·확인 일정은 아직 정하지 않았다.

### 5.2 확인 자원의 기본 월 예상 비용

계정 청구 데이터와 별도로 **AWS 공식 공개 가격표**를 조회해 계산했다. 가정은 서울 리전, Linux 공유 테넌시, On-Demand, 월 **730시간** 지속 운영, USD다. 월 시간 수를 표준화한 예산값이며 특정 달의 확정 청구 예측이 아니다.

| 항목 | 실제 확인 수량·사양 | 공개 단가 | 월 예상 USD |
| --- | --- | --- | ---: |
| API·Worker EC2 | `t3.medium` × 2 | $0.052 / 대·시간 | **75.92** |
| EC2 EBS gp3 | 30GiB × 2, 각각 3,000 IOPS·125MiB/s | $0.0912 / GB·월 | **5.47** |
| RDS PostgreSQL | `db.t4g.small`, Single-AZ × 1 | $0.051 / 시간 | **37.23** |
| RDS gp3 | 30GiB, 3,000 IOPS·125MiB/s | $0.131 / GB·월 | **3.93** |
| 사용 중 공인 IPv4 | EC2 2개 + RDS 1개 = 3개 | $0.005 / 개·시간 | **10.95** |
| **확인 자원 기본 비용 소계** | 위 항목에 한정 | 반올림 전 합계 $133.502 | **133.50 / 월** |

단가 출처(조회일 2026-09-10):

- [AWS 서울 EC2 공개 가격표](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-northeast-2/index.csv): 버전 `20260909164938`, 적용일 `2026-09-01`. Compute SKU `G5CAZXC4M5ENHEZN`, gp3 SKU `MTK7D9SGKGYR3JD6`.
- [AWS 서울 RDS 공개 가격표](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonRDS/current/ap-northeast-2/index.csv): 버전 `20260909231334`, 적용일 `2026-09-01`. DB SKU `UMH398UHTY46K8BK`, gp3 SKU `8TFTRRBWJSP95DQP`.
- [AWS VPC 공인 IPv4 가격](https://aws.amazon.com/vpc/pricing/): 사용 중 IPv4의 시간당 요금 적용. EC2 탄력적 IP를 위 3개에 중복 합산하지 않는다.
- [AWS 공개 가격표 조회 방법](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/using-the-aws-price-list-bulk-api-fetching-price-list-files-manually.html).

소계에는 **CPU 크레딧 추가 요금, 가용 영역 간·인터넷 전송량, S3, 추가 백업·스냅샷, 기타 AWS 자원, Vercel 요금제·초과 사용, 도메인 갱신, LLM·메일/SMS 사용료, 세금·크레딧·예약 할인**을 포함하지 않았다. API와 Worker/RDS의 AZ가 다르므로 전송량 비용도 실청구에서 확인해야 한다. Vercel·도메인·외부 제공자 실제 결제액과 전체 월 운영비는 미확인이다.

## 6. 직접 다시 확인하는 방법

### 6.1 AWS 콘솔

1. 운영 계정에서 서울 리전을 선택하고 **EC2 → 인스턴스**에서 API·Worker 역할을 확인한다. **스토리지**에서 크기·IOPS·처리량, **보안**에서 SG 소스·포트를 확인한다.
2. 각 EC2에서 **연결 → Session Manager → 연결**로 접속해 아래 Docker 조회 명령을 실행한다. API 3개, Worker 7개는 이 확인일의 기준값이다.
3. **RDS → 데이터베이스 → 운영 PostgreSQL**에서 구성·연결 및 보안·유지 관리 및 백업을 확인한다. 이 화면의 DB 한도와 실제 SQL 설정을 혼동하지 않는다.
4. 비용은 권한이 있는 계정의 **Billing / Cost Explorer**에서 대상 기간·통화·서비스별 값을 기록한다. 권한 오류는 미확인으로 남기고 해당 계정의 청구 담당자에게 확인한다.

빌링온에서는 **Cost Management → 비용 탐색기 → Account ID**에서 전체 선택을 해제하고 운영 AWS 계정 ID와 일치하는 계정만 선택한다. **Granularity=Monthly, GroupBy=Service**로 이전 달을 비교하고, **Daily**로 바꿔 최근 날짜의 서비스별 비용을 확인한다. 대시보드 상단의 그룹 전체 사용금액과 운영 계정의 사용금액을 구분하고, 마지막 날짜의 집계가 완료됐는지 확인한다.

```bash
sudo docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
sudo docker volume ls
```

`docker inspect` 전체 출력이나 `.env` 원문에는 비밀값이 포함될 수 있다. 공개 증거에는 필요한 상태·이미지·서비스 수·포트 또는 비밀값을 제외한 설정만 기록한다.

### 6.2 DB 설정 확인

기존 운영 DB 클라이언트에서 다음 읽기 전용 SQL을 실행한다. 새 계정·접근 규칙 생성은 이 점검에 포함하지 않는다.

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SHOW max_connections;
SHOW superuser_reserved_connections;
SHOW reserved_connections;
SHOW timezone;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
SELECT count(*) AS connection_count_at_check FROM pg_stat_activity;
ROLLBACK;
```

### 6.3 공개 진입점 확인

```bash
dig +short NS catchhole.com
dig +short CNAME www.catchhole.com
dig +short A api.catchhole.com
curl -I https://www.catchhole.com/
curl -fsS https://api.catchhole.com/actuator/health
```

API A 레코드와 EC2 주소는 비공개 환경에서 대조하고 원문 IP를 이슈에 복사하지 않는다. 프론트는 `server: Vercel`, API health는 `status=UP`을 확인한다.

## 7. 미확인 사항과 후속 연결

| 항목 | 남은 확인·판단 | 후속 연결 |
| --- | --- | --- |
| 최종 청구·전체 월 비용 | 빌링온 사용금액 확인 완료. 최종 할인·크레딧·세금·결제액과 외부 서비스 요금은 미확인 | #171의 남은 비용 항목. 담당자·일정 합의 필요 |
| DNS 관리·운영자 접속 | DNS 관리 계정·변경 책임자는 미확인. IP 허용은 SG 설명과 팀 설명상 센터·자택 접속 용도로 정리했으며 서버별 범위는 2절 참고 | DNS 담당자 인계 및 접속 장소·팀원 변경 시 허용 IP 재점검. 설정 변경이 필요하면 별도 작업 |
| 이전 로컬 DB·잔존 파일 | 과거 데이터·볼륨 정리 이력, 별도 백업·파일 보존 이유 확인 | #171 현황 보완, 현재 복구 검증은 #175 |
| 백업 목표·삭제 절차 | RPO/RTO·보존 목표, 최종 스냅샷 절차 합의 | [#172](https://github.com/catchhole-soma/catchhole-backend-java/issues/172) |
| API·EC2·RDS 알림 | 현재 계정의 수집·알림 경로와 실제 장애·복구 알림 수신 검증 | [#173](https://github.com/catchhole-soma/catchhole-backend-java/issues/173), [#174](https://github.com/catchhole-soma/catchhole-backend-java/issues/174) |
| DB 복구·API/Worker 롤백·Job 회수 | 격리 환경에서 실제 결과·소요 시간·호환성 검증 | [#175](https://github.com/catchhole-soma/catchhole-backend-java/issues/175)~[#178](https://github.com/catchhole-soma/catchhole-backend-java/issues/178) |
| S3 정책·추가 인프라 | Versioning·Lifecycle·VPC Endpoint 등은 이번 조회에서 미확인 | [#119 후속 후보](https://github.com/catchhole-soma/catchhole-backend-java/issues/119) |

문서 PR과 확인 결과를 #171에 연결해 검토한 뒤 Epic의 해당 상태를 갱신한다. 이 기록만으로 #172~#178의 설정 보완·알림·복구 검증을 완료 처리하지 않는다.
