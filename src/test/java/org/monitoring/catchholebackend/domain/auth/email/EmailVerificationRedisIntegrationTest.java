package org.monitoring.catchholebackend.domain.auth.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.config.emailverification.EmailVerificationProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Redis 이메일 인증 저장소")
class EmailVerificationRedisIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.10-alpine3.21")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private EmailVerificationStore emailVerificationStore;
    private EmailVerificationRateLimiter emailVerificationRateLimiter;
    private EmailVerificationHasher emailVerificationHasher;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        EmailVerificationProperties properties = new EmailVerificationProperties(
                "test-email-verification-secret-at-least-32-bytes",
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(10),
                5, 5, 10, 10, 20, 100, 3000
        );
        emailVerificationStore = new EmailVerificationStore(redisTemplate, properties);
        emailVerificationHasher = new EmailVerificationHasher(properties);
        emailVerificationRateLimiter = new EmailVerificationRateLimiter(
                redisTemplate,
                Clock.systemUTC(),
                properties
        );
    }

    @Test
    @DisplayName("인증번호와 가입 토큰 TTL을 적용하고 가입 토큰을 한 번만 소비한다")
    void appliesTtlAndConsumesSignupTokenOnce() {
        emailVerificationStore.replaceActiveVerificationFlow(
                "email-hash",
                "verification-id",
                "writer@example.com",
                "code-hash"
        );
        Long flowTtl = redisTemplate.getExpire("email-verification:flow:verification-id");

        EmailVerificationStore.ConfirmationResult confirmation =
                emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                        "verification-id",
                        "code-hash",
                        "signup-token"
                );
        Long tokenTtl = redisTemplate.getExpire("email-verification:signup-token:signup-token");

        assertThat(flowTtl).isBetween(298L, 300L);
        assertThat(confirmation.status()).isEqualTo(1);
        assertThat(confirmation.expiresInSeconds()).isEqualTo(600);
        assertThat(tokenTtl).isBetween(598L, 600L);
        assertThat(emailVerificationStore.consumeSignupToken("signup-token", "writer@example.com")).isTrue();
        assertThat(emailVerificationStore.consumeSignupToken("signup-token", "writer@example.com")).isFalse();
    }

    @Test
    @DisplayName("재전송은 이전 인증 흐름을 즉시 폐기한다")
    void resendInvalidatesPreviousCode() {
        emailVerificationStore.replaceActiveVerificationFlow(
                "email-hash",
                "old-id",
                "writer@example.com",
                "old-code-hash"
        );
        emailVerificationStore.replaceActiveVerificationFlow(
                "email-hash",
                "new-id",
                "writer@example.com",
                "new-code-hash"
        );

        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "old-id",
                "old-code-hash",
                "old-token"
        ).status()).isZero();
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "new-id",
                "new-code-hash",
                "new-token"
        ).status()).isEqualTo(1);
    }

    @Test
    @DisplayName("인증번호를 5회 틀리면 잠그고 이후 올바른 번호도 거부한다")
    void locksAfterFiveInvalidAttempts() {
        emailVerificationStore.replaceActiveVerificationFlow(
                "email-hash",
                "verification-id",
                "writer@example.com",
                "correct-hash"
        );

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                    "verification-id",
                    "wrong-hash",
                    "token-" + attempt
            ).status())
                    .isEqualTo(-1);
        }
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id",
                "wrong-hash",
                "token-5"
        ).status()).isEqualTo(-2);
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id",
                "correct-hash",
                "token-correct"
        ).status()).isEqualTo(-2);
    }

    @Test
    @DisplayName("동시에 같은 인증번호를 확인해도 하나의 가입 토큰만 발급한다")
    void concurrentConfirmationIssuesOneToken() throws Exception {
        emailVerificationStore.replaceActiveVerificationFlow(
                "email-hash",
                "verification-id",
                "writer@example.com",
                "correct-hash"
        );
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<EmailVerificationStore.ConfirmationResult>> tasks = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                String candidate = "signup-token-" + index;
                tasks.add(() -> emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                        "verification-id",
                        "correct-hash",
                        candidate
                ));
            }
            List<Future<EmailVerificationStore.ConfirmationResult>> futures = executor.invokeAll(tasks);
            Set<String> issuedTokens = new HashSet<>();
            for (Future<EmailVerificationStore.ConfirmationResult> future : futures) {
                EmailVerificationStore.ConfirmationResult result = future.get();
                assertThat(result.status()).isIn(1, 2);
                issuedTokens.add(result.token());
            }
            assertThat(issuedTokens).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("60초 재전송 대기 제한은 Retry-After를 반환한다")
    void resendCooldownReturnsRetryAfter() {
        emailVerificationRateLimiter.acquireSendPermit("email-hash", "ip-hash");

        assertThatThrownBy(() -> emailVerificationRateLimiter.acquireSendPermit("email-hash", "ip-hash"))
                .isInstanceOf(AppException.class)
                .satisfies(exception -> {
                    AppException appException = (AppException) exception;
                    assertThat(appException.getResultCode())
                            .isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_RATE_LIMITED);
                    assertThat(appException.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
                });
    }

    @Test
    @DisplayName("이메일 도메인 대소문자를 바꿔도 다른 IP에서 재전송 대기를 우회할 수 없다")
    void domainCaseVariantsShareResendCooldown() {
        String originalHash = verificationEmailHash("Writer@example.com");
        String variantHash = verificationEmailHash("Writer@EXAMPLE.COM");
        assertThat(variantHash).isEqualTo(originalHash);
        assertThat(verificationEmailHash("writer@example.com")).isNotEqualTo(originalHash);
        emailVerificationRateLimiter.acquireSendPermit(originalHash, "original-ip");

        assertRateLimited(() -> emailVerificationRateLimiter.acquireSendPermit(variantHash, "another-ip"));
    }

    @Test
    @DisplayName("도메인 대소문자를 바꾼 재전송도 이전 인증 흐름과 가입 토큰을 폐기한다")
    void domainCaseResendRevokesPreviousFlowAndToken() {
        emailVerificationStore.replaceActiveVerificationFlow(
                verificationEmailHash("Writer@example.com"), "old-id", "Writer@example.com", "old-code-hash");
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "old-id", "old-code-hash", "old-token").status()).isEqualTo(1);

        emailVerificationStore.replaceActiveVerificationFlow(
                verificationEmailHash("Writer@EXAMPLE.COM"), "new-id", "Writer@EXAMPLE.COM", "new-code-hash");

        assertThat(emailVerificationStore.findEmailBySignupToken("old-token")).isNull();
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "old-id", "old-code-hash", "unused-token").status()).isZero();
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "new-id", "new-code-hash", "new-token").status()).isEqualTo(1);
        assertThat(emailVerificationStore.findEmailBySignupToken("new-token")).isEqualTo("Writer@EXAMPLE.COM");
    }

    @Test
    @DisplayName("이메일 시간당 5건과 하루 10건 경계에서 차단한다")
    void enforcesEmailLimits() {
        for (int index = 0; index < 5; index++) {
            emailVerificationRateLimiter.acquireSendPermit("email-hash", "ip-" + index);
            deleteCooldown("email-hash");
        }
        assertRateLimited(() -> emailVerificationRateLimiter.acquireSendPermit("email-hash", "ip-next"));

        clearKeys("email-verification:rate:email:hour:*");
        for (int index = 5; index < 10; index++) {
            emailVerificationRateLimiter.acquireSendPermit("email-hash", "ip-" + index);
            deleteCooldown("email-hash");
            clearKeys("email-verification:rate:email:hour:*");
        }
        assertRateLimited(() -> emailVerificationRateLimiter.acquireSendPermit("email-hash", "ip-last"));
    }

    @Test
    @DisplayName("IP 시간당 10건과 하루 20건 경계에서 차단한다")
    void enforcesIpLimits() {
        for (int index = 0; index < 10; index++) {
            emailVerificationRateLimiter.acquireSendPermit("email-" + index, "ip-hash");
            deleteCooldown("email-" + index);
        }
        assertRateLimited(() -> emailVerificationRateLimiter.acquireSendPermit("email-hour-last", "ip-hash"));

        clearKeys("email-verification:rate:ip:hour:*");
        for (int index = 10; index < 20; index++) {
            emailVerificationRateLimiter.acquireSendPermit("email-" + index, "ip-hash");
            deleteCooldown("email-" + index);
            clearKeys("email-verification:rate:ip:hour:*");
        }
        assertRateLimited(() -> emailVerificationRateLimiter.acquireSendPermit("email-day-last", "ip-hash"));
    }

    @Test
    @DisplayName("전체 하루 100건과 월 3000건 경계에서 차단한다")
    void enforcesGlobalLimits() {
        String date = LocalDate.now(KST).toString();
        String month = YearMonth.now(KST).toString();
        redisTemplate.opsForValue().set("email-verification:rate:global:day:" + date, "100", Duration.ofHours(1));
        assertRateLimited(() -> emailVerificationRateLimiter.acquireSendPermit("email-day", "ip-day"));

        redisTemplate.delete("email-verification:rate:global:day:" + date);
        redisTemplate.opsForValue().set("email-verification:rate:global:month:" + month, "3000", Duration.ofHours(1));
        assertRateLimited(() -> emailVerificationRateLimiter.acquireSendPermit("email-month", "ip-month"));
    }

    @Test
    @DisplayName("확인된 흐름도 코드가 틀리면 가입 토큰을 반환하지 않는다")
    void confirmedFlowStillChecksCode() {
        createConfirmedFlow();
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id", "wrong-hash", "new-token").status()).isEqualTo(-1);
        assertThat(emailVerificationStore.findEmailBySignupToken("signup-token")).isEqualTo("writer@example.com");
    }

    @Test
    @DisplayName("재확인은 기존 가입 토큰만 반환하고 남은 유효시간을 연장하지 않는다")
    void repeatedConfirmationDoesNotExtendTokenTtl() {
        createConfirmedFlow();
        redisTemplate.expire("email-verification:signup-token:signup-token", Duration.ofSeconds(30));
        EmailVerificationStore.ConfirmationResult result = emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id", "code-hash", "new-token");
        assertThat(result.status()).isEqualTo(2);
        assertThat(result.token()).isEqualTo("signup-token");
        assertThat(result.expiresInSeconds()).isBetween(28L, 30L);
        assertThat(emailVerificationStore.findEmailBySignupToken("new-token")).isNull();
    }

    @Test
    @DisplayName("재전송하면 이미 확인된 이전 가입 토큰도 폐기한다")
    void resendRevokesPreviouslyIssuedSignupToken() {
        createConfirmedFlow();
        emailVerificationStore.replaceActiveVerificationFlow(
                "email-hash", "new-id", "writer@example.com", "new-code-hash");
        assertThat(emailVerificationStore.findEmailBySignupToken("signup-token")).isNull();
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id", "code-hash", "reissued-token").status()).isZero();
    }

    @Test
    @DisplayName("가입 토큰이 만료되면 이전 인증번호로 새 토큰을 발급할 수 없다")
    void expiredSignupTokenCannotBeReissued() {
        createConfirmedFlow();
        redisTemplate.expire("email-verification:signup-token:signup-token", Duration.ZERO);
        assertThat(emailVerificationStore.findEmailBySignupToken("signup-token")).isNull();
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id", "code-hash", "new-token").status()).isZero();
        assertThat(emailVerificationStore.findEmailBySignupToken("new-token")).isNull();
    }

    @Test
    @DisplayName("가입 토큰을 소비한 뒤 같은 인증번호로 토큰을 다시 발급할 수 없다")
    void consumedSignupTokenCannotBeReissued() {
        createConfirmedFlow();
        assertThat(emailVerificationStore.consumeSignupToken("signup-token", "writer@example.com")).isTrue();
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id", "code-hash", "new-token").status()).isZero();
    }

    @Test
    @DisplayName("이메일이 일치하지 않으면 가입 토큰을 소진시키지 않는다")
    void mismatchedEmailDoesNotConsumeToken() {
        createConfirmedFlow();
        assertThat(emailVerificationStore.consumeSignupToken("signup-token", "another@example.com")).isFalse();
        assertThat(emailVerificationStore.consumeSignupToken("signup-token", "writer@example.com")).isTrue();
    }

    @Test
    @DisplayName("동시에 같은 가입 토큰을 소비하면 하나의 요청만 성공한다")
    void concurrentConsumptionSucceedsOnce() throws Exception {
        createConfirmedFlow();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                tasks.add(() -> emailVerificationStore.consumeSignupToken("signup-token", "writer@example.com"));
            }
            int successes = 0;
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                if (result.get()) successes++;
            }
            assertThat(successes).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("만료된 미확인 흐름에서는 올바른 코드도 거부한다")
    void expiredCodeIsRejected() {
        emailVerificationStore.replaceActiveVerificationFlow(
                "email-hash", "verification-id", "writer@example.com", "code-hash");
        redisTemplate.expire("email-verification:flow:verification-id", Duration.ZERO);
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id", "code-hash", "signup-token").status()).isZero();
    }

    @Test
    @DisplayName("제한된 발송 요청은 다른 카운터를 증가시키지 않는다")
    void rejectedPermitDoesNotIncrementOtherCounters() {
        emailVerificationRateLimiter.acquireSendPermit("email-hash", "ip-hash");
        assertRateLimited(() -> emailVerificationRateLimiter.acquireSendPermit("email-hash", "other-ip"));
        assertThat(redisTemplate.opsForValue().get("email-verification:rate:ip:hour:other-ip")).isNull();
        assertThat(redisTemplate.opsForValue().get(
                "email-verification:rate:global:day:" + LocalDate.now(KST))).isEqualTo("1");
    }

    @Test
    @DisplayName("KST 일·월 한도는 다음 자정과 다음 달 첫날에 만료된다")
    void calendarLimitsExpireAtKstBoundaries() {
        emailVerificationRateLimiter.acquireSendPermit("email-hash", "ip-hash");
        long nowMillis = System.currentTimeMillis();
        long dayEnd = LocalDate.now(KST).plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli();
        long monthEnd = YearMonth.now(KST).plusMonths(1).atDay(1).atStartOfDay(KST).toInstant().toEpochMilli();
        Long dayTtl = redisTemplate.getExpire(
                "email-verification:rate:global:day:" + LocalDate.now(KST), java.util.concurrent.TimeUnit.MILLISECONDS);
        Long monthTtl = redisTemplate.getExpire(
                "email-verification:rate:global:month:" + YearMonth.now(KST), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(dayTtl).isBetween(dayEnd - nowMillis - 1000, dayEnd - nowMillis + 1000);
        assertThat(monthTtl).isBetween(monthEnd - nowMillis - 1000, monthEnd - nowMillis + 1000);
    }

    @Test
    @DisplayName("확인된 흐름을 보존해도 인증번호 자체의 유효시간은 연장하지 않는다")
    void confirmedFlowCannotExtendCodeExpiration() {
        createConfirmedFlow();
        redisTemplate.opsForHash().put("email-verification:flow:verification-id", "codeExpiresAt", "0");
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id", "code-hash", "new-token").status()).isZero();
        assertThat(emailVerificationStore.findEmailBySignupToken("signup-token")).isEqualTo("writer@example.com");
    }

    private void createConfirmedFlow() {
        emailVerificationStore.replaceActiveVerificationFlow(
                "email-hash", "verification-id", "writer@example.com", "code-hash");
        assertThat(emailVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id", "code-hash", "signup-token").status()).isEqualTo(1);
    }

    private String verificationEmailHash(String email) {
        return emailVerificationHasher.hashIdentifier(EmailAddressNormalizer.normalizeForVerificationKey(email));
    }

    private void assertRateLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(AppException.class)
                .extracting("resultCode")
                .isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_RATE_LIMITED);
    }

    private void deleteCooldown(String emailHash) {
        redisTemplate.delete("email-verification:cooldown:" + emailHash);
    }

    private void clearKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
