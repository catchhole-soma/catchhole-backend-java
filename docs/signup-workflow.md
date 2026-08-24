# Signup Workflow

휴대폰 인증번호 발송부터 인증 완료, 회원·refresh token 저장과 자동 로그인 응답까지 실제 코드 호출 순서를 정리합니다.

API 요청·응답과 오류 코드는 [Auth](auth.md)를, 사용자가 보는 화면 전환과 상태는 Frontend의 `docs/screen-flow.md`를 기준으로 확인합니다. 이 문서는 Controller, Service, Redis, DB 사이의 실행 순서와 트랜잭션 경계를 따라가는 용도입니다.

## 코드 진입점

| 단계 | 코드 | 주요 메서드 |
| --- | --- | --- |
| Frontend 폼 | `CatchHole-Front/src/app/components/catchhole/SSignup.tsx` | `handleRequestVerification`, `handleConfirmVerification`, `handleSignup` |
| 인증 API 진입 | [`PhoneVerificationController`](../src/main/java/org/monitoring/catchholebackend/domain/auth/controller/PhoneVerificationController.java) | `requestPhoneVerification`, `confirmPhoneVerification` |
| 인증 유스케이스 | [`PhoneVerificationServiceImpl`](../src/main/java/org/monitoring/catchholebackend/domain/auth/service/PhoneVerificationServiceImpl.java) | `sendPhoneVerificationCode`, `confirmPhoneVerificationCode`, `getVerifiedPhoneNumberBySignupToken`, `consumeSignupToken` |
| 인증 값 생성·HMAC | [`PhoneVerificationHasher`](../src/main/java/org/monitoring/catchholebackend/domain/auth/phone/PhoneVerificationHasher.java), [`PhoneVerificationCodeGenerator`](../src/main/java/org/monitoring/catchholebackend/domain/auth/phone/PhoneVerificationCodeGenerator.java), [`PhoneVerificationTokenGenerator`](../src/main/java/org/monitoring/catchholebackend/domain/auth/phone/PhoneVerificationTokenGenerator.java) | `hashIdentifier`, `hashVerificationCode`, `generate` |
| 발송 제한 | [`PhoneVerificationRateLimiter`](../src/main/java/org/monitoring/catchholebackend/domain/auth/phone/PhoneVerificationRateLimiter.java) | `acquireSendPermit` |
| Redis 인증 상태 | [`PhoneVerificationStore`](../src/main/java/org/monitoring/catchholebackend/domain/auth/phone/PhoneVerificationStore.java) | `replaceActiveVerificationFlow`, `confirmVerificationCodeAndIssueSignupToken`, `findPhoneNumberBySignupToken`, `consumeSignupToken` |
| SMS 발송 | [`SmsSender`](../src/main/java/org/monitoring/catchholebackend/domain/auth/sms/SmsSender.java) | `sendVerificationCode` |
| 회원가입 API 진입 | [`AuthController`](../src/main/java/org/monitoring/catchholebackend/domain/auth/controller/AuthController.java) | `signup`, `tokenResponse` |
| 회원가입 유스케이스 | [`AuthServiceImpl`](../src/main/java/org/monitoring/catchholebackend/domain/auth/service/AuthServiceImpl.java) | `signup`, `validateSignupUniqueness`, `issueTokens` |
| 현재 법률 문서 검증 | [`LegalDocumentServiceImpl`](../src/main/java/org/monitoring/catchholebackend/domain/legal/service/LegalDocumentServiceImpl.java) | `getCurrentDocuments`, `requireCurrentSignupDocuments` |
| 회원 조립 | [`AuthMapper`](../src/main/java/org/monitoring/catchholebackend/domain/auth/mapper/AuthMapper.java), [`Member`](../src/main/java/org/monitoring/catchholebackend/domain/member/entity/Member.java) | `toEntity`, `registerPhoneVerified` |
| 로그인 토큰 발급 | [`JwtTokenProvider`](../src/main/java/org/monitoring/catchholebackend/domain/auth/token/JwtTokenProvider.java), [`RefreshTokenGenerator`](../src/main/java/org/monitoring/catchholebackend/domain/auth/token/RefreshTokenGenerator.java), [`TokenHashProvider`](../src/main/java/org/monitoring/catchholebackend/domain/auth/token/TokenHashProvider.java), [`RefreshTokenCookieFactory`](../src/main/java/org/monitoring/catchholebackend/domain/auth/token/RefreshTokenCookieFactory.java) | `generateAccessToken`, `generate`, `hash`, `create` |

## 전체 흐름

