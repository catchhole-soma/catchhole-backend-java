package org.monitoring.catchholebackend.domain.analysis.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;

@DisplayName("누적 분석의 고정 입력 참고 목록")
class AnalysisRunContextMapperTest {
    private final AnalysisRunContextMapper mapper = new AnalysisRunContextMapper(
            new CharacterFactEvidenceMapper(mock(CharacterFactSourceResolver.class)));

    @Test
    @DisplayName("DB JSON 객체 키 순서가 바뀌어도 claim과 다음 비교에 같은 참고 목록을 전달한다")
    void jsonObjectReorderingPreservesContextEquality() {
        AnalysisJob job = job();
        ObjectNode inMemory = input(List.of("d", "b", "a", "c"));
        ObjectNode reloaded = input(List.of("a", "b", "c", "d"));
        when(job.getAutomaticInputState()).thenReturn(inMemory);
        var claimed = mapper.toResponse(job);
        when(job.getAutomaticInputState()).thenReturn(reloaded);

        assertThat(mapper.toResponse(job)).isEqualTo(claimed);
        assertThat(claimed.unresolvedReferences()).hasSize(4);
        assertThat(claimed.unresolvedReferences()).allSatisfy(reference -> {
            assertThat(reference.sourceEpisodeNo()).isEqualTo(1);
            assertThat(reference.evidenceSpans()).hasSize(1);
        });
        assertThat(inMemory.path("references").fieldNames()).toIterable().containsExactly("d", "b", "a", "c");
    }

    @Test
    @DisplayName("참고의 실제 값이 달라지면 입력 문맥도 다르게 유지한다")
    void contentChangesRemainDetectable() {
        AnalysisJob job = job();
        ObjectNode input = input(List.of("a", "b"));
        when(job.getAutomaticInputState()).thenReturn(input);
        var claimed = mapper.toResponse(job);
        ((ObjectNode) input.path("references").path("a")).put("value", "바뀐 값");

        assertThat(mapper.toResponse(job)).isNotEqualTo(claimed);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "1층")
    @DisplayName("범위 검토 참고는 원본과 기존 경로를 전달하고 기존 참고의 JSON은 늘리지 않는다")
    void preservesReviewPathsWithoutAddingNullFieldsToLegacyReferences(String matchedScope) throws Exception {
        AnalysisJob job = job();
        ObjectNode input = input(List.of("a", "b"));
        ((ObjectNode) input.path("references").path("b")).put("scopeName", "외부")
                .put("matchedScopeName", matchedScope).put("matchedPropertyName", "광원");
        when(job.getAutomaticInputState()).thenReturn(input);

        var references = mapper.toResponse(job).unresolvedReferences();
        var review = references.get(1);
        assertThat(review.scopeName()).isEqualTo("외부");
        assertThat(review.matchedScopeName()).isEqualTo(matchedScope);
        assertThat(review.matchedPropertyName()).isEqualTo("광원");
        // MVC는 Jackson 3를 사용하므로 실제 응답과 같은 직렬화 경계를 확인한다.
        var jsonMapper = new tools.jackson.databind.ObjectMapper();
        var legacyJson = jsonMapper.readTree(jsonMapper.writeValueAsString(references.getFirst()));
        assertThat(legacyJson.has("scopeName")).isFalse();
        assertThat(legacyJson.has("matchedScopeName")).isFalse();
        assertThat(legacyJson.has("matchedPropertyName")).isFalse();
        var reviewJson = jsonMapper.readTree(jsonMapper.writeValueAsString(review));
        assertThat(reviewJson.path("scopeName").asText()).isEqualTo("외부");
        assertThat(reviewJson.path("matchedPropertyName").asText()).isEqualTo("광원");
        assertThat(reviewJson.has("matchedScopeName")).isEqualTo(matchedScope != null);
    }

    private AnalysisJob job() {
        AnalysisJob job = mock(AnalysisJob.class);
        when(job.isOrderedProvisional()).thenReturn(true);
        when(job.getAnalysisRunId()).thenReturn(UUID.randomUUID());
        when(job.getRunGeneration()).thenReturn(1L);
        when(job.getInputStateHash()).thenReturn("a".repeat(64));
        when(job.getSourceContentHash()).thenReturn("b".repeat(64));
        return job;
    }

    private ObjectNode input(List<String> ids) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        ObjectNode references = input.putObject("references");
        ids.forEach(id -> references.putObject(id)
                .put("domain", "worldSettings").put("subjectName", "미궁")
                .put("sourceEpisodeNo", 1).put("reason", "비교 실패")
                .put("settingName", "설정 " + id).put("value", "값 " + id)
                .putArray("evidenceSpans").addObject().put("quote", "원문 " + id));
        return input;
    }
}
