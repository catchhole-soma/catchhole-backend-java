package org.monitoring.catchholebackend.domain.paymentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.type.MemberRole;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
import org.monitoring.catchholebackend.global.config.paymentreview.PaymentReviewProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("결제 심사 회원 권한과 법률 문서 HTTP 계약")
class PaymentReviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired JwtTokenProvider tokens;
    @Autowired PaymentReviewProperties properties;
    Member reviewer;
    Member regular;
    String reviewerToken;
    String regularToken;

    @BeforeEach
    void setUp() {
        reviewer = members.saveAndFlush(Member.register("reviewer@example.com", "hash", null, "심사 회원"));
        regular = members.saveAndFlush(Member.register("regular@example.com", "hash", null, "일반 회원"));
        reviewerToken = tokens.generateAccessToken(reviewer);
        regularToken = tokens.generateAccessToken(regular);
        properties.setEnabled(true);
        properties.setMemberIds(Set.of(reviewer.getId()));
        properties.setStoreId("store-test-fixture");
        properties.setChannelKey("channel-key-test-fixture");
    }

    @Test
    @DisplayName("비로그인과 위조 토큰으로 접근 여부 및 원문을 읽을 수 없다")
    void anonymousAndForgedTokensAreRejected() throws Exception {
        for (String endpoint : new String[] {"access", "page"}) {
            mvc.perform(get("/api/v1/payment-reviews/" + endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/payment-reviews/" + endpoint).header("Authorization", "Bearer forged"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("일반 회원은 심사 회원 번호를 전달해도 접근 권한과 결제 자료를 얻지 못한다")
    void regularMemberCannotImpersonateReviewer() throws Exception {
        mvc.perform(get("/api/v1/payment-reviews/access").header("Authorization", "Bearer " + regularToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed").value(false));
        mvc.perform(get("/api/v1/payment-reviews/page").param("memberId", reviewer.getId().toString())
                        .header("Authorization", "Bearer " + regularToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("허용 회원만 결제창 설정과 유료 약관을 받고 캐시는 금지된다")
    void reviewerReceivesPageAndNoStore() throws Exception {
        mvc.perform(get("/api/v1/payment-reviews/access").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.allowed").value(true));
        mvc.perform(get("/api/v1/payment-reviews/page").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.storeId").value("store-test-fixture"))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.purchaseConditions", containsString("400만")))
                .andExpect(jsonPath("$.data.terms", containsString("제7조의2")))
                .andExpect(jsonPath("$.data.privacy", containsString("유료 구매")));
    }

    @Test
    @DisplayName("허용 목록 제거와 기능 비활성은 기존 토큰의 다음 조회에도 즉시 적용된다")
    void revocationAndDisableRejectExistingToken() throws Exception {
        properties.setMemberIds(Set.of());
        mvc.perform(get("/api/v1/payment-reviews/page").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
        properties.setMemberIds(Set.of(reviewer.getId()));
        properties.setEnabled(false);
        mvc.perform(get("/api/v1/payment-reviews/access").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(jsonPath("$.data.allowed").value(false));
        mvc.perform(get("/api/v1/payment-reviews/page").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자 권한만으로 심사 권한을 얻지 못한다")
    void administratorIsNotAutomaticallyAllowed() throws Exception {
        ReflectionTestUtils.setField(regular, "role", MemberRole.ADMIN);
        members.saveAndFlush(regular);
        mvc.perform(get("/api/v1/payment-reviews/page").header("Authorization", "Bearer " + tokens.generateAccessToken(regular)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("허용된 회원도 비활성화되면 기존 토큰으로 접근하지 못한다")
    void inactiveReviewerIsUnauthenticated() throws Exception {
        ReflectionTestUtils.setField(reviewer, "status", MemberStatus.SUSPENDED);
        members.saveAndFlush(reviewer);
        mvc.perform(get("/api/v1/payment-reviews/page").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("기본 설정은 비공개이며 채널 미설정은 결제창을 준비 완료로 표시하지 않는다")
    void defaultsAreClosedAndMissingChannelIsNotReady() throws Exception {
        var defaults = new PaymentReviewProperties();
        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getMemberIds()).isEmpty();
        properties.setChannelKey("");
        mvc.perform(get("/api/v1/payment-reviews/page").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ready").value(false));
    }

    @Test
    @DisplayName("실제 OpenAPI에 인증과 403 및 응답 원문을 문서화하고 SDK 생성에 사용한다")
    void openApiContract() throws Exception {
        String document = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(document);
        var operation = root.path("paths").path("/api/v1/payment-reviews/page").path("get");
        assertThat(operation.path("operationId").asText()).isEqualTo("getPaymentReviewPage");
        assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(operation.path("responses").has("403")).isTrue();
        String output = System.getenv("PAYMENT_REVIEW_OPENAPI_OUTPUT");
        if (output != null) Files.writeString(Path.of(output), document);
    }
}
