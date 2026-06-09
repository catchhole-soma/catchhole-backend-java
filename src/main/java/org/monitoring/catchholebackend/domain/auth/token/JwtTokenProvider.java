package org.monitoring.catchholebackend.domain.auth.token;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.global.config.auth.AuthProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String ISSUER = "catchhole";

    private final JwtEncoder jwtEncoder;
    private final AuthProperties authProperties;

    public String generateAccessToken(Member member) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(authProperties.jwt().accessTokenExpiration());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(member.getId()))
                .claim("email", member.getEmail())
                .claim("roles", List.of(member.getRole().name()))
                .build();

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    public long getAccessTokenExpiresInSeconds() {
        return authProperties.jwt().accessTokenExpiration().toSeconds();
    }
}