```mermaid
flowchart TD
    A["SSignup<br/>휴대폰 번호 입력"] --> B["handleRequestVerification<br/>인증번호 발송 요청"]
    B --> C["POST /api/v1/auth/phone-verifications"]
    C --> D["전화번호·IP·전체 발송 제한 확인<br/>Redis Lua"]
    D --> E["이전 인증 흐름 폐기 후<br/>새 인증번호 HMAC 저장"]
    E --> F["Fake 또는 SOLAPI SMS 발송"]
    F --> G["verificationId와<br/>5분·60초 타이머 수신"]
    G --> H["handleConfirmVerification<br/>6자리 인증번호 확인"]
    H --> I["POST /phone-verifications/{verificationId}/confirm"]
    I --> J["인증번호 원자 검증 후<br/>10분 가입 토큰 발급"]
    J --> K["현재 PUBLISHED 법률 문서 조회<br/>동의·확인과 만 14세 확인"]
    K --> L["handleSignup<br/>계정 정보·가입 토큰·두 문서 ID 제출"]
    L --> M["POST /api/v1/auth/signup<br/>전화번호는 body에서 제외"]
    M --> N["두 문서 ID가 현재 게시본인지 검증"]
    N --> O["가입 토큰으로 검증된<br/>전화번호 조회"]
    O --> P["회원·법률 기록·refresh token 저장 후<br/>DB flush"]
    P --> Q["가입 토큰 GETDEL<br/>1회 소비"]
    Q --> R["DB transaction commit"]
    R --> S["access token body +<br/>refresh token HttpOnly cookie"]
    S --> T["saveAuthToken 후<br/>/works 이동"]
```

## 인증번호 발송 코드 흐름

```mermaid
sequenceDiagram
    autonumber
    actor Front as SSignup.handleRequestVerification
    participant Controller as PhoneVerificationController
    participant Service as PhoneVerificationServiceImpl
    participant MemberRepo as MemberRepository
    participant Limiter as PhoneVerificationRateLimiter
    participant Store as PhoneVerificationStore
    participant Redis
    participant Sender as SmsSender

    Front->>Controller: POST /phone-verifications<br/>phoneNumber
    Controller->>Service: sendPhoneVerificationCode(phoneNumber, clientIp)
    Service->>MemberRepo: existsByPhoneNumber(phoneNumber)
    alt 이미 가입된 번호
        MemberRepo-->>Service: true
        Service-->>Front: 409 AUTH_PHONE_NUMBER_DUPLICATED
    else 가입되지 않은 번호
        Service->>Service: 전화번호·IP hashIdentifier
        Service->>Limiter: acquireSendPermit(phoneHash, ipHash)
        Limiter->>Redis: 재전송·시간·일·월 한도 Lua 확인 및 증가
        alt 제한 초과 또는 Redis 장애
            Redis-->>Limiter: 제한 또는 실패
            Limiter-->>Front: 429 AUTH_PHONE_VERIFICATION_RATE_LIMITED<br/>또는 503 AUTH_PHONE_VERIFICATION_UNAVAILABLE
            Note over Store,Sender: 인증 흐름과 SMS 발송은 실행하지 않음
        else 발송 허용
            Redis-->>Limiter: 발송 허용
            Service->>Service: verificationId·6자리 번호 생성 및 인증번호 HMAC
            Service->>Store: replaceActiveVerificationFlow(...)
            Store->>Redis: 이전 인증 흐름 삭제 + 새 인증 흐름 5분 TTL 저장
            Redis-->>Store: 저장 완료
            Service->>Sender: sendVerificationCode(phoneNumber, verificationCode)
            alt SMS provider 실패
                Sender-->>Service: AUTH_PHONE_VERIFICATION_UNAVAILABLE
                Service-->>Front: 503 AUTH_PHONE_VERIFICATION_UNAVAILABLE
                Note over Redis,Sender: 저장한 인증 흐름·발송량은 유지하고<br/>자동 재시도나 counter rollback은 하지 않음
            else 발송 접수
                Sender-->>Service: 완료
                Service-->>Controller: verificationId, expiresInSeconds, resendAfterSeconds
                Controller-->>Front: 200 인증번호 발송 완료
            end
        end
    end
```

## 인증번호 확인 코드 흐름

