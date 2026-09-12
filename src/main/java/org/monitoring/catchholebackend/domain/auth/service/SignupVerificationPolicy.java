package org.monitoring.catchholebackend.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationMethod;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(SignupVerificationProperties.class)
public class SignupVerificationPolicy {
    private final SignupVerificationProperties properties;

    public SignupVerificationMethod verificationMethod() {
        return properties.verificationMethod();
    }

    public void requireMethod(SignupVerificationMethod method) {
        if (verificationMethod() != method) {
            throw new AppException(AuthErrorCode.AUTH_SIGNUP_VERIFICATION_METHOD_DISABLED);
        }
    }
}
