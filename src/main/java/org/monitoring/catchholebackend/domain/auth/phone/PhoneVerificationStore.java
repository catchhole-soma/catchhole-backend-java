package org.monitoring.catchholebackend.domain.auth.phone;

import java.time.Duration;
import java.util.List;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.config.phoneverification.PhoneVerificationProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class PhoneVerificationStore {

    private static final String FLOW_PREFIX = "phone-verification:flow:";
    private static final String ACTIVE_PREFIX = "phone-verification:active:";
    private static final String TOKEN_PREFIX = "phone-verification:signup-token:";

    private static final DefaultRedisScript<Long> START_SCRIPT = new DefaultRedisScript<>("""
            local previousId = redis.call('GET', KEYS[1])
            if previousId then redis.call('DEL', ARGV[1] .. previousId) end
            redis.call('HSET', KEYS[2],
                'phoneNumber', ARGV[2],
                'codeHash', ARGV[3],
                'attempts', '0',
                'confirmed', 'false')
            redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[4]))
            redis.call('SET', KEYS[1], ARGV[5], 'PX', tonumber(ARGV[4]))
            return 1
            """, Long.class);

    private static final DefaultRedisScript<List> CONFIRM_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return {0, '', 0} end

            local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
            if attempts >= tonumber(ARGV[2]) then return {-2, '', 0} end

            if redis.call('HGET', KEYS[1], 'confirmed') == 'true' then
                local existingToken = redis.call('HGET', KEYS[1], 'token')
                local existingTtl = redis.call('PTTL', ARGV[5] .. existingToken)
                if existingTtl < 1 then return {0, '', 0} end
                return {2, existingToken, math.ceil(existingTtl / 1000)}
            end

            if redis.call('HGET', KEYS[1], 'codeHash') ~= ARGV[1] then
                attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
                if attempts >= tonumber(ARGV[2]) then return {-2, '', 0} end
                return {-1, '', 0}
            end

            local phoneNumber = redis.call('HGET', KEYS[1], 'phoneNumber')
            if redis.call('SET', KEYS[2], phoneNumber, 'PX', tonumber(ARGV[4]), 'NX') == false then
                return {-3, '', 0}
            end
            redis.call('HSET', KEYS[1], 'confirmed', 'true', 'token', ARGV[3])
            return {1, ARGV[3], math.ceil(tonumber(ARGV[4]) / 1000)}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final PhoneVerificationProperties properties;

    public PhoneVerificationStore(
            StringRedisTemplate redisTemplate,
            PhoneVerificationProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void start(String phoneHash, String verificationId, String phoneNumber, String codeHash) {
        try {
            Long result = redisTemplate.execute(
                    START_SCRIPT,
                    List.of(ACTIVE_PREFIX + phoneHash, FLOW_PREFIX + verificationId),
                    FLOW_PREFIX,
                    phoneNumber,
                    codeHash,
                    Long.toString(properties.codeExpiration().toMillis()),
                    verificationId
            );
            if (result == null || result != 1L) {
                throw unavailable(null);
            }
        } catch (AppException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public ConfirmationResult confirm(String verificationId, String codeHash, String signupToken) {
        try {
            List<?> result = redisTemplate.execute(
                    CONFIRM_SCRIPT,
                    List.of(FLOW_PREFIX + verificationId, TOKEN_PREFIX + signupToken),
                    codeHash,
                    Integer.toString(properties.maxAttempts()),
                    signupToken,
                    Long.toString(properties.signupTokenExpiration().toMillis()),
                    TOKEN_PREFIX
            );
            if (result == null || result.size() < 3) {
                throw unavailable(null);
            }
            int status = Math.toIntExact(asLong(result.get(0)));
            return new ConfirmationResult(status, result.get(1).toString(), asLong(result.get(2)));
        } catch (AppException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public String findPhoneNumber(String signupToken) {
        try {
            return redisTemplate.opsForValue().get(TOKEN_PREFIX + signupToken);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public boolean consume(String signupToken, String expectedPhoneNumber) {
        try {
            String phoneNumber = redisTemplate.opsForValue().getAndDelete(TOKEN_PREFIX + signupToken);
            return expectedPhoneNumber.equals(phoneNumber);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public Duration codeExpiration() {
        return properties.codeExpiration();
    }

    public Duration resendInterval() {
        return properties.resendInterval();
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private AppException unavailable(Throwable cause) {
        return cause == null
                ? new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE)
                : new AppException(AuthErrorCode.AUTH_PHONE_VERIFICATION_UNAVAILABLE, cause);
    }

    public record ConfirmationResult(int status, String token, long expiresInSeconds) {
    }
}
