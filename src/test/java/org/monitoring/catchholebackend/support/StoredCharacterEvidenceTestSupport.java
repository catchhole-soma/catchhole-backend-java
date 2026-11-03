package org.monitoring.catchholebackend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection;
import org.monitoring.catchholebackend.domain.analysis.processor.SettingBookAutomaticEvidenceValidator;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.StoredCharacterEvidenceRepository;
import static org.mockito.Mockito.*;

/** Unit capture fixtures model the already-recorded result; real receipt creation is tested on PostgreSQL. */
public final class StoredCharacterEvidenceTestSupport {
    private StoredCharacterEvidenceTestSupport() {}
    public static StoredCharacterEvidenceRepository verified(String content) {
        var repository = mock(StoredCharacterEvidenceRepository.class);
        when(repository.find(any())).thenAnswer(call -> {
            SettingCandidate candidate = call.getArgument(0);
            if (candidate.getAnalysisJob().getSourceReferenceInvalidatedAt() != null) return StoredCharacterEvidenceRepository.Evidence.purged();
            var validator = new SettingBookAutomaticEvidenceValidator(null);
            boolean timeValid = true;
            var timeSpans = JsonNodeFactory.instance.arrayNode();
            JsonNode time = candidate.getNarrativeTime();
            if (time != null && !time.isNull()) for (String field : List.of("factTime", "referenceTime")) {
                JsonNode point = time.path(field);
                if (point.path("evidenceSpans").isArray()) timeSpans.addAll((com.fasterxml.jackson.databind.node.ArrayNode)point.path("evidenceSpans"));
                if (point.isObject() && !validator.matches(content, point.path("evidenceSpans"))) timeValid = false;
            }
            var identity = JsonNodeFactory.instance.arrayNode();
            if (candidate.getRawAiResultJson() != null) for (JsonNode member : candidate.getRawAiResultJson().path("setting_book_identity_proof").path("members")) {
                JsonNode span = member.path("profileEvidence");
                if (!GeneralEvidenceProjection.verifiedSpans(content, JsonNodeFactory.instance.arrayNode().add(span)).isEmpty()) identity.add(span.deepCopy());
            }
            return new StoredCharacterEvidenceRepository.Evidence(false, true,
                    GeneralEvidenceProjection.verifiedSpans(content, candidate.getEvidenceSpans()), timeValid, identity, timeSpans);
        });
        return repository;
    }
}
