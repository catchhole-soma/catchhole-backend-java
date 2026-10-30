package org.monitoring.catchholebackend.domain.analysis.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.GeneralEvidenceSourceRepository;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Read-only display projection. Raw extraction evidence and semantic proof validators stay unchanged. */
@Component
@RequiredArgsConstructor
public class GeneralEvidenceProjection {
    private static final String CACHE_ATTRIBUTE = GeneralEvidenceProjection.class.getName() + ".sources";
    private final ObjectStorageService storage;
    private final GeneralEvidenceSourceRepository sources;

    public ArrayNode forCandidate(SettingCandidate candidate) {
        return candidate == null ? empty() : forSource(candidate.getWork().getId(),
                candidate.getAnalysisJob(), candidate.getEvidenceSpans());
    }

    public ArrayNode forWorldCandidate(WorldSettingCandidate candidate) {
        return candidate == null ? empty() : forSource(candidate.getWork().getId(),
                candidate.getAnalysisJob(), candidate.getEvidenceSpans());
    }

    /** Detail endpoints already read the source; validate that content without a second storage request. */
    public ArrayNode forCandidate(SettingCandidate candidate, String content) {
        if (candidate == null || content == null || !hasLocatedSpan(candidate.getEvidenceSpans())) return empty();
        var source = source(candidate.getAnalysisJob(), requestCache());
        if (source == null || !Objects.equals(candidate.getWork().getId(), source.workId())
                || source.key() == null || source.hash() == null
                || !source.hash().equals(storage.contentHash(content))) return empty();
        return verifiedSpans(content, candidate.getEvidenceSpans());
    }

    private ArrayNode forSource(UUID workId, AnalysisJob job, JsonNode evidence) {
        if (job == null || job.getId() == null || !hasLocatedSpan(evidence)) return empty();
        Cache cache = requestCache();
        var source = source(job, cache);
        if (source == null || !Objects.equals(workId, source.workId()) || source.key() == null
                || source.hash() == null) return empty();
        String binding = source.key() + "\n" + source.version() + "\n" + source.hash();
        Map<String, String> contents = cache.contents;
        if (!contents.containsKey(binding)) {
            String content = null;
            try {
                String loaded = storage.getText(source.key());
                if (loaded != null && source.hash().equals(storage.contentHash(loaded))) content = loaded;
            } catch (RuntimeException unavailable) {
                // Public source previews may be absent. Strict processing capture still validates the manifest/hash.
            }
            contents.put(binding, content);
        }
        return verifiedSpans(contents.get(binding), evidence);
    }

    private GeneralEvidenceSourceRepository.Source source(AnalysisJob job, Cache cache) {
        if (job == null || job.getId() == null) return null;
        if (!cache.sources.containsKey(job.getId())) cache.sources.put(job.getId(), sources.find(job.getId()));
        return cache.sources.get(job.getId());
    }

    /** Missing offsets never trigger quote search; malformed or nonmatching spans are omitted individually. */
    public static ArrayNode verifiedSpans(String content, JsonNode evidence) {
        ArrayNode result = empty();
        if (content == null || evidence == null || !evidence.isArray()) return result;
        int length = content.codePointCount(0, content.length());
        for (JsonNode span : evidence) {
            if (!hasCompleteOffsets(span) || !span.path("quote").isTextual()
                    || span.path("quote").asText().isBlank()) continue;
            int start = offset(span, "startOffset", "start_offset").intValue();
            int end = offset(span, "endOffset", "end_offset").intValue();
            if (start < 0 || start >= end || end > length) continue;
            String quote = span.path("quote").asText();
            if (!quote.equals(content.substring(content.offsetByCodePoints(0, start),
                    content.offsetByCodePoints(0, end)))) continue;
            result.addObject().put("quote", quote).put("startOffset", start).put("endOffset", end);
        }
        return result;
    }

    public static String unavailableReason(JsonNode evidence) {
        if (evidence == null || evidence.isNull() || evidence.isArray() && evidence.isEmpty()) return "NO_RECORDED_QUOTE";
        if (!evidence.isArray()) return "EVIDENCE_UNAVAILABLE";
        for (JsonNode span : evidence) {
            if (!span.isObject() || !span.path("quote").isTextual() || span.path("quote").asText().isBlank()) return "EVIDENCE_UNAVAILABLE";
            if (!absent(offset(span, "startOffset", "start_offset"))
                    || !absent(offset(span, "endOffset", "end_offset"))) return "INVALID_OFFSETS";
        }
        return "OFFSETS_UNAVAILABLE";
    }

    private static boolean hasLocatedSpan(JsonNode evidence) {
        if (evidence == null || !evidence.isArray()) return false;
        for (JsonNode span : evidence) if (hasCompleteOffsets(span)) return true;
        return false;
    }

    private static boolean hasCompleteOffsets(JsonNode span) {
        if (!span.isObject() || conflicts(span, "startOffset", "start_offset")
                || conflicts(span, "endOffset", "end_offset")) return false;
        JsonNode start = offset(span, "startOffset", "start_offset");
        JsonNode end = offset(span, "endOffset", "end_offset");
        return start != null && end != null && start.isIntegralNumber() && end.isIntegralNumber()
                && start.canConvertToInt() && end.canConvertToInt();
    }

    private static boolean conflicts(JsonNode span, String camel, String snake) {
        return span.has(camel) && span.has(snake) && !span.get(camel).equals(span.get(snake));
    }

    private static JsonNode offset(JsonNode span, String camel, String snake) {
        return span.has(camel) ? span.get(camel) : span.get(snake);
    }

    private static boolean absent(JsonNode value) {
        return value == null || value.isNull();
    }

    private static ArrayNode empty() {
        return JsonNodeFactory.instance.arrayNode();
    }

    private Cache requestCache() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) return new Cache();
        Cache cache = (Cache) request.getAttribute(CACHE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (cache == null) {
            cache = new Cache();
            request.setAttribute(CACHE_ATTRIBUTE, cache, RequestAttributes.SCOPE_REQUEST);
        }
        return cache;
    }

    private static final class Cache {
        private final Map<UUID, GeneralEvidenceSourceRepository.Source> sources = new HashMap<>();
        private final Map<String, String> contents = new HashMap<>();
    }
}
