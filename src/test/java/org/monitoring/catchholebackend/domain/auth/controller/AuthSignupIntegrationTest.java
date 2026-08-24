package org.monitoring.catchholebackend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.service.PhoneVerificationService;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.repository.LegalDocumentRepository;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberLegalRecord;
import org.monitoring.catchholebackend.domain.member.repository.MemberLegalRecordRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
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

    @Autowired
    private LegalDocumentRepository legalDocumentRepository;

    private Long termsDocumentId;
    private Long privacyPolicyDocumentId;

    @BeforeEach
    void setUpLegalDocuments() {
        termsDocumentId = legalDocumentRepository.save(legalDocument(LegalDocumentType.TERMS_OF_SERVICE)).getId();
        privacyPolicyDocumentId = legalDocumentRepository.save(legalDocument(LegalDocumentType.PRIVACY_POLICY)).getId();
    }

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
                                  "age14OrOlderConfirmed": true,
                                  "termsDocumentId": %d,
                                  "privacyPolicyDocumentId": %d,
                                  "phoneVerificationToken": "phone-verification-token"
                                }
                                """.formatted(termsDocumentId, privacyPolicyDocumentId)))
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
        assertThat(savedMember.getAgeRequirementConfirmedAt()).isNotNull();
        assertThat(passwordEncoder.matches("password123", savedMember.getPasswordHash())).isTrue();
        java.util.List<MemberLegalRecord> legalRecords =
                memberLegalRecordRepository.findAllByMemberIdOrderByRecordedAtAsc(savedMember.getId());
        assertThat(legalRecords)
                .extracting(
                        record -> record.getDocumentType(),
                        record -> record.getActionType(),
                        record -> record.getDocumentVersion()
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.TERMS_OF_SERVICE,
                                LegalRecordAction.AGREED,
                                "2026-08-24"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                LegalDocumentType.PRIVACY_POLICY,
                                LegalRecordAction.ACKNOWLEDGED,
                                "2026-08-24"
                        )
                );
        assertThat(legalRecords)
                .extracting(record -> record.getLegalDocument().getId())
                .containsExactly(termsDocumentId, privacyPolicyDocumentId);
        assertThat(legalRecords)
                .allSatisfy(record -> assertThat(record.getRecordedAt())
                        .isEqualTo(savedMember.getAgeRequirementConfirmedAt()));
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
                                  "age14OrOlderConfirmed": true,
                                  "termsDocumentId": %d,
                                  "privacyPolicyDocumentId": %d,
                                  "phoneVerificationToken": "phone-verification-token"
                                }
                                """.formatted(termsDocumentId, privacyPolicyDocumentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "termsAccepted",
                                "privacyPolicyAcknowledged"
                        )));

        assertThat(memberRepository.findByEmail("unchecked-writer@example.com")).isEmpty();
    }

    @Test
    @DisplayName("회원가입은 만 14세 이상 확인을 요구한다")
    void signupRequiresAgeRequirementConfirmation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "underage-writer@example.com",
                                  "password": "password123",
                                  "displayName": "연령 미확인 작가",
                                  "termsAccepted": true,
                                  "privacyPolicyAcknowledged": true,
                                  "age14OrOlderConfirmed": false,
                                  "termsDocumentId": %d,
                                  "privacyPolicyDocumentId": %d,
                                  "phoneVerificationToken": "phone-verification-token"
                                }
                                """.formatted(termsDocumentId, privacyPolicyDocumentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("age14OrOlderConfirmed"));

        assertThat(memberRepository.findByEmail("underage-writer@example.com")).isEmpty();
    }

    @Test
    @DisplayName("회원가입은 화면에 표시한 문서가 현재 문서가 아니면 회원을 만들지 않는다")
    void signupRejectsRetiredOrStaleLegalDocumentIds() throws Exception {
        org.mockito.Mockito.when(
                        phoneVerificationService.getVerifiedPhoneNumberBySignupToken("phone-verification-token")
                )
                .thenReturn("01055557777");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "stale-writer@example.com",
                                  "password": "password123",
                                  "displayName": "이전 문서 작가",
                                  "termsAccepted": true,
                                  "privacyPolicyAcknowledged": true,
                                  "age14OrOlderConfirmed": true,
                                  "termsDocumentId": 999999,
                                  "privacyPolicyDocumentId": %d,
                                  "phoneVerificationToken": "phone-verification-token"
                                }
                                """.formatted(privacyPolicyDocumentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LEGAL_DOCUMENT_NOT_CURRENT"))
                .andExpect(jsonPath("$.error.context.currentTermsDocumentId").value(termsDocumentId))
                .andExpect(jsonPath("$.error.context.currentPrivacyPolicyDocumentId").value(privacyPolicyDocumentId));

        assertThat(memberRepository.findByEmail("stale-writer@example.com")).isEmpty();
    }

    private LegalDocument legalDocument(LegalDocumentType type) {
        return LegalDocument.published(
                type,
                "ko-KR",
                "2026-08-24",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "CatchHole 이용약관" : "CatchHole 개인정보처리방침",
                "# 원문",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "a".repeat(64) : "b".repeat(64),
                java.time.LocalDate.of(2026, 8, 24),
                java.time.LocalDateTime.of(2026, 8, 24, 18, 0)
        );
    }
}
