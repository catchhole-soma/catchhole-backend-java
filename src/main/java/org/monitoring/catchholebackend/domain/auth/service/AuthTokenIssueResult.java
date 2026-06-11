package org.monitoring.catchholebackend.domain.auth.service;

import org.monitoring.catchholebackend.domain.auth.dto.response.AuthTokenResponse;

public record AuthTokenIssueResult(
        AuthTokenResponse tokenResponse,
        String refreshToken
) {
}
