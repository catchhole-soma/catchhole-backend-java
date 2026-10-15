package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;

/** 동일 이름 예외는 고정 원문의 별도 프로필과 각 후보의 귀속을 검증한 실행에만 허용한다. */
@Component
@RequiredArgsConstructor
public class SettingBookIdentityValidator {
    private static final String PROOF = "setting_book_identity_proof";
    private static final Set<String> FIELDS = Set.of("version", "producer", "identityDecision", "groupId", "normalizedName",
            "sourceAnalysisJobId", "settingBookId", "revisionId", "contentHash", "referenceTimeKind", "referenceEpisodeNo",
            "members", "distinctEvidenceSpans", "parentTargetRef");
    private static final Set<String> SPAN_FIELDS = Set.of("quote", "startOffset", "endOffset");

    private final ObjectStorageService storage;
    private final AnalysisCandidateSourceGuard sourceGuard;
    private final WorkCharacterRepository characters;

    public ValidatedIdentities validate(AnalysisJob job, List<SettingCandidate> candidates) {
        if (!job.isSettingBookSource() || !job.isAutomaticReview() || !job.isOrderedProvisional()) return ValidatedIdentities.empty();
        JsonNode input = job.getAutomaticInputState();
        Map<String, Set<String>> supplied = suppliedActualNames(input);
        Map<String, List<SettingCandidate>> byName = new LinkedHashMap<>();
        for (SettingCandidate candidate : candidates) {
            if (candidate.getEntityName() != null) byName.computeIfAbsent(name(candidate.getEntityName()), ignored -> new ArrayList<>()).add(candidate);
        }
        Set<UUID> required = new HashSet<>();
        for (var group : byName.entrySet()) {
            Set<String> discoveries = new HashSet<>();
            group.getValue().stream().filter(SettingCandidate::isCharacterDiscovery).map(SettingCandidate::getProvisionalSubjectKey)
                    .filter(Objects::nonNull).forEach(discoveries::add);
            if (discoveries.size() > 1 || supplied.getOrDefault(group.getKey(), Set.of()).size() > 1
                    || !discoveries.isEmpty() && !supplied.getOrDefault(group.getKey(), Set.of()).isEmpty()) {
                group.getValue().forEach(candidate -> required.add(candidate.getId()));
            }
            group.getValue().stream().filter(candidate -> proof(candidate) != null).forEach(candidate -> required.add(candidate.getId()));
        }
        if (required.isEmpty()) return ValidatedIdentities.empty();
        var denied = new ValidatedIdentities(Set.copyOf(required), Map.of(), Map.of());
        // Completion applies contributors after sealing, immediately before RUNNING becomes SUCCEEDED.
        if (job.getJournalStatus() != AnalysisJournalStatus.SEALED
                || job.getStatus() != AnalysisJobStatus.RUNNING && job.getStatus() != AnalysisJobStatus.SUCCEEDED
                || input == null || !input.path("characters").isObject()) return denied;
        try {
            sourceGuard.assertCurrent(job, null);
        } catch (AppException staleSource) {
            return denied;
        }
        String content = storage.getText(job.getSourceContentS3Key());
        if (content == null || !Objects.equals(storage.contentHash(content), job.getSourceContentHash())) return denied;
        List<WorkCharacter> actual = characters.findAllByWorkIdOrderByCreatedAtDesc(job.getWork().getId());
        Map<UUID, String> accepted = new HashMap<>();
        Map<UUID, Set<String>> members = new HashMap<>();
        for (var named : byName.entrySet()) {
            List<SettingCandidate> group = named.getValue();
            JsonNode common = group.stream().map(SettingBookIdentityValidator::proof).filter(Objects::nonNull).findFirst().orElse(null);
            if (!validManifest(common, job, named.getKey())) continue;
            Map<String, Span> profiles = profiles(common, content);
            if (profiles == null || !validDistinctSpans(common, content) || !validTargets(profiles.keySet(), group, supplied.getOrDefault(named.getKey(), Set.of()), actual, job, named.getKey())) continue;
            boolean validDiscoveries = group.stream().filter(SettingCandidate::isCharacterDiscovery).allMatch(candidate ->
                    validCandidate(candidate, common, profiles, content, job));
            if (!validDiscoveries) continue;
            for (SettingCandidate candidate : group) {
                if (validCandidate(candidate, common, profiles, content, job)) {
                    accepted.put(candidate.getId(), proof(candidate).path("parentTargetRef").asText());
                    members.put(candidate.getId(), Set.copyOf(profiles.keySet()));
                }
            }
        }
        return new ValidatedIdentities(Set.copyOf(required), Map.copyOf(accepted), Map.copyOf(members));
    }

