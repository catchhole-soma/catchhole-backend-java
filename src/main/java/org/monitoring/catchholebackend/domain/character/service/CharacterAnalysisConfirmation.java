package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.springframework.stereotype.Component;

/** 분석의 임시 입력을 사용자 확정 시점의 실제 설정과 선택된 선행 제안에 대조한다. */
@Component
@RequiredArgsConstructor
public class CharacterAnalysisConfirmation {

    private final WorkCharacterRepository characterRepository;
    private final SettingCandidateRepository candidateRepository;
    private final CharacterSnapshotSourceRepository sourceRepository;
    private final CharacterSnapshotAccessor accessor;
    private final org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository factRepository;

    public boolean hasCurrentContext(SettingCandidate candidate, List<SettingCandidate> earlierApplied) {
        if (candidate.isCharacterDiscovery()) {
            return true;
        }
        var batch = candidate.getCharacterComparisonBatch();
        if (batch == null || batch.getAnalysisContextSnapshotJson() == null || !candidate.isComparisonCompleted()) {
            return false;
        }
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> actual = loadActual(candidate);
        if (actual == null) {
            return false;
        }
        for (SettingCandidate earlier : earlierApplied) {
            if (!sameTarget(candidate, earlier) || earlier.isCharacterDiscovery()
                    || earlier.getCharacterComparisonBatch() != null
                    && earlier.getCharacterComparisonBatch().getId().equals(batch.getId())) {
                continue;
            }
            if (!earlier.isComparisonCompleted()) {
                return false;
            }
            apply(actual, earlier);
        }
        CharacterFactType factType = batch.getCanonicalFactType();
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> expected = new LinkedHashMap<>();
        for (JsonNode entry : batch.getAnalysisContextSnapshotJson().path("slots")) {
            if (factType.name().equals(entry.path("factType").asText())) {
                CharacterSnapshotEntry snapshot = accessor.entry(factType, entry.path("factKey").asText(),
                        entry.path("factValue").isNull() ? null : entry.path("factValue").asText(),
                        entry.path("valueJson").isNull() ? null : entry.path("valueJson"));
                expected.put(snapshot.slot(), snapshot);
            }
        }
        actual.entrySet().removeIf(entry -> entry.getKey().factType() != factType);
        if (!actual.keySet().equals(expected.keySet())) {
            return false;
        }
        return actual.entrySet().stream().allMatch(entry ->
                Objects.equals(entry.getValue().factValue(), expected.get(entry.getKey()).factValue())
                        && Objects.equals(entry.getValue().valueJson(), expected.get(entry.getKey()).valueJson()));
    }

    public boolean isDependencyConfirmed(SettingCandidate candidate, UUID dependencyId) {
        UUID characterId = resolvedCharacterId(candidate);
        return characterId != null && candidateRepository.findByIdAndWorkId(dependencyId, candidate.getWork().getId())
                .filter(dependency -> dependency.getReviewStatus() == SettingCandidateReviewStatus.CONFIRMED)
                .filter(dependency -> dependency.getConfirmedApplicationMode()
                        == org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode.APPLY_PROPOSAL)
                .filter(dependency -> characterId.equals(dependency.getMatchedCharacterId()))
                .isPresent();
    }

