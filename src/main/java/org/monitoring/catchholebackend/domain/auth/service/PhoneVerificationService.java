package org.monitoring.catchholebackend.domain.auth.service;

import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationResponse;

public interface PhoneVerificationService {

    PhoneVerificationResponse start(String phoneNumber, String clientIp);

    PhoneVerificationConfirmResponse confirm(String verificationId, String code);

    String getVerifiedPhoneNumber(String signupToken);

    void consumeSignupToken(String signupToken, String expectedPhoneNumber);
}
