package org.monitoring.catchholebackend.domain.auth.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.auth.dto.response.AuthTokenResponse;
import org.monitoring.catchholebackend.domain.auth.entity.RefreshToken;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.domain.auth.mapper.AuthMapper;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.auth.token.RefreshTokenGenerator;
import org.monitoring.catchholebackend.domain.auth.token.TokenHashProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberLegalRecordRepository;
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
    private final MemberLegalRecordRepository memberLegalRecordRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final TokenHashProvider tokenHashProvider;
    private final AuthProperties authProperties;
    private final PhoneVerificationService phoneVerificationService;
    private final AuthMapper authMapper;

    @Override
    @Transactional
    public AuthTokenIssueResult signup(AuthSignupRequest request) {
        String phoneNumber = phoneVerificationService.getVerifiedPhoneNumberBySignupToken(
                request.phoneVerificationToken()
        );
        validateSignupUniqueness(request.email(), phoneNumber);

        Member member = authMapper.toEntity(
                request,
                passwordEncoder.encode(request.password()),
                phoneNumber
        );

        Member savedMember = memberRepository.save(member);
        memberLegalRecordRepository.saveAll(
                authMapper.toLegalRecordEntities(savedMember, LocalDateTime.now())
        );
        AuthTokenIssueResult result = issueTokens(savedMember);
        // Redis 토큰 소비 실패가 회원·법률 문서 기록·refresh token 저장까지 롤백되도록 같은 트랜잭션 안에서 먼저 flush한다.
        memberRepository.flush();
        phoneVerificationService.consumeSignupToken(request.phoneVerificationToken(), phoneNumber);
        return result;
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

        RefreshToken savedRefreshToken = refreshTokenRepository.findByTokenHash(tokenHashProvider.hash(refreshToken))
                .orElseThrow(() -> new AppException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        LocalDateTime now = LocalDateTime.now();
        if (savedRefreshToken.isRevoked() || savedRefreshToken.isExpired(now)) {
            throw new AppException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        Member member = savedRefreshToken.getMember();
        member.validateActive();
        savedRefreshToken.revoke(now);

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

        AuthTokenResponse tokenResponse = AuthTokenResponse.bearer(
                accessToken,
                jwtTokenProvider.getAccessTokenExpiresInSeconds()
        );
        return new AuthTokenIssueResult(tokenResponse, refreshToken);
    }

    private void validateSignupUniqueness(String email, String phoneNumber) {
        if (memberRepository.existsByEmail(email)) {
            throw new AppException(AuthErrorCode.AUTH_EMAIL_DUPLICATED);
        }
        if (memberRepository.existsByPhoneNumber(phoneNumber)) {
            throw new AppException(AuthErrorCode.AUTH_PHONE_NUMBER_DUPLICATED);
        }
    }
}
