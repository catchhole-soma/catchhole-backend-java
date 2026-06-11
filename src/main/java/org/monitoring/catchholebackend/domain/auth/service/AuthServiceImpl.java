package org.monitoring.catchholebackend.domain.auth.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.auth.dto.response.AuthTokenResponse;
import org.monitoring.catchholebackend.domain.auth.entity.RefreshToken;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.auth.token.RefreshTokenGenerator;
import org.monitoring.catchholebackend.domain.auth.token.TokenHashProvider;
import org.monitoring.catchholebackend.domain.member.dto.response.MemberResponse;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.mapper.MemberMapper;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.config.auth.AuthProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final TokenHashProvider tokenHashProvider;
    private final MemberMapper memberMapper;
    private final AuthProperties authProperties;

    @Override
    @Transactional
    public MemberResponse signup(AuthSignupRequest request) {
        validateSignupUniqueness(request);

        Member member = Member.register(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.phoneNumber(),
                request.displayName()
        );

        return memberMapper.toResponse(memberRepository.save(member));
    }

    @Override
    @Transactional
    public AuthTokenIssueResult login(AuthLoginRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new AppException(AuthErrorCode.AUTH_INVALID_CREDENTIALS));
        member.validateActive();

        if (!passwordEncoder.matches(request.password(), member.getPasswordHash())) {
            throw new AppException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        return issueTokens(member);
    }

    @Override
    @Transactional
    public AuthTokenIssueResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AppException(AuthErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND);
        }

        RefreshToken savedToken = refreshTokenRepository.findByTokenHash(tokenHashProvider.hash(refreshToken))
                .orElseThrow(() -> new AppException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        LocalDateTime now = LocalDateTime.now();
        if (savedToken.isRevoked() || savedToken.isExpired(now)) {
            throw new AppException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        Member member = savedToken.getMember();
        member.validateActive();
        savedToken.revoke(now);

        return issueTokens(member);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        refreshTokenRepository.findByTokenHash(tokenHashProvider.hash(refreshToken))
                .ifPresent(token -> token.revoke(LocalDateTime.now()));
    }

    private AuthTokenIssueResult issueTokens(Member member) {
        String accessToken = jwtTokenProvider.generateAccessToken(member);
        String refreshToken = refreshTokenGenerator.generate();
        RefreshToken savedRefreshToken = RefreshToken.builder()
                .member(member)
                .tokenHash(tokenHashProvider.hash(refreshToken))
                .expiresAt(LocalDateTime.now().plus(authProperties.refreshTokenExpiration()))
                .build();
        refreshTokenRepository.save(savedRefreshToken);

        AuthTokenResponse response = AuthTokenResponse.bearer(
                accessToken,
                jwtTokenProvider.getAccessTokenExpiresInSeconds()
        );
        return new AuthTokenIssueResult(response, refreshToken);
    }

    private void validateSignupUniqueness(AuthSignupRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new AppException(AuthErrorCode.AUTH_EMAIL_DUPLICATED);
        }
        if (memberRepository.existsByPhoneNumber(request.phoneNumber())) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_NUMBER_DUPLICATED);
        }
    }
}
