package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnalysisStateJournalTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisStateJournal journal = new AnalysisStateJournal();

    @Test
    @DisplayName("같은 변경을 재시도해도 한 번만 적용하고 입력과 당시 값을 변경하지 않는다")
    void replaysImmutableChangesOnce() throws Exception {
        ObjectNode base = journal.emptyState();
        ObjectNode target = mapper.createObjectNode();
        target.putObject("slots");
        base.withObject("characters").set("character:one", target);
        ObjectNode value = mapper.createObjectNode().put("value", "부상");
        AnalysisStateChange add = change("one", List.of("characters", "character:one", "slots", "STATUS:injury"), value, false, "ADD");
        value.put("value", "수정된 현재 후보");
        JsonNode first = journal.apply(base, List.of(add, add));
        assertThat(first.at("/characters/character:one/slots/STATUS:injury/value").asText()).isEqualTo("부상");
        assertThat(base.at("/characters/character:one/slots").isEmpty()).isTrue();
        ((ObjectNode) add.value()).put("value", "호출자가 바꾼 값");
        assertThat(journal.apply(base, journal.fromJson(journal.toJson(List.of(add, add))))).isEqualTo(first);
        assertThat(journal.hash(journal.apply(base, List.of(add)))).isEqualTo(journal.hash(first));
    }

    @Test
    @DisplayName("회차 내부 순서를 보존해 변경·병합·제거하고 오래된 유효 상태는 유지한다")
    void preservesOrderAndActiveState() throws Exception {
        JsonNode base = mapper.readTree("""
                {"characters":{"hero":{"slots":{"old-skill":{"value":"검술"}}}},"worldSettings":{},"references":{}}
                """);
        List<String> injury = List.of("characters", "hero", "slots", "injury");
        List<AnalysisStateChange> changes = List.of(
                change("add", injury, mapper.readTree("{\"value\":\"부상\"}"), false, "ADD"),
                change("update", injury, mapper.readTree("{\"value\":\"회복 중\"}"), false, "UPDATE"),
                change("merge", injury, mapper.readTree("{\"value\":\"회복 중, 보행 가능\"}"), false, "MERGE"),
                change("remove", injury, null, true, "REMOVE"),
                change("history", List.of("references", "old-event"), mapper.readTree("{\"reason\":\"과거 사건\"}"), false, "HISTORY_ONLY"),
                change("exclude", List.of("references", "repeat"), mapper.readTree("{\"reason\":\"반복\"}"), false, "EXCLUDE"));
        JsonNode result = journal.apply(base, changes);
        assertThat(result.at("/characters/hero/slots/injury").isMissingNode()).isTrue();
        assertThat(result.at("/characters/hero/slots/old-skill/value").asText()).isEqualTo("검술");
        assertThat(result.path("references").size()).isEqualTo(2);
        List<AnalysisStateChange> reversed = new ArrayList<>(changes.reversed());
        assertThatThrownBy(() -> journal.apply(base, reversed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("같은 event ID의 다른 값과 해소되지 않은 부모·제거 경로는 거절한다")
    void rejectsChangedDuplicateAndMissingTargets() throws Exception {
        JsonNode base = journal.emptyState();
        var first = change("id", List.of("references", "one"), mapper.readTree("{\"value\":1}"), false, "REVIEW_REQUIRED");
        var other = change("id", List.of("references", "one"), mapper.readTree("{\"value\":2}"), false, "REVIEW_REQUIRED");
        assertThatThrownBy(() -> journal.apply(base, List.of(first, other))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.apply(base, List.of(change("missing", List.of("characters", "unknown", "slots", "key"), mapper.createObjectNode(), false, "UPDATE"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.apply(base, List.of(change("missing", List.of("characters", "unknown"), null, true, "REMOVE"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("객체 순서와 숫자 표기는 hash를 바꾸지 않지만 배열 순서는 구분한다")
    void canonicalHashIgnoresOnlyRepresentationalDifferences() throws Exception {
        JsonNode first = mapper.readTree("{\"이름\":\"주인공\",\"값\":1.00,\"배열\":[1,2]}");
        JsonNode second = mapper.readTree("{\"배열\":[1,2],\"값\":1,\"이름\":\"주인공\"}");
        assertThat(journal.hash(first)).isEqualTo(journal.hash(second));
        assertThat(journal.canonicalJson(first)).contains("\"값\":1,");
        assertThat(journal.hash(first)).isNotEqualTo(journal.hash(mapper.readTree("{\"배열\":[2,1],\"값\":1,\"이름\":\"주인공\"}")));
    }

    @Test
    @DisplayName("Python 평가기와 공유하는 v1 기록의 상태와 SHA-256을 재현한다")
    void replaysSharedContractFixture() throws Exception {
        JsonNode fixture = mapper.readTree(getClass().getResourceAsStream("/analysis/ordered-journal-v1.json"));
        JsonNode actual = journal.apply(fixture.path("baseState"), journal.fromJson(fixture.path("changes")));
        assertThat(actual).isEqualTo(fixture.path("expectedState"));
        assertThat(journal.canonicalJson(actual)).isEqualTo(fixture.path("expectedCanonical").asText());
        assertThat(journal.hash(actual)).isEqualTo(fixture.path("expectedHash").asText());
    }

    private AnalysisStateChange change(String id, List<String> path, JsonNode value, boolean remove, String operation) {
        return new AnalysisStateChange(id, path, value, remove, operation,
                List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    @Test
    @DisplayName("원문 파기는 복사된 근거만 지우며 설정값과 당시 hash를 보존한다")
    void purgesCopiedEvidenceWithoutChangingDomainValues() throws Exception {
        JsonNode original = mapper.readTree("""
                {"outputStateHash":"old-hash","changes":[
                  {"path":["references","one"],"value":{"reason":"원문 인용","factValue":"설정값"}},
                  {"path":["worldSettings","one"],"value":{"identityEvidence":["원문"],"propertiesJson":{"reason":"세계관 속성값"}}},
                  {"path":["characters","one","slots","key"],"value":{"valueJson":{"reason":"설정값"}}}
                ]}
                """);
        JsonNode redacted = journal.purgeSourceEvidence(original);
        assertThat(redacted.at("/changes/0/value/reason").isMissingNode()).isTrue();
        assertThat(redacted.at("/changes/1/value/identityEvidence").isMissingNode()).isTrue();
        assertThat(redacted.at("/changes/0/value/factValue").asText()).isEqualTo("설정값");
        assertThat(redacted.at("/changes/1/value/propertiesJson/reason").asText()).isEqualTo("세계관 속성값");
        assertThat(redacted.at("/changes/2/value/valueJson/reason").asText()).isEqualTo("설정값");
        assertThat(redacted.path("outputStateHash")).isEqualTo(original.path("outputStateHash"));
        assertThat(redacted.path("sourceEvidencePurged").asBoolean()).isTrue();
        assertThat(original.at("/changes/0/value/reason").asText()).isEqualTo("원문 인용");
    }
}
