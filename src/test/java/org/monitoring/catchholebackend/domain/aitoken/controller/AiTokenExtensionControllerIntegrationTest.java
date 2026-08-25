package org.monitoring.catchholebackend.domain.aitoken.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenExtensionRequest;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenAccountRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenExtensionRequestRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenGrantRepository;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenGrantType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.type.MemberRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "ai-token.default-grant=321")
@Transactional
@DisplayName("AI 토큰 추가 사용량 요청 API 통합")
class AiTokenExtensionControllerIntegrationTest {

    private static final String USER_REQUEST_URL = "/api/v1/ai-token-usages/extension-requests";
    private static final String ADMIN_REQUEST_URL = "/api/v1/admin/ai-token-extension-requests";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AiTokenAccountRepository accountRepository;

    @Autowired
    private AiTokenExtensionRequestRepository extensionRequestRepository;

    @Autowired
    private AiTokenGrantRepository grantRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member author;
    private Member admin;
    private String authorToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        author = memberRepository.save(Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        ));
        admin = Member.register(
                "admin@example.com",
                "encoded-password",
                "01087654321",
                "운영자"
        );
        ReflectionTestUtils.setField(admin, "role", MemberRole.ADMIN);
        admin = memberRepository.save(admin);
        authorToken = jwtTokenProvider.generateAccessToken(author);
        adminToken = jwtTokenProvider.generateAccessToken(admin);
    }

    @Test
    @DisplayName("앞뒤 공백을 제외한 35자 피드백을 저장하고 같은 대기 요청을 중복 생성하지 않는다")
    void createsOnePendingRequestWithNormalizedFeedback() throws Exception {
        String normalizedFeedback = "가".repeat(35);
        UUID firstRequestId = createRequest("  " + normalizedFeedback + "  ", authorToken);

        UUID duplicateRequestId = createRequest("나".repeat(35), authorToken);

        assertThat(duplicateRequestId).isEqualTo(firstRequestId);
        assertThat(extensionRequestRepository.count()).isEqualTo(1);
        AiTokenExtensionRequest saved = extensionRequestRepository.findById(firstRequestId).orElseThrow();
        assertThat(saved.getFeedback()).isEqualTo(normalizedFeedback);
        assertThat(saved.getStatus()).isEqualTo(AiTokenExtensionStatus.PENDING);

        mockMvc.perform(get(USER_REQUEST_URL + "/me/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending").value(true))
                .andExpect(jsonPath("$.data.request.id").value(firstRequestId.toString()));
    }

    @Test
    @DisplayName("34자 피드백은 저장하지 않는다")
    void rejectsFeedbackShorterThanThirtyFiveCharacters() throws Exception {
        mockMvc.perform(post(USER_REQUEST_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("가".repeat(34))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AI_TOKEN_EXTENSION_FEEDBACK_INVALID"));

        assertThat(extensionRequestRepository.count()).isZero();
    }

    @Test
    @DisplayName("소진 계정 승인은 현재 제공량을 100% 복구하고 반복 승인해도 한 번만 지급한다")
    void approvalUsesDefaultGrantAndIsIdempotent() throws Exception {
        UUID requestId = createRequest("추가 분석이 필요한 이유를 충분히 설명하는 피드백입니다.".repeat(3), authorToken);
        AiTokenAccount account = accountRepository.findByMemberId(author.getId()).orElseThrow();
        account.reserve(321);
        account.settle(321, 321);

        approve(requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.grantedAmount").value(321))
                .andExpect(jsonPath("$.data.grantedTokens").value(642))
                .andExpect(jsonPath("$.data.usedTokens").value(321))
                .andExpect(jsonPath("$.data.remainingTokens").value(321));

        mockMvc.perform(get("/api/v1/ai-token-usages/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grantedTokens").value(642))
                .andExpect(jsonPath("$.data.usedTokens").value(321))
                .andExpect(jsonPath("$.data.remainingTokens").value(321))
                .andExpect(jsonPath("$.data.remainingPercent").value(100.0));

        approve(requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grantedTokens").value(642))
                .andExpect(jsonPath("$.data.usedTokens").value(321));

        assertThat(grantRepository.countByMemberIdAndGrantType(author.getId(), AiTokenGrantType.DEFAULT))
                .isEqualTo(1);
        assertThat(grantRepository.countByMemberIdAndGrantType(author.getId(), AiTokenGrantType.MANUAL))
                .isEqualTo(1);
        assertThat(accountRepository.findByMemberId(author.getId()).orElseThrow().getGrantedTokens())
                .isEqualTo(642);
    }

    @Test
    @DisplayName("일반 회원은 운영자 목록과 승인 API를 호출할 수 없다")
    void authorCannotUseAdminApis() throws Exception {
        UUID requestId = createRequest("추가 사용량 요청 권한 검증을 위한 충분한 길이의 피드백입니다.".repeat(2), authorToken);

        mockMvc.perform(get(ADMIN_REQUEST_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(post(ADMIN_REQUEST_URL + "/{requestId}/approve", requestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));

        assertThat(grantRepository.countByMemberIdAndGrantType(author.getId(), AiTokenGrantType.MANUAL))
                .isZero();
    }

    @Test
    @DisplayName("운영자는 요청을 조회하고 거절할 수 있으며 거절 뒤 승인할 수 없다")
    void adminCanListAndRejectRequest() throws Exception {
        UUID requestId = createRequest("운영자 거절 흐름을 확인하기 위한 충분한 길이의 피드백입니다.".repeat(2), authorToken);

        mockMvc.perform(get(ADMIN_REQUEST_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(requestId.toString()))
                .andExpect(jsonPath("$.data.content[0].memberEmail").value("writer@example.com"))
                .andExpect(jsonPath("$.data.content[0].grantedTokens").value(321));

        mockMvc.perform(post(ADMIN_REQUEST_URL + "/{requestId}/reject", requestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"현재 베타 운영 기준에 따라 이번 요청은 지급하지 않습니다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.grantedAmount").doesNotExist());

        approve(requestId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AI_TOKEN_EXTENSION_REVIEW_CONFLICT"));
        assertThat(grantRepository.countByMemberIdAndGrantType(author.getId(), AiTokenGrantType.MANUAL))
                .isZero();
    }

    private UUID createRequest(String feedback, String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(post(USER_REQUEST_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(feedback)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.path("data").path("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions approve(UUID requestId) throws Exception {
        return mockMvc.perform(post(ADMIN_REQUEST_URL + "/{requestId}/approve", requestId)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)));
    }

    private String requestBody(String feedback) throws Exception {
        return objectMapper.writeValueAsString(new RequestBody(feedback, "REQUEST_BLOCKED"));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record RequestBody(String feedback, String context) {
    }
}
