package org.monitoring.catchholebackend.domain.analysis.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * #387 누적 상태 전체({characters, worldSettings, references})에서 대상별 읽기 값을 잘라낸다. 전체 상태를 저장하는
 * 입력(수동 S0 replay·자동 format 1)의 대상별 읽기가 이 규칙 하나를 쓴다. 반환 값은 원본 노드를 빌린 읽기 전용 view이며
 * 복사 여부는 호출자가 정한다.
 */
public final class AnalysisStateSlices {
    public static final String CHARACTERS = "characters";
    public static final String WORLD_SETTINGS = "worldSettings";
    public static final String REFERENCES = "references";
    private static final Set<String> TARGET_DOMAINS = Set.of(CHARACTERS, WORLD_SETTINGS);
    private static final List<String> IDENTITY_FIELDS = List.of("actualCharacterId", "provisionalSubjectKey", "name", "aliases");

    private AnalysisStateSlices() {
    }

    /** 한 대상. 없으면 MissingNode다. */
    public static JsonNode target(JsonNode state, String domain, String ref) {
        return state.path(requireDomain(domain)).path(ref);
    }

    /** 이 상태의 references. 상태에 없으면 MissingNode다. */
    public static JsonNode references(JsonNode state) {
        return state.path(REFERENCES);
    }

    /** domain의 refs 대상(없는 ref는 넣지 않는다)과 references만 담은 부분 상태. */
    public static ObjectNode subset(JsonNode state, String domain, Collection<String> refs) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ObjectNode targets = result.putObject(requireDomain(domain));
        for (String ref : refs) {
            JsonNode target = state.path(domain).path(ref);
            if (!target.isMissingNode()) targets.set(ref, target);
        }
        JsonNode references = state.path(REFERENCES);
        if (!references.isMissingNode()) result.set(REFERENCES, references);
        return result;
    }

    /** category가 같은 세계관 대상. 상태의 저장 순서를 유지한다. */
    public static List<JsonNode> worldSubjects(JsonNode state, String category) {
        List<JsonNode> result = new ArrayList<>();
        state.path(WORLD_SETTINGS).forEach(target -> {
            if (category.equals(target.path("category").asText())) result.add(target);
        });
        return result;
    }

    /** 인물 식별 색인: ref → {actualCharacterId, provisionalSubjectKey, name, aliases} 중 있는 필드. 저장 순서를 유지한다. */
    public static ObjectNode characterIdentities(JsonNode state) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        state.path(CHARACTERS).properties().forEach(entry -> {
            ObjectNode identity = result.putObject(entry.getKey());
            for (String field : IDENTITY_FIELDS) {
                if (entry.getValue().has(field)) identity.set(field, entry.getValue().get(field));
            }
        });
        return result;
    }

    private static String requireDomain(String domain) {
        if (!TARGET_DOMAINS.contains(domain)) {
            throw new IllegalArgumentException("누적 상태의 대상 영역은 characters 또는 worldSettings여야 합니다.");
        }
        return domain;
    }
}
