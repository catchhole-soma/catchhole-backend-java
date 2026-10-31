# ADR-0002: API 다중화 시 Redis를 서버 밖의 공용 저장소로 옮긴다

| 항목 | 내용 |
| --- | --- |
| 상태 | **제안 (초안)** — [ADR-0001](0001-api-multi-instance-ecs-fargate.md)이 채택될 때 유효 |
| 작성일 | 2026-10-30 |
| 작성자 | mkshie |
| 관련 이슈 | [#336](https://github.com/catchhole-soma/catchhole-backend-java/issues/336), [#280](https://github.com/catchhole-soma/catchhole-backend-java/issues/280) |

> 팀 검토 전 제안이다. ElastiCache 등 제품의 채택은 확정되지 않았다.

## 1. 배경

- **Redis를 쓰는 곳.** 이메일·휴대폰 인증번호, 발송 횟수 제한(이메일·IP·전체), 가입 토큰이다. Redis에 접근하는 코드는 `EmailVerificationStore`, `EmailVerificationRateLimiter`, `PhoneVerificationStore`, `PhoneVerificationRateLimiter` 네 클래스뿐이다(`domain/auth`). 사용 명령은 Lua 스크립트(`DefaultRedisScript`), `GET`, `GETDEL`, `DEL`이다.
- **운영 Redis.** API 서버의 같은 Compose에서 실행된다. `maxmemory 64mb`, `noeviction`, `--save ""`로 디스크에 저장하지 않으며 비밀번호가 필수다([compose.api.prod.yml](../../deploy/compose.api.prod.yml)).
- **재시작 영향.** Redis가 재시작되면 진행 중인 인증과 아직 쓰지 않은 가입 토큰이 초기화된다([Infrastructure Flow](../infrastructure-flow.md)). 데이터가 5~10분짜리라 사용자가 인증을 다시 하면 된다.
- **서버가 두 대가 되면.** 서버마다 Redis가 따로 있으면 다른 서버가 발급한 인증번호를 찾지 못한다. 발송 제한도 서버 수만큼 느슨해진다.
- **장애 영향 범위.** 코드 기준으로 Redis 장애의 영향은 위 인증 기능에 한정된다. 다만 Actuator Redis health가 전체 health group(`/healthz`)에 포함돼 있어, 트래픽 health를 분리하지 않으면 영향이 서비스 전체로 커질 수 있다([ADR-0001](0001-api-multi-instance-ecs-fargate.md) 4.2).

## 2. 판단 기준

1. 모든 API task가 같은 인증 데이터와 발송 제한 상태를 본다.
2. 발송 제한의 원자성을 유지한다. 현재는 Lua 스크립트로 처리한다.
3. 장애 영향이 인증 기능에 머문다. 트래픽 health 분리가 전제다.
4. 비용과 운영 부담이 작다.

## 3. 검토한 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| A. **ElastiCache, Valkey 엔진 (제안)** | 패치·장애 교체를 AWS가 관리한다. Redis와 호환돼 현재 클라이언트 설정을 그대로 쓴다. AWS는 Valkey 엔진을 Redis OSS 엔진보다 낮은 요금으로 제공한다 | 월 비용이 생긴다. 전송 중 암호화·인증 방식을 정해야 한다 | 제안 |
| B. 별도 EC2의 Redis 컨테이너 | 가장 저렴하고 현재 설정을 그대로 옮길 수 있다 | 직접 운영해야 한다. 새 단일 장애점이 생긴다. 영향이 인증 기능뿐이라 감수할 수는 있다 | 비용이 가장 중요하면 대안 |
| C. PostgreSQL로 이전 | 운영할 구성 요소가 하나 줄어든다 | TTL 자동 만료가 없어 정리 작업이 필요하다. DB 쓰기와 연결 사용이 늘어난다. TTL·원자적 rate limit·1회 토큰 때문에 Redis를 고른 기존 결정([README](../../README.md))을 바꾸는 일이다 | 별도 논의 필요 |
| D. ALB sticky session + 서버별 Redis | 변경이 가장 적다 | 서버가 죽으면 그 서버의 인증 데이터가 사라진다. 발송 제한이 서버 수만큼 느슨해진다 | 제외 |

## 4. 제안하는 결정

ElastiCache for Valkey **노드 하나(복제본 없음)**로 시작한다.

- 데이터가 몇 분짜리이고 다시 발급할 수 있으며, 지금도 디스크에 저장하지 않는다. 그래서 복제본으로 가용성을 높이기보다 비용을 우선한다. 노드 장애 시 영향은 인증 기능이고, 노드 교체 동안 사용자가 인증을 다시 하면 된다.
- 애플리케이션은 이미 `REDIS_HOST`·`REDIS_PORT`·`REDIS_PASSWORD` 환경변수로 연결한다([application-prod.yml](../../src/main/resources/application-prod.yml)). 전송 중 암호화를 켜면 TLS 연결 설정이 추가로 필요하다.
- Valkey는 Redis 7.2 계열에서 갈라졌다. 현재 쓰는 명령은 호환 범위로 보이지만 Lua 스크립트가 같은 결과를 내는지 검증 환경에서 확인한다.
- 보안 그룹은 API task에서 오는 연결만 허용한다.

## 5. 결과

- **좋아지는 점**: 모든 task가 같은 인증·발송 제한 상태를 본다. API 서버를 교체하거나 늘려도 Redis 데이터가 영향을 받지 않는다.
- **대가**: 노드 요금이 생긴다. Redis가 API 서버 밖으로 나가 네트워크 왕복이 생긴다. 현재 `connect-timeout`·`timeout`은 2초다.
- **위험**: 노드 하나라 Redis 장애 시 인증 기능이 멈춘다. 트래픽 health에서 Redis를 빼지 않으면 장애가 전체로 번진다.

## 6. 검증 기준

| 시나리오 | 목표 |
| --- | --- |
| task 1에서 발송한 인증번호를 task 2로 확인 | 성공 |
| 여러 task로 나눠 발송 요청 | 발송 제한이 task 수와 무관하게 같은 한도로 적용 |
| Redis 중지 | 인증 API만 실패하고 다른 API와 ALB 대상 health는 유지 |
| Redis 재시작 | 진행 중 인증이 초기화되고 다시 인증하면 성공. 기존 동작과 같음 |
| Lua 스크립트 | Valkey에서 기존 Redis와 같은 결과 |

## 7. 미결정·미확인

| 항목 | 상태 |
| --- | --- |
| 노드 크기와 월 비용 | 미산정 |
| 전송 중 암호화(TLS) 사용 | 미결정 |
| 서브넷·보안 그룹 | ADR-0001 네트워크 구성에 따름 |
| 대안 B·C와의 비용 비교 | 미산정 |

## 8. 참고

- [Amazon ElastiCache 요금](https://aws.amazon.com/elasticache/pricing/)
- [Auth Domain](../auth.md) — 휴대폰 인증과 Redis 정책
- [Infrastructure Flow](../infrastructure-flow.md) — Redis 재시작 영향
