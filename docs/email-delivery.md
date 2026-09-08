# 이메일 인증 발송과 운영 설정

확인일: 2026-09-08. 초기 발송 제공자는 Gmail SMTP, 발신 계정은 `aicatchhole@gmail.com`이다. 운영 서버 `/opt/catchhole/api.env`에 사용자가 설정을 저장했고, 비밀값을 출력하지 않는 검사로 필수값과 API 서버에서 Gmail STARTTLS·SMTP 로그인 성공을 확인했다. 로컬 `local` 프로필의 실제 SMTP 발송은 사용자가 가입 화면에서 요청하여 수신을 확인했으며, 해당 메일은 스팸함에 도착했다. 운영 새 코드 배포 후 가입 완료까지의 점검은 남아 있다. SES는 추후 전환 선택지다.

## 가입 인증과 이메일 전송

회원가입 화면에서 이메일로 인증번호를 요청하면 Backend가 인증 흐름과 발송 한도를 Redis에 기록한 뒤 `EmailSender`를 한 번 호출한다. 발송 제공자가 접수하면 화면에 인증번호 입력을 안내한다. 이용자가 코드를 확인하면 가입에만 사용할 수 있는 일회용 토큰을 발급하고, 최종 가입 시 토큰의 이메일과 입력 이메일이 일치하는지 확인한다. 로그인 방식은 기존 이메일·비밀번호를 유지한다.

`SmtpEmailSender`는 Spring `JavaMailSenderImpl`로 UTF-8 일반 텍스트 메일을 한 주소에 발송한다. 수신자의 이메일 제공자와 발신자의 SMTP 제공자는 같을 필요가 없다. Gmail SMTP로 보내더라도 네이버·다음 등 다른 서비스 주소에서 인증번호를 받을 수 있다. SMTP 성공 응답은 발송 서버의 접수를 뜻하며 받은편지함 도착까지 보장하지 않는다.

