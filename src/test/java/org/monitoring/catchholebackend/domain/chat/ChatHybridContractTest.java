package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract;
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

    @Test
    @DisplayName("인용 등록 요청과 완료 결과는 검색 인용 60개까지 허용하고 61개는 거절한다")
    void retrievalSourceCountLimit() {
        assertThat(ChatContract.MAX_RETRIEVAL_SOURCES).isEqualTo(60);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new RetrievalSourcesRequest(references(60)))).isEmpty();
            assertThat(validator.validate(new RetrievalSourcesRequest(references(61))))
                    .extracting(violation -> violation.getPropertyPath().toString()).containsExactly("sources");
            assertThat(validator.validate(result(references(60)))).isEmpty();
            assertThat(validator.validate(result(references(61))))
                    .extracting(violation -> violation.getPropertyPath().toString()).containsExactly("retrievalSources");
            // 완료 보고는 Finish로 감싸 받으므로 중첩 검증에서도 같은 상한이 적용돼야 한다.
            assertThat(validator.validate(new Finish(result(references(60)), null))).isEmpty();
            assertThat(validator.validate(new Finish(result(references(61)), null)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("result.retrievalSources");
        }
    }

    @Test
    @DisplayName("검색 인용 60개를 담은 결과도 저장 JSON을 손실 없이 왕복한다")
    void sixtyRetrievalSourcesSurvivePersistence() {
        var result = result(references(60));
        var persistence = new ChatMapper();
        var restored = persistence.result(persistence.encode(result));
        assertThat(restored).isEqualTo(result);
        assertThat(restored.retrievalSources()).hasSize(60);
    }

    private static Result result(List<RetrievalReference> references) {
        return new Result(Outcome.ANSWER, "합성 답변입니다.", List.of(), List.of(), references);
    }

    private static List<RetrievalReference> references(int count) {
        String quote = "민수는 동료를 지키기 위해 흑염🔥을 사용했다.";
        int length = quote.codePointCount(0, quote.length());
        return IntStream.range(0, count)
                .mapToObj(index -> new RetrievalReference(UUID.randomUUID(), UUID.randomUUID(), index + 1,
                        "a".repeat(64), "source-v1", quote, 0, length))
                .toList();
    }
}
