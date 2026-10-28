package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisStateSource;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Component;

/** 일반 단일 회차의 입력 경로와 분리된 누적 실행 S0 capture다. */
@Component
@RequiredArgsConstructor
public class CharacterAnalysisStateSource implements AnalysisStateSource {

    private final WorkCharacterRepository characterRepository;
    private final CharacterSnapshotSourceRepository sourceRepository;
    private final CharacterSnapshotAccessor snapshotAccessor;
    private final CharacterAnalysisStateMapper mapper;
    private final SettingCandidateRepository candidateRepository;
    private final org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository factRepository;
    private final org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver supportingEvidence;

    @Override
    public String domain() {
        return "characters";
    }

    @Override
    public JsonNode capture(Work work) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var capture = supportingEvidence.begin(work);
        var discoveries = candidateRepository.findConfirmedDiscoveries(work.getId());
        for (WorkCharacter character : characterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(
                work.getId(), CharacterStatus.ACTIVE
        )) {
            ObjectNode state = captureCharacter(character, capture);
            mapper.addConfirmedIdentity(state, discoveries.stream()
                    .filter(candidate -> character.getId().equals(candidate.getMatchedCharacterId())).toList());
            result.set(CharacterAnalysisStateMapper.persistedRef(character.getId()), state);
        }
        return result;
    }
    public ObjectNode captureCharacter(WorkCharacter character) {
        return captureCharacter(character, supportingEvidence.begin(character.getWork()));
    }

    private ObjectNode captureCharacter(WorkCharacter character,
            org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.Capture capture) {
        Map<CharacterSnapshotSlot, List<CharacterFact>> facts = new LinkedHashMap<>();
        for (CharacterSnapshotSource source : sourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(character.getId())) {
            facts.computeIfAbsent(new CharacterSnapshotSlot(source.getFactType(), source.getFactKey()), ignored -> new ArrayList<>()).add(source.getSourceFact());
        }
        var entries = snapshotAccessor.read(character, facts);
        ObjectNode state = mapper.toState(character, entries, facts);
        state.put("currentSupportingEvidenceVersion", 1);
        for (var entry : entries.values()) {
            ObjectNode slot = (ObjectNode) state.path("slots").path(CharacterAnalysisStateMapper.slotKey(entry.slot()));
            slot.set("supportingSources", supportingEvidence.captureSlot(capture, character, entry.slot(), facts.getOrDefault(entry.slot(), List.of())));
        }
        var active = facts.values().stream().flatMap(List::stream).map(CharacterFact::getId).collect(java.util.stream.Collectors.toSet());
        var history = state.putArray("historyFacts");
        for (CharacterFact fact : factRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId())) {
            if (active.contains(fact.getId())) continue;
            ObjectNode row = history.addObject();
            row.put("factId", fact.getId() == null ? null : fact.getId().toString());
            SettingCandidate candidate = fact.getSettingCandidate();
            row.put("sourceCandidateId", candidate == null ? null : candidate.getId().toString());
            row.put("effectiveFromEpisodeNo", fact.getEffectiveFromEpisodeNo());
            row.put("factType", fact.getFactType().name()).put("factKey", fact.getFactKey()).put("factValue", fact.getFactValue());
            row.set("valueJson", fact.getValueJson() == null ? JsonNodeFactory.instance.nullNode() : fact.getValueJson().deepCopy());
            row.put("operation", candidate == null || candidate.getSuggestedOperation() == null ? null : candidate.getSuggestedOperation().name());
            row.put("temporalScope", candidate == null || candidate.getTemporalScope() == null ? null : candidate.getTemporalScope().name());
            row.set("removedSnapshotEntries", candidate == null || candidate.getRemovedSnapshotEntriesJson() == null
                    ? JsonNodeFactory.instance.arrayNode() : candidate.getRemovedSnapshotEntriesJson().deepCopy());
            ObjectNode origin = supportingEvidence.captureFact(capture, fact);
            row.set("narrativeTime", origin.path("narrativeTime").deepCopy());
            row.set("supportingSource", origin);
        }
        JsonNode current = character.getCurrentNarrativeTime();
        if (current != null && !current.isNull()) {
            UUID sourceId;
            try { sourceId = UUID.fromString(current.path("sourceCandidateId").asText()); }
            catch (IllegalArgumentException exception) { throw new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException(); }
            SettingCandidate source = candidateRepository.findByIdAndWorkId(sourceId, character.getWork().getId())
                    .orElseThrow(org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException::new);
            UUID sourceOwner = source.getMatchedCharacterId();
            if (sourceOwner == null && source.getProvisionalSubjectKey() != null) {
                String key = source.getProvisionalSubjectKey();
                UUID anchor = UUID.fromString(key.substring("provisional-character:".length()));
                var promoted = candidateRepository.findPromotedProvisionalCharacterIds(character.getWork().getId(), key, anchor,
                        org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED);
                if (promoted.size() == 1) sourceOwner = promoted.getFirst();
            }
            if (!character.getId().equals(sourceOwner)) throw new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException();
            ObjectNode frozen = current.deepCopy();
            ObjectNode origin = supportingEvidence.captureCandidate(capture, source);
            if ("VERIFIED".equals(origin.path("availability").asText())) {
                ObjectNode point = frozen.deepCopy(); point.remove("sourceCandidateId");
                if (!point.equals(origin.path("narrativeTime").path("referenceTime"))) throw new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException();
            }
            frozen.set("supportingSource", origin);
            state.set("currentNarrativeTime", frozen);
        }
        return state;
    }

}
