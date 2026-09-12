package org.monitoring.catchholebackend.domain.analysis.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** S0와 검증된 변경 기록을 LLM 없이 재적용하는 순수 연산이다. */
@Component
public class AnalysisStateJournal {

    public static final int FORMAT_VERSION = 1;

    public ObjectNode emptyState() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject("characters");
        root.putObject("worldSettings");
        root.putObject("references");
        return root;
    }

    public JsonNode apply(JsonNode base, List<AnalysisStateChange> changes) {
        if (base == null || !base.isObject()) {
            throw new IllegalArgumentException("고정된 시작 상태가 필요합니다.");
        }
        ObjectNode result = base.deepCopy();
        Map<String, AnalysisStateChange> applied = new LinkedHashMap<>();
        for (AnalysisStateChange change : changes) {
            AnalysisStateChange previous = applied.putIfAbsent(change.eventId(), change);
            if (previous != null) {
                if (!previous.equals(change)) {
                    throw new IllegalArgumentException("같은 변경 식별자의 내용이 다릅니다.");
                }
                continue;
            }
            JsonNode parent = result;
            for (int index = 0; index < change.path().size() - 1; index++) {
                parent = parent.get(change.path().get(index));
                if (parent == null || !parent.isObject()) {
                    throw new IllegalArgumentException("변경 대상의 상위 경로가 존재하지 않습니다.");
                }
            }
            ObjectNode object = (ObjectNode) parent;
            String key = change.path().getLast();
            if (change.remove()) {
                if (!object.has(key)) {
                    throw new IllegalArgumentException("제거할 설정이 존재하지 않습니다.");
                }
                object.remove(key);
            } else {
                object.set(key, change.value());
            }
        }
        return result;
    }

    public ArrayNode toJson(List<AnalysisStateChange> changes) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (AnalysisStateChange change : changes) {
            ObjectNode node = result.addObject();
            node.put("eventId", change.eventId());
            ArrayNode path = node.putArray("path");
            change.path().forEach(path::add);
            node.set("value", change.value());
            node.put("remove", change.remove());
            node.put("operation", change.operation());
            ArrayNode sources = node.putArray("sourceCandidateIds");
            change.sourceCandidateIds().forEach(id -> sources.add(id.toString()));
        }
        return result;
    }

    /** 원문 파기 때만 사용한다. 당시 hash는 감사용으로 남기며 이 기록은 다시 replay하지 않는다. */
    public JsonNode purgeSourceEvidence(JsonNode record) {
        if (record == null) {
            return null;
        }
        ObjectNode redacted = record.deepCopy();
        for (JsonNode change : redacted.path("changes")) {
            JsonNode path = change.path("path");
            JsonNode value = change.path("value");
            if (path.size() != 2 || !value.isObject()) {
                continue;
            }
            if ("references".equals(path.path(0).asText())) {
                // 참고 기록에는 정식 설정과 달리 원문 주장·인용의 복사본이 들어 있다.
                // 새 payload 필드가 추가되어도 파기에서 빠지지 않도록 감사 메타데이터만 남긴다.
                ((ObjectNode) value).retain(List.of("domain", "operation", "confirmationStatus",
                        "candidateId", "decisionId", "targetRef", "sourceEpisodeNo", "sourceCandidateIds",
                        "discoveryCandidateId", "rejectedCandidateId", "kind", "policyVersion", "claimHash"));
            } else if (List.of("characters", "worldSettings").contains(path.path(0).asText())) {
                ((ObjectNode) value).remove("identityEvidence");
            }
        }
        redacted.put("sourceEvidencePurged", true);
        return redacted;
    }

    public List<AnalysisStateChange> fromJson(JsonNode changes) {
        if (changes == null || !changes.isArray()) {
            throw new IllegalArgumentException("변경 기록 배열이 필요합니다.");
        }
        List<AnalysisStateChange> result = new ArrayList<>();
        for (JsonNode node : changes) {
            if (!node.path("eventId").isTextual() || !node.path("operation").isTextual()
                    || !node.path("remove").isBoolean() || !node.path("path").isArray()
                    || !node.path("sourceCandidateIds").isArray()) {
                throw new IllegalArgumentException("변경 기록의 형식이 올바르지 않습니다.");
            }
            List<String> path = new ArrayList<>();
            for (JsonNode item : node.path("path")) {
                if (!item.isTextual()) {
                    throw new IllegalArgumentException("변경 경로는 문자열이어야 합니다.");
                }
                path.add(item.textValue());
            }
            List<UUID> sources = new ArrayList<>();
            for (JsonNode item : node.path("sourceCandidateIds")) {
                if (!item.isTextual()) {
                    throw new IllegalArgumentException("출처 식별자가 올바르지 않습니다.");
                }
                sources.add(UUID.fromString(item.textValue()));
            }
            result.add(new AnalysisStateChange(node.path("eventId").textValue(), path,
                    node.get("value"), node.path("remove").booleanValue(),
                    node.path("operation").textValue(), sources));
        }
        return List.copyOf(result);
    }

    public String hash(JsonNode state) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(state).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    /** 객체 key는 정렬하고 배열 순서·유니코드는 유지하며 숫자는 지수 없는 최소 십진수로 고정한다. */
    public String canonicalJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            return sorted.entrySet().stream()
                    .map(entry -> TextNode.valueOf(entry.getKey()) + ":" + canonicalJson(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(canonicalJson(value)));
            return "[" + String.join(",", values) + "]";
        }
        if (node.isNumber()) {
            if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
                throw new IllegalArgumentException("상태에 유한한 숫자만 사용할 수 있습니다.");
            }
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (!node.isTextual() && !node.isBoolean()) {
            throw new IllegalArgumentException("상태에 지원하지 않는 JSON 값이 있습니다.");
        }
        return node.toString();
    }
}
