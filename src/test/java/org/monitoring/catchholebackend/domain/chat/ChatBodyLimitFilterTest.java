package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.chat.controller.ChatBodyLimitFilter;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.mapper.ChatMapper;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Reference;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("채팅 경로 요청 본문 크기 제한")
class ChatBodyLimitFilterTest {
    static final String RUN = "/api/internal/v1/chat-runs/" + UUID.randomUUID();

    record Sent(int status, String response, byte[] forwarded) {}

    @ParameterizedTest
    @ValueSource(ints = {131_072, 1_048_576})
    @DisplayName("설정한 상한과 같은 크기의 본문은 그대로 넘기고 1바이트라도 넘으면 413으로 끊는다")
    void enforcesConfiguredLimit(int limit) throws Exception {
        byte[] exact = body(limit);
        assertThat(send(limit, RUN + "/finish", exact).forwarded()).isEqualTo(exact);

        var rejected = send(limit, RUN + "/finish", body(limit + 1));
        assertThat(rejected.status()).isEqualTo(413);
        assertThat(rejected.response()).contains("REQUEST_BODY_TOO_LARGE");
        assertThat(rejected.forwarded()).isNull();
    }

    @Test
    @DisplayName("한글 4000자 인용 60개의 등록·완료 요청은 기본 128KiB를 넘고 1MiB 상한 안에 들어간다")
    void sixtyFullSizeCitationsNeedRaisedLimit() throws Exception {
        var json = new ChatMapper();
        String quote = "가".repeat(4000);
        var citations = IntStream.range(0, 60)
                .mapToObj(index -> new RetrievalReference(UUID.randomUUID(), UUID.randomUUID(), 1000,
                        "a".repeat(64), "v".repeat(100), quote, 1_000_000, 1_004_000))
                .toList();
        List<Reference> directSources = IntStream.range(0, 50)
                .mapToObj(index -> new Reference(SettingTool.CHARACTER_EVIDENCE, UUID.randomUUID()))
                .toList();
        byte[] register = json.encode(new RetrievalSourcesRequest(citations)).getBytes(StandardCharsets.UTF_8);
        byte[] finish = json.encode(new Finish(new Result(Outcome.ANSWER, "가".repeat(16000), directSources,
                Collections.nCopies(20, "가".repeat(200)), citations), null)).getBytes(StandardCharsets.UTF_8);

        assertThat(register.length).isGreaterThan(131_072).isLessThan(1_048_576);
        assertThat(finish.length).isGreaterThan(register.length).isLessThan(1_048_576);
        assertThat(send(131_072, RUN + "/retrieval-sources", register).status()).isEqualTo(413);
        assertThat(send(1_048_576, RUN + "/retrieval-sources", register).forwarded()).isEqualTo(register);
        assertThat(send(1_048_576, RUN + "/finish", finish).forwarded()).isEqualTo(finish);
    }

    @Test
    @DisplayName("채팅 밖 경로의 본문은 이 필터가 제한하지 않는다")
    void ignoresOtherPaths() throws Exception {
        byte[] large = body(2_000);
        assertThat(send(512, "/api/v1/works/" + UUID.randomUUID() + "/episodes", large).forwarded()).isEqualTo(large);
    }

    private static byte[] body(int size) {
        byte[] body = new byte[size];
        java.util.Arrays.fill(body, (byte) 'a');
        return body;
    }

    private static Sent send(int limit, String path, byte[] body) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setContent(body);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new ChatBodyLimitFilter(new ChatProperties(false, false, null, null,
                limit, 0, 0, 0, 0, 0, 0, 0, null, null, null)).doFilter(request, response, chain);
        var forwarded = chain.getRequest();
        return new Sent(response.getStatus(), response.getContentAsString(),
                forwarded == null ? null : forwarded.getInputStream().readAllBytes());
    }
}
