package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisLiveSubjectSource;
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

/**
 * 일반 단일 회차의 입력 경로와 분리된 누적 실행 S0 capture다. #387부터 자동 누적 format 2의 대상별·현재값 live 읽기와 지문도
 * 같은 규칙으로 제공한다.
 */
@Component
@RequiredArgsConstructor
public class CharacterAnalysisStateSource implements AnalysisStateSource, AnalysisLiveSubjectSource {

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
        return capture(work, characterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(work.getId(), CharacterStatus.ACTIVE),
                candidateRepository.findConfirmedDiscoveries(work.getId()), supportingEvidence.begin(work), false);
    }

    /** #387 요청한 ACTIVE 인물만 capture와 같은 규칙으로 읽는다. 다른 인물의 엔티티·Fact·후보는 읽지 않는다. */
    @Override
    public ObjectNode captureSubjects(Work work, Collection<String> refs) {
        List<UUID> ids = new ArrayList<>();
        for (String ref : refs) persistedId(ref).ifPresent(ids::add);
        if (ids.isEmpty()) return JsonNodeFactory.instance.objectNode();
        return capture(work, characterRepository.findAllByWorkIdAndStatusAndIdInOrderByCreatedAtDesc(work.getId(), CharacterStatus.ACTIVE, ids),
                candidateRepository.findConfirmedDiscoveriesForCharacters(work.getId(), ids), supportingEvidence.beginLazily(work), false);
    }

    /** #387 claim 응답용 현재값. 이력 Fact와 slot 근거 사본을 만들지 않으며 그 밖의 값은 capture와 같다. */
    @Override
    public ObjectNode captureCurrentValues(Work work) {
        return capture(work, characterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(work.getId(), CharacterStatus.ACTIVE),
                candidateRepository.findConfirmedDiscoveries(work.getId()), supportingEvidence.begin(work), true);
    }

    @Override
    public ObjectNode toCurrentValues(ObjectNode capturedState) {
        ObjectNode result = capturedState.deepCopy();
        result.remove("historyFacts");
        result.path("slots").forEach(slot -> ((ObjectNode) slot).remove("supportingSources"));
        return result;
    }

    /**
     * #387 지문 행: ACTIVE 인물마다 [id, 이름, snapshotVersion, Fact 수, 가장 늦은 Fact 생성 시각, 확정 인물 발견 id 목록].
     * 이름만 바꾸는 updateBasicInfo, 현재값을 바꾸지 않는 이력 Fact 추가, 별칭·식별 근거를 바꾸는 인물 발견 확정은
     * snapshotVersion을 올리지 않으므로 각각 따로 넣는다. 보관·복구·생성은 id 집합을 바꾼다.
     */
    @Override
    public ArrayNode fingerprintRows(UUID workId) {
        Map<UUID, org.monitoring.catchholebackend.domain.character.repository.CharacterFactStamp> facts = new java.util.HashMap<>();
        factRepository.findActiveFactStamps(workId).forEach(stamp -> facts.put(stamp.characterId(), stamp));
        Map<UUID, List<String>> discoveries = new java.util.HashMap<>();
        candidateRepository.findConfirmedDiscoveryStamps(workId).forEach(stamp ->
                discoveries.computeIfAbsent(stamp.characterId(), ignored -> new ArrayList<>()).add(stamp.candidateId().toString()));
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        characterRepository.findSettingVersions(workId, CharacterStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(row -> row.id().toString()))
                .forEach(row -> {
                    var stamp = facts.get(row.id());
                    ArrayNode values = rows.addArray().add(row.id().toString()).add(row.name()).add(row.snapshotVersion())
                            .add(stamp == null ? 0 : stamp.factCount());
                    values.add(stamp == null || stamp.latestCreatedAt() == null ? null : stamp.latestCreatedAt().toString());
                    ArrayNode ids = values.addArray();
                    discoveries.getOrDefault(row.id(), List.of()).stream().sorted().forEach(ids::add);
                });
        return rows;
    }

    /**
     * #387 인물 식별 색인. capture의 인물마다 actualCharacterId·provisionalSubjectKey(null)·이름·별칭을 고른 값과 같다.
     * 별칭은 addConfirmedIdentity와 같은 규칙(trim, 빈 값·대표 이름 제외, 중복 제거, 후보 생성 순)이다. 순서는 생성 역순, 같은 시각이면 id다.
     */
    @Override
    public ObjectNode identities(UUID workId) {
        Map<UUID, List<String>> names = new java.util.HashMap<>();
        candidateRepository.findConfirmedDiscoveryAliases(workId).forEach(alias ->
                names.computeIfAbsent(alias.characterId(), ignored -> new ArrayList<>()).add(alias.entityName()));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (var row : characterRepository.findSettingVersions(workId, CharacterStatus.ACTIVE)) {
            ObjectNode identity = result.putObject(CharacterAnalysisStateMapper.persistedRef(row.id()));
            identity.put("actualCharacterId", row.id().toString());
            identity.putNull("provisionalSubjectKey");
            identity.put("name", row.name());
            ArrayNode aliases = identity.putArray("aliases");
            names.getOrDefault(row.id(), List.of()).stream().filter(java.util.Objects::nonNull).map(String::trim)
                    .filter(alias -> !alias.isBlank() && !alias.equals(row.name())).distinct().forEach(aliases::add);
        }
        return result;
    }

    private ObjectNode capture(Work work, List<WorkCharacter> characters, List<SettingCandidate> discoveries,
            org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.Capture capture,
            boolean currentValuesOnly) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (WorkCharacter character : characters) {
            ObjectNode state = captureCharacter(character, capture, currentValuesOnly);
            var confirmed = discoveries.stream().filter(candidate -> character.getId().equals(candidate.getMatchedCharacterId())).toList();
            var identityEvidence = JsonNodeFactory.instance.arrayNode();
            for (SettingCandidate discovery : confirmed) identityEvidence.addAll(supportingEvidence.captureRetainedIdentityEvidence(capture, discovery));
            mapper.addConfirmedIdentity(state, confirmed, identityEvidence, job -> supportingEvidence.source(capture, job));
            result.set(CharacterAnalysisStateMapper.persistedRef(character.getId()), state);
        }
        return result;
    }

    private static java.util.Optional<UUID> persistedId(String ref) {
        String prefix = "character:";
        if (ref == null || !ref.startsWith(prefix)) return java.util.Optional.empty();
        try {
            UUID id = UUID.fromString(ref.substring(prefix.length()));
            return CharacterAnalysisStateMapper.persistedRef(id).equals(ref) ? java.util.Optional.of(id) : java.util.Optional.empty();
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    public ObjectNode captureCharacter(WorkCharacter character) {
        return captureCharacter(character, supportingEvidence.begin(character.getWork()), false);
    }

    private ObjectNode captureCharacter(WorkCharacter character,
            org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.Capture capture,
            boolean currentValuesOnly) {
        Map<CharacterSnapshotSlot, List<CharacterFact>> facts = new LinkedHashMap<>();
        for (CharacterSnapshotSource source : sourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(character.getId())) {
            facts.computeIfAbsent(new CharacterSnapshotSlot(source.getFactType(), source.getFactKey()), ignored -> new ArrayList<>()).add(source.getSourceFact());
        }
        var entries = snapshotAccessor.read(character, facts);
        ObjectNode state = mapper.toState(character, entries, facts, job -> supportingEvidence.source(capture, job));
        state.put("currentSupportingEvidenceVersion", 1);
        if (currentValuesOnly) {
            // claim 응답은 이력과 slot 근거 사본을 읽지 않는다. 대표 서사 시점의 근거는 모델 값이 쓰므로 아래에서 그대로 만든다.
            state.remove("historyFacts");
            captureCurrentNarrativeTime(character, capture, state);
            return state;
        }
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
        captureCurrentNarrativeTime(character, capture, state);
        return state;
    }

    private void captureCurrentNarrativeTime(WorkCharacter character,
            org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.Capture capture,
            ObjectNode state) {
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
            ObjectNode origin = supportingEvidence.captureRetainedCandidate(capture, source);
            if (!origin.path("narrativeTime").path("referenceTime").isMissingNode()
                    && !origin.path("narrativeTime").path("referenceTime").isNull()) {
                ObjectNode point = frozen.deepCopy(); point.remove("sourceCandidateId");
                if (!point.equals(origin.path("narrativeTime").path("referenceTime"))) throw new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException();
            }
            // Preserve the exact expected-current value for approval/replay. The response mapper
            // removes uncertified quotes from the model-facing copy without changing this state.
            frozen.set("supportingSource", origin);
            state.set("currentNarrativeTime", frozen);
        }
    }

}
