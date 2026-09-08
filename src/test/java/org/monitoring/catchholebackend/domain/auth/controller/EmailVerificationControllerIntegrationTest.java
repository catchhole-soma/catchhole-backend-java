package org.monitoring.catchholebackend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.service.AuthService;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.repository.LegalDocumentRepository;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberLegalRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "auth.signup-verification.verification-method=EMAIL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("이메일 인증 API 통합 테스트")
class EmailVerificationControllerIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.10-alpine3.21")
    ).withExposedPorts(6379);

    @Autowired private MockMvc mockMvc;
    @Autowired private RedisConnectionFactory redisConnectionFactory;
    @Autowired private AuthService authService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private MemberLegalRecordRepository memberLegalRecordRepository;
    @Autowired private LegalDocumentRepository legalDocumentRepository;
    private Long termsDocumentId;
    private Long privacyPolicyDocumentId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetVerificationState() {
        refreshTokenRepository.deleteAll();
        memberLegalRecordRepository.deleteAll();
        memberRepository.deleteAll();
        legalDocumentRepository.deleteAll();
        termsDocumentId = legalDocumentRepository.save(legalDocument(LegalDocumentType.TERMS_OF_SERVICE)).getId();
        privacyPolicyDocumentId = legalDocumentRepository.save(legalDocument(LegalDocumentType.PRIVACY_POLICY)).getId();
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("익명 이메일 발송과 OTP 확인은 정책 시간과 이메일 가입 토큰을 반환한다")
    void sendsAndConfirmsEmail() throws Exception {
        String verificationId = requestVerification("email-api@example.com");
        mockMvc.perform(post("/api/v1/auth/email-verifications/{id}/confirm", verificationId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerificationToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));
    }

    @Test
    @DisplayName("동일 이메일의 즉시 재발송은 429와 Retry-After를 반환한다")
    void immediateResendReturnsRetryAfter() throws Exception {
        requestVerification("resend-api@example.com");
        mockMvc.perform(post("/api/v1/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"resend-api@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_VERIFICATION_RATE_LIMITED"));
    }

    @Test
    @DisplayName("유효하지 않은 이메일과 인증번호 형식을 거부한다")
    void rejectsMalformedRequests() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"invalid-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/auth/email-verifications/id/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("존재하지 않는 인증 흐름은 410 만료 응답을 반환한다")
    void unknownFlowReturnsExpired() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verifications/unknown/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_VERIFICATION_EXPIRED"));
    }

    @Test
    @DisplayName("다섯 번 오입력한 흐름은 올바른 인증번호도 거부한다")
    void locksAfterFiveWrongCodes() throws Exception {
        String verificationId = requestVerification("attempts-api@example.com");
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(post("/api/v1/auth/email-verifications/{id}/confirm", verificationId)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_VERIFICATION_CODE_INVALID"));
        }
        mockMvc.perform(post("/api/v1/auth/email-verifications/{id}/confirm", verificationId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED"));
        mockMvc.perform(post("/api/v1/auth/email-verifications/{id}/confirm", verificationId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED"));
    }

    @Test
    @DisplayName("메일 발송부터 가입과 내 정보 조회까지 전화번호 없이 완료한다")
    void completesEmailSignupAndMe() throws Exception {
        String email = "complete-api@example.com";
        String signupToken = confirm(requestVerification(email));
        String signupResponse = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupRequest(email, signupToken))))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(signupResponse).path("data").path("accessToken").asText();
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.phoneVerified").value(false));
        Member member = memberRepository.findByEmail(email).orElseThrow();
        assertThat(member.getPhoneNumber()).isNull();
        assertThat(member.isEmailVerified()).isTrue();
        assertThat(memberLegalRecordRepository.count()).isEqualTo(2);
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이메일 가입 토큰을 동시에 사용해도 회원과 동의 기록은 한 번만 생성된다")
    void concurrentSignupCreatesOneMember() throws Exception {
        String email = "concurrent-api@example.com";
        String signupToken = confirm(requestVerification(email));
        Callable<Boolean> signup = () -> {
            try {
                authService.signup(signupRequest(email, signupToken));
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(signup, signup));
            int successes = 0;
            for (var future : futures) if (future.get()) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(memberLegalRecordRepository.count()).isEqualTo(2);
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
    }

    private AuthSignupRequest signupRequest(String email, String token) {
        return new AuthSignupRequest(email, "password123", "이메일 작가", true, true, true,
                termsDocumentId, privacyPolicyDocumentId, null, token);
    }

    private String confirm(String verificationId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/email-verifications/{id}/confirm", verificationId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("emailVerificationToken").asText();
    }

    private LegalDocument legalDocument(LegalDocumentType type) {
        return LegalDocument.published(type, "ko-KR", "2026-08-24",
                type == LegalDocumentType.TERMS_OF_SERVICE ? "CatchHole 이용약관" : "CatchHole 개인정보처리방침",
                "# 원문", type == LegalDocumentType.TERMS_OF_SERVICE ? "a".repeat(64) : "b".repeat(64),
                java.time.LocalDate.of(2026, 8, 24), java.time.LocalDateTime.of(2026, 8, 24, 18, 0));
    }

    private String requestVerification(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("verificationId").asText();
    }
}
