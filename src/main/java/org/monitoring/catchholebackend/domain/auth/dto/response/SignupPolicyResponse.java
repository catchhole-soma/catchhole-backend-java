package org.monitoring.catchholebackend.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.global.config.auth.SignupVerificationMethod;

@Schema(description = "서버가 요구하는 회원가입 인증 수단")
public record SignupPolicyResponse(
        @Schema(description = "가입에 필요한 인증 수단", requiredMode = Schema.RequiredMode.REQUIRED)
        SignupVerificationMethod verificationMethod
) {
}
