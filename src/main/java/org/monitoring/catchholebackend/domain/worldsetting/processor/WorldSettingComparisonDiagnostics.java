package org.monitoring.catchholebackend.domain.worldsetting.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;

/** 진단에도 실제 비교 입력의 후보와 경로만 허용한다. 원문이나 모델 응답은 직렬화하지 않는다. */
public final class WorldSettingComparisonDiagnostics {
    private WorldSettingComparisonDiagnostics() {}

    public static JsonNode forCandidate(JsonNode diagnostics, String ref) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (JsonNode entry : diagnostics) {
            if (entry.path("candidateRefs").isEmpty() || java.util.stream.StreamSupport
                    .stream(entry.path("candidateRefs").spliterator(), false).anyMatch(value -> value.asText().equals(ref))) {
                result.add(entry.deepCopy());
            }
        }
        return result;
    }

    public static JsonNode validate(List<WorldSettingComparisonDiagnostic> diagnostics,
            Set<String> candidateRefs, JsonNode targets) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (diagnostics == null) return result;
        if (diagnostics.size() > 30) throw invalid();
        for (var diagnostic : diagnostics) {
            if (diagnostic == null || diagnostic.attempt() < 1 || diagnostic.attempt() > 30
                    || diagnostic.rule() == null || diagnostic.rule().length() > 100
                    || !diagnostic.rule().matches("[A-Z][A-Z0-9_]*")
                    || diagnostic.candidateRefs() == null || diagnostic.candidateRefs().size() > 20
                    || diagnostic.selectedProperties() == null || diagnostic.selectedProperties().size() > 20
                    || !candidateRefs.containsAll(diagnostic.candidateRefs())
                    || new HashSet<>(diagnostic.candidateRefs()).size() != diagnostic.candidateRefs().size()) throw invalid();
            var entry = result.addObject().put("attempt", diagnostic.attempt()).put("rule", diagnostic.rule());
            if (diagnostic.stage() != null) entry.put("stage", diagnostic.stage().name());
            if (diagnostic.phase() != null) entry.put("phase", diagnostic.phase().name());
            var refs = entry.putArray("candidateRefs");
            diagnostic.candidateRefs().forEach(refs::add);
            var paths = entry.putArray("selectedProperties");
            Set<String> selected = new HashSet<>();
            for (var property : diagnostic.selectedProperties()) {
                if (property == null || !property.isTargetReferenceValid() || property.propertyName() == null
                        || property.propertyName().isBlank() || property.propertyName().length() > 100
                        || property.scopeName() != null && property.scopeName().length() > 100) throw invalid();
                String ref = property.targetWorldSettingId() == null ? property.provisionalSubjectKey()
                        : "world:" + property.targetWorldSettingId();
                if (targets == null || ref == null || !targets.path(ref).isObject()) throw invalid();
                var actual = new WorldSettingPropertyView(targets.path(ref).path("propertiesJson"))
                        .path(property.scopeName(), property.propertyName());
                if (actual == null || !selected.add(ref + "|" + actual.scopeName() + "|" + actual.settingName())) throw invalid();
                var path = paths.addObject();
                if (property.targetWorldSettingId() != null) path.put("targetWorldSettingId", property.targetWorldSettingId().toString());
                else path.put("provisionalSubjectKey", property.provisionalSubjectKey());
                path.put("scopeName", actual.scopeName());
                path.put("propertyName", actual.settingName());
            }
        }
        return result;
    }

    private static AppException invalid() {
        return new AppException(WorldSettingErrorCode.WORLD_SETTING_INPUT_INVALID);
    }
}
