package org.monitoring.catchholebackend.domain.worldsetting.processor;

import com.fasterxml.jackson.databind.JsonNode;

/** BOOK의 주장 상태는 보존된 추출 객체와 지정된 sourceCandidates 자식에서만 읽는다. */
public final class WorldSettingExtractionAssertion {
    private WorldSettingExtractionAssertion() {
    }

    public static boolean isAsserted(JsonNode raw) {
        if (raw == null || !raw.isObject() || !raw.path("epistemic_status").isTextual()
                || !"ASSERTED".equals(raw.path("epistemic_status").textValue())) return false;
        if (!raw.has("sourceCandidates")) return true;
        JsonNode children = raw.get("sourceCandidates");
        if (!children.isArray() || children.isEmpty()) return false;
        for (JsonNode child : children) if (!isAsserted(child)) return false;
        return true;
    }
}
