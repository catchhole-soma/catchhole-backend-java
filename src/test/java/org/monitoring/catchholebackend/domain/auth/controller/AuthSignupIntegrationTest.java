package org.monitoring.catchholebackend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.service.PhoneVerificationService;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberLegalRecordRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.type.LegalRecordAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("회원가입 API 통합")
class AuthSignupIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberLegalRecordRepository memberLegalRecordRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private PhoneVerificationService phoneVerificationService;

    @Test
    @DisplayName("회원가입 요청은 회원과 refresh token을 DB에 저장하고 인증 응답을 반환한다")
    void signupPersistsMemberAndRefreshTokenAndReturnsAuthentication() throws Exception {
        long refreshTokenCountBefore = refreshTokenRepository.count();
        org.mockito.Mockito.when(
                        phoneVerificationService.getVerifiedPhoneNumberBySignupToken("phone-verification-token")
                )
                .thenReturn("01055556666");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "new-writer@example.com",
                                  "password": "password123",
                                  "displayName": "신규 작가",
                                  "termsAccepted": true,
                                  "privacyPolicyAcknowledged": true,
                                  "phoneVerificationToken": "phone-verification-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("refreshToken=")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andExpect(jsonPath("$.data.accessToken", notNullValue()))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800));

        Member savedMember = memberRepository.findByEmail("new-writer@example.com").orElseThrow();
        assertThat(savedMember.getPhoneNumber()).isEqualTo("01055556666");
        assertThat(savedMember.getDisplayName()).isEqualTo("신규 작가");
        assertThat(savedMember.isPhoneVerified()).isTrue();
        assertThat(passwordEncoder.matches("password123", savedMember.getPasswordHash())).isTrue();
        assertThat(memberLegalRecordRepository.findAllByMemberIdOrderByRecordedAtAsc(savedMember.getId()))
                .extracting(
                        record -> record.getDocumentType(),
                        record -> record.getActionType(),
                        record -> record.getDocumentVersion()
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.TERMS_OF_SERVICE,
                                LegalRecordAction.AGREED,
                                "2026-08-23"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.PRIVACY_POLICY,
                                LegalRecordAction.ACKNOWLEDGED,
                                "2026-08-23"
                        )
                );
        assertThat(refreshTokenRepository.count()).isEqualTo(refreshTokenCountBefore + 1);
        org.mockito.Mockito.verify(phoneVerificationService)
                .consumeSignupToken("phone-verification-token", "01055556666");
    }

    @Test
    @DisplayName("회원가입은 이용약관 동의와 개인정보처리방침 확인을 모두 요구한다")
    void signupRequiresTermsAcceptanceAndPrivacyPolicyAcknowledgement() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "unchecked-writer@example.com",
                                  "password": "password123",
                                  "displayName": "미확인 작가",
                                  "termsAccepted": false,
                                  "privacyPolicyAcknowledged": false,
                                  "phoneVerificationToken": "phone-verification-token"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "termsAccepted",
                                "privacyPolicyAcknowledged"
                        )));

        assertThat(memberRepository.findByEmail("unchecked-writer@example.com")).isEmpty();
    }
}
