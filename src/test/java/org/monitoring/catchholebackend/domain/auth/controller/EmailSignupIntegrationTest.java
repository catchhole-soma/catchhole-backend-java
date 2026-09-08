package org.monitoring.catchholebackend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.exception.AuthErrorCode;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.service.EmailVerificationService;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.repository.LegalDocumentRepository;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberLegalRecordRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "auth.signup-verification.verification-method=EMAIL",
        "spring.datasource.url=jdbc:h2:mem:email-signup-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("이메일 가입의 회원 저장 및 트랜잭션 경계")
class EmailSignupIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository members;
    @Autowired private MemberLegalRecordRepository legalRecords;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private LegalDocumentRepository documents;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailVerificationService emailVerification;
    private Long termsId;
    private Long privacyId;

    @BeforeEach
    void prepare() {
        refreshTokens.deleteAll();
        legalRecords.deleteAll();
        members.deleteAll();
        documents.deleteAll();
        termsId = documents.save(document(LegalDocumentType.TERMS_OF_SERVICE)).getId();
        privacyId = documents.save(document(LegalDocumentType.PRIVACY_POLICY)).getId();
    }

    @Test
    @DisplayName("전화번호 없는 두 회원을 저장하고 이메일 인증 상태와 동의 시각을 기록한다")
    void createsMultipleEmailVerifiedMembersWithoutPhone() throws Exception {
        for (String email : new String[]{"first@example.com", "second@example.com"}) {
            when(emailVerification.getVerifiedEmailBySignupToken(email)).thenReturn(email);
            mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content(body(email, "\"emailVerificationToken\":\"" + email + "\"")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty());
            Member member = members.findByEmail(email).orElseThrow();
            assertThat(member.getPhoneNumber()).isNull();
            assertThat(member.isPhoneVerified()).isFalse();
            assertThat(member.isEmailVerified()).isTrue();
            assertThat(passwordEncoder.matches("password123", member.getPasswordHash())).isTrue();
            assertThat(legalRecords.findAllByMemberIdOrderByRecordedAtAsc(member.getId()))
                    .hasSize(2).allSatisfy(record ->
                            assertThat(record.getRecordedAt()).isEqualTo(member.getAgeRequirementConfirmedAt()));
            verify(emailVerification).consumeSignupToken(email, email);
        }
        assertThat(members.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Redis 토큰 소비 실패는 회원과 동의 기록 및 refresh token 저장을 모두 롤백한다")
    void failedTokenConsumptionRollsBackAllSignupWrites() throws Exception {
        when(emailVerification.getVerifiedEmailBySignupToken("used-token")).thenReturn("rollback@example.com");
        doThrow(new AppException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID))
                .when(emailVerification).consumeSignupToken("used-token", "rollback@example.com");
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body("rollback@example.com", "\"emailVerificationToken\":\"used-token\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_VERIFICATION_TOKEN_INVALID"));
        assertThat(members.count()).isZero();
        assertThat(legalRecords.count()).isZero();
        assertThat(refreshTokens.count()).isZero();
    }

    @Test
    @DisplayName("인증한 이메일을 바꿔 제출하면 회원을 만들지 않는다")
    void rejectsDifferentEmail() throws Exception {
        when(emailVerification.getVerifiedEmailBySignupToken("email-token")).thenReturn("verified@example.com");
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body("different@example.com", "\"emailVerificationToken\":\"email-token\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_VERIFICATION_EMAIL_MISMATCH"));
        assertThat(members.count()).isZero();
    }

    @Test
    @DisplayName("이메일 모드에서는 휴대폰 토큰만으로 가입할 수 없다")
    void requiresEmailTokenEvenWhenPhoneTokenIsPresent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body("phone-only@example.com", "\"phoneVerificationToken\":\"phone-token\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_VERIFICATION_TOKEN_REQUIRED"));
        verifyNoInteractions(emailVerification);
        assertThat(members.count()).isZero();
    }

    @Test
    @DisplayName("이메일 인증 이력이 없는 기존 전화번호 회원도 계속 로그인할 수 있다")
    void existingPhoneVerifiedMemberCanStillLogIn() throws Exception {
        Member legacy = members.save(Member.registerPhoneVerified("legacy@example.com",
                passwordEncoder.encode("password123"), "01011112222", "기존 작가", LocalDateTime.now()));
        assertThat(legacy.isEmailVerified()).isFalse();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"legacy@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("이메일 모드 정책은 공개 조회되며 SMS 발송은 차단된다")
    void policyBlocksInactiveSmsChannel() throws Exception {
        mockMvc.perform(get("/api/v1/auth/signup-policy"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationMethod").value("EMAIL"));
        mockMvc.perform(post("/api/v1/auth/phone-verifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"01011112222\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_SIGNUP_VERIFICATION_METHOD_DISABLED"));
    }

    private String body(String email, String tokenField) {
        return """
                {"email":"%s","password":"password123","displayName":"작가",
                 "termsAccepted":true,"privacyPolicyAcknowledged":true,"age14OrOlderConfirmed":true,
                 "termsDocumentId":%d,"privacyPolicyDocumentId":%d,%s}
                """.formatted(email, termsId, privacyId, tokenField);
    }

    private LegalDocument document(LegalDocumentType type) {
        return LegalDocument.published(type, "ko-KR", "2026-08-24", "가입 문서", "# 원문",
                "a".repeat(64), LocalDate.of(2026, 8, 24), LocalDateTime.of(2026, 8, 24, 18, 0));
    }
}
