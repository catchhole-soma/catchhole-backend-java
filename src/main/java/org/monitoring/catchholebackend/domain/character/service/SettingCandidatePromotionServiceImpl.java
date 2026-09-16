package org.monitoring.catchholebackend.domain.character.service;

import org.monitoring.catchholebackend.domain.worldimage.service.AutomaticImageService;
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
    private final CharacterAnalysisConfirmation analysisConfirmation;
    private final CharacterFactComparisonJobCoordinator characterComparisonJobCoordinator;
    private final SettingCandidatePromotionMapper promotionMapper;
    private final SettingCandidateSchemaResolver schemaResolver;
    private final CharacterSnapshotAccessor snapshotAccessor;
    private final CharacterSnapshotSourceManager snapshotSourceManager;
    private final CharacterSettingValueValidator valueValidator;
    private final AutomaticImageService automaticImages;

    @Override
    @Transactional
    public void promote(
            SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode
    ) {
        automaticImages.refreshCharacterImages(List.of(promote(candidate, applicationMode, new HashSet<>(), Map.of(), new LinkedHashMap<>())));
    }

    @Override
    @Transactional
    public void promoteGroup(List<SettingCandidateGroupPromotion> promotions) {
        Set<UUID> versionedCharacterIds = new HashSet<>();
        Map<UUID, Long> initialSnapshotVersions = captureInitialSnapshotVersions(promotions);
        validateGroupRemovalSnapshotVersions(promotions, initialSnapshotVersions);
        Map<String, WorkCharacter> promotedSubjects = new LinkedHashMap<>();
        var affected = promotions.stream().map(promotion -> promote(
                promotion.candidate(),
                promotion.applicationMode(),
                versionedCharacterIds,
                initialSnapshotVersions,
                promotedSubjects
        )).toList();
        automaticImages.refreshCharacterImages(affected);
    }

    private WorkCharacter promote(
            SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode,
            Set<UUID> versionedCharacterIds,
            Map<UUID, Long> initialSnapshotVersions,
            Map<String, WorkCharacter> promotedSubjects
    ) {
        candidate.recordConfirmedApplicationMode(applicationMode);
        if (candidate.isCharacterDiscovery()) {
            ResolvedCharacter resolved = resolveCharacterForPromotion(candidate, promotedSubjects);
            updateFirstAppearance(resolved.character(), candidate.getEpisode());
            return resolved.character();
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
        ResolvedCharacter resolved = resolveCharacterForPromotion(candidate, promotedSubjects);
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
        return resolved.character();
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
                    validateActiveStatusPromotion(
                            candidate,
                            schemaMatch.matchedSchema().getFactType(),
                            candidate.getSuggestedOperation(),
                            promotion.applicationMode(),
                            candidate.getProposedValueJson()
                    );
                });

        WorkCharacter character = workCharacterRepository.save(promotionMapper.toWorkCharacter(representative));
        ResolvedCharacter resolved = new ResolvedCharacter(character, false);
        Set<UUID> versionedCharacterIds = new HashSet<>();
        long initialSnapshotVersion = character.getSnapshotVersion();
        for (SettingCandidateGroupPromotion promotion : promotions) {
            SettingCandidate candidate = promotion.candidate();
            if (!candidate.confirm()) {
                continue;
            }
            candidate.recordConfirmedApplicationMode(promotion.applicationMode());
            boolean orderedProposal = hasOrderedProvisionalSubject(candidate);
            if (orderedProposal) {
                candidate.bindPromotedProvisionalCharacter(character);
            } else {
                candidate.matchPromotedNewCharacter(character);
            }
            if (candidate.isCharacterDiscovery()) {
                updateFirstAppearance(character, candidate.getEpisode());
            } else {
                promoteSetting(
                        candidate,
                        promotion.applicationMode(),
                        resolveSchema(candidate),
                        orderedProposal ? new ResolvedCharacter(character, false) : resolved,
                        versionedCharacterIds,
                        initialSnapshotVersion
                );
            }
        }
        automaticImages.refreshCharacterImages(List.of(character));
        // 현재 묶음 밖에도 같은 이름의 미검토 후보가 있다면 새 캐릭터에 연결하고 재비교를 예약한다.
        matchPendingUnresolvedSiblings(workId, characterName, character, true);
    }

    @Override
    public void validateAutomaticPromotion(SettingCandidate candidate, List<SettingCandidate> earlierAccepted) {
        if (candidate.getAnalysisJob() == null || !candidate.getAnalysisJob().isAutomaticReview()) {
            throw new IllegalArgumentException("Automatic promotion requires an automatic analysis job.");
        }
        if (candidate.isCharacterDiscovery()) return;
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot =
                analysisConfirmation.loadActual(candidate);
        if (snapshot == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID);
        }
        for (SettingCandidate earlier : earlierAccepted) {
            if (earlier.isCharacterDiscovery()
                    || !Objects.equals(earlier.getMatchedCharacterId(), candidate.getMatchedCharacterId())
                    || !Objects.equals(earlier.getProvisionalSubjectKey(), candidate.getProvisionalSubjectKey())) continue;
            PromotionPlan plan = preparePromotion(earlier, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL,
                    resolveSchema(earlier), new ResolvedCharacter(null, false), snapshot, 0);
            projectPlan(snapshot, plan);
        }
        // Ordered provisional subjects use their explicit comparison operation,
        // including history-only. No entity, fact or source is written here.
        preparePromotion(candidate, CharacterFactConfirmApplicationMode.APPLY_PROPOSAL,
                resolveSchema(candidate), new ResolvedCharacter(null, false), snapshot, 0);
    }

    private void projectPlan(Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot, PromotionPlan plan) {
        if (plan.historyOnly()) return;
        plan.removals().forEach(snapshot::remove);
        if (plan.operation() != CharacterFactOperation.REMOVE) {
            snapshot.put(plan.slot(), snapshotAccessor.entry(plan.slot().factType(), plan.slot().factKey(),
                    plan.proposedFactValue() == null ? null : plan.proposedFactValue().trim(), plan.proposedValue()));
        }
    }

    private void promoteSetting(
            SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode,
            SettingCandidateSchemaMatch schemaMatch,
            ResolvedCharacter resolved,
            Set<UUID> versionedCharacterIds,
            long removalSnapshotVersion
    ) {
        WorkCharacter character = resolved.character();
        boolean historyOnly = applicationMode == CharacterFactConfirmApplicationMode.HISTORY_ONLY
                || candidate.getSuggestedOperation() == CharacterFactOperation.HISTORY_ONLY;
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot = historyOnly ? Map.of() : snapshotAccessor.read(
                character, snapshotSourceManager.findSourceFactsBySlot(character));
        PromotionPlan plan = preparePromotion(candidate, applicationMode, schemaMatch, resolved,
                snapshot, removalSnapshotVersion);
        updateFirstAppearance(character, candidate.getEpisode());
        CharacterFact newFact = characterFactRepository.saveAndFlush(promotionMapper.toCharacterFact(
                candidate, character, plan.slot().factType(), plan.slot().factKey(), plan.candidateValue()));
        if (plan.historyOnly()) return;
        plan.removals().forEach(snapshot::remove);
        snapshotSourceManager.removeSources(character, plan.removals());
        if (plan.operation() != CharacterFactOperation.REMOVE) {
            snapshot.put(plan.slot(), snapshotAccessor.entry(plan.slot().factType(), plan.slot().factKey(),
                    plan.proposedFactValue() == null ? null : plan.proposedFactValue().trim(), plan.proposedValue()));
            if (plan.operation() == CharacterFactOperation.MERGE) {
                snapshotSourceManager.mergeSource(character, plan.slot(), newFact);
            } else {
                snapshotSourceManager.replaceSources(character, plan.slot(), List.of(newFact));
            }
        }
        replaceSnapshotOncePerCharacter(character, snapshot, versionedCharacterIds);
    }

    /** Side-effect-free validation shared by automatic preflight and actual persistence. */
    private PromotionPlan preparePromotion(SettingCandidate candidate,
            CharacterFactConfirmApplicationMode applicationMode, SettingCandidateSchemaMatch schemaMatch,
            ResolvedCharacter resolved, Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot,
            long removalSnapshotVersion) {
        CharacterFactType factType = schemaMatch.matchedSchema().getFactType();
        String factKey = candidate.getResolvedCanonicalFactKey() == null
                || candidate.getResolvedCanonicalFactKey().isBlank()
                ? schemaMatch.factKey() : candidate.getResolvedCanonicalFactKey().trim();
        valueValidator.validateCandidate(candidate, factType, schemaMatch.matchedSchema().getValueType());
        JsonNode normalized = valueValidator.resolveCandidateValue(candidate, factType,
                schemaMatch.matchedSchema().getValueType());
        CharacterFactOperation operation = candidate.getSuggestedOperation();
        validatePromotionPolicy(candidate, resolved, operation, applicationMode);
        validateRemovalSnapshotVersion(candidate, operation, applicationMode, removalSnapshotVersion);
        JsonNode proposed = candidate.getProposedValueJson();
        validateActiveStatusPromotion(candidate, factType, operation, applicationMode, proposed);
        CharacterSnapshotSlot slot = new CharacterSnapshotSlot(factType, factKey);
        if (applicationMode == CharacterFactConfirmApplicationMode.HISTORY_ONLY
                || operation == CharacterFactOperation.HISTORY_ONLY) {
            return new PromotionPlan(operation, slot, normalized, null, null, List.of(), true);
        }
        if (operation == CharacterFactOperation.REMOVE) {
            return new PromotionPlan(operation, slot, normalized, null, null,
                    resolveRemovalSlotsForPromotion(candidate, snapshot, slot), false);
        }
        validateComparedTarget(candidate, slot);
        String proposedText = candidate.getProposedFactValue();
        valueValidator.validateProposal(proposed, proposedText, factType, schemaMatch.matchedSchema().getValueType());
        List<CharacterSnapshotSlot> removals = parseRemovedSlots(candidate.getRemovedSnapshotEntriesJson(), snapshot, slot);
        return new PromotionPlan(operation, slot, normalized, proposed, proposedText, removals, false);
    }

    private record PromotionPlan(CharacterFactOperation operation, CharacterSnapshotSlot slot,
            JsonNode candidateValue, JsonNode proposedValue, String proposedFactValue,
            List<CharacterSnapshotSlot> removals, boolean historyOnly) { }

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
        if (applicationMode == CharacterFactConfirmApplicationMode.HISTORY_ONLY
                || candidate.getAnalysisJob() != null && candidate.getAnalysisJob().isOrderedProvisional()) {
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
            if (candidate.getAnalysisJob() != null && candidate.getAnalysisJob().isOrderedProvisional()) {
                continue;
            }
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
        validateActiveStatusPromotion(
                candidate,
                factType,
                candidate.getSuggestedOperation(),
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
            CharacterSnapshotSlot canonicalSlot
    ) {
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

    private ResolvedCharacter resolveCharacterForPromotion(SettingCandidate candidate,
            Map<String, WorkCharacter> promotedSubjects) {
        if (usesOrderedCharacterResolution(candidate)) {
            String subjectKey = candidate.getProvisionalSubjectKey() != null ? candidate.getProvisionalSubjectKey()
                    : "user-created:" + SettingCandidateGroupNameNormalizer.toGroupKey(candidate.getEntityName());
            WorkCharacter character = promotedSubjects.get(subjectKey);
            if (character == null) {
                UUID characterId = analysisConfirmation.resolvedCharacterId(candidate);
                if (characterId != null) {
                    character = workCharacterRepository.findByIdAndWorkIdForUpdate(
                            characterId, candidate.getWork().getId()).orElseThrow(() ->
                            new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID));
                } else {
                    String name = promotionMapper.toCharacterName(candidate);
                    if (existsCharacterByGroupName(candidate.getWork().getId(), name)) {
                        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
                    }
                    character = workCharacterRepository.save(promotionMapper.toWorkCharacter(candidate));
                }
                promotedSubjects.put(subjectKey, character);
            }
            if (character.getStatus() != CharacterStatus.ACTIVE) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID);
            }
            candidate.bindPromotedProvisionalCharacter(character);
            return new ResolvedCharacter(character, false);
        }
        return switch (candidate.getMatchStatus()) {
            case MATCHED, AUTO_MATCHED_BY_NAME -> new ResolvedCharacter(
                    getMatchedCharacter(candidate),
                    false
            );
            case UNRESOLVED -> resolveUnresolvedCharacter(candidate);
            case AMBIGUOUS -> throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        };
    }

    private boolean usesOrderedCharacterResolution(SettingCandidate candidate) {
        return hasOrderedProvisionalSubject(candidate)
                || candidate.getAnalysisJob() != null && candidate.getAnalysisJob().isOrderedProvisional()
                && candidate.isUserModified() && candidate.getMatchedCharacterId() == null;
    }

    private boolean hasOrderedProvisionalSubject(SettingCandidate candidate) {
        return candidate.getProvisionalSubjectKey() != null
                && candidate.getAnalysisJob() != null && candidate.getAnalysisJob().isOrderedProvisional();
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
            return new ResolvedCharacter(existingCharacter, true);
        }
        if (existsCharacterByGroupName(workId, characterName)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }

        WorkCharacter newCharacter = workCharacterRepository.save(promotionMapper.toWorkCharacter(candidate));
        boolean orderedProposal = hasOrderedProvisionalSubject(candidate);
        if (orderedProposal) {
            candidate.bindPromotedProvisionalCharacter(newCharacter);
        } else {
            candidate.matchPromotedNewCharacter(newCharacter);
        }
        matchPendingUnresolvedSiblings(workId, characterName, newCharacter, true);
        return new ResolvedCharacter(newCharacter, false);
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
                .stream()
                .filter(sibling -> sibling.getAnalysisJob() == null
                        || !sibling.getAnalysisJob().isOrderedProvisional())
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
        characterComparisonJobCoordinator.enqueueIfNeeded(
                candidate.getWork().getMember().getId(),
                candidate
        );
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
            boolean reusedExistingForUnresolved
    ) {
    }
}