```mermaid
sequenceDiagram
    autonumber
    actor Front as SSignup.handleConfirmVerification
    participant Controller as PhoneVerificationController
    participant Service as PhoneVerificationServiceImpl
    participant Store as PhoneVerificationStore
    participant Redis
    participant Mapper as PhoneVerificationMapper

    Front->>Controller: POST /phone-verifications/{verificationId}/confirm<br/>code
    Controller->>Service: confirmPhoneVerificationCode(verificationId, code)
    Service->>Service: 가입 토큰 후보 생성 + 인증번호 HMAC
    Service->>Store: confirmVerificationCodeAndIssueSignupToken(...)
    Store->>Redis: 인증번호·오입력 횟수 확인 + 가입 토큰 SET NX<br/>단일 Lua 실행
    alt flow 없음 또는 TTL 만료
        Redis-->>Store: status 0
        Service-->>Front: 410 AUTH_PHONE_VERIFICATION_EXPIRED
    else 인증번호 불일치, 5회 미만
        Redis-->>Store: status -1 + attempts 증가
        Service-->>Front: 400 AUTH_PHONE_VERIFICATION_CODE_INVALID
    else 5회 오입력 잠금
        Redis-->>Store: status -2
        Service-->>Front: 429 AUTH_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED
    else 가입 토큰 충돌 또는 Redis 장애
        Redis-->>Store: status -3 또는 실패
        Service-->>Front: 503 AUTH_PHONE_VERIFICATION_UNAVAILABLE
    else 인증 성공
        Redis-->>Store: 신규 토큰 status 1 또는 기존 토큰 status 2
        Store-->>Service: token, expiresInSeconds
        Service->>Mapper: toConfirmResponse(confirmationResult)
        Mapper-->>Controller: phoneVerificationToken, expiresInSeconds
        Controller-->>Front: 200 휴대폰 인증 완료
        Note over Front: token은 컴포넌트 메모리에만 보관
    end
```

## 최종 회원가입 트랜잭션 흐름

```mermaid
sequenceDiagram
    autonumber
    actor Front as SSignup.handleSignup
    participant Controller as AuthController
    participant Service as AuthServiceImpl
    participant PhoneService as PhoneVerificationService
    participant Redis
    participant MemberRepo as MemberRepository
    participant LegalService as LegalDocumentService
    participant LegalRepo as LegalDocumentRepository
    participant Mapper as AuthMapper / Member
    participant RecordRepo as MemberLegalRecordRepository
    participant Token as JWT / RefreshToken components
    participant RefreshRepo as RefreshTokenRepository

    Front->>Controller: POST /auth/signup<br/>계정 정보, 필수 확인 3개, 두 문서 ID, phoneVerificationToken
    Note over Front,Controller: @Valid 실패 시 Service 호출 전 400 응답
    Controller->>Service: signup(request)
    Note over Service,RefreshRepo: Spring DB transaction 시작
    Service->>PhoneService: getVerifiedPhoneNumberBySignupToken(token)
    PhoneService->>Redis: GET signup-token:{token}
    alt 토큰 없음·만료·이미 소비됨
        Redis-->>PhoneService: null
        PhoneService-->>Service: AUTH_PHONE_VERIFICATION_TOKEN_INVALID 예외
        Service-->>Front: 400 AUTH_PHONE_VERIFICATION_TOKEN_INVALID
    else 검증된 전화번호 조회
        Redis-->>PhoneService: phoneNumber
        PhoneService-->>Service: phoneNumber
        Service->>MemberRepo: existsByEmail(email)
        alt 이메일 중복
            MemberRepo-->>Service: true
            Service-->>Front: 409 AUTH_EMAIL_DUPLICATED
            Note over Service,Redis: 가입 토큰은 아직 소비하지 않음
        else 사용 가능한 이메일
            MemberRepo-->>Service: false
            Service->>MemberRepo: existsByPhoneNumber(phoneNumber)
            alt 전화번호 중복
                MemberRepo-->>Service: true
                Service-->>Front: 409 AUTH_PHONE_NUMBER_DUPLICATED
                Note over Service,Redis: 가입 토큰은 아직 소비하지 않음
            else 가입 가능
                MemberRepo-->>Service: false
                Service->>LegalService: requireCurrentSignupDocuments(termsId, privacyId)
                LegalService->>LegalRepo: ko-KR PUBLISHED 문서 조회
                alt 현재 게시본 없음 또는 ID 불일치
                    LegalRepo-->>LegalService: 문서 없음 또는 다른 ID
                    LegalService-->>Front: 503 LEGAL_DOCUMENTS_UNAVAILABLE<br/>또는 409 LEGAL_DOCUMENT_NOT_CURRENT
                    Note over Service,Redis: 회원·동의 이력은 저장하지 않고 가입 토큰 유지
                else 두 현재 게시본 검증 완료
                    LegalRepo-->>LegalService: 이용약관·개인정보처리방침
                    Service->>Service: 한 번의 recordedAt 생성 + PasswordEncoder.encode
                    Service->>Mapper: toEntity(request, passwordHash, phoneNumber, recordedAt)
                    Mapper->>Mapper: Member.registerPhoneVerified<br/>ACTIVE, AUTHOR, phoneVerified=true, age 시각
                    Mapper-->>Service: Member
                    Service->>MemberRepo: save(member)
                    Service->>Mapper: toLegalRecordEntities(member, documents, recordedAt)
                    Service->>RecordRepo: saveAll(정확한 문서 FK·snapshot 2건)
                    Service->>Token: access token + opaque refresh token 생성<br/>refresh token hash 계산
                    Token-->>Service: AuthTokenIssueResult 재료
                    Service->>RefreshRepo: save(refreshTokenHash, expiresAt)
                    Service->>MemberRepo: flush()
                    Note over MemberRepo,RefreshRepo: 회원·법률 기록·refresh token과 unique/FK 제약을 DB에 먼저 반영
                    alt DB unique 또는 flush 실패
                        MemberRepo-->>Service: DB flush 예외
                        Note over Service,Redis: DB rollback, 가입 토큰은 소비하지 않음
                        Service-->>Front: 요청 실패
                    else DB flush 성공
                        Service->>PhoneService: consumeSignupToken(token, phoneNumber)
                        PhoneService->>Redis: GETDEL signup-token:{token}
                        alt 동시 요청이 먼저 소비했거나 번호 불일치
                            Redis-->>PhoneService: 없음 또는 다른 번호
                            PhoneService-->>Service: AUTH_PHONE_VERIFICATION_TOKEN_INVALID 예외
                            Note over Service,RefreshRepo: 예외로 회원·법률 기록·refresh token DB transaction rollback
                            Service-->>Front: 400 AUTH_PHONE_VERIFICATION_TOKEN_INVALID
                        else 토큰 1회 소비 성공
                            Redis-->>PhoneService: 같은 phoneNumber
                            PhoneService-->>Service: 완료
                            Note over Service,RefreshRepo: DB transaction commit
                            Service-->>Controller: access token + refresh token 원문
                            Controller->>Controller: RefreshTokenCookieFactory.create(refreshToken)
                            Controller-->>Front: 200 access token body<br/>refresh token HttpOnly cookie
                            Front->>Front: sessionStorage 인증 흐름 제거<br/>saveAuthToken(response)
                            Front->>Front: /works로 replace 이동
                        end
                    end
                end
            end
        end
    end
```

