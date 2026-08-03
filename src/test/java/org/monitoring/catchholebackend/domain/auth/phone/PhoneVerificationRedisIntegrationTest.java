package org.monitoring.catchholebackend.domain.auth.phone;

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
import org.monitoring.catchholebackend.global.config.phoneverification.PhoneVerificationProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Redis 휴대폰 인증 저장소")
class PhoneVerificationRedisIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.10-alpine3.21")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private PhoneVerificationStore phoneVerificationStore;
    private PhoneVerificationRateLimiter phoneVerificationRateLimiter;

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
        PhoneVerificationProperties properties = new PhoneVerificationProperties(
                "test-phone-verification-secret-at-least-32-bytes",
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(10),
                5
        );
        phoneVerificationStore = new PhoneVerificationStore(redisTemplate, properties);
        phoneVerificationRateLimiter = new PhoneVerificationRateLimiter(
                redisTemplate,
                Clock.systemUTC(),
                properties
        );
    }

    @Test
    @DisplayName("인증번호와 가입 토큰 TTL을 적용하고 가입 토큰을 한 번만 소비한다")
    void appliesTtlAndConsumesSignupTokenOnce() {
        phoneVerificationStore.replaceActiveVerificationFlow(
                "phone-hash",
                "verification-id",
                "01012345678",
                "code-hash"
        );
        Long flowTtl = redisTemplate.getExpire("phone-verification:flow:verification-id");

        PhoneVerificationStore.ConfirmationResult confirmation =
                phoneVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                        "verification-id",
                        "code-hash",
                        "signup-token"
                );
        Long tokenTtl = redisTemplate.getExpire("phone-verification:signup-token:signup-token");

        assertThat(flowTtl).isBetween(298L, 300L);
        assertThat(confirmation.status()).isEqualTo(1);
        assertThat(confirmation.expiresInSeconds()).isEqualTo(600);
        assertThat(tokenTtl).isBetween(598L, 600L);
        assertThat(phoneVerificationStore.consumeSignupToken("signup-token", "01012345678")).isTrue();
        assertThat(phoneVerificationStore.consumeSignupToken("signup-token", "01012345678")).isFalse();
    }

    @Test
    @DisplayName("재전송은 이전 인증 흐름을 즉시 폐기한다")
    void resendInvalidatesPreviousCode() {
        phoneVerificationStore.replaceActiveVerificationFlow(
                "phone-hash",
                "old-id",
                "01012345678",
                "old-code-hash"
        );
        phoneVerificationStore.replaceActiveVerificationFlow(
                "phone-hash",
                "new-id",
                "01012345678",
                "new-code-hash"
        );

        assertThat(phoneVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "old-id",
                "old-code-hash",
                "old-token"
        ).status()).isZero();
        assertThat(phoneVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "new-id",
                "new-code-hash",
                "new-token"
        ).status()).isEqualTo(1);
    }

    @Test
    @DisplayName("인증번호를 5회 틀리면 잠그고 이후 올바른 번호도 거부한다")
    void locksAfterFiveInvalidAttempts() {
        phoneVerificationStore.replaceActiveVerificationFlow(
                "phone-hash",
                "verification-id",
                "01012345678",
                "correct-hash"
        );

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(phoneVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                    "verification-id",
                    "wrong-hash",
                    "token-" + attempt
            ).status())
                    .isEqualTo(-1);
        }
        assertThat(phoneVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id",
                "wrong-hash",
                "token-5"
        ).status()).isEqualTo(-2);
        assertThat(phoneVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                "verification-id",
                "correct-hash",
                "token-correct"
        ).status()).isEqualTo(-2);
    }

    @Test
    @DisplayName("동시에 같은 인증번호를 확인해도 하나의 가입 토큰만 발급한다")
    void concurrentConfirmationIssuesOneToken() throws Exception {
        phoneVerificationStore.replaceActiveVerificationFlow(
                "phone-hash",
                "verification-id",
                "01012345678",
                "correct-hash"
        );
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<PhoneVerificationStore.ConfirmationResult>> tasks = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                String candidate = "signup-token-" + index;
                tasks.add(() -> phoneVerificationStore.confirmVerificationCodeAndIssueSignupToken(
                        "verification-id",
                        "correct-hash",
                        candidate
                ));
            }
            List<Future<PhoneVerificationStore.ConfirmationResult>> futures = executor.invokeAll(tasks);
            Set<String> issuedTokens = new HashSet<>();
            for (Future<PhoneVerificationStore.ConfirmationResult> future : futures) {
                PhoneVerificationStore.ConfirmationResult result = future.get();
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
        phoneVerificationRateLimiter.acquireSendPermit("phone-hash", "ip-hash");

        assertThatThrownBy(() -> phoneVerificationRateLimiter.acquireSendPermit("phone-hash", "ip-hash"))
                .isInstanceOf(AppException.class)
                .satisfies(exception -> {
                    AppException appException = (AppException) exception;
                    assertThat(appException.getResultCode())
                            .isEqualTo(AuthErrorCode.AUTH_PHONE_VERIFICATION_RATE_LIMITED);
                    assertThat(appException.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
                });
    }

    @Test
    @DisplayName("전화번호 시간당 5건과 하루 10건 경계에서 차단한다")
    void enforcesPhoneLimits() {
        for (int index = 0; index < 5; index++) {
            phoneVerificationRateLimiter.acquireSendPermit("phone-hash", "ip-" + index);
            deleteCooldown("phone-hash");
        }
        assertRateLimited(() -> phoneVerificationRateLimiter.acquireSendPermit("phone-hash", "ip-next"));

        clearKeys("phone-verification:rate:phone:hour:*");
        for (int index = 5; index < 10; index++) {
            phoneVerificationRateLimiter.acquireSendPermit("phone-hash", "ip-" + index);
            deleteCooldown("phone-hash");
            clearKeys("phone-verification:rate:phone:hour:*");
        }
        assertRateLimited(() -> phoneVerificationRateLimiter.acquireSendPermit("phone-hash", "ip-last"));
    }

    @Test
    @DisplayName("IP 시간당 10건과 하루 20건 경계에서 차단한다")
    void enforcesIpLimits() {
        for (int index = 0; index < 10; index++) {
            phoneVerificationRateLimiter.acquireSendPermit("phone-" + index, "ip-hash");
            deleteCooldown("phone-" + index);
        }
        assertRateLimited(() -> phoneVerificationRateLimiter.acquireSendPermit("phone-hour-last", "ip-hash"));

        clearKeys("phone-verification:rate:ip:hour:*");
        for (int index = 10; index < 20; index++) {
            phoneVerificationRateLimiter.acquireSendPermit("phone-" + index, "ip-hash");
            deleteCooldown("phone-" + index);
            clearKeys("phone-verification:rate:ip:hour:*");
        }
        assertRateLimited(() -> phoneVerificationRateLimiter.acquireSendPermit("phone-day-last", "ip-hash"));
    }

    @Test
    @DisplayName("전체 하루 20건과 월 200건 경계에서 차단한다")
    void enforcesGlobalLimits() {
        String date = LocalDate.now(KST).toString();
        String month = YearMonth.now(KST).toString();
        redisTemplate.opsForValue().set("phone-verification:rate:global:day:" + date, "20", Duration.ofHours(1));
        assertRateLimited(() -> phoneVerificationRateLimiter.acquireSendPermit("phone-day", "ip-day"));

        redisTemplate.delete("phone-verification:rate:global:day:" + date);
        redisTemplate.opsForValue().set("phone-verification:rate:global:month:" + month, "200", Duration.ofHours(1));
        assertRateLimited(() -> phoneVerificationRateLimiter.acquireSendPermit("phone-month", "ip-month"));
    }

    private void assertRateLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(AppException.class)
                .extracting("resultCode")
                .isEqualTo(AuthErrorCode.AUTH_PHONE_VERIFICATION_RATE_LIMITED);
    }

    private void deleteCooldown(String phoneHash) {
        redisTemplate.delete("phone-verification:cooldown:" + phoneHash);
    }

    private void clearKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
