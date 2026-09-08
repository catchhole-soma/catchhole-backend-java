package org.monitoring.catchholebackend.domain.auth.mapper;

import java.time.Duration;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationSendResponse;
import org.monitoring.catchholebackend.domain.auth.email.EmailVerificationStore;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationMapper {

    public EmailVerificationSendResponse toSendResponse(
            String verificationId,
            Duration codeExpiration,
            Duration resendInterval
    ) {
        return new EmailVerificationSendResponse(
                verificationId,
                codeExpiration.toSeconds(),
                resendInterval.toSeconds()
        );
    }

    public EmailVerificationConfirmResponse toConfirmResponse(
            EmailVerificationStore.ConfirmationResult result
    ) {
        return new EmailVerificationConfirmResponse(result.token(), result.expiresInSeconds());
    }
}
