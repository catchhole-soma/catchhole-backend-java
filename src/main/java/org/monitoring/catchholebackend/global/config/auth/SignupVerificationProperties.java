package org.monitoring.catchholebackend.global.config.auth;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "auth.signup-verification")
public record SignupVerificationProperties(
        @NotNull @DefaultValue("EMAIL") SignupVerificationMethod verificationMethod
) {
}