    private boolean validManifest(JsonNode proof, AnalysisJob job, String normalizedName) {
        if (!fields(proof, FIELDS) || !proof.path("version").isIntegralNumber() || !proof.path("version").canConvertToInt() || proof.path("version").asInt() != 1
                || !text(proof, "producer", "ORDERED_CHARACTER_SUBJECT_RESOLVER") || !text(proof, "identityDecision", "DISTINCT_IDENTITIES")
                || !proof.path("groupId").isTextual() || !proof.path("groupId").asText().matches("G[1-9][0-9]*")
                || !text(proof, "normalizedName", normalizedName)
                || !text(proof, "sourceAnalysisJobId", job.getId().toString())
                || !text(proof, "settingBookId", job.getSourceSettingBook().getId().toString())
                || !text(proof, "revisionId", job.getSourceSettingBookRevision().getId().toString())
                || !text(proof, "contentHash", job.getSourceContentHash())
                || !text(proof, "referenceTimeKind", job.getSourceSettingBookRevision().getReferenceTimeKind().name())) return false;
        Integer episode = job.getSourceSettingBookRevision().getReferenceEpisodeNo();
        JsonNode suppliedEpisode = proof.path("referenceEpisodeNo");
        return episode == null ? suppliedEpisode.isNull()
                : suppliedEpisode.isIntegralNumber() && suppliedEpisode.canConvertToInt() && suppliedEpisode.intValue() == episode;
    }

    private Map<String, Span> profiles(JsonNode proof, String content) {
        JsonNode members = proof.path("members");
        if (!members.isArray() || members.size() < 2) return null;
        Map<String, Span> profiles = new LinkedHashMap<>();
        for (JsonNode member : members) {
            if (!fields(member, Set.of("targetRef", "profileEvidence")) || !member.path("targetRef").isTextual()) return null;
            String ref = member.path("targetRef").asText();
            Span span = span(member.path("profileEvidence"), content, true);
            if (targetId(ref) == null || span == null || profiles.containsKey(ref)
                    || profiles.values().stream().anyMatch(other -> span.start() < other.end() && other.start() < span.end())) return null;
            profiles.put(ref, span);
        }
        return profiles;
    }

    private boolean validDistinctSpans(JsonNode proof, String content) {
        JsonNode spans = proof.path("distinctEvidenceSpans");
        if (!spans.isArray() || spans.isEmpty()) return false;
        Set<Span> distinct = new HashSet<>();
        for (JsonNode node : spans) {
            Span span = span(node, content, true);
            if (span == null || !distinct.add(span)) return false;
        }
        return true;
    }

    private boolean validTargets(Set<String> refs, List<SettingCandidate> group, Set<String> supplied,
            List<WorkCharacter> actual, AnalysisJob job, String normalizedName) {
        boolean provisional = refs.stream().allMatch(ref -> ref.startsWith("provisional-character:"));
        boolean existing = refs.stream().allMatch(ref -> ref.startsWith("character:"));
        if (!provisional && !existing) return false;
        if (existing) {
            if (!refs.equals(supplied) || supplied.size() < 2) return false;
            return refs.stream().allMatch(ref -> actual.stream().anyMatch(character -> character.getId().equals(targetId(ref))
                    && character.getStatus() == CharacterStatus.ACTIVE && character.getWork().getId().equals(job.getWork().getId())));
        }
        if (!supplied.isEmpty() || actual.stream().anyMatch(character -> name(character.getName()).equals(normalizedName))) return false;
        Set<String> discoveries = new LinkedHashSet<>();
        for (SettingCandidate candidate : group) {
            if (!candidate.isCharacterDiscovery()) continue;
            String ref = candidate.getProvisionalSubjectKey();
            if (ref == null || !ref.equals("provisional-character:" + candidate.getId()) || !discoveries.add(ref)) return false;
        }
        return refs.equals(discoveries);
    }

