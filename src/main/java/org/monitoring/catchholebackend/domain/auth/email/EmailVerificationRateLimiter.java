package org.monitoring.catchholebackend.domain.auth.email;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.config.emailverification.EmailVerificationProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 이메일·IP·전체 발송량 제한을 Redis에서 관리한다.
 * 모든 한도를 Lua 한 번으로 확인한 뒤 함께 증가시켜 일부 카운터만 반영되는 경쟁 상태를 막는다.
 */
@Component
public class EmailVerificationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationRateLimiter.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    // 반환값은 {획득 여부(1/0), 제한 해제까지 남은 초}이며 제한 시 어떤 카운터도 증가시키지 않는다.
    private static final DefaultRedisScript<List> ACQUIRE_SEND_PERMIT_SCRIPT = new DefaultRedisScript<>("""
            local retryAfter = 0
            if redis.call('EXISTS', KEYS[1]) == 1 then
                local cooldownTtl = redis.call('PTTL', KEYS[1])
                if cooldownTtl > retryAfter then retryAfter = cooldownTtl end
            end

            for i = 2, 7 do
                local argumentIndex = 2 + ((i - 2) * 2)
                local limit = tonumber(ARGV[argumentIndex])
                local current = tonumber(redis.call('GET', KEYS[i]) or '0')
                if current >= limit then
                    local ttl = redis.call('PTTL', KEYS[i])
                    if ttl < 1 then ttl = 1000 end
                    if ttl > retryAfter then retryAfter = ttl end
                end
            end

            if retryAfter > 0 then
                return {0, math.ceil(retryAfter / 1000)}
            end

            for i = 2, 7 do
                local argumentIndex = 2 + ((i - 2) * 2)
                local expiresAt = tonumber(ARGV[argumentIndex + 1])
                local count = redis.call('INCR', KEYS[i])
                if count == 1 then redis.call('PEXPIREAT', KEYS[i], expiresAt) end
            end
            redis.call('PSETEX', KEYS[1], tonumber(ARGV[1]), '1')
            return {1, 0}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final EmailVerificationProperties properties;

    public EmailVerificationRateLimiter(
            StringRedisTemplate redisTemplate,
            @Qualifier("emailVerificationClock") Clock emailVerificationClock,
            EmailVerificationProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.clock = emailVerificationClock;
        this.properties = properties;
    }

    public void acquireSendPermit(String emailHash, String ipHash) {
        Instant now = clock.instant();
        ZonedDateTime nowKst = now.atZone(KST);
        LocalDate date = nowKst.toLocalDate();
        YearMonth month = YearMonth.from(nowKst);
        long hourlyExpiresAt = now.plusMillis(ONE_HOUR_MILLIS).toEpochMilli();
        long dailyExpiresAt = date.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli();
        long monthlyExpiresAt = month.plusMonths(1).atDay(1).atStartOfDay(KST).toInstant().toEpochMilli();

        List<String> keys = List.of(
                "email-verification:cooldown:" + emailHash,
                "email-verification:rate:email:hour:" + emailHash,
                "email-verification:rate:email:day:" + date + ":" + emailHash,
                "email-verification:rate:ip:hour:" + ipHash,
                "email-verification:rate:ip:day:" + date + ":" + ipHash,
                "email-verification:rate:global:day:" + date,
                "email-verification:rate:global:month:" + month
        );
        List<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(properties.resendInterval().toMillis()));
        addLimit(arguments, properties.emailHourlyLimit(), hourlyExpiresAt);
        addLimit(arguments, properties.emailDailyLimit(), dailyExpiresAt);
        addLimit(arguments, properties.ipHourlyLimit(), hourlyExpiresAt);
        addLimit(arguments, properties.ipDailyLimit(), dailyExpiresAt);
        addLimit(arguments, properties.globalDailyLimit(), dailyExpiresAt);
        addLimit(arguments, properties.globalMonthlyLimit(), monthlyExpiresAt);

        try {
            List<?> result = redisTemplate.execute(
                    ACQUIRE_SEND_PERMIT_SCRIPT,
                    keys,
                    arguments.toArray()
            );
            if (result == null || result.size() < 2) {
                throw unavailable(null);
            }
            if (asLong(result.get(0)) == 0) {
                log.warn("이메일 인증번호 발송 요청이 제한되었습니다.");
                throw new AppException(
                        AuthErrorCode.AUTH_EMAIL_VERIFICATION_RATE_LIMITED,
                        Math.max(1, asLong(result.get(1)))
                );
            }
        } catch (AppException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void addLimit(List<String> arguments, int limit, long expiresAt) {
        arguments.add(Integer.toString(limit));
        arguments.add(Long.toString(expiresAt));
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private AppException unavailable(Throwable cause) {
        return cause == null
                ? new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE)
                : new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_UNAVAILABLE, cause);
    }
}
