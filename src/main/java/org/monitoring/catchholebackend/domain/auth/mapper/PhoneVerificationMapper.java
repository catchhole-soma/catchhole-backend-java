package org.monitoring.catchholebackend.domain.auth.mapper;

import java.time.Duration;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationSendResponse;
import org.monitoring.catchholebackend.domain.auth.phone.PhoneVerificationStore;
import org.springframework.stereotype.Component;

@Component
public class PhoneVerificationMapper {

    public PhoneVerificationSendResponse toSendResponse(
            String verificationId,
            Duration codeExpiration,
            Duration resendInterval
    ) {
        return new PhoneVerificationSendResponse(
                verificationId,
                codeExpiration.toSeconds(),
                resendInterval.toSeconds()
        );
    }

    public PhoneVerificationConfirmResponse toConfirmResponse(
            PhoneVerificationStore.ConfirmationResult result
    ) {
        return new PhoneVerificationConfirmResponse(result.token(), result.expiresInSeconds());
    }
}
