package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("누적 분석 변경 기록의 불변 복원과 저장 호환성")
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
    @DisplayName("버퍼보다 큰 인용과 유니코드·제어 문자도 기존 canonical UTF-8 hash를 보존한다")
    void streamsLargeEvidenceWithoutChangingCanonicalBytesOrInput() throws Exception {
        String quote = "가😀".repeat(10_000) + "\n\t\"\\";
        ObjectNode input = mapper.createObjectNode();
        input.put("z", quote);
        input.set("a", mapper.readTree("[null,true,false,-0.0,1e-12,1.2300]"));
        JsonNode before = input.deepCopy();
        String canonical = "{\"a\":[null,true,false,0,0.000000000001,1.23],\"z\":\""
                + "가😀".repeat(10_000) + "\\n\\t\\\"\\\\\"}";
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(journal.canonicalJson(input)).isEqualTo(canonical);
        assertThat(journal.hash(input)).isEqualTo(expectedHash);
        assertThat(input).isEqualTo(before);
        input.put("z", quote + "추가 근거");
        assertThat(journal.hash(input)).isNotEqualTo(expectedHash);
    }

    @Test
    @DisplayName("스트리밍 hash도 비유한 숫자와 지원하지 않는 JSON 노드를 거절한다")
    void streamingHashKeepsInvalidValueRejection() {
        for (JsonNode invalid : List.of(mapper.getNodeFactory().numberNode(Double.NaN),
                mapper.getNodeFactory().numberNode(Double.POSITIVE_INFINITY),
                mapper.getNodeFactory().binaryNode(new byte[]{1}), mapper.missingNode())) {
            assertThatThrownBy(() -> journal.hash(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> journal.canonicalJson(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("Python 평가기와 공유하는 v1·v2 기록의 상태와 SHA-256을 재현한다")
    void replaysSharedContractFixture(int version) throws Exception {
        JsonNode fixture = mapper.readTree(getClass().getResourceAsStream("/analysis/ordered-journal-v" + version + ".json"));
        JsonNode actual = journal.apply(fixture.path("baseState"), journal.fromJson(fixture.path("changes")));
        assertThat(actual).isEqualTo(fixture.path("expectedState"));
        assertThat(journal.canonicalJson(actual)).isEqualTo(fixture.path("expectedCanonical").asText());
        assertThat(journal.hash(actual)).isEqualTo(fixture.path("expectedHash").asText());
    }

    @Test
    @DisplayName("과거 전체 배열과 새 증분 이력이 섞여도 순서·중복 사실·근거·최종 hash가 같다")
    void mixesLegacyHistoryReplacementAndAppendWithoutDroppingEvidence() throws Exception {
        JsonNode base = mapper.readTree("""
                {"characters":{"hero":{"historyFacts":[{"factValue":"검술 1단","evidenceSpans":[{"quote":"첫 근거"}]}]}},
                 "worldSettings":{},"references":{}}
                """);
        var first = base.at("/characters/hero/historyFacts").deepCopy();
        var second = mapper.readTree("""
                [{"factValue":"검술 2단","evidenceSpans":[{"quote":"승급 근거 😀"}]}]
                """);
        var third = mapper.readTree("""
                [{"factValue":"검술 3단","sourceEpisodeNo":3}]
                """);
        var wholeSecond = ((com.fasterxml.jackson.databind.node.ArrayNode) first).deepCopy().addAll((com.fasterxml.jackson.databind.node.ArrayNode) second);
        var legacy = change("legacy", List.of("characters", "hero", "historyFacts"), wholeSecond, false, "UPDATE");
        var append = AnalysisStateChange.appendHistory("append", "hero", third, "HISTORY_ONLY", List.of());
        // 동일 내용의 서로 다른 사건은 별도 이력으로 남고 동일 event 재전달만 제거한다.
        var repeatedFact = AnalysisStateChange.appendHistory("another-event", "hero", third, "HISTORY_ONLY", List.of());
        JsonNode expected = base.deepCopy();
        ((ObjectNode) expected.path("characters").path("hero")).set("historyFacts", wholeSecond.deepCopy()
                .addAll(((com.fasterxml.jackson.databind.node.ArrayNode) third).deepCopy())
                .addAll(((com.fasterxml.jackson.databind.node.ArrayNode) third).deepCopy()));
        var mixed = List.of(legacy, append, append, repeatedFact);
        JsonNode actual = journal.apply(base, journal.fromJson(journal.toJson(mixed)));
        assertThat(actual).isEqualTo(expected);
        assertThat(journal.hash(actual)).isEqualTo(journal.hash(expected));
        assertThat(journal.toJson(mixed).get(0).has("valueMode")).isFalse();
        assertThat(journal.toJson(mixed).get(1).path("valueMode").asText()).isEqualTo("APPEND_HISTORY");
        assertThat(append.value()).hasSize(1);
        ((ObjectNode) third.get(0)).put("factValue", "나중에 바뀐 값");
        assertThat(journal.apply(base, mixed)).isEqualTo(expected);
        assertThat(base.at("/characters/hero/historyFacts")).hasSize(1);

        var restore = change("restore", List.of("characters", "hero", "historyFacts"), first, false, "REVIEW_REQUIRED");
        assertThat(journal.apply(base, List.of(legacy, append, restore))).isEqualTo(base);
    }

    @Test
    @DisplayName("이력 추가는 캐릭터 이력 배열에만 허용하며 다른 모드의 동일 event를 충돌로 처리한다")
    void rejectsInvalidAppendModesAndConflictingDuplicateEvents() throws Exception {
        JsonNode empty = mapper.createArrayNode();
        JsonNode base = mapper.readTree("""
                {"characters":{"hero":{"historyFacts":[]}},"worldSettings":{},"references":{}}
                """);
        var append = AnalysisStateChange.appendHistory("event", "hero", empty, "UPDATE", List.of());
        var replace = new AnalysisStateChange("event", append.path(), empty, false, "UPDATE", List.of());
        assertThatThrownBy(() -> journal.apply(base, List.of(append, replace))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisStateChange.appendHistory("bad", "hero", mapper.createObjectNode(), "UPDATE", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisStateChange("bad", List.of("worldSettings", "hero", "historyFacts"), empty,
                false, "UPDATE", List.of(), AnalysisStateChange.ValueMode.APPEND_HISTORY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisStateChange("bad", append.path(), null,
                true, "REMOVE", List.of(), AnalysisStateChange.ValueMode.APPEND_HISTORY)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode encoded = (ObjectNode) journal.toJson(List.of(append)).get(0);
        encoded.put("valueMode", "UNKNOWN");
        assertThatThrownBy(() -> journal.fromJson(mapper.createArrayNode().add(encoded))).isInstanceOf(IllegalArgumentException.class);
        encoded.putNull("valueMode");
        assertThatThrownBy(() -> journal.fromJson(mapper.createArrayNode().add(encoded))).isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) base.path("characters").path("hero")).put("historyFacts", "잘못된 기존값");
        assertThatThrownBy(() -> journal.apply(base, List.of(append))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("증분 이력의 원문 파기도 인용만 제거하고 설정·출처·저장 모드는 보존한다")
    void purgesAppendedHistoryEvidenceWithoutLosingValuesOrStorageMode() throws Exception {
        var additions = mapper.readTree("""
                [{"factValue":"회복","sourceCandidateId":"candidate-1","supportingSource":{
                    "sourceEpisodeNo":3,"evidenceSpans":[{"quote":"비공개 원문","startOffset":0,"endOffset":6}]}}]
                """);
        var change = AnalysisStateChange.appendHistory("history", "hero", additions, "REMOVE", List.of());
        ObjectNode record = mapper.createObjectNode().put("formatVersion", 2).put("outputStateHash", "audit-hash");
        record.set("changes", journal.toJson(List.of(change)));
        JsonNode purged = journal.purgeSourceEvidence(record);
        assertThat(purged.toString()).doesNotContain("비공개 원문");
        assertThat(purged.at("/changes/0/value/0/factValue").asText()).isEqualTo("회복");
        assertThat(purged.at("/changes/0/value/0/sourceCandidateId").asText()).isEqualTo("candidate-1");
        assertThat(purged.at("/changes/0/valueMode").asText()).isEqualTo("APPEND_HISTORY");
        assertThat(purged.path("formatVersion").asInt()).isEqualTo(2);
        assertThat(purged.path("outputStateHash").asText()).isEqualTo("audit-hash");
        assertThat(record.toString()).contains("비공개 원문");
        assertThat(journal.purgeSourceEvidence(purged)).isEqualTo(purged);
    }

    private AnalysisStateChange change(String id, List<String> path, JsonNode value, boolean remove, String operation) {
        return new AnalysisStateChange(id, path, value, remove, operation,
                List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    @Test
    @DisplayName("원문 파기는 대표 시점과 Fact 시점의 복사 인용도 지우고 설정값을 보존한다")
    void purgesNarrativeTimeEvidenceFromCharacterAndSlotChanges() throws Exception {
        JsonNode original = mapper.readTree("""
                {"outputStateHash":"audit-hash","changes":[
                  {"path":["characters","one"],"value":{
                    "currentNarrativeTime":{"description":"장마 첫날","sourceCandidateId":"candidate-1",
                      "evidenceSpans":[{"quote":"지울 시간 인용","startOffset":3,"endOffset":11}]},
                    "slots":{"STATUS:status.흉터":{"factValue":"흉터","valueJson":{"active":true},
                      "supportingSources":[{"narrativeTime":{"factTime":{"description":"208년 사고",
                        "evidenceSpans":[{"quote":"지울 사고 인용","startOffset":0,"endOffset":8}]}}}]}}}},
                  {"path":["characters","one","currentNarrativeTime"],"value":{
                    "description":"장마 첫날","evidenceSpans":[{"quote":"지울 시간 인용"}]}}
                ]}
                """);
        JsonNode redacted = journal.purgeSourceEvidence(original);
        assertThat(redacted.toString()).doesNotContain("지울 시간 인용", "지울 사고 인용");
        assertThat(redacted.at("/changes/0/value/slots/STATUS:status.흉터/factValue").asText()).isEqualTo("흉터");
        assertThat(redacted.path("outputStateHash").asText()).isEqualTo("audit-hash");
        assertThat(original.toString()).contains("지울 시간 인용");
        assertThat(journal.purgeSourceEvidence(redacted)).isEqualTo(redacted);
    }

    @Test
    @DisplayName("원문 파기는 복사된 근거만 지우며 설정값과 당시 hash를 보존한다")
    void purgesCopiedEvidenceWithoutChangingDomainValues() throws Exception {
        JsonNode original = mapper.readTree("""
                {"outputStateHash":"old-hash","changes":[
                  {"path":["references","one"],"value":{"domain":"characters","operation":"REVIEW_REQUIRED",
                    "sourceEpisodeNo":3,"sourceCandidateIds":["candidate-1"],"reason":"삭제할 원문 인용",
                    "factValue":"삭제할 참고 값","valueJson":{"text":"삭제할 값"},"value":"삭제할 값",
                    "proposedValue":"삭제할 값","sourceValues":["삭제할 값"],
                    "evidenceSpans":[{"quote":"삭제할 원문 인용"}],"futurePayload":{"quote":"삭제할 원문 인용"}}},
                  {"path":["worldSettings","one"],"value":{"identityEvidence":["원문"],"propertiesJson":{"reason":"세계관 속성값"}}},
                  {"path":["characters","one","slots","key"],"value":{"valueJson":{"reason":"설정값"}}}
                ]}
                """);
        JsonNode redacted = journal.purgeSourceEvidence(original);
        assertThat(redacted.at("/changes/0/value/reason").isMissingNode()).isTrue();
        assertThat(redacted.at("/changes/1/value/identityEvidence").isMissingNode()).isTrue();
        assertThat(redacted.at("/changes/0/value")).isEqualTo(mapper.readTree("""
                {"domain":"characters","operation":"REVIEW_REQUIRED","sourceEpisodeNo":3,
                 "sourceCandidateIds":["candidate-1"]}
                """));
        assertThat(redacted.toString()).doesNotContain("삭제할");
        assertThat(redacted.at("/changes/1/value/propertiesJson/reason").asText()).isEqualTo("세계관 속성값");
        assertThat(redacted.at("/changes/2/value/valueJson/reason").asText()).isEqualTo("설정값");
        assertThat(redacted.path("outputStateHash")).isEqualTo(original.path("outputStateHash"));
        assertThat(redacted.path("sourceEvidencePurged").asBoolean()).isTrue();
        assertThat(original.at("/changes/0/value/reason").asText()).isEqualTo("삭제할 원문 인용");
        assertThat(journal.purgeSourceEvidence(redacted)).isEqualTo(redacted);
    }
}