    public UUID resolvedCharacterId(SettingCandidate candidate) {
        if (candidate.getMatchedCharacterId() != null || candidate.getProvisionalSubjectKey() == null) {
            return candidate.getMatchedCharacterId();
        }
        String subjectKey = candidate.getProvisionalSubjectKey();
        UUID anchorId = UUID.fromString(subjectKey.substring("provisional-character:".length()));
        List<UUID> promoted = candidateRepository.findPromotedProvisionalCharacterIds(
                candidate.getWork().getId(), subjectKey, anchorId, SettingCandidateReviewStatus.CONFIRMED);
        if (promoted.size() > 1) {
            throw new org.monitoring.catchholebackend.global.exception.AppException(
                    org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        return promoted.isEmpty() ? null : promoted.getFirst();
    }

    public Map<CharacterSnapshotSlot, CharacterSnapshotEntry> loadActual(SettingCandidate candidate) {
        UUID characterId = resolvedCharacterId(candidate);
        if (characterId == null) {
            return candidate.getProvisionalSubjectKey() == null ? null : new LinkedHashMap<>();
        }
        WorkCharacter character = characterRepository.findByIdAndWorkIdForUpdate(
                characterId, candidate.getWork().getId()).orElse(null);
        if (character == null || character.getStatus() != CharacterStatus.ACTIVE) {
            return null;
        }
        Map<CharacterSnapshotSlot, List<CharacterFact>> sources = new LinkedHashMap<>();
        sourceRepository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(character.getId())
                .forEach(source -> sources.computeIfAbsent(new CharacterSnapshotSlot(
                        source.getFactType(), source.getFactKey()), ignored -> new ArrayList<>()).add(source.getSourceFact()));
        return accessor.read(character, sources);
    }

    /** 늦은 사용자 확정의 현재값 보호. 없는 현재값도 과거 적용 흔적이 있으면 복원하지 않는다. */
    public boolean keepLateReviewInHistory(SettingCandidate candidate, CharacterFactType type, String key) {
        UUID id = resolvedCharacterId(candidate);
        if (id == null) return false;
        WorkCharacter character = characterRepository.findByIdAndWorkIdForUpdate(id, candidate.getWork().getId()).orElse(null);
        if (character == null || character.getStatus() != CharacterStatus.ACTIVE) throw new org.monitoring.catchholebackend.global.exception.AppException(
                org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID);
        var slot = new CharacterSnapshotSlot(type, key);
        int episode = candidate.getEpisode() == null ? -1 : candidate.getEpisode().getEpisodeNo();
        if (!accessor.read(character).containsKey(slot)) {
            var history = factRepository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(id, type, key);
            return history.stream().anyMatch(fact -> fact.getSettingCandidate() == null
                    || fact.getSettingCandidate().getSuggestedOperation() != CharacterFactOperation.HISTORY_ONLY
                    && fact.getSettingCandidate().getConfirmedApplicationMode()
                        != org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode.HISTORY_ONLY);
        }
        var sources = sourceRepository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderBySourceOrderAsc(id, type, key);
        if (sources.isEmpty() || episode < 1) return true;
        return sources.stream().anyMatch(source -> {
            var fact = source.getSourceFact();
            var origin = fact.getSettingCandidate();
            Integer number = fact.getEffectiveFromEpisodeNo();
            if (number == null && fact.getSourceEpisode() != null) number = fact.getSourceEpisode().getEpisodeNo();
            if (number == null && origin != null && origin.getEpisode() != null) number = origin.getEpisode().getEpisodeNo();
            return origin == null || origin.isUserModified() || number == null || number >= episode;
        });
    }

    private boolean sameTarget(SettingCandidate left, SettingCandidate right) {
        return Objects.equals(left.getMatchedCharacterId(), right.getMatchedCharacterId())
                && Objects.equals(left.getProvisionalSubjectKey(), right.getProvisionalSubjectKey());
    }

    private void apply(Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot, SettingCandidate candidate) {
        CharacterFactOperation operation = candidate.getSuggestedOperation();
        if (operation != CharacterFactOperation.ADD && operation != CharacterFactOperation.UPDATE
                && operation != CharacterFactOperation.MERGE && operation != CharacterFactOperation.REMOVE) {
            return;
        }
        JsonNode removals = candidate.getRemovedSnapshotEntriesJson();
        if (removals != null) {
            removals.forEach(removed -> snapshot.remove(new CharacterSnapshotSlot(
                    CharacterFactType.valueOf(removed.path("factType").asText()), removed.path("factKey").asText())));
        }
        if (operation == CharacterFactOperation.REMOVE && candidate.getComparisonTargetFactType() != null
                && candidate.getComparisonTargetFactKey() != null) {
            snapshot.remove(new CharacterSnapshotSlot(candidate.getComparisonTargetFactType(),
                    candidate.getComparisonTargetFactKey().trim()));
        }
        if (operation != CharacterFactOperation.REMOVE) {
            CharacterSnapshotEntry entry = accessor.entry(candidate.getComparisonTargetFactType(),
                    candidate.getResolvedCanonicalFactKey(), candidate.getProposedFactValue(),
                    candidate.getProposedValueJson());
            snapshot.put(entry.slot(), entry);
        }
    }
}
