package org.monitoring.catchholebackend.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.dto.response.SignupPolicyResponse;
import org.monitoring.catchholebackend.domain.auth.service.SignupVerificationPolicy;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Auth")
public class SignupPolicyController {
    private final SignupVerificationPolicy policy;

    @GetMapping("/api/v1/auth/signup-policy")
    @Operation(operationId = "getSignupPolicy", summary = "회원가입 인증 수단 조회")
    public ResponseEntity<CommonResponse<SignupPolicyResponse>> getSignupPolicy() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CommonResponse.success(new SignupPolicyResponse(policy.verificationMethod())));
    }
}
