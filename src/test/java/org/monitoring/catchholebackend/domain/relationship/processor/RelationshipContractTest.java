package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipSourceResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;

@DisplayName("관계 후보 생성·가계도 연동 공통 v1 fixture")
class RelationshipContractTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RelationshipRules rules = new RelationshipRules();

    @Test
    @DisplayName("공통 출처·후보 payload를 손실 없이 읽고 설명용 토큰은 실제 요청으로 사용할 수 없다")
    void sharedPayloadRoundTripsAndPlaceholderCannotPassRequestValidation() throws Exception {
        JsonNode fixture = fixture();
        assertThat(fixture.path("schemaVersion").asInt()).isEqualTo(1);
        RelationshipSourceResponse source = json.treeToValue(fixture.path("source"), RelationshipSourceResponse.class);
        RelationshipCandidateCreateRequest candidate = json.treeToValue(
                fixture.path("candidateRequest"), RelationshipCandidateCreateRequest.class);

        assertThat((JsonNode) json.valueToTree(source)).isEqualTo(fixture.path("source"));
        assertThat((JsonNode) json.valueToTree(candidate)).isEqualTo(fixture.path("candidateRequest"));
        assertThat(candidate.evidence().getFirst().sourceFactId()).isEqualTo(source.factId());
        assertThat(candidate.evidence().getFirst().sourceToken()).isEqualTo(source.contextToken());
        assertThat(source.contextToken()).startsWith("PLACEHOLDER_");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(candidate);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("evidence[0].sourceToken");
        }
    }

    @Test
    @DisplayName("공통 사례의 역방향·대칭 정규화와 세부 유형·기간 구분을 실제 관계 규칙으로 검증한다")
    void normalizationCasesExecuteProductionRules() throws Exception {
        JsonNode fixture = fixture();
        Map<String, String> canonicalKeys = new LinkedHashMap<>();
        Map<String, String> identities = new LinkedHashMap<>();
        assertThat(fixture.path("normalizationCases")).hasSize(12);

        for (JsonNode testCase : fixture.path("normalizationCases")) {
            String name = testCase.path("name").asText();
            ObjectNode payload = fixture.path("candidateRequest").deepCopy();
            testCase.path("input").properties().forEach(entry -> payload.set(entry.getKey(), entry.getValue()));
            RelationshipCandidateCreateRequest request = json.treeToValue(payload, RelationshipCandidateCreateRequest.class);
            rules.validateDraft(request);
            RelationshipCandidate candidate = RelationshipCandidate.create(null, request.requestId(), "fixture",
                    character(request.subjectCharacterId()), character(request.objectCharacterId()), request.objectMention(),
                    request.kind(), request.subtype(), request.temporalScope(), request.timeLabel(), request.assertion(), null);
            var canonical = rules.canonicalize(candidate);
            assertThat(canonical.subjectId().toString()).as(name).isEqualTo(testCase.path("expectedSubjectId").asText());
            assertThat(canonical.objectId().toString()).as(name).isEqualTo(testCase.path("expectedObjectId").asText());
            assertThat(canonical.kind().name()).as(name).isEqualTo(testCase.path("expectedKind").asText());
            assertThat(candidate.getSubtype()).as(name).isEqualTo(request.subtype());
            assertThat(candidate.getTemporalScope()).as(name).isEqualTo(request.temporalScope());
            assertThat(candidate.getTimeLabel()).as(name).isEqualTo(request.timeLabel());

            String identity = testCase.path("expectedIdentity").asText();
            String priorKey = canonicalKeys.putIfAbsent(identity, canonical.key());
            if (priorKey != null) assertThat(canonical.key()).as(name).isEqualTo(priorKey);
            String priorIdentity = identities.putIfAbsent(canonical.key(), identity);
            if (priorIdentity != null) assertThat(identity).as(name).isEqualTo(priorIdentity);
        }
        assertThat(canonicalKeys).hasSize(9);
    }

    private WorkCharacter character(UUID id) {
        WorkCharacter character = mock(WorkCharacter.class);
        when(character.getId()).thenReturn(id);
        return character;
    }

    private JsonNode fixture() throws Exception {
        return json.readTree(Files.readString(Path.of("docs/contracts/family-relationships-v1.json")));
    }
}
