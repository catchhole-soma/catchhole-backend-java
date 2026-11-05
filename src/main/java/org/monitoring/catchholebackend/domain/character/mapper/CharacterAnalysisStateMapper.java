package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.springframework.stereotype.Component;

/** 실행 시작 snapshot과 검증된 제안의 값을 후보의 이후 수정과 독립된 JSON으로 조립한다. */
@Component
public class CharacterAnalysisStateMapper {

    public static String persistedRef(UUID characterId) {
        return "character:" + Objects.requireNonNull(characterId);
    }

    public static String provisionalRef(UUID discoveryCandidateId) {
        return "provisional-character:" + Objects.requireNonNull(discoveryCandidateId);
    }

    public static String slotKey(CharacterSnapshotSlot slot) {
        return slot.factType().name() + ":" + slot.factKey();
    }

    public ObjectNode toState(
            WorkCharacter character,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            Map<CharacterSnapshotSlot, List<CharacterFact>> sourceFacts
    ) {
        return toState(character, entries, sourceFacts, null);
    }

    public ObjectNode toState(WorkCharacter character,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries,
            Map<CharacterSnapshotSlot, List<CharacterFact>> sourceFacts,
            java.util.function.Function<org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob, org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView> sourceLookup) {
        ObjectNode state = identity(character.getName(), character.getSnapshotVersion());
        state.set("currentNarrativeTime", copy(character.getCurrentNarrativeTime()));
        state.put("actualCharacterId", character.getId().toString());
        state.putNull("provisionalSubjectKey");
        state.set("protectedSlots", character.getManuallyEditedSlots().deepCopy());
        ObjectNode slots = state.withObject("slots");
        entries.values().stream()
                .sorted(Comparator.comparing(entry -> slotKey(entry.slot())))
                .forEach(entry -> {
                    List<CharacterFact> sources = sourceFacts.getOrDefault(entry.slot(), List.of());
                    ObjectNode slot = toSlot(entry, "CONFIRMED", null, List.of());
                    slot.set("provenance", currentProvenance(character, entry, sources, sourceLookup));
                    slots.set(slotKey(entry.slot()), slot);
                });
        return state;
    }

    /**
     * #381 검수 live 설정 행. {@link #toState}와 같은 key 순서·slot 값이지만 provenance·근거·이력은 넣지 않는다
     * (후보·Fact·Job을 읽지 않기 위함이며 AI 모델 투영이 버리는 값이다).
     */
    public ObjectNode toCurrentValueState(WorkCharacter character, List<String> aliases,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> entries) {
        ObjectNode state = identity(character.getName(), character.getSnapshotVersion());
        state.set("currentNarrativeTime", copy(character.getCurrentNarrativeTime()));
        state.put("actualCharacterId", character.getId().toString());
        state.putNull("provisionalSubjectKey");
        state.set("protectedSlots", character.getManuallyEditedSlots().deepCopy());
        state.remove("historyFacts");
        ArrayNode names = state.putArray("aliases");
        aliases.forEach(names::add);
        ObjectNode slots = state.withObject("slots");
        entries.values().stream()
                .sorted(Comparator.comparing(entry -> slotKey(entry.slot())))
                .forEach(entry -> {
                    ObjectNode slot = toSlot(entry, "CONFIRMED", null, List.of());
                    slot.remove("provenance");
                    slots.set(slotKey(entry.slot()), slot);
                });
        return state;
    }

    public static ObjectNode currentProvenance(WorkCharacter character, CharacterSnapshotEntry entry, List<CharacterFact> sources) {
        return currentProvenance(character, entry, sources, null);
    }

