package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidatePromotionMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAssembler;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaMatch;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
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

    private static final int MAX_PROPERTY_KEY_LENGTH = 100;

    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterFactRepository characterFactRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final EpisodeRepository episodeRepository;
    private final WorkRepository workRepository;
    private final SettingCandidatePromotionMapper settingCandidatePromotionMapper;
    private final SettingCandidateSchemaResolver settingCandidateSchemaResolver;
    private final CharacterSnapshotAssembler characterSnapshotAssembler;

    @Override
    @Transactional
    public void promote(SettingCandidate candidate) {
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(candidate.getWork().getId());
        SettingCandidateSchemaMatch schemaMatch = settingCandidateSchemaResolver.resolve(
                candidate.getAttributeName(),
                candidate.getValueType(),
                schemas
        );
        validateMergePolicy(schemaMatch.matchedSchema().getMergePolicy());
        String factKey = schemaMatch.factKey();
        CharacterFactType factType = schemaMatch.matchedSchema().getFactType();
        validateCoreSnapshotValue(candidate, factType);
        validateStructuredProperties(
                candidate.getValueJson(),
                factType,
                schemaMatch.matchedSchema().getValueType()
        );

        // schema 매칭, 값 타입, merge policy 검증을 통과한 후보만 캐릭터 생성과 Fact 저장으로 진행한다.
        WorkCharacter character = resolveCharacterForPromotion(candidate);
        updateFirstAppearance(character, candidate.getEpisode());

        CharacterFact newFact = characterFactRepository.saveAndFlush(
                settingCandidatePromotionMapper.toCharacterFact(candidate, character, factType, factKey)
        );

        // confirm 순서와 회차 순서가 다를 수 있으므로 같은 key의 fact 전체를 다시 평가한다.
        List<CharacterFact> facts = characterFactRepository
                .findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                        character.getId(),
                        factType,
                        factKey
                );
        CharacterFact currentFact = selectCurrentFact(facts, newFact);
        facts.forEach(fact -> updateCurrentState(fact, currentFact));
        // dirty isCurrent 변경을 바로 다음 all-current 조회에 반영한다.
        characterFactRepository.flush();

        // 전체 current Fact 재구성으로 다른 key를 보존하고 legacy snapshot도 현재 map 계약으로 정규화한다.
        List<CharacterFact> currentFacts = characterFactRepository
                .findAllByWorkCharacterIdAndIsCurrentTrueOrderByFactTypeAscFactKeyAsc(character.getId());
        CharacterSnapshot snapshot = characterSnapshotAssembler.assemble(currentFacts);
        character.replaceCurrentSnapshots(
                snapshot.currentAge(),
                snapshot.currentLevel(),
                snapshot.profileJson(),
                snapshot.statsJson(),
                snapshot.skillsJson(),
                snapshot.itemsJson(),
                snapshot.statusesJson()
        );
    }

    private void validateMergePolicy(CharacterSettingMergePolicy mergePolicy) {
        if (mergePolicy == CharacterSettingMergePolicy.REPLACE
                || mergePolicy == CharacterSettingMergePolicy.UPSERT_BY_NAME) {
            return;
        }
        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MERGE_POLICY_UNSUPPORTED);
    }

    /**
     * AGE와 LEVEL은 수정 API와 같은 0 이상 int 정수 계약을 통과한 뒤에만 확정한다.
     */
    private void validateCoreSnapshotValue(SettingCandidate candidate, CharacterFactType factType) {
        if (factType != CharacterFactType.AGE && factType != CharacterFactType.LEVEL) {
            return;
        }
        BigDecimal value = resolveCoreSnapshotNumber(candidate);
        if (value == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
        }
        try {
            if (value.intValueExact() < 0) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
            }
        } catch (ArithmeticException exception) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
        }
    }

    /**
     * 상세 응답에 공개되는 최상위 속성과 scalar envelope가 전체 수정 요청으로 왕복 가능한지 검증한다.
     */
    private void validateStructuredProperties(
            JsonNode valueJson,
            CharacterFactType factType,
            SettingValueType valueType
    ) {
        if (!hasEditableProperties(factType) || valueJson == null || !valueJson.isObject()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        boolean hasPublicProperty = valueJson.size() > (valueJson.has("value") ? 1 : 0);
        valueJson.properties().forEach(entry -> {
            String rawKey = entry.getKey();
            if (rawKey.equals("value")) {
                return;
            }
            String key = rawKey.trim();
            JsonNode propertyValue = entry.getValue();
            boolean invalidTextValue = propertyValue.isTextual()
                    && (propertyValue.asText().isEmpty()
                            || !propertyValue.asText().equals(propertyValue.asText().trim()));
            if (rawKey.isBlank()
                    || rawKey.length() > MAX_PROPERTY_KEY_LENGTH
                    || !rawKey.equals(key)
                    || key.equals("value")
                    || !keys.add(key)
                    || invalidTextValue) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
            }
        });
        if (hasPublicProperty
                && valueType != SettingValueType.JSON
                && !hasCompatibleScalarEnvelope(valueJson, valueType)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_JSON_INVALID);
        }
    }

    private boolean hasEditableProperties(CharacterFactType factType) {
        return switch (factType) {
            case PROFILE, STAT, SKILL, ITEM, STATUS -> true;
            case AGE, LEVEL, TIME -> false;
        };
    }

    private boolean hasCompatibleScalarEnvelope(JsonNode valueJson, SettingValueType valueType) {
        JsonNode valueNode = valueJson.get("value");
        if (valueNode == null) {
            return false;
        }
        if (valueNode.isNull() || valueType == SettingValueType.UNKNOWN) {
            return true;
        }
        return switch (valueType) {
            case STRING -> valueNode.isTextual();
            case NUMBER -> valueNode.isNumber();
            case BOOLEAN -> valueNode.isBoolean();
            case JSON, UNKNOWN -> true;
        };
    }

    /**
     * 구조화 대표값이 있으면 우선 사용하고, 없을 때만 표시값을 숫자로 해석한다.
     */
    private BigDecimal resolveCoreSnapshotNumber(SettingCandidate candidate) {
        JsonNode valueNode = candidate.getValueJson();
        if (valueNode != null && valueNode.isObject()) {
            valueNode = valueNode.get("value");
        }
        if (valueNode != null && !valueNode.isNull()) {
            return valueNode.isNumber() ? valueNode.decimalValue() : null;
        }
        String attributeValue = candidate.getAttributeValue();
        if (attributeValue == null) {
            return null;
        }
        try {
            return new BigDecimal(attributeValue.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private WorkCharacter resolveCharacterForPromotion(SettingCandidate candidate) {
        // 직접·이름 자동 연결은 명시된 ID를 사용하고, UNRESOLVED만 이름으로 재사용/생성한다.
        return switch (candidate.getMatchStatus()) {
            case MATCHED, AUTO_MATCHED_BY_NAME -> getMatchedCharacter(candidate);
            case UNRESOLVED -> resolveUnresolvedCharacter(candidate);
            case AMBIGUOUS -> throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        };
    }

    private WorkCharacter getMatchedCharacter(SettingCandidate candidate) {
        // MATCHED와 캐릭터 ID는 항상 함께 있어야 하며 불완전한 조합은 임의 복구하지 않는다.
        UUID matchedCharacterId = candidate.getMatchedCharacterId();
        if (matchedCharacterId == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        // whole-map snapshot 교체를 직렬화해 서로 다른 factKey confirm 사이의 lost update를 막는다.
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

    private WorkCharacter resolveUnresolvedCharacter(SettingCandidate candidate) {
        // UNRESOLVED인데 ID가 채워진 모순 상태에서는 어느 값을 신뢰할지 결정하지 않고 confirm을 중단한다.
        if (candidate.getMatchedCharacterId() != null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT);
        }
        UUID workId = candidate.getWork().getId();
        String characterName = settingCandidatePromotionMapper.toCharacterName(candidate);

        // 동일 이름 신규 후보의 조회-생성을 작품 단위로 직렬화해 중복 캐릭터 생성을 막는다.
        workRepository.findByIdForUpdate(workId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));

        // 동일 이름의 활성 캐릭터는 재사용하고, 보관 캐릭터만 있거나 이름 자체가 없으면 새 캐릭터를 만든다.
        WorkCharacter character = workCharacterRepository.findByWorkIdAndNameAndStatus(
                        workId,
                        characterName,
                        CharacterStatus.ACTIVE
                )
                .map(existingCharacter -> lockActiveCharacter(existingCharacter, workId))
                .orElseGet(() -> workCharacterRepository.save(
                        settingCandidatePromotionMapper.toWorkCharacter(candidate)
                ));

        // 현재 후보는 이미 CONFIRMED이고, 나머지 동일 이름 형제 후보는 아직 PENDING_REVIEW인 상태에서 연결한다.
        candidate.matchPromotedCharacter(character);
        matchPendingUnresolvedSiblings(workId, characterName, character);
        return character;
    }

    private WorkCharacter lockActiveCharacter(WorkCharacter character, UUID workId) {
        // 기존 MATCHED confirm과 같은 캐릭터 row lock을 사용해 Fact current 및 snapshot 갱신을 직렬화한다.
        WorkCharacter lockedCharacter = workCharacterRepository
                .findByIdAndWorkIdForUpdate(character.getId(), workId)
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                ));

        // 보관 캐릭터를 자동 복구하거나 같은 이름으로 새로 만들지 않고 사용자 판단이 필요한 충돌로 남긴다.
        if (lockedCharacter.getStatus() != CharacterStatus.ACTIVE) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }
        return lockedCharacter;
    }

    private void matchPendingUnresolvedSiblings(
            UUID workId,
            String normalizedEntityName,
            WorkCharacter character
    ) {
        // AMBIGUOUS와 이미 검토된 후보는 유지하고, exact-name 형제 후보만 다음 confirm에 재사용한다.
        settingCandidateRepository.findAllByNormalizedEntityNameAndMatchState(
                        workId,
                        normalizedEntityName,
                        SettingEntityType.CHARACTER,
                        SettingCandidateReviewStatus.PENDING_REVIEW,
                        SettingCandidateMatchStatus.UNRESOLVED
                )
                .forEach(sibling -> sibling.autoMatchSameNameCharacter(character));
    }

    private void updateFirstAppearance(WorkCharacter character, Episode sourceEpisode) {
        // 첫 등장은 확정 순서가 아니라 가장 이른 업로드 회차 기준으로 유지한다.
        if (sourceEpisode == null) {
            return;
        }
        UUID currentFirstAppearanceId = character.getFirstAppearanceEpisodeId();
        if (currentFirstAppearanceId == null) {
            character.updateFirstAppearanceEpisodeId(sourceEpisode.getId());
            return;
        }
        episodeRepository.findByIdAndWorkId(currentFirstAppearanceId, character.getWork().getId())
                .filter(currentFirstAppearance -> sourceEpisode.getEpisodeNo() < currentFirstAppearance.getEpisodeNo())
                .ifPresent(currentFirstAppearance -> character.updateFirstAppearanceEpisodeId(sourceEpisode.getId()));
    }

    private CharacterFact selectCurrentFact(List<CharacterFact> facts, CharacterFact newFact) {
        return facts.stream()
                .reduce((current, candidate) -> isMoreRecent(candidate, current, newFact) ? candidate : current)
                .orElse(newFact);
    }

    private boolean isMoreRecent(CharacterFact candidate, CharacterFact current, CharacterFact newFact) {
        // null episodeNo는 가장 오래된 값으로 보고, 같은 회차는 생성 시각과 방금 저장한 fact로 tie-break 한다.
        int episodeComparison = compareNullableInteger(
                candidate.getEffectiveFromEpisodeNo(),
                current.getEffectiveFromEpisodeNo()
        );
        if (episodeComparison != 0) {
            return episodeComparison > 0;
        }

        int createdAtComparison = compareNullableDateTime(candidate.getCreatedAt(), current.getCreatedAt());
        if (createdAtComparison != 0) {
            return createdAtComparison > 0;
        }

        return candidate.getId().equals(newFact.getId()) && !current.getId().equals(newFact.getId());
    }

    private int compareNullableInteger(Integer left, Integer right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private int compareNullableDateTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private void updateCurrentState(CharacterFact fact, CharacterFact currentFact) {
        if (fact.getId().equals(currentFact.getId())) {
            fact.markCurrent();
            return;
        }
        fact.markHistorical();
    }
}
