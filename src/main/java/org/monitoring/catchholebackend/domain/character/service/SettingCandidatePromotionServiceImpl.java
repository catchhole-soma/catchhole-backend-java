package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidatePromotionMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingValueValidator;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSourceManager;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaMatch;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateGroupNameNormalizer;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingCandidatePromotionServiceImpl implements SettingCandidatePromotionService {

    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterFactRepository characterFactRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final EpisodeRepository episodeRepository;
    private final WorkRepository workRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final AiTokenService aiTokenService;
    private final SettingCandidatePromotionMapper promotionMapper;
    private final SettingCandidateSchemaResolver schemaResolver;
    private final CharacterSnapshotAccessor snapshotAccessor;
    private final CharacterSnapshotSourceManager snapshotSourceManager;
    private final CharacterSettingValueValidator valueValidator;

    @Override
    @Transactional
    public void promote(
            SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode
    ) {
        promote(candidate, applicationMode, new HashSet<>(), Map.of());
    }

    @Override
    @Transactional
    public void promoteGroup(List<SettingCandidateGroupPromotion> promotions) {
        Set<UUID> versionedCharacterIds = new HashSet<>();
        Map<UUID, Long> initialSnapshotVersions = captureInitialSnapshotVersions(promotions);
        validateGroupRemovalSnapshotVersions(promotions, initialSnapshotVersions);
        promotions.forEach(promotion -> promote(
                promotion.candidate(),
                promotion.applicationMode(),
                versionedCharacterIds,
                initialSnapshotVersions
        ));
    }

    private void promote(
            SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode,
            Set<UUID> versionedCharacterIds,
            Map<UUID, Long> initialSnapshotVersions
    ) {
        if (candidate.isCharacterDiscovery()) {
            ResolvedCharacter resolved = resolveCharacterForPromotion(candidate);
            updateFirstAppearance(resolved.character(), candidate.getEpisode());
            return;
        }

        SettingCandidateSchemaMatch schemaMatch = resolveSchema(candidate);
        valueValidator.validateCandidate(
                candidate,
                schemaMatch.matchedSchema().getFactType(),
                schemaMatch.matchedSchema().getValueType()
        );
        validateActiveStatusBeforeCharacterResolution(
                candidate,
                schemaMatch.matchedSchema().getFactType(),
                applicationMode
        );
        ResolvedCharacter resolved = resolveCharacterForPromotion(candidate);
        long removalSnapshotVersion = initialSnapshotVersions.getOrDefault(
                resolved.character().getId(),
                resolved.character().getSnapshotVersion()
        );
        promoteSetting(
                candidate,
                applicationMode,
                schemaMatch,
                resolved,
                versionedCharacterIds,
                removalSnapshotVersion
        );
    }

    @Override
    @Transactional
    public void promoteNewCharacterGroup(List<SettingCandidateGroupPromotion> promotions) {
        if (promotions.isEmpty()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }
        SettingCandidate representative = promotions.getFirst().candidate();
        UUID workId = representative.getWork().getId();
        String characterName = promotionMapper.toCharacterName(representative);
        boolean invalidGroup = promotions.stream()
                .map(SettingCandidateGroupPromotion::candidate)
                .anyMatch(candidate -> !candidate.getWork().getId().equals(workId)
                        || candidate.getMatchStatus() != SettingCandidateMatchStatus.UNRESOLVED
                        || candidate.getMatchedCharacterId() != null
                        || !SettingCandidateGroupNameNormalizer.toGroupKey(candidate.getEntityName())
                        .equals(SettingCandidateGroupNameNormalizer.toGroupKey(characterName)));
        if (invalidGroup) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        if (findActiveCharacterByGroupName(workId, characterName).isPresent()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        if (existsCharacterByGroupName(workId, characterName)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }

        promotions.stream()
                .filter(promotion -> !promotion.candidate().isCharacterDiscovery())
                .forEach(promotion -> {
                    SettingCandidate candidate = promotion.candidate();
                    SettingCandidateSchemaMatch schemaMatch = resolveSchema(candidate);
                    valueValidator.validateCandidate(
                            candidate,
                            schemaMatch.matchedSchema().getFactType(),
                            schemaMatch.matchedSchema().getValueType()
                    );
                    validateActiveStatusBeforeCharacterResolution(
                            candidate,
                            schemaMatch.matchedSchema().getFactType(),
                            promotion.applicationMode()
                    );
                });

        WorkCharacter character = workCharacterRepository.save(promotionMapper.toWorkCharacter(representative));
        ResolvedCharacter resolved = new ResolvedCharacter(character, true, false);
        Set<UUID> versionedCharacterIds = new HashSet<>();
        for (SettingCandidateGroupPromotion promotion : promotions) {
            SettingCandidate candidate = promotion.candidate();
            if (!candidate.confirm()) {
                continue;
            }
            candidate.matchPromotedNewCharacter(character);
            if (candidate.isCharacterDiscovery()) {
                updateFirstAppearance(character, candidate.getEpisode());
            } else {
                promoteSetting(
                        candidate,
                        promotion.applicationMode(),
                        resolveSchema(candidate),
                        resolved,
                        versionedCharacterIds,
                        character.getSnapshotVersion()
                );
            }
        }
        // 현재 묶음 밖에도 같은 이름의 미검토 후보가 있다면 새 캐릭터에 연결하고 재비교를 예약한다.
        matchPendingUnresolvedSiblings(workId, characterName, character, true);
    }

    private void promoteSetting(
            SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode,
            SettingCandidateSchemaMatch schemaMatch,
            ResolvedCharacter resolved,
            Set<UUID> versionedCharacterIds,
            long removalSnapshotVersion
    ) {
        CharacterFactType factType = schemaMatch.matchedSchema().getFactType();
        String factKey = candidate.getResolvedCanonicalFactKey() == null
                || candidate.getResolvedCanonicalFactKey().isBlank()
                ? schemaMatch.factKey()
                : candidate.getResolvedCanonicalFactKey().trim();
        valueValidator.validateCandidate(candidate, factType, schemaMatch.matchedSchema().getValueType());
        JsonNode normalizedCandidateValue = valueValidator.resolveCandidateValue(
                candidate,
                factType,
                schemaMatch.matchedSchema().getValueType()
        );

        WorkCharacter character = resolved.character();
        updateFirstAppearance(character, candidate.getEpisode());

        CharacterFactOperation operation = resolved.newlyCreated()
                ? CharacterFactOperation.ADD
                : candidate.getSuggestedOperation();
        validatePromotionPolicy(candidate, resolved, operation, applicationMode);
        validateRemovalSnapshotVersion(
                candidate,
                operation,
                applicationMode,
                removalSnapshotVersion
        );
        validateActiveStatusPromotion(
                candidate,
                factType,
                operation,
                applicationMode,
                resolved.newlyCreated() ? normalizedCandidateValue : candidate.getProposedValueJson()
        );

        CharacterFact newFact = characterFactRepository.saveAndFlush(
                promotionMapper.toCharacterFact(
                        candidate,
                        character,
                        factType,
                        factKey,
                        normalizedCandidateValue
                )
        );
        if (applicationMode == CharacterFactConfirmApplicationMode.HISTORY_ONLY
                || operation == CharacterFactOperation.HISTORY_ONLY) {
            return;
        }

        CharacterSnapshotSlot targetSlot = new CharacterSnapshotSlot(factType, factKey);
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot = snapshotAccessor.read(
                character,
                snapshotSourceManager.findSourceFactsBySlot(character)
        );
        if (operation == CharacterFactOperation.REMOVE) {
            List<CharacterSnapshotSlot> removedSlots = resolveRemovalSlotsForPromotion(
                    candidate,
                    snapshot,
                    targetSlot
            );
            removedSlots.forEach(snapshot::remove);
            snapshotSourceManager.removeSources(character, removedSlots);
            replaceSnapshotOncePerCharacter(character, snapshot, versionedCharacterIds);
            return;
        }
        validateComparedTarget(candidate, resolved, targetSlot);

        JsonNode proposedValue = resolved.newlyCreated()
                ? normalizedCandidateValue
                : candidate.getProposedValueJson();
        String proposedFactValue = resolved.newlyCreated()
                ? candidate.getAttributeValue()
                : candidate.getProposedFactValue();
        if (!resolved.newlyCreated()
                && (proposedFactValue == null || proposedFactValue.isBlank())) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
        valueValidator.validateProposal(
                proposedValue,
                proposedFactValue,
                factType,
                schemaMatch.matchedSchema().getValueType()
        );

        List<CharacterSnapshotSlot> removedSlots = resolved.newlyCreated()
                ? List.of()
                : parseRemovedSlots(candidate.getRemovedSnapshotEntriesJson(), snapshot, targetSlot);
        removedSlots.forEach(snapshot::remove);
        snapshotSourceManager.removeSources(character, removedSlots);

        snapshot.put(
                targetSlot,
                snapshotAccessor.entry(
                        factType,
                        factKey,
                        proposedFactValue == null ? null : proposedFactValue.trim(),
                        proposedValue
                )
        );
        if (operation == CharacterFactOperation.MERGE) {
            snapshotSourceManager.mergeSource(character, targetSlot, newFact);
        } else {
            snapshotSourceManager.replaceSources(character, targetSlot, List.of(newFact));
        }

        replaceSnapshotOncePerCharacter(character, snapshot, versionedCharacterIds);
    }

    private void replaceSnapshotOncePerCharacter(
            WorkCharacter character,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot,
            Set<UUID> versionedCharacterIds
    ) {
        // 묶음 안에서 여러 slot을 갱신해도 외부에 보이는 snapshot version은 캐릭터별 한 번만 증가한다.
        boolean incrementSnapshotVersion = versionedCharacterIds.add(character.getId());
        snapshotAccessor.replace(character, snapshot, true, incrementSnapshotVersion);
    }

    private SettingCandidateSchemaMatch resolveSchema(SettingCandidate candidate) {
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(
                candidate.getWork().getId()
        );
        SettingCandidateSchemaMatch schemaMatch = schemaResolver.resolve(
                candidate.getAttributeName(),
                candidate.getValueType(),
                schemas
        );
        validateMergePolicy(schemaMatch.matchedSchema().getMergePolicy());
        return schemaMatch;
    }

    private void validatePromotionPolicy(
            SettingCandidate candidate,
            ResolvedCharacter resolved,
            CharacterFactOperation operation,
            CharacterFactConfirmApplicationMode applicationMode
    ) {
        if (resolved.reusedExistingForUnresolved()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        if (resolved.newlyCreated()) {
            return;
        }
        if (!candidate.isComparisonCompleted() || operation == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        if (applicationMode == CharacterFactConfirmApplicationMode.HISTORY_ONLY) {
            if (operation == CharacterFactOperation.EXCLUDE) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
            }
            return;
        }
        if (operation == CharacterFactOperation.EXCLUDE
                || operation == CharacterFactOperation.REVIEW_REQUIRED) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
    }

    private void validateRemovalSnapshotVersion(
            SettingCandidate candidate,
            CharacterFactOperation operation,
            CharacterFactConfirmApplicationMode applicationMode,
            long expectedSnapshotVersion
    ) {
        if (applicationMode == CharacterFactConfirmApplicationMode.HISTORY_ONLY) {
            return;
        }
        JsonNode removals = candidate.getRemovedSnapshotEntriesJson();
        boolean removesSnapshot = operation == CharacterFactOperation.REMOVE
                || removals != null && removals.isArray() && !removals.isEmpty();
        if (removesSnapshot
                && !Objects.equals(
                candidate.getComparisonBaseSnapshotVersion(),
                expectedSnapshotVersion
        )) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE);
        }
    }

    private Map<UUID, Long> captureInitialSnapshotVersions(
            List<SettingCandidateGroupPromotion> promotions
    ) {
        Map<UUID, Long> versions = new LinkedHashMap<>();
        for (SettingCandidateGroupPromotion promotion : promotions) {
            SettingCandidate candidate = promotion.candidate();
            UUID characterId = candidate.getMatchedCharacterId();
            if (characterId == null || versions.containsKey(characterId)) {
                continue;
            }
            WorkCharacter character = getMatchedCharacter(candidate);
            versions.put(characterId, character.getSnapshotVersion());
        }
        return versions;
    }

    private void validateGroupRemovalSnapshotVersions(
            List<SettingCandidateGroupPromotion> promotions,
            Map<UUID, Long> initialSnapshotVersions
    ) {
        for (SettingCandidateGroupPromotion promotion : promotions) {
            if (promotion.applicationMode() == CharacterFactConfirmApplicationMode.HISTORY_ONLY) {
                continue;
            }
            SettingCandidate candidate = promotion.candidate();
            JsonNode removals = candidate.getRemovedSnapshotEntriesJson();
            boolean removesSnapshot = candidate.getSuggestedOperation() == CharacterFactOperation.REMOVE
                    || removals != null && removals.isArray() && !removals.isEmpty();
            Long initialVersion = initialSnapshotVersions.get(candidate.getMatchedCharacterId());
            if (removesSnapshot
                    && (initialVersion == null
                    || !Objects.equals(candidate.getComparisonBaseSnapshotVersion(), initialVersion))) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_STALE);
            }
        }
    }

    private void validateActiveStatusPromotion(
            SettingCandidate candidate,
            CharacterFactType factType,
            CharacterFactOperation operation,
            CharacterFactConfirmApplicationMode applicationMode,
            JsonNode proposedValueJson
    ) {
        boolean upsertsSnapshot = operation == CharacterFactOperation.ADD
                || operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE;
        if (applicationMode != CharacterFactConfirmApplicationMode.HISTORY_ONLY
                && factType == CharacterFactType.STATUS
                && upsertsSnapshot
                && (isExplicitlyInactiveStatus(candidate.getValueJson())
                || isExplicitlyInactiveStatus(proposedValueJson))) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
    }

    private void validateActiveStatusBeforeCharacterResolution(
            SettingCandidate candidate,
            CharacterFactType factType,
            CharacterFactConfirmApplicationMode applicationMode
    ) {
        CharacterFactOperation operation = candidate.getMatchedCharacterId() == null
                ? CharacterFactOperation.ADD
                : candidate.getSuggestedOperation();
        validateActiveStatusPromotion(
                candidate,
                factType,
                operation,
                applicationMode,
                candidate.getProposedValueJson()
        );
    }

    private boolean isExplicitlyInactiveStatus(JsonNode valueJson) {
        JsonNode active = valueJson == null || !valueJson.isObject() ? null : valueJson.get("active");
        return active != null && active.isBoolean() && !active.booleanValue();
    }

    private void validateComparedTarget(
            SettingCandidate candidate,
            ResolvedCharacter resolved,
            CharacterSnapshotSlot canonicalSlot
    ) {
        if (resolved.newlyCreated()) {
            return;
        }
        if (candidate.getComparisonTargetFactType() != canonicalSlot.factType()
                || !Objects.equals(candidate.getComparisonTargetFactKey(), canonicalSlot.factKey())) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
        }
    }

    private List<CharacterSnapshotSlot> parseRemovedSlots(
            JsonNode removedEntriesJson,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot,
            CharacterSnapshotSlot targetSlot
    ) {
        if (removedEntriesJson == null || removedEntriesJson.isNull()) {
            return List.of();
        }
        if (!removedEntriesJson.isArray()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
        }
        List<CharacterSnapshotSlot> slots = new ArrayList<>();
        Set<CharacterSnapshotSlot> distinct = new HashSet<>();
        for (JsonNode node : removedEntriesJson) {
            try {
                CharacterSnapshotSlot slot = new CharacterSnapshotSlot(
                        CharacterFactType.valueOf(node.path("factType").asText()),
                        node.path("factKey").asText().trim()
                );
                if (slot.factKey().isEmpty()
                        || slot.factType() != CharacterFactType.STATUS
                        || slot.equals(targetSlot)
                        || !snapshot.containsKey(slot)
                        || !distinct.add(slot)) {
                    throw new IllegalArgumentException();
                }
                slots.add(slot);
            } catch (IllegalArgumentException exception) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
            }
        }
        return slots;
    }

    private List<CharacterSnapshotSlot> resolveRemovalSlotsForPromotion(
            SettingCandidate candidate,
            Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot,
            CharacterSnapshotSlot canonicalSlot
    ) {
        if (canonicalSlot.factType() != CharacterFactType.STATUS) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
        boolean hasLegacyTargetType = candidate.getComparisonTargetFactType() != null;
        boolean hasLegacyTargetKey = candidate.getComparisonTargetFactKey() != null
                && !candidate.getComparisonTargetFactKey().isBlank();
        if (hasLegacyTargetType != hasLegacyTargetKey) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
        }

        Set<CharacterSnapshotSlot> removedSlots = new LinkedHashSet<>();
        if (hasLegacyTargetType) {
            CharacterSnapshotSlot legacyTarget = new CharacterSnapshotSlot(
                    candidate.getComparisonTargetFactType(),
                    candidate.getComparisonTargetFactKey().trim()
            );
            if (!legacyTarget.equals(canonicalSlot)) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_TARGET_INVALID);
            }
            removedSlots.add(legacyTarget);
        }
        removedSlots.addAll(parseRemovedSlots(
                candidate.getRemovedSnapshotEntriesJson(),
                snapshot,
                null
        ));
        if (removedSlots.isEmpty()
                || removedSlots.stream().anyMatch(slot -> !snapshot.containsKey(slot))) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID);
        }
        return List.copyOf(removedSlots);
    }

    private void validateMergePolicy(CharacterSettingMergePolicy mergePolicy) {
        if (mergePolicy == CharacterSettingMergePolicy.REPLACE
                || mergePolicy == CharacterSettingMergePolicy.UPSERT_BY_NAME) {
            return;
        }
        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MERGE_POLICY_UNSUPPORTED);
    }

    private ResolvedCharacter resolveCharacterForPromotion(SettingCandidate candidate) {
        return switch (candidate.getMatchStatus()) {
            case MATCHED, AUTO_MATCHED_BY_NAME -> new ResolvedCharacter(
                    getMatchedCharacter(candidate),
                    false,
                    false
            );
            case UNRESOLVED -> resolveUnresolvedCharacter(candidate);
            case AMBIGUOUS -> throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        };
    }

    private WorkCharacter getMatchedCharacter(SettingCandidate candidate) {
        UUID matchedCharacterId = candidate.getMatchedCharacterId();
        if (matchedCharacterId == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        WorkCharacter character = workCharacterRepository
                .findByIdAndWorkIdForUpdate(matchedCharacterId, candidate.getWork().getId())
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                ));
        if (character.getStatus() != CharacterStatus.ACTIVE) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID);
        }
        return character;
    }

    private ResolvedCharacter resolveUnresolvedCharacter(SettingCandidate candidate) {
        if (candidate.getMatchedCharacterId() != null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        UUID workId = candidate.getWork().getId();
        String characterName = promotionMapper.toCharacterName(candidate);
        workRepository.findByIdForUpdate(workId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));

        WorkCharacter existingCharacter = findActiveCharacterByGroupName(workId, characterName)
                .map(character -> lockActiveCharacter(character, workId))
                .orElse(null);
        if (existingCharacter != null) {
            candidate.matchPromotedExistingCharacter(existingCharacter);
            matchPendingUnresolvedSiblings(workId, characterName, existingCharacter, false);
            return new ResolvedCharacter(existingCharacter, false, true);
        }
        if (existsCharacterByGroupName(workId, characterName)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }

        WorkCharacter newCharacter = workCharacterRepository.save(promotionMapper.toWorkCharacter(candidate));
        candidate.matchPromotedNewCharacter(newCharacter);
        matchPendingUnresolvedSiblings(workId, characterName, newCharacter, true);
        return new ResolvedCharacter(newCharacter, true, false);
    }

    private WorkCharacter lockActiveCharacter(WorkCharacter character, UUID workId) {
        WorkCharacter lockedCharacter = workCharacterRepository
                .findByIdAndWorkIdForUpdate(character.getId(), workId)
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                ));
        if (lockedCharacter.getStatus() != CharacterStatus.ACTIVE) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }
        return lockedCharacter;
    }

    private Optional<WorkCharacter> findActiveCharacterByGroupName(UUID workId, String entityName) {
        Optional<WorkCharacter> exactMatch = workCharacterRepository.findByWorkIdAndNameAndStatus(
                workId,
                SettingCandidateGroupNameNormalizer.toDisplayName(entityName),
                CharacterStatus.ACTIVE
        );
        if (exactMatch.isPresent()) {
            return exactMatch;
        }
        List<WorkCharacter> normalizedMatches = workCharacterRepository
                .findAllByWorkIdAndStatusOrderByCreatedAtDesc(
                        workId,
                        CharacterStatus.ACTIVE
                ).stream()
                .filter(character -> SettingCandidateGroupNameNormalizer.belongsToSameGroup(
                        character.getName(),
                        entityName
                ))
                .toList();
        if (normalizedMatches.size() > 1) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }
        return normalizedMatches.stream().findFirst();
    }

    private boolean existsCharacterByGroupName(UUID workId, String entityName) {
        String displayName = SettingCandidateGroupNameNormalizer.toDisplayName(entityName);
        if (workCharacterRepository.existsByWorkIdAndName(workId, displayName)) {
            return true;
        }
        return workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(workId).stream()
                .anyMatch(character -> SettingCandidateGroupNameNormalizer.belongsToSameGroup(
                        character.getName(),
                        entityName
                ));
    }

    private void matchPendingUnresolvedSiblings(
            UUID workId,
            String normalizedEntityName,
            WorkCharacter character,
            boolean newlyCreated
    ) {
        settingCandidateRepository.findAllByNormalizedEntityNameAndMatchState(
                        workId,
                        SettingCandidateGroupNameNormalizer.toGroupKey(normalizedEntityName),
                        SettingEntityType.CHARACTER,
                        SettingCandidateReviewStatus.PENDING_REVIEW,
                        SettingCandidateMatchStatus.UNRESOLVED
                )
                .forEach(sibling -> {
                    if (newlyCreated) {
                        sibling.autoMatchSameNameCharacter(character);
                    } else {
                        sibling.matchExistingCharacter(character);
                    }
                    enqueueComparisonJobIfNeeded(sibling);
                });
    }

    private void enqueueComparisonJobIfNeeded(SettingCandidate candidate) {
        if (candidate.isCharacterDiscovery()) {
            return;
        }
        // 같은 이름 sibling은 사용자 확정 트랜잭션에서 새로 MATCHED/PENDING이 되므로
        // 원 분석 Job의 drain 시점과 무관하게 후보 전용 hidden Job에 위임한다.
        // promotion도 Work -> candidate 잠금 순서 안에서 실행된다. Worker의 Job -> candidate
        // 순서와 교차하지 않도록 활성 Job 존재 여부는 pessimistic lock 없이 확인한다.
        if (analysisJobRepository.existsBySettingCandidateIdAndStatusIn(
                candidate.getId(),
                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING)
        )) {
            return;
        }
        aiTokenService.ensureComparisonCanStart(candidate.getWork().getMember().getId());
        analysisJobRepository.save(AnalysisJob.createCharacterFactComparison(candidate));
    }

    private void updateFirstAppearance(WorkCharacter character, Episode sourceEpisode) {
        if (sourceEpisode == null) {
            return;
        }
        UUID currentFirstAppearanceId = character.getFirstAppearanceEpisodeId();
        if (currentFirstAppearanceId == null) {
            character.updateFirstAppearanceEpisodeId(sourceEpisode.getId());
            return;
        }
        episodeRepository.findByIdAndWorkId(currentFirstAppearanceId, character.getWork().getId())
                .filter(current -> sourceEpisode.getEpisodeNo() < current.getEpisodeNo())
                .ifPresent(current -> character.updateFirstAppearanceEpisodeId(sourceEpisode.getId()));
    }

    private record ResolvedCharacter(
            WorkCharacter character,
            boolean newlyCreated,
            boolean reusedExistingForUnresolved
    ) {
    }
}