## 상세 처리 기준

1. 회원가입 요청에는 전화번호가 없습니다. `AuthServiceImpl.signup()`이 가입 토큰으로 Redis에서 조회한 번호만 `Member.phoneNumber`에 사용하므로 클라이언트가 인증하지 않은 번호를 제출할 수 없습니다.
2. 가입 토큰은 중복 검증 전에 조회하지만 DB 저장 전에는 삭제하지 않습니다. 회원과 refresh token을 flush한 다음 `GETDEL`이 성공해야 DB transaction을 commit할 수 있습니다.
3. Redis는 JPA transaction에 참여하지 않습니다. 현재 구현은 DB unique 제약을 먼저 flush하고 Redis 토큰을 소비한 뒤 추가 DB 변경 없이 commit해 두 저장소 사이의 실패 가능 구간을 줄이지만, 분산 transaction처럼 완전히 원자적인 commit을 제공하지는 않습니다.
4. 같은 가입 토큰의 동시 요청은 Redis `GETDEL` 성공을 하나로 제한하고, 실패한 요청은 `AppException`으로 DB transaction을 rollback 합니다. 이메일·전화번호 unique 제약은 동시에 다른 토큰을 사용한 가입까지 막는 최종 방어선입니다.
5. 인증번호 확인을 동시에 호출하면 Redis Lua가 최초 가입 토큰 하나만 만들고 이후 요청에는 같은 토큰을 반환합니다.
6. Redis rate limit과 인증 흐름 저장이 모두 성공한 뒤 SMS를 한 번 호출합니다. SMS timeout이나 provider 오류에서는 중복 문자와 이중 비용을 피하기 위해 자동 재시도하지 않습니다.
7. 가입 성공 응답 자체가 로그인 결과입니다. access token은 응답 body, refresh token은 원문을 DB에 남기지 않고 hash만 저장한 뒤 `HttpOnly` 쿠키로 전달합니다.
8. Front는 현재 게시 문서 묶음에서 확인한 두 ID를 보내고 Backend는 버전 문자열을 신뢰하지 않습니다. 두 ID 모두 가입 시점의 현재 `PUBLISHED` 문서여야 하며 교체된 문서는 409로 다시 확인받습니다.
9. 이용약관 동의, 개인정보처리방침 확인과 만 14세 이상 확인은 모두 필수입니다. 두 법률 기록과 `members.age_requirement_confirmed_at`은 같은 `recordedAt`을 사용해 한 가입 사건으로 추적합니다.
