package org.monitoring.catchholebackend.domain.feedback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenExtensionRequest;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenExtensionRequestRepository;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionSource;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.feedback.entity.Feedback;
import org.monitoring.catchholebackend.domain.feedback.repository.FeedbackRepository;
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
@DisplayName("서비스 의견 API 통합")
class FeedbackControllerIntegrationTest {

    private static final String FEEDBACK_URL = "/api/v1/feedbacks";
    private static final String EXTENSION_URL = "/api/v1/ai-token-usages/extension-requests";
    private static final String ADMIN_URL = "/api/v1/admin/ai-token-extension-requests";
    private static final AtomicInteger MEMBER_SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private AiTokenExtensionRequestRepository extensionRequestRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member author;
    private String authorToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        int sequence = MEMBER_SEQUENCE.incrementAndGet();
        author = memberRepository.save(Member.register(
                "feedback-writer-" + sequence + "@example.com",
                "encoded-password",
                String.format("0109%07d", sequence),
                "피드백 작가"
        ));
        Member admin = Member.register(
                "feedback-admin-" + sequence + "@example.com",
                "encoded-password",
                String.format("0108%07d", sequence),
                "피드백 운영자"
        );
        ReflectionTestUtils.setField(admin, "role", MemberRole.ADMIN);
        admin = memberRepository.save(admin);
        authorToken = jwtTokenProvider.generateAccessToken(author);
        adminToken = jwtTokenProvider.generateAccessToken(admin);
    }

    @Test
    @DisplayName("의견은 매번 저장하고 일반 피드백 보상 요청은 처리 뒤에도 회원당 한 번만 유지한다")
    void savesEveryFeedbackAndCreatesOneLifetimeRewardRequest() throws Exception {
        JsonNode first = createFeedback("첫 번째 서비스 의견입니다. ".repeat(3), "/dashboard")
                .path("data");
        UUID rewardRequestId = UUID.fromString(first.path("rewardRequestId").asText());
        assertThat(first.path("rewardRequestOutcome").asText()).isEqualTo("CREATED");
        assertThat(first.path("rewardRequestStatus").asText()).isEqualTo("PENDING");

        approve(rewardRequestId);

        JsonNode second = createFeedback("두 번째 서비스 의견도 별도 행으로 남겨야 합니다. ".repeat(3), "/works")
                .path("data");
        assertThat(second.path("rewardRequestOutcome").asText()).isEqualTo("ALREADY_REQUESTED");
        assertThat(second.path("rewardRequestId").asText()).isEqualTo(rewardRequestId.toString());
        assertThat(second.path("rewardRequestStatus").asText()).isEqualTo("APPROVED");

        assertThat(feedbackRepository.countByMemberId(author.getId())).isEqualTo(2);
        assertThat(extensionRequestRepository.count()).isEqualTo(1);
        AiTokenExtensionRequest rewardRequest = extensionRequestRepository
                .findFirstByMemberIdAndSource(
                        author.getId(),
                        AiTokenExtensionSource.GENERAL_FEEDBACK_REWARD
                )
                .orElseThrow();
        assertThat(rewardRequest.getId()).isEqualTo(rewardRequestId);
        assertThat(feedbackRepository.findAll())
                .extracting(Feedback::getRewardRequestId)
                .containsOnly(rewardRequestId);
    }

    @Test
    @DisplayName("다른 추가 사용량 요청이 대기 중이어도 의견은 저장하고 보상 요청 생성만 보류한다")
    void savesFeedbackWhileAnotherExtensionRequestIsPending() throws Exception {
        UUID quotaRequestId = createQuotaExtensionRequest();

        JsonNode pending = createFeedback("대기 중인 요청과 별개로 저장되어야 하는 서비스 의견입니다. ".repeat(2), "/episode-upload")
                .path("data");
        assertThat(pending.path("rewardRequestOutcome").asText()).isEqualTo("PENDING_REQUEST_EXISTS");
        assertThat(pending.path("rewardRequestId").isMissingNode()
                || pending.path("rewardRequestId").isNull()).isTrue();
        assertThat(feedbackRepository.countByMemberId(author.getId())).isEqualTo(1);
        assertThat(extensionRequestRepository.count()).isEqualTo(1);

        approve(quotaRequestId);

        JsonNode created = createFeedback("기존 요청 처리 뒤 처음 생성되는 일반 피드백 보상 요청입니다. ".repeat(2), "/dashboard")
                .path("data");
        assertThat(created.path("rewardRequestOutcome").asText()).isEqualTo("CREATED");
        assertThat(feedbackRepository.countByMemberId(author.getId())).isEqualTo(2);
        assertThat(extensionRequestRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("35자 미만 의견과 쿼리가 포함된 화면 경로는 저장하지 않는다")
    void rejectsInvalidFeedbackInput() throws Exception {
        mockMvc.perform(post(FEEDBACK_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("가".repeat(34), "/dashboard")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FEEDBACK_CONTENT_INVALID"));

        mockMvc.perform(post(FEEDBACK_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("올바른 길이지만 화면 경로가 잘못된 의견입니다. ".repeat(3), "/dashboard?tab=world")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FEEDBACK_PAGE_PATH_INVALID"));

        assertThat(feedbackRepository.count()).isZero();
        assertThat(extensionRequestRepository.count()).isZero();
    }

    private JsonNode createFeedback(String content, String pagePath) throws Exception {
        MvcResult result = mockMvc.perform(post(FEEDBACK_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(content, pagePath)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID createQuotaExtensionRequest() throws Exception {
        String body = objectMapper.writeValueAsString(new ExtensionRequestBody(
                "사용량이 부족해 기존 추가 사용량 요청을 먼저 생성합니다. ".repeat(2),
                "REQUEST_BLOCKED"
        ));
        MvcResult result = mockMvc.perform(post(EXTENSION_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("QUOTA_EXHAUSTION"))
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.path("data").path("id").asText());
    }

    private void approve(UUID requestId) throws Exception {
        mockMvc.perform(post(ADMIN_URL + "/{requestId}/approve", requestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk());
    }

    private String requestBody(String content, String pagePath) throws Exception {
        return objectMapper.writeValueAsString(new FeedbackRequestBody(content, pagePath));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record FeedbackRequestBody(String content, String pagePath) {
    }

    private record ExtensionRequestBody(String feedback, String context) {
    }
}
