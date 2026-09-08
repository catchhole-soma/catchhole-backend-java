# 이메일 인증 가입 전환 결과 (#181)

작업 브랜치: Java Backend와 Frontend의 `feat/gh-181-email-verification-signup`.
이슈: [catchhole-backend-java #181](https://github.com/catchhole-soma/catchhole-backend-java/issues/181).

## 변경 결과

기본 회원가입 인증을 이메일로 바꿨다. 전화번호 없이 이메일 소유를 확인한 뒤 기존 이메일·비밀번호 계정과 로그인 세션을 만든다. 기존 전화번호 인증 구현과 회원 정보는 유지하며, 신규 가입 정책은 서버의 `SIGNUP_VERIFICATION_METHOD=EMAIL|PHONE`으로 선택한다. 기존 이메일 회원에게 추가 전화번호 인증을 요청하는 화면은 별도 범위다.

V42 migration은 새 테이블 없이 `members.phone_number`의 NULL을 허용하고 `email_verified`를 추가한다. 전화번호의 unique 제약은 실제 값에 대해 유지된다. 이메일 신규 회원은 `email_verified=true`, `phone_verified=false`, 전화번호 NULL이고 기존 회원의 이메일 인증은 false로 보존된다. 인증 여부가 바뀌었다는 이유로 기존 로그인·작품 접근을 막지 않는다.

## 사용자에게 적용되는 기본값

| 항목 | 기본값 |
| --- | --- |
| 인증번호 | 6자리 숫자 |
| 재전송 가능 시간 | 발송 후 60초 |
| 인증번호 입력 제한시간 | 발급 후 5분 |
| 코드 오입력 | 5회 시 해당 흐름 잠금, 재전송 대기 뒤 새 코드 요청 |
| 인증 후 가입 완료 제한시간 | 일회용 가입 토큰 발급 후 10분 |
| 이메일별 발송 제한 | 시간 5회, KST 하루 10회 |
| IP별 발송 제한 | 시간 10회, KST 하루 20회 |
| 서비스 전체 발송 제한 | KST 하루 100회, 월 3,000회 |

재전송하면 이전 코드와 가입 토큰이 무효화된다. 코드 재확인으로 원래 만료시간을 늘릴 수 없다. 발송 한도는 환경변수로 조정 가능하며 실패한 SMTP 요청도 되돌리지 않는다. Redis 장애에서는 외부 발송을 하지 않는다.

## 전송 방식과 비용

인증은 애플리케이션의 일회용 코드(OTP) 방식이며, Backend에서 메일 제공자에 전송하는 프로토콜은 **SMTP submission 587 + 필수 STARTTLS**다. TLS 1.2/1.3과 서버 인증서·호스트 검증을 강제한다. 연결 타임아웃 3초, 읽기/쓰기 각각 5초이며 자동 재시도는 하지 않는다. 이 구현은 메일함을 읽는 IMAP/POP이나 Google OAuth 로그인을 사용하지 않는다.

초기 SMTP 제공자는 Gmail, 발신 계정은 `aicatchhole@gmail.com`이다. 사용자가 운영 `api.env`에 앱 비밀번호와 EMAIL 설정을 저장했고, API 서버의 STARTTLS·SMTP 로그인 성공 및 로컬 가입 화면에서 요청한 실제 메일 수신을 확인했다. 별도 발송 서비스 결제 없이 시작할 수 있지만, Gmail의 일반 하루 500통 제한은 전달 보장량이 아니며 다른 발송도 포함한다.

Amazon SES를 선택하면 AWS 사용량 과금이 발생한다. 2026-09-08 공식 가격 기준 신규 기본 Essentials는 1,000통당 $0.16, À la carte는 $0.10이다. 월 3,000통의 메일 건수 요금은 약 $0.48 또는 $0.30이며 데이터·부가 기능·세금은 별도다. 유료 서비스 가입·결제는 수행하지 않았으며, 실제 Gmail 발송은 사용자가 로컬 가입 화면에서 요청했다.

제공자별 설정 단계, 공식 가격·제한 출처와 주의할 계정 조건은 [이메일 발송 운영 문서](email-delivery.md)에 모았다.

## 검증

- Frontend 전체 E2E **204개 통과, 자격 증명 필요한 기존 live 테스트 2개 스킵**, 빌드 통과, lint 오류 0개(기존 경고 39개). 이메일/전화 인증 및 정책 상태 회귀 26개 별도 통과.
- Java 전체 회귀 테스트: **771개 통과, 실패 0, 스킵 0**. Docker의 실제 Redis·PostgreSQL 통합 테스트 포함.
- SMTP 관련 28개 테스트: 로컬 STARTTLS 서버에 실제 인증·MIME 메시지 전달, STARTTLS 미지원·신뢰되지 않은 인증서·호스트 불일치 거절 포함.
- V1~V41 DB에 기존 전화번호 회원을 넣고 V42로 올리는 migration 테스트에서 데이터 보존과 복수 NULL 전화번호 가입 확인.
- 별도 pgvector PostgreSQL에 V1~V42 적용 후 JPA `validate` 기동 성공.
- 실제 브라우저 → Java API → Redis → PostgreSQL에서 이메일 발송 요청, 오입력 오류, 정상 확인, 동의, 가입, `/works` 이동 및 `/auth/me` 확인 완료. `emailVerified=true`, `phoneVerified=false`, `phoneNumber=null` 및 HttpOnly refresh cookie 확인.
- 전체 가입 브라우저 연동은 `e2e` profile의 Fake sender를 사용했다. 별도로 운영 서버의 Gmail STARTTLS·SMTP 로그인과 로컬 `local` profile의 실제 메일 수신을 확인했다. 수신 메일은 스팸함에 도착했으며 Frontend는 발송 성공 후 스팸함 확인을 별도 안내 상자로 표시한다. 운영 새 코드에서의 실제 메일 인증·가입 완료는 배포 후 점검한다.
- 운영 Compose 설정 검증과 `git diff --check` 통과.

프론트의 화면·재전송·만료·정책 변경 회귀, 빌드·lint 결과와 디자인 반영 내역은 Frontend 저장소의 `design/EMAIL_SIGNUP_GH181.md`와 `docs/screens/email-signup-*.png`를 참고한다.

## 운영 적용 및 배포 후 확인

1. 서버 `/opt/catchhole/api.env`의 Gmail·EMAIL 설정과 별도 해시 secret은 준비됐다. 실제 비밀값은 저장소·이슈·채팅에 넣지 않는다.
2. [배포 순서](../deploy/EC2_DEPLOYMENT.md)에 따라 가입 공백 없이 전환하려면 새 Backend PHONE → 새 Frontend → EMAIL 순서로 배포한다. 현재처럼 EMAIL을 미리 저장하고 양쪽 PR을 연이어 머지하면 자동 배포로 적용되지만, 양쪽 배포 완료 사이에는 신규 가입이 잠시 막힐 수 있다.
3. 지정한 테스트 수신자에서 실제 도착·스팸함·재전송·가입을 확인한다. SMTP 접수 성공만으로 받은편지함 전달을 보장하지 않는다.

이 작업은 브랜치의 구현·검증이며 운영 코드 배포는 수행하지 않았다. 디자인 원본 `.pen` 갱신은 전용 도구가 열린 디자인 파일 컨텍스트를 찾지 못해 남아 있고, 화면 코드·스크린샷·구체적인 반영 계획은 준비했다.
