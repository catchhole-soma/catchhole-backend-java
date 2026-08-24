package org.monitoring.catchholebackend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.auth.service.AuthService;
import org.monitoring.catchholebackend.domain.legal.entity.LegalDocument;
import org.monitoring.catchholebackend.domain.legal.repository.LegalDocumentRepository;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberLegalRecordRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("휴대폰 인증과 회원가입 API 통합")
class PhoneVerificationControllerIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.10-alpine3.21")
    ).withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberLegalRecordRepository memberLegalRecordRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private AuthService authService;

    @Autowired
    private LegalDocumentRepository legalDocumentRepository;

    private Long termsDocumentId;
    private Long privacyPolicyDocumentId;

    @BeforeEach
    void cleanUp() {
        refreshTokenRepository.deleteAll();
        memberLegalRecordRepository.deleteAll();
        memberRepository.deleteAll();
        legalDocumentRepository.deleteAll();
        redisConnectionFactory.getConnection().serverCommands().flushDb();
        termsDocumentId = legalDocumentRepository.save(legalDocument(LegalDocumentType.TERMS_OF_SERVICE)).getId();
        privacyPolicyDocumentId = legalDocumentRepository.save(legalDocument(LegalDocumentType.PRIVACY_POLICY)).getId();
    }

    @Test
    @DisplayName("발송, 고정 OTP 확인, 1회 토큰 회원가입을 완료하고 클라이언트 전화번호는 무시한다")
    void completesPhoneVerificationAndSignup() throws Exception {
        String verificationId = requestVerification("01055556666");
        String signupToken = confirm(verificationId, "123456");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "verified@example.com",
                                  "password": "password123",
                                  "displayName": "인증 작가",
                                  "termsAccepted": true,
                                  "privacyPolicyAcknowledged": true,
                                  "age14OrOlderConfirmed": true,
                                  "termsDocumentId": %d,
                                  "privacyPolicyDocumentId": %d,
                                  "phoneVerificationToken": "%s",
                                  "phoneNumber": "01000000000"
                                }
                                """.formatted(termsDocumentId, privacyPolicyDocumentId, signupToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("refreshToken=")))
                .andExpect(jsonPath("$.data.accessToken", notNullValue()));

        Member member = memberRepository.findByEmail("verified@example.com").orElseThrow();
        assertThat(member.getPhoneNumber()).isEqualTo("01055556666");
        assertThat(member.isPhoneVerified()).isTrue();

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "reused@example.com",
                                  "password": "password123",
                                  "displayName": "재사용",
                                  "termsAccepted": true,
                                  "privacyPolicyAcknowledged": true,
                                  "age14OrOlderConfirmed": true,
                                  "termsDocumentId": %d,
                                  "privacyPolicyDocumentId": %d,
                                  "phoneVerificationToken": "%s"
                                }
                                """.formatted(termsDocumentId, privacyPolicyDocumentId, signupToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_VERIFICATION_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("회원가입 토큰이 없으면 가입할 수 없다")
    void rejectsSignupWithoutPhoneVerificationToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "missing-token@example.com",
                                  "password": "password123",
                                  "displayName": "미인증",
                                  "termsAccepted": true,
                                  "privacyPolicyAcknowledged": true,
                                  "age14OrOlderConfirmed": true,
                                  "termsDocumentId": %d,
                                  "privacyPolicyDocumentId": %d
                                }
                                """.formatted(termsDocumentId, privacyPolicyDocumentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("같은 번호의 즉시 재전송은 429와 Retry-After를 반환한다")
    void returnsRetryAfterForImmediateResend() throws Exception {
        requestVerification("01077778888");

        mockMvc.perform(post("/api/v1/auth/phone-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"01077778888\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_VERIFICATION_RATE_LIMITED"));
    }

    @Test
    @DisplayName("인증번호 5회 오입력 후에는 올바른 번호도 거부한다")
    void locksVerificationAfterFiveInvalidCodes() throws Exception {
        String verificationId = requestVerification("01088889999");
        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(post("/api/v1/auth/phone-verifications/{id}/confirm", verificationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"000000\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_VERIFICATION_CODE_INVALID"));
        }
        mockMvc.perform(post("/api/v1/auth/phone-verifications/{id}/confirm", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED"));
        mockMvc.perform(post("/api/v1/auth/phone-verifications/{id}/confirm", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED"));
    }

    @Test
    @DisplayName("같은 가입 토큰의 동시 요청에서도 회원은 하나만 생성된다")
    void concurrentSignupTokenCreatesOneMember() throws Exception {
        String signupToken = confirm(requestVerification("01099990000"), "123456");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                int requestIndex = index;
                requests.add(() -> {
                    try {
                        authService.signup(new AuthSignupRequest(
                                "concurrent-" + requestIndex + "@example.com",
                                "password123",
                                "동시 가입 " + requestIndex,
                                true,
                                true,
                                true,
                                termsDocumentId,
                                privacyPolicyDocumentId,
                                signupToken
                        ));
                        return true;
                    } catch (RuntimeException exception) {
                        return false;
                    }
                });
            }
            List<Future<Boolean>> futures = executor.invokeAll(requests);
            assertThat(futures.stream().filter(future -> get(future)).count()).isEqualTo(1);
            assertThat(memberRepository.findAll())
                    .filteredOn(member -> member.getPhoneNumber().equals("01099990000"))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private String requestVerification(String phoneNumber) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/phone-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"" + phoneNumber + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("verificationId").asText();
    }

    private String confirm(String verificationId, String code) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/phone-verifications/{id}/confirm", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return data.path("phoneVerificationToken").asText();
    }

    private boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
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