전송에는 **SMTP submission 587 + 필수 STARTTLS**를 사용한다. SMTP 연결을 TLS로 전환한 뒤 SMTP 계정을 인증하고 메일을 제출한다. TLS 1.2/1.3과 인증서의 신뢰 체인·호스트 이름 검증을 강제하며 STARTTLS 미지원 서버에는 자격 증명과 메일을 보내지 않는다. 연결 타임아웃은 3초, 소켓 읽기·쓰기 타임아웃은 각각 5초다. SMTP 디버그 로그를 끄고 provider 예외의 원문·cause도 제거해 수신 주소·인증번호·자격 증명이 로그나 응답에 남지 않게 한다. [Angus SMTP 설정](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html), [Spring 이메일 전송과 타임아웃](https://docs.spring.io/spring-boot/reference/io/email.html)

타임아웃 뒤에는 실제 접수 여부가 불확실하므로 자동 재시도하지 않는다. 사용자가 재전송 대기 후 새 인증번호를 요청한다. 발송 실패는 `AUTH_EMAIL_VERIFICATION_UNAVAILABLE`로 정규화하고, Redis 발송 한도는 실패해도 되돌리지 않아 실패 반복으로 제한을 우회하지 못하게 한다.

## 환경별 동작

| 환경 | 가입 방식 | 발송 동작 |
| --- | --- | --- |
| local | EMAIL 기본 | `EMAIL_PROVIDER` 미설정 또는 `fake`는 실제 발송 없이 `123456`; `smtp`는 실제 발송 설정 필요 |
| test / e2e | 테스트가 지정 | 외부 환경변수와 무관하게 Fake, SMTP 비밀값 불필요 |
| prod | EMAIL | `smtp`와 유효한 host/from/username/password가 없으면 기동 실패 |
| prod | PHONE | 메일 발송기는 비활성 Fake로 구성되어 메일 비밀값 없이 기동 가능 |

운영에서 `PHONE`으로 되돌릴 때에도 현재 이메일 인증 회원은 유지한다. 전화번호 인증을 다시 가입 필수로 만들려면 `SIGNUP_VERIFICATION_METHOD=PHONE`과 기존 SOLAPI 설정을 적용한다. 기존 회원에게 추가 전화번호 인증을 요구하는 흐름은 별도 제품 정책이다.

## 설정 계약

`EmailDeliveryConfig`가 SMTP 클라이언트를 직접 만들기 때문에 `spring.mail.*`이 아닌 아래 환경변수를 사용한다. `EMAIL_PROVIDER=smtp`여도 애플리케이션 기동 시 실제 접속이나 시험 메일을 보내지 않는다. 기동 검증은 설정 유무·형식 검증이며 계정의 인증 성공, 제공자 권한, 전달 가능성은 별도 실제 연동 검증 대상이다.

| 환경변수 | 설정 키 | 값 |
| --- | --- | --- |
| `SIGNUP_VERIFICATION_METHOD` | `auth.signup-verification.verification-method` | `EMAIL` 또는 `PHONE` |
| `EMAIL_PROVIDER` | `email.provider` | `fake` 또는 `smtp` |
| `EMAIL_SMTP_HOST` | `email.smtp.host` | 프로토콜 접두어 없는 SMTP 호스트 이름 |
| `EMAIL_SMTP_PORT` | `email.smtp.port` | `587` 고정, 기본값 587 |
| `EMAIL_SMTP_USERNAME` | `email.smtp.username` | 제공자가 발급한 SMTP 사용자 이름 |
| `EMAIL_SMTP_PASSWORD` | `email.smtp.password` | 앱 비밀번호 또는 SMTP 전용 비밀번호 |
| `EMAIL_FROM` | `email.smtp.from` | 발신 권한이 있는 단일 이메일 주소, 표시 이름 제외 |
| `EMAIL_VERIFICATION_HASH_SECRET` | `auth.email-verification.hash-secret` | 운영용 최소 32바이트 별도 비밀값 |

실제 비밀값은 배포 secret 또는 서버 `.env`로 주입하고 저장소·이슈·채팅·실행 로그에 넣지 않는다. 이메일 발송 자체에는 inbound 포트 개방이나 별도 메일 수신 서버가 필요하지 않다. Backend에서 SMTP 호스트의 TCP 587로 나가는 연결과 DNS 조회가 가능해야 한다.

## Gmail SMTP 연결

초기 발신 계정은 서비스용 Gmail `aicatchhole@gmail.com`을 사용한다. Gmail 설정은 호스트 `smtp.gmail.com`, 포트 `587`, SMTP 사용자 이름과 발신 주소 모두 `aicatchhole@gmail.com`이다. 인증에는 계정 로그인 비밀번호 대신 앱 비밀번호를 사용한다. [Google SMTP 설정](https://support.google.com/mail/answer/7104828?hl=en-GB)

`deploy/api.env.example`에 반영한 비밀값이 아닌 설정은 다음과 같다. 운영 서버의 `/opt/catchhole/api.env`에 이 값을 적용하고 `EMAIL_SMTP_PASSWORD`와 `EMAIL_VERIFICATION_HASH_SECRET`을 별도로 채워야 한다. 예제 수정만으로 운영 발송이 활성화되지는 않는다.

```dotenv
EMAIL_PROVIDER=smtp
EMAIL_SMTP_HOST=smtp.gmail.com
EMAIL_SMTP_PORT=587
EMAIL_SMTP_USERNAME=aicatchhole@gmail.com
EMAIL_FROM=aicatchhole@gmail.com
```

설정 순서는 다음과 같다.

1. `aicatchhole@gmail.com` Google 계정에서 2단계 인증을 켠다.
2. 같은 계정의 [앱 비밀번호 관리](https://myaccount.google.com/apppasswords)에서 `CatchHole SMTP` 등 용도를 알아볼 수 있는 이름으로 서버 전용 앱 비밀번호를 생성한다.
3. 운영 서버 `/opt/catchhole/api.env`에 위의 Gmail 설정을 적용한다.
4. 같은 파일의 `EMAIL_SMTP_PASSWORD`에 앱 비밀번호를 비밀값으로 저장한다. 화면 표시용 공백을 제외한 값을 사용하며 저장소나 채팅에 붙여 넣지 않는다.
5. `SIGNUP_VERIFICATION_METHOD=EMAIL` 및 별도 해시 secret을 설정하고, 승인된 테스트 수신 주소로 실제 도착·스팸함·재전송·가입 완료를 확인한 뒤 운영에 적용한다.

앱 비밀번호는 2단계 인증이 켜져 있어야 만들 수 있다. 조직 정책, 보안 키만 사용하는 2단계 인증, Advanced Protection 등에 따라 메뉴가 없을 수 있다. 계정 비밀번호를 바꾸면 기존 앱 비밀번호가 폐기되므로 SMTP secret도 다시 발급해야 한다. Google은 가능한 경우 OAuth 로그인을 권장하지만 이번 서버 구현은 SMTP 전용 자격 증명을 사용하는 방식이다. [Google 앱 비밀번호 안내](https://support.google.com/accounts/answer/185833?hl=en)

무료 개인 Gmail 계정은 별도 SMS 건당 비용 없이 시작할 수 있으나 무제한 발송 채널은 아니다. Google은 하루 500통 초과 등의 상황에서 제한될 수 있고 제한 해제까지 1~24시간 걸릴 수 있다고 안내한다. 계정의 다른 발송도 한도에 포함되므로 캐치홀 기본 전체 한도인 하루 100회·월 3,000회를 유지하고 한도 수치를 전달 보장량으로 해석하지 않는다. Google Workspace 계정의 요금·한도와 조직 정책은 별도로 확인한다. [무료 Gmail 계정 안내](https://knowledge.workspace.google.com/admin/support/get-help-with-your-free-gmail-account?hl=en), [Gmail 발송 제한](https://support.google.com/mail/answer/22839?hl=en)

## 추후 Amazon SES SMTP로 전환하는 경우

애플리케이션 코드는 유지하고 SMTP 환경변수를 SES 값으로 바꿀 수 있다. SES 콘솔에서 리전을 선택하고 발신 이메일 또는 도메인을 검증하며, 해당 리전 SMTP endpoint와 SMTP 전용 자격 증명을 사용한다. SMTP 비밀번호는 일반 AWS secret access key와 다른 값이고 리전별 자격 증명이다. 일반 회원 이메일에 발송하려면 해당 리전의 sandbox 해제가 필요하다. Sandbox에서는 검증한 수신자 중심으로 제한되며 기본 한도는 24시간 200통·초당 1통이다. [SES SMTP 자격 증명](https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html), [SES production access](https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html)

2026-09-08 공식 가격표 기준 신규 기본 Essentials는 처음 월 1천만 통 구간에서 **1,000통당 $0.16**이며, 선택 가능한 À la carte는 **1,000통당 $0.10**이다. 3,000통이면 메일 건수 요금은 각각 약 **$0.48 / $0.30**이고 데이터 전송·부가 기능·세금 등은 별도다. 계정·리전의 현재 요금제를 확인해야 하며 무료 크레딧을 기본 비용 계산에 포함하지 않는다. [SES 공식 가격](https://aws.amazon.com/ses/pricing/)

발신 도메인을 사용하는 경우 제공자가 안내하는 DKIM과 발신 도메인 인증을 설정한다. 계정 생성, 도메인 등록·검증, sandbox 해제, 요금제 선택과 실제 외부 발송은 이번 코드 구현에서 수행하지 않는다.

## 검증 범위

- SMTP 메시지의 단일 수신자·UTF-8 본문, 실패 원문 제거 및 자동 재시도 없음.
- 환경별 Fake 강제, prod EMAIL 설정 누락과 잘못된 host/from/port 거절, prod PHONE의 메일 자격 증명 없는 기동.
- 테스트 실행 중 `keytool`로 만든 localhost 인증서와 로컬 임시 포트 SMTP 서버를 통해 실제 STARTTLS·인증·MIME 수신 검증.
- STARTTLS 미지원, 인증서 미신뢰, 인증서 호스트 이름 불일치 시 자격 증명과 메일 전송 전 거절.

테스트에서만 생성한 인증서를 신뢰하고 임시 포트로 연결한다. 운영 코드는 플랫폼 기본 신뢰 루트와 hostname 검증을 유지한다. Gmail 계정 인증과 로컬 실제 수신은 위 확인 범위에 포함되며, 자동화된 전체 가입 E2E는 Fake sender를 사용한다. 운영 배포 이후에는 별도로 실제 수신·코드 확인·가입 완료를 점검한다. SES 실제 발송은 검증하지 않았다.
