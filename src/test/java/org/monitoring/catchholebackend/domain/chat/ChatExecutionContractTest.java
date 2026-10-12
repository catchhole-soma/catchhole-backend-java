package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;

@DisplayName("Java·Python 채팅 실행 v1 fixture")
class ChatExecutionContractTest {
    @Test
    @DisplayName("양쪽 실행 계약의 필드·null·enum을 손실 없이 읽고 쓴다")
    void roundTrip() throws Exception {
        var json = new ObjectMapper();
        var fixtures =
                json.readTree(Files.readString(Path.of("docs/contracts/chat-execution-v1.json")));
        var types =
                java.util.Map.of(
                        "dispatch",
                        Dispatch.class,
                        "context",
                        Context.class,
                        "reserve",
                        Reserve.class,
                        "usage",
                        Usage.class,
                        "call",
                        CallView.class,
                        "finish",
                        Finish.class,
                        "executionLimitFinish",
                        Finish.class);
        for (var entry : types.entrySet())
            assertThat(
                            (tools.jackson.databind.JsonNode)
                                    json.readTree(
                                            json.writeValueAsString(
                                                    json.treeToValue(
                                                            fixtures.get(entry.getKey()),
                                                            entry.getValue()))))
                    .isEqualTo(fixtures.get(entry.getKey()));
    }
}
