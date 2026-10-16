package org.monitoring.catchholebackend.domain.character.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("캐릭터 Fact 비교 Worker Mapper 테스트")
class CharacterFactComparisonWorkerMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CharacterFactComparisonWorkerMapper mapper = new CharacterFactComparisonWorkerMapper();

    @Test
    @DisplayName("인물 묶음 이력은 모든 타입을 보존하고 과거 타입별 묶음은 기존 범위만 전달한다")
    void personHistoryIncludesEveryTypeWithoutChangingLegacyScope() {
        var job = org.mockito.Mockito.mock(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.class);
        UUID characterId = UUID.randomUUID();
        var state = objectMapper.createObjectNode().put("actualCharacterId", characterId.toString()).putNull("provisionalSubjectKey");
        state.putObject("slots");
        var history = state.putArray("historyFacts");
        for (String type : List.of("PROFILE", "STATUS")) {
            UUID source = UUID.randomUUID();
            var fact = history.addObject().putNull("factId").put("sourceCandidateId", source.toString())
                    .put("factType", type).put("factKey", "old.value").put("factValue", "과거 값")
                    .put("operation", "HISTORY_ONLY").put("temporalScope", "PAST").putNull("narrativeTime");
            fact.putObject("valueJson").put("value", "과거 값");
            fact.putArray("removedSnapshotEntries");
            var origin = fact.putObject("supportingSource").put("sourceCandidateId", source.toString())
                    .putNull("sourceAnalysisJobId").put("availability", "UNAVAILABLE").put("unavailableReason", "LEGACY_NO_MANIFEST")
                    .putNull("candidateWasEdited").putNull("source").put("quoteRelation", "SOURCE_CONTEXT_ONLY");
            origin.putArray("evidenceSpans");
        }

        var person = mapper.toAdvisoryCurrentContext(job, state, characterId, null, null);
        var legacy = mapper.toAdvisoryCurrentContext(job, state, characterId, null,
                org.monitoring.catchholebackend.domain.character.type.CharacterFactType.STATUS);

        assertThat(person.historyFacts()).hasSize(2);
        assertThat(objectMapper.valueToTree(legacy).path("historyFacts")).hasSize(1);
        assertThat(objectMapper.valueToTree(legacy).path("historyFacts").get(0).path("factType").asText()).isEqualTo("STATUS");
        assertThat(state.path("historyFacts")).hasSize(2);
    }

    @Test
    @DisplayName("복수 근거의 camelCase와 snake_case offset을 저장 순서대로 변환한다")
    void toEvidenceSpansMapsBothOffsetNamingConventionsInOrder() {
        var evidenceSpans = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("quote", "회복 효과로 신체가 빠르게 재생된다.")
                        .put("startOffset", 10)
                        .put("endOffset", 29))
                .add(objectMapper.createObjectNode()
                        .put("quote", "통증이 줄어들었다.")
                        .put("start_offset", 40)
                        .put("end_offset", 51));

        var result = mapper.toEvidenceSpans(evidenceSpans);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).quote()).isEqualTo("회복 효과로 신체가 빠르게 재생된다.");
        assertThat(result.get(0).startOffset()).isEqualTo(10);
        assertThat(result.get(0).endOffset()).isEqualTo(29);
        assertThat(result.get(1).quote()).isEqualTo("통증이 줄어들었다.");
        assertThat(result.get(1).startOffset()).isEqualTo(40);
        assertThat(result.get(1).endOffset()).isEqualTo(51);
    }
}
