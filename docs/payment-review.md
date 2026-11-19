# GH441 심사 회원 전용 결제 화면 API

프론트의 상품·PG 결제창을 지정된 일반 회원에게만 공개한다. [이슈 #441](https://github.com/catchhole-soma/catchhole-backend-java/issues/441). 실결제 승인, 주문/지급/환불 원장, DB migration, 새 회원 역할은 추가하지 않는다.

## 서버 접근 계약

- `GET /api/v1/payment-reviews/access`: 로그인한 회원에게 `{allowed: boolean}`. OFF·빈 허용 목록·미허용 회원은 false다.
- `GET /api/v1/payment-reviews/page`: 매 요청마다 허용 여부를 확인하고 결제창 설정과 구매조건·이용약관·개인정보처리방침 Markdown을 반환한다. 미허용은 403, 비로그인/비활성 회원은 기존 JWT 인증의 401이다.
- 현재 회원 번호는 기존 JWT 서명 검증→DB 활성 상태 확인으로 얻는다. 쿼리·헤더의 임의 memberId, 이메일, ADMIN 역할은 권한을 부여하지 않는다.
- 두 성공 응답은 `Cache-Control: no-store`. 일반 회원에게 원문이나 채널 값을 내리지 않는다. 프론트의 메뉴·페이지 가드 외에 서버 자료 API도 검증한다.
- 프론트는 로그인 세대별 Query, 직접 주소 차단, 결제창 직전 재조회와 세대 확인을 적용한다. 이미 내려받은 공개 SDK 식별값을 비밀값/승인 권한으로 간주하지 않으며 이미 열린 외부 PG 창을 회수하는 API는 아니다.

## 설정과 배포

| 변수 | 기본값 | 적용 위치 |
| --- | --- | --- |
| `PAYMENT_REVIEW_ENABLED` | false | base YAML → API Compose |
| `PAYMENT_REVIEW_MEMBER_IDS` | 빈 목록 | 같은 위치, 쉼표 구분 실제 회원 번호 |
| `PAYMENT_REVIEW_STORE_ID` | 빈 문자열 | PortOne 공개 상점 식별값 |
| `PAYMENT_REVIEW_CHANNEL_KEY` | 빈 문자열 | 콘솔에서 TEST/이니시스 V2/INIpayTest임을 확인한 공개 채널 키 |

설정은 local/prod 공통이며 Worker에는 추가하지 않는다. 설정 변경 뒤 API 재시작/재배포가 필요하다. 기능 OFF·빈 목록이면 모두 거절하며 채널 누락은 ready=false다. ready는 문자열 설정의 존재만 뜻하고 PG 승인/심사 통과를 의미하지 않는다. 실채널 연결·결제 승인은 이 작업의 범위가 아니다. API secret/signkey/비밀번호는 이 설정에 넣지 않는다.

백엔드 PR을 먼저 반영하고, 심사용 일반 계정을 정상 가입 절차로 준비한 뒤 해당 회원 번호를 등록한다. 프론트 `VITE_PAYMENT_REVIEW_ENABLED=true`도 필요하다. 프론트만 먼저 배포되거나 서버가 실패하면 접근을 열지 않는다. 운영 계정 생성·허용 목록 적용·배포는 별도이며 이번 구현에서 수행하지 않았다.

## 원문과 공개 약관

`src/main/resources/payment-review/`의 세 원문이 보호 API의 출처다. Front `docs/legal/`의 동명 파일은 리뷰/DEV 미리보기 사본이며 변경할 때 해시를 대조한다. 원문은 서버 시작 시 읽고, 누락되면 조용히 빈 문서를 반환하지 않고 시작을 실패시킨다.

`/billing/terms`, `/billing/privacy`는 심사 계정만 접근한다. 기존 공개 `/terms`, `/privacy`와 회원가입의 `legal_documents`/`member_legal_records`는 변경하지 않는다. 가짜 문서 버전·시행일을 만들지 않는다. 실제 유료 출시 전 개정 공지·게시와 구매 동의 원장 구현을 진행한다.

확정 상품: 기본 2,900원/400만, 더블 4,900원/800만 캐치홀 토큰(VAT 포함). 구매별 12개월, 무료 우선·유료분 만료순 사용, 남은 유료분 비례 환불과 3영업일 내 조치는 후속 실제 결제 구현의 요구사항이다.

## 검증

`PaymentReviewIntegrationTest`는 실제 Spring Security/JWT→회원 DB와 MockMvc로 8개 시나리오를 검사한다. 비로그인·위조 토큰, 일반 회원의 ID 위장, 허용 회원·문서·no-store, 허용 회수/OFF, ADMIN 자동 허용 금지, 비활성 계정, 기본값/채널 누락, OpenAPI 인증·403 계약이다. 별도 로컬 인메모리 H2와 실제 HTTP 로그인은 Front 브라우저 연결 검증에 사용하고 운영 DB/AI/실제 PG 호출은 하지 않는다.

2026-11-19 검증 결과:

- 전체 `./gradlew test`: 5,152개 통과, 356개 skip, 실패 0. 환경 opt-in 등의 skip을 통과로 세지 않았다.
- 최종 `./gradlew test --tests '*PaymentReview*' bootJar`: 통합 8개와 기본 OFF·빈 목록/쉼표 목록 바인딩 2개, 합계 10개 통과 및 실행 JAR 빌드 성공.
- 실제 로컬 HTTP와 브라우저에서 일반 회원은 access=false/page=403, 심사 회원은 access=true/page=200과 메뉴·상품·약관을 확인했다. 두 계정은 AUTHOR이며 운영 계정을 만들거나 수정하지 않았다.
- 서버가 반환한 원문 3개의 SHA-256을 Front 사본과 대조했다. GFM의 물결표 취소선 해석을 막아 예상 회차 `15~30`, `30~60`이 정상 표시되는 것도 확인했다.

## 2026-11-19 고객센터 연락처 확정

사용자가 아톡 뉴 베이직 월 3,300원으로 070-8027-2690을 개통했고 전화 상담을 평일 10:00~18:00으로 정했다. 같은 날 전화가 잘 되는 것도 확인했다. 심사 원문 3개는 Front 사본과 일치한다. 이니시스 신청 정보 변경·운영 배포·통신판매업의 카드 심사 처리·실제 심사 계정 생성은 남아 있다. 기존 공개 법률 문서와 동의 이력은 변경하지 않는다.

공정위 사업자번호 조회는 등록 정보 없음이었다. 등록증상 2026-08-11 신규 일반과세자이며, 첫 유료 판매라는 전제로 법정 신고 면제의 직전년도 거래 50회 미만 기준에 해당한다. 다만 [포트원 공식 안내](https://help.portone.io/content/requirements)는 KB국민카드 등에 신고번호를 요구한다. 신고 시 기본 등록면허세 40,500원과 이듬해 1월 1일 유지 시 추가 정기분은 PG 보험료와 별도다. 상세 근거와 비용 결정은 Front `docs/payment-release-readiness.md`에 기록했으며 신고·과금·외부 문의 발송은 하지 않았다.
