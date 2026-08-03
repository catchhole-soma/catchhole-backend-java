package org.monitoring.catchholebackend.domain.auth.phone;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.config.phoneverification.PhoneVerificationProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class PhoneVerificationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationRateLimiter.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long ONE_HOUR_MILLIS = 3_600_000L;
    private static final int PHONE_HOURLY_LIMIT = 5;
    private static final int PHONE_DAILY_LIMIT = 10;
    private static final int IP_HOURLY_LIMIT = 10;
    private static final int IP_DAILY_LIMIT = 20;
    private static final int GLOBAL_DAILY_LIMIT = 20;
    private static final int GLOBAL_MONTHLY_LIMIT = 200;

    private static final DefaultRedisScript<List> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
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
    private final PhoneVerificationProperties properties;

    public PhoneVerificationRateLimiter(
            StringRedisTemplate redisTemplate,
            Clock phoneVerificationClock,
            PhoneVerificationProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.clock = phoneVerificationClock;
        this.properties = properties;
    }

    public void acquire(String phoneHash, String ipHash) {
        Instant now = clock.instant();
        ZonedDateTime nowKst = now.atZone(KST);
        LocalDate date = nowKst.toLocalDate();
        YearMonth month = YearMonth.from(nowKst);
        long hourlyExpiresAt = now.plusMillis(ONE_HOUR_MILLIS).toEpochMilli();
        long dailyExpiresAt = date.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli();
        long monthlyExpiresAt = month.plusMonths(1).atDay(1).atStartOfDay(KST).toInstant().toEpochMilli();

        List<String> keys = List.of(
                "phone-verification:cooldown:" + phoneHash,
                "phone-verification:rate:phone:hour:" + phoneHash,
                "phone-verification:rate:phone:day:" + date + ":" + phoneHash,
                "phone-verification:rate:ip:hour:" + ipHash,
                "phone-verification:rate:ip:day:" + date + ":" + ipHash,
                "phone-verification:rate:global:day:" + date,
                "phone-verification:rate:global:month:" + month
        );
        List<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(properties.resendInterval().toMillis()));
        addLimit(arguments, PHONE_HOURLY_LIMIT, hourlyExpiresAt);
        addLimit(arguments, PHONE_DAILY_LIMIT, dailyExpiresAt);
        addLimit(arguments, IP_HOURLY_LIMIT, hourlyExpiresAt);
        addLimit(arguments, IP_DAILY_LIMIT, dailyExpiresAt);
        addLimit(arguments, GLOBAL_DAILY_LIMIT, dailyExpiresAt);
        addLimit(arguments, GLOBAL_MONTHLY_LIMIT, monthlyExpiresAt);

        try {
            List<?> result = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    keys,
                    arguments.toArray()
            );
            if (result == null || result.size() < 2) {
                throw unavailable(null);
            }
            if (asLong(result.get(0)) == 0) {
                log.warn("Phone verification request rate limited.");
                throw new AppException(
                        AuthErrorCode.AUTH_PHONE_VERIFICATION_RATE_LIMITED,
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
                ? new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE)
                : new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE, cause);
    }
}
