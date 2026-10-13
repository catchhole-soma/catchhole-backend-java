package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.mapper.ChatMapper;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Java·Python 검색 인용 v1 fixture")
class ChatHybridContractTest {
    @Test
    @DisplayName("검색 인용 요청·응답·최종 결과가 같은 Unicode 위치와 원문 식별자를 보존한다")
    void roundTrip() throws Exception {
        var json = new ObjectMapper();
        var fixtures = json.readTree(Files.readString(Path.of("docs/contracts/chat-hybrid-v1.json")));
        for (var entry : Map.of("retrievalRequest", RetrievalSourcesRequest.class,
                "retrievalResponse", RetrievalSources.class, "result", Result.class).entrySet()) {
            var value = json.treeToValue(fixtures.get(entry.getKey()), entry.getValue());
            assertThat(json.readTree(json.writeValueAsString(value))).isEqualTo(fixtures.get(entry.getKey()));
        }
        var result = json.treeToValue(fixtures.get("result"), Result.class);
        var quote = result.retrievalSources().getFirst();
        assertThat(quote.endOffset() - quote.startOffset()).isEqualTo(quote.quote().codePointCount(0, quote.quote().length()));
        assertThat(quote.quote().length()).isGreaterThan(quote.endOffset() - quote.startOffset());
        var persistence = new ChatMapper();
        assertThat(persistence.result(persistence.encode(result))).isEqualTo(result);
    }
}