    private boolean validCandidate(SettingCandidate candidate, JsonNode common, Map<String, Span> profiles, String content, AnalysisJob job) {
        JsonNode own = proof(candidate);
        if (candidate.isUserModified() || candidate.getAnalysisJob() == null || !job.getId().equals(candidate.getAnalysisJob().getId())
                || !job.getWork().getId().equals(candidate.getWork().getId()) || !candidate.isPendingReview()
                || candidate.getEpisode() != null || candidate.getSourceKind() != job.getSourceKind()
                || candidate.getSourceSettingBook() == null || !job.getSourceSettingBook().getId().equals(candidate.getSourceSettingBook().getId())
                || candidate.getSourceSettingBookRevision() == null || !job.getSourceSettingBookRevision().getId().equals(candidate.getSourceSettingBookRevision().getId())
                || !Objects.equals(candidate.getSourceContentS3Key(), job.getSourceContentS3Key())
                || !validManifest(own, job, name(candidate.getEntityName()))) return false;
        for (String key : FIELDS) {
            if (!key.equals("parentTargetRef") && !Objects.equals(common.get(key), own.get(key))) return false;
        }
        String target = candidate.getProvisionalSubjectKey() != null ? candidate.getProvisionalSubjectKey()
                : candidate.getMatchedCharacterId() == null ? null : "character:" + candidate.getMatchedCharacterId();
        if (!text(own, "parentTargetRef", target)) return false;
        Span parent = profiles.get(target);
        JsonNode evidence = candidate.getEvidenceSpans();
        if (parent == null || evidence == null || !evidence.isArray() || evidence.isEmpty()) return false;
        for (JsonNode item : evidence) {
            Span ownSpan = span(item, content, false);
            if (ownSpan == null || ownSpan.start() < parent.start() || ownSpan.end() > parent.end()) return false;
        }
        return true;
    }

    private Span span(JsonNode node, String content, boolean exactFields) {
        if (node == null || !node.isObject() || exactFields && !fields(node, SPAN_FIELDS) || !node.path("quote").isTextual()) return null;
        JsonNode start = node.has("startOffset") ? node.path("startOffset") : node.path("start_offset");
        JsonNode end = node.has("endOffset") ? node.path("endOffset") : node.path("end_offset");
        if (!start.isIntegralNumber() || !end.isIntegralNumber() || !start.canConvertToInt() || !end.canConvertToInt()) return null;
        int from = start.intValue(), to = end.intValue(), length = content.codePointCount(0, content.length());
        if (from < 0 || from >= to || to > length) return null;
        return content.substring(content.offsetByCodePoints(0, from), content.offsetByCodePoints(0, to)).equals(node.path("quote").asText())
                ? new Span(from, to) : null;
    }

    private Map<String, Set<String>> suppliedActualNames(JsonNode input) {
        Map<String, Set<String>> result = new HashMap<>();
        if (input == null || !input.path("characters").isObject()) return result;
        input.path("characters").properties().forEach(entry -> {
            JsonNode target = entry.getValue();
            UUID id = targetId(entry.getKey());
            if (!entry.getKey().startsWith("character:") || id == null || !text(target, "actualCharacterId", id.toString())) return;
            List<String> names = new ArrayList<>();
            if (target.path("name").isTextual()) names.add(target.path("name").asText());
            if (target.path("aliases").isArray()) target.path("aliases").forEach(alias -> { if (alias.isTextual()) names.add(alias.asText()); });
            names.forEach(value -> result.computeIfAbsent(name(value), ignored -> new HashSet<>()).add(entry.getKey()));
        });
        return result;
    }

    private static boolean fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) return false;
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static boolean text(JsonNode node, String key, String expected) {
        return expected != null && node != null && node.path(key).isTextual() && expected.equals(node.path(key).asText());
    }

    private static UUID targetId(String ref) {
        if (ref == null || !ref.startsWith("character:") && !ref.startsWith("provisional-character:")) return null;
        String id = ref.substring(ref.indexOf(':') + 1);
        try { UUID parsed = UUID.fromString(id); return parsed.toString().equals(id) ? parsed : null; }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static String name(String value) { return SettingCandidateGroupNameNormalizer.toGroupKey(value); }

    private static JsonNode proof(SettingCandidate candidate) {
        JsonNode raw = candidate.getRawAiResultJson();
        return raw == null || !raw.has(PROOF) ? null : raw.get(PROOF);
    }

    private record Span(int start, int end) { }

    public record ValidatedIdentities(Set<UUID> requiredCandidateIds, Map<UUID, String> parentRefs, Map<UUID, Set<String>> memberRefs) {
        public static ValidatedIdentities empty() { return new ValidatedIdentities(Set.of(), Map.of(), Map.of()); }
        public boolean requiresProof(SettingCandidate candidate) { return requiredCandidateIds.contains(candidate.getId()); }
        public boolean permits(SettingCandidate candidate) { return parentRefs.containsKey(candidate.getId()); }
        public boolean allowsCreatedSiblings(SettingCandidate candidate, List<WorkCharacter> collisions, Map<String, WorkCharacter> promoted) {
            String parent = parentRefs.get(candidate.getId());
            if (parent == null || !parent.startsWith("provisional-character:")) return false;
            Set<UUID> created = new HashSet<>();
            memberRefs.get(candidate.getId()).forEach(ref -> { if (promoted.containsKey(ref)) created.add(promoted.get(ref).getId()); });
            return collisions.stream().allMatch(character -> created.contains(character.getId()));
        }
    }
}