    private static ObjectNode currentProvenance(WorkCharacter character, CharacterSnapshotEntry entry,
            List<CharacterFact> sources, java.util.function.Function<org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob, org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView> sourceLookup) {
        Integer episodeNo = sources.stream().map(CharacterFact::getSourceEpisode).filter(Objects::nonNull)
                .map(episode -> episode.getEpisodeNo()).max(Integer::compareTo).orElse(null);
        ObjectNode source = provenance("CONFIRMED", episodeNo, sources.stream().map(CharacterFact::getSettingCandidate)
                .filter(Objects::nonNull).map(SettingCandidate::getId).distinct().toList());
        source.put("reviewSource", !sources.isEmpty() && sources.stream().allMatch(fact -> fact.getSettingCandidate() != null
                && fact.getSettingCandidate().isReviewedAutomatically()) ? "AUTOMATIC" : "HUMAN");
        boolean protectedValue = character.isManuallyEditedSlot(entry.slot().factType(), entry.slot().factKey())
                || sources.stream().anyMatch(fact -> fact.getSettingCandidate() == null || fact.getSettingCandidate().hasUserEditedContent());
        source.put("currentValueProtected", protectedValue);
        ArrayNode times = source.putArray("currentSourceTimes");
        if (sources.isEmpty()) times.add(org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.toJson(
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(null, null, null)));
        for (CharacterFact fact : sources) {
            times.add(org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.toJson(
                    sourceLookup == null
                            ? org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(fact.getExtractedByJob(),
                                    fact.getEffectiveFromEpisodeNo(), fact.getSettingCandidate() == null ? null : fact.getSettingCandidate().getTemporalScope())
                            : org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.fromSource(sourceLookup.apply(fact.getExtractedByJob()),
                                    fact.getEffectiveFromEpisodeNo(), fact.getSettingCandidate() == null ? null : fact.getSettingCandidate().getTemporalScope())));
        }
        sources.stream().map(CharacterFact::getSettingCandidate).filter(Objects::nonNull)
                .max(Comparator.comparing(SettingCandidate::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .ifPresent(candidate -> {
                    putSource(source, candidate.getAnalysisJob(), sourceLookup);
                    source.put("effectiveFromEpisodeNo", candidate.getEffectiveFromEpisodeNo());
                });
        return source;
    }

    public static List<org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterProtectedSlotPayload> protectedSlots(JsonNode state) {
        List<org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterProtectedSlotPayload> result = new java.util.ArrayList<>();
        for (JsonNode slot : state.path("protectedSlots")) {
            result.add(new org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterProtectedSlotPayload(
                    org.monitoring.catchholebackend.domain.character.type.CharacterFactType.valueOf(slot.path("factType").asText()), slot.path("factKey").asText()));
        }
        return List.copyOf(result);
    }

    public static List<org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterProtectedSlotPayload> protectedSlots(WorkCharacter character) {
        ObjectNode state = JsonNodeFactory.instance.objectNode();
        if (character != null) state.set("protectedSlots", character.getManuallyEditedSlots());
        return protectedSlots(state);
    }

    public void addConfirmedIdentity(ObjectNode state, List<SettingCandidate> discoveries, ArrayNode verifiedEvidence) {
        addConfirmedIdentity(state, discoveries, verifiedEvidence, null);
    }

    public void addConfirmedIdentity(ObjectNode state, List<SettingCandidate> discoveries, ArrayNode verifiedEvidence,
            java.util.function.Function<org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob, org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView> sourceLookup) {
        ArrayNode aliases = state.putArray("aliases");
        discoveries.stream().map(SettingCandidate::getEntityName).filter(Objects::nonNull)
                .map(String::trim).filter(name -> !name.isBlank() && !name.equals(state.path("name").asText()))
                .distinct().forEach(aliases::add);
        ArrayNode evidence = state.putArray("identityEvidence");
        java.util.Set<JsonNode> seen = new java.util.LinkedHashSet<>();
        verifiedEvidence.forEach(span -> { if (seen.add(span)) evidence.add(span.deepCopy()); });
        Integer episode = discoveries.stream().map(SettingCandidate::getEpisode).filter(Objects::nonNull)
                .map(source -> source.getEpisodeNo()).max(Integer::compareTo).orElse(null);
        ObjectNode source = provenance("CONFIRMED", episode,
                discoveries.stream().map(SettingCandidate::getId).toList());
        source.put("reviewSource", !discoveries.isEmpty()
                && discoveries.stream().allMatch(SettingCandidate::isReviewedAutomatically) ? "AUTOMATIC" : "HUMAN");
        discoveries.stream().findFirst().ifPresent(candidate -> putSource(source, candidate.getAnalysisJob(), sourceLookup));
        state.set("provenance", source);
    }

    public ObjectNode toProvisionalState(UUID discoveryCandidateId, String name, Integer episodeNo) {
        ObjectNode state = identity(name, 0);
        state.putNull("actualCharacterId");
        state.put("provisionalSubjectKey", provisionalRef(discoveryCandidateId));
        state.set("provenance", provenance("PROVISIONAL", episodeNo, List.of(discoveryCandidateId)));
        return state;
    }

    public ObjectNode toSlot(
            CharacterSnapshotEntry entry,
            String confirmationStatus,
            Integer sourceEpisodeNo,
            List<UUID> sourceCandidateIds
    ) {
        ObjectNode slot = JsonNodeFactory.instance.objectNode();
        slot.put("factType", entry.slot().factType().name());
        slot.put("factKey", entry.slot().factKey());
        slot.put("factValue", entry.factValue());
        slot.set("valueJson", copy(entry.valueJson()));
        slot.set("provenance", provenance(confirmationStatus, sourceEpisodeNo, sourceCandidateIds));
        return slot;
    }

    public ObjectNode toSlot(CharacterSnapshotEntry entry, String status, Integer episode, List<UUID> ids,
            org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job) {
        ObjectNode node = toSlot(entry, status, episode, ids);
        org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put((ObjectNode) node.path("provenance"), job);
        return node;
    }

    private static void putSource(ObjectNode node,
            org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job,
            java.util.function.Function<org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob, org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView> sourceLookup) {
        if (sourceLookup == null) org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(node, job);
        else org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(node, sourceLookup.apply(job));
    }

    private ObjectNode identity(String name, long version) {
        ObjectNode state = JsonNodeFactory.instance.objectNode();
        state.put("name", Objects.requireNonNull(name));
        state.putArray("aliases");
        state.putArray("protectedSlots");
        state.put("snapshotVersion", version);
        state.putNull("currentNarrativeTime");
        state.putArray("historyFacts");
        state.putObject("slots");
        state.putObject("absences");
        return state;
    }

    private static ObjectNode provenance(String status, Integer episodeNo, List<UUID> sourceIds) {
        ObjectNode provenance = JsonNodeFactory.instance.objectNode();
        provenance.put("confirmationStatus", status);
        provenance.put("sourceEpisodeNo", episodeNo);
        ArrayNode sources = provenance.putArray("sourceCandidateIds");
        sourceIds.forEach(id -> sources.add(id.toString()));
        return provenance;
    }

    private JsonNode copy(JsonNode value) {
        return value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy();
    }
}
