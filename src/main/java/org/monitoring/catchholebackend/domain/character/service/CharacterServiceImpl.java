package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterSettingPropertyRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterSettingUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterArchiveResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterRestoreResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterSummaryResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAssembler;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CharacterServiceImpl implements CharacterService {

    private static final Map<CharacterFactType, String> KEY_PREFIXES = Map.of(
            CharacterFactType.PROFILE, "profile.",
            CharacterFactType.STAT, "stats.",
            CharacterFactType.SKILL, "skill.",
            CharacterFactType.ITEM, "item.",
            CharacterFactType.STATUS, "status."
    );

    private final WorkRepository workRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterFactRepository characterFactRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final EpisodeRepository episodeRepository;
    private final CharacterSnapshotAssembler characterSnapshotAssembler;
    private final CharacterMapper characterMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public PageResponse<CharacterSummaryResponse> getCharacters(
            Long memberId,
            UUID workId,
            int page,
            int size
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        return getCharacterPage(work.getId(), CharacterStatus.ACTIVE, page, size);
    }

    @Override
    public PageResponse<CharacterSummaryResponse> getArchivedCharacters(
            Long memberId,
            UUID workId,
            int page,
            int size
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        return getCharacterPage(work.getId(), CharacterStatus.ARCHIVED, page, size);
    }

    /**
     * 상태별 캐릭터 카드 목록과 첫 등장 회차 번호를 같은 페이지 계약으로 조립한다.
     */
    private PageResponse<CharacterSummaryResponse> getCharacterPage(
            UUID workId,
            CharacterStatus status,
            int page,
            int size
    ) {
        Page<WorkCharacter> characters = workCharacterRepository
                .findAllByWorkIdAndStatusOrderByCreatedAtDescIdDesc(
                        workId,
                        status,
                        PageRequest.of(page, size)
                );
        Map<UUID, Integer> firstAppearanceEpisodeNosById = findFirstAppearanceEpisodeNosById(
                characters.getContent(),
                workId
        );
        return PageResponse.from(
                characters,
                characterMapper.toSummaryResponseList(characters.getContent(), firstAppearanceEpisodeNosById)
        );
    }

    @Override
    public CharacterDetailResponse getCharacter(Long memberId, UUID workId, UUID characterId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        WorkCharacter character = getActiveCharacter(work.getId(), characterId);
        return toDetailResponse(character);
    }

    @Override
    @Transactional
    public CharacterDetailResponse updateCharacter(
            Long memberId,
            UUID workId,
            UUID characterId,
            CharacterUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorkCharacter character = getActiveCharacterForUpdate(work.getId(), characterId);
        String name = request.name().trim();
        if (workCharacterRepository.existsByWorkIdAndNameAndIdNot(work.getId(), name, characterId)) {
            throw new AppException(CharacterErrorCode.CHARACTER_NAME_DUPLICATED);
        }

        Episode firstAppearanceEpisode = findFirstAppearanceEpisodeByNo(
                work.getId(),
                request.firstAppearanceEpisodeNo()
        );
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository
                .findAllActiveForWork(work.getId());
        List<CharacterFact> currentFacts = characterFactRepository
                .findAllByWorkCharacterIdAndIsCurrentTrueOrderByFactTypeAscFactKeyAsc(characterId);
        Map<FactIdentity, DesiredFact> desiredFacts = toDesiredFacts(request, schemas, currentFacts);
        character.updateBasicInfo(name, normalizeNullableText(request.roleLabel()));
        character.updateFirstAppearanceEpisodeId(
                firstAppearanceEpisode == null ? null : firstAppearanceEpisode.getId()
        );

        applyManualCorrections(character, currentFacts, desiredFacts);
        characterFactRepository.flush();

        List<CharacterFact> updatedCurrentFacts = characterFactRepository
                .findAllByWorkCharacterIdAndIsCurrentTrueOrderByFactTypeAscFactKeyAsc(characterId);
        replaceSnapshots(character, updatedCurrentFacts);
        return characterMapper.toDetailResponse(
                character,
                firstAppearanceEpisode,
                updatedCurrentFacts,
                schemas
        );
    }

    @Override
    @Transactional
    public CharacterArchiveResponse archiveCharacter(Long memberId, UUID workId, UUID characterId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        WorkCharacter character = getActiveCharacterForUpdate(work.getId(), characterId);
        character.archive();
        return characterMapper.toArchiveResponse(character);
    }

    @Override
    @Transactional
    public CharacterRestoreResponse restoreCharacter(Long memberId, UUID workId, UUID characterId) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorkCharacter character = getArchivedCharacterForUpdate(work.getId(), characterId);
        if (workCharacterRepository.existsByWorkIdAndNameAndIdNot(
                work.getId(),
                character.getName(),
                characterId
        )) {
            throw new AppException(CharacterErrorCode.CHARACTER_NAME_DUPLICATED);
        }
        character.restore();
        return characterMapper.toRestoreResponse(character);
    }

    /**
     * 캐릭터 카드에 표시할 첫 등장 회차 번호를 작품 범위에서 일괄 조회한다.
     */
    private Map<UUID, Integer> findFirstAppearanceEpisodeNosById(
            List<WorkCharacter> characters,
            UUID workId
    ) {
        List<UUID> episodeIds = characters.stream()
                .map(WorkCharacter::getFirstAppearanceEpisodeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (episodeIds.isEmpty()) {
            return Map.of();
        }
        return episodeRepository.findAllByWorkIdAndIdIn(workId, episodeIds).stream()
                .collect(Collectors.toMap(Episode::getId, Episode::getEpisodeNo));
    }

    /**
     * 작품에 속한 활성 캐릭터를 조회하고, 없거나 보관된 캐릭터이면 조회 실패로 처리한다.
     */
    private WorkCharacter getActiveCharacter(UUID workId, UUID characterId) {
        return workCharacterRepository.findByIdAndWorkIdAndStatus(characterId, workId, CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    }

    /**
     * 수정 중 동일 캐릭터의 동시 변경을 막기 위해 활성 캐릭터를 비관적 잠금으로 조회한다.
     */
    private WorkCharacter getActiveCharacterForUpdate(UUID workId, UUID characterId) {
        return workCharacterRepository.findByIdAndWorkIdAndStatusForUpdate(
                        characterId,
                        workId,
                        CharacterStatus.ACTIVE
                )
                .orElseThrow(() -> new AppException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    }

    /**
     * 복구 중 동일 캐릭터의 동시 상태 변경을 막기 위해 보관 캐릭터를 비관적 잠금으로 조회한다.
     */
    private WorkCharacter getArchivedCharacterForUpdate(UUID workId, UUID characterId) {
        return workCharacterRepository.findByIdAndWorkIdAndStatusForUpdate(
                        characterId,
                        workId,
                        CharacterStatus.ARCHIVED
                )
                .orElseThrow(() -> new AppException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    }

    /**
     * 캐릭터의 첫 등장 회차와 현재 Fact를 조회해 상세 응답 생성에 필요한 데이터를 구성한다.
     */
    private CharacterDetailResponse toDetailResponse(WorkCharacter character) {
        Episode firstAppearanceEpisode = findFirstAppearanceEpisodeById(
                character.getWork().getId(),
                character.getFirstAppearanceEpisodeId()
        );
        List<CharacterFact> currentFacts = characterFactRepository
                .findAllByWorkCharacterIdAndIsCurrentTrueOrderByFactTypeAscFactKeyAsc(character.getId());
        return toDetailResponse(character, firstAppearanceEpisode, currentFacts);
    }

    /**
     * 현재 설정 스키마를 함께 조회해 캐릭터와 Fact를 화면용 상세 응답으로 변환한다.
     */
    private CharacterDetailResponse toDetailResponse(
            WorkCharacter character,
            Episode firstAppearanceEpisode,
            List<CharacterFact> currentFacts
    ) {
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository
                .findAllActiveForWork(character.getWork().getId());
        return characterMapper.toDetailResponse(character, firstAppearanceEpisode, currentFacts, schemas);
    }

    /**
     * 수정 요청의 첫 등장 회차 번호를 작품 내 회차로 해석하고, 유효하지 않으면 실패시킨다.
     */
    private Episode findFirstAppearanceEpisodeByNo(UUID workId, Integer episodeNo) {
        if (episodeNo == null) {
            return null;
        }
        return episodeRepository.findByWorkIdAndEpisodeNo(workId, episodeNo)
                .orElseThrow(() -> new AppException(EpisodeErrorCode.EPISODE_NOT_FOUND));
    }

    /**
     * 저장된 첫 등장 회차 ID가 현재 작품에 유효한 경우에만 상세 조회용 회차를 반환한다.
     */
    private Episode findFirstAppearanceEpisodeById(UUID workId, UUID episodeId) {
        if (episodeId == null) {
            return null;
        }
        return episodeRepository.findByIdAndWorkId(episodeId, workId).orElse(null);
    }

    /**
     * 화면에서 전달한 현재 설정 전체를 Fact 유형과 key로 식별되는 목표 상태로 변환한다.
     */
    private Map<FactIdentity, DesiredFact> toDesiredFacts(
            CharacterUpdateRequest request,
            List<CharacterSettingSchema> schemas,
            List<CharacterFact> currentFacts
    ) {
        Map<FactIdentity, DesiredFact> desiredFacts = new LinkedHashMap<>();
        if (request.currentAge() != null) {
            addDesiredFact(desiredFacts, integerFact(CharacterFactType.AGE, "age", request.currentAge()));
        }
        if (request.currentLevel() != null) {
            addDesiredFact(desiredFacts, integerFact(CharacterFactType.LEVEL, "level", request.currentLevel()));
        }
        addDesiredFacts(desiredFacts, CharacterFactType.PROFILE, request.profile(), schemas, currentFacts);
        addDesiredFacts(desiredFacts, CharacterFactType.STAT, request.stats(), schemas, currentFacts);
        addDesiredFacts(desiredFacts, CharacterFactType.SKILL, request.skills(), schemas, currentFacts);
        addDesiredFacts(desiredFacts, CharacterFactType.ITEM, request.items(), schemas, currentFacts);
        addDesiredFacts(desiredFacts, CharacterFactType.STATUS, request.statuses(), schemas, currentFacts);
        return desiredFacts;
    }

    /**
     * 나이와 레벨 정숫값을 문자열 표시값과 JSON 값을 가진 목표 Fact로 변환한다.
     */
    private DesiredFact integerFact(CharacterFactType factType, String key, Integer value) {
        ObjectNode valueJson = JsonNodeFactory.instance.objectNode();
        valueJson.put("value", value);
        return new DesiredFact(factType, key, value.toString(), SettingValueType.NUMBER, valueJson);
    }

    /**
     * 같은 유형의 설정 요청 목록을 검증·변환해 중복 없는 목표 Fact에 추가한다.
     */
    private void addDesiredFacts(
            Map<FactIdentity, DesiredFact> desiredFacts,
            CharacterFactType factType,
            List<CharacterSettingUpdateRequest> requests,
            List<CharacterSettingSchema> schemas,
            List<CharacterFact> currentFacts
    ) {
        for (CharacterSettingUpdateRequest request : requests) {
            String key = request.key().trim();
            validateKey(factType, key, schemas);
            validateRegisteredValueType(factType, key, request.valueType(), schemas);
            String factValue = normalizeNullableText(request.value());
            CharacterFact currentFact = currentFacts.stream()
                    .filter(fact -> fact.getFactType() == factType && fact.getFactKey().equals(key))
                    .findFirst()
                    .orElse(null);
            JsonNode valueJson = toDesiredValueJson(request, currentFact, factValue);
            addDesiredFact(desiredFacts, new DesiredFact(
                    factType,
                    key,
                    factValue,
                    request.valueType(),
                    valueJson
            ));
        }
    }

    /**
     * 화면에 노출되지 않는 propertyless raw 값과 JSON의 예약 value envelope는 보존하고,
     * 사용자가 전달한 properties만 현재 목표 상태로 재구성한다.
     */
    private JsonNode toDesiredValueJson(
            CharacterSettingUpdateRequest request,
            CharacterFact currentFact,
            String factValue
    ) {
        if (request.properties().isEmpty()
                && currentFact != null
                && Objects.equals(currentFact.getFactValue(), factValue)
                && isPropertylessRawValue(currentFact.getValueJson())) {
            return currentFact.getValueJson();
        }

        boolean preserveCurrentValue = !request.properties().isEmpty()
                && currentFact != null
                && currentFact.getValueJson() != null
                && currentFact.getValueJson().isObject()
                && currentFact.getValueJson().has("value")
                && (request.valueType() == SettingValueType.JSON
                    || Objects.equals(currentFact.getFactValue(), factValue));
        JsonNode valueJson = toValueJson(request, !preserveCurrentValue);
        if (preserveCurrentValue) {
            ((ObjectNode) valueJson).set("value", currentFact.getValueJson().get("value"));
        }
        return valueJson;
    }

    /**
     * 상세 응답의 properties로 복원할 수 없는 raw 값 표현인지 확인한다.
     */
    private boolean isPropertylessRawValue(JsonNode valueJson) {
        if (valueJson == null || !valueJson.isObject()) {
            return true;
        }
        return valueJson.isEmpty() || (valueJson.size() == 1 && valueJson.has("value"));
    }

    /**
     * 등록된 exact schema key를 우선 허용하고, custom key는 Fact 유형별 prefix 규칙을 검증한다.
     */
    private void validateKey(
            CharacterFactType factType,
            String key,
            List<CharacterSettingSchema> schemas
    ) {
        boolean registeredExactKey = schemas.stream()
                .anyMatch(schema -> schema.getFactType() == factType && schema.getSchemaKey().trim().equals(key));
        if (registeredExactKey || (factType == CharacterFactType.PROFILE && key.equals("profile"))) {
            return;
        }
        String requiredPrefix = KEY_PREFIXES.get(factType);
        if (requiredPrefix == null || !key.startsWith(requiredPrefix) || key.length() == requiredPrefix.length()) {
            throw new AppException(CharacterErrorCode.CHARACTER_SETTING_KEY_INVALID);
        }
    }

    /**
     * 등록된 exact 또는 pattern schema가 있는 key만 요청 값 타입과 일치하는지 검증한다.
     * 등록되지 않은 수동 custom key는 기존 정책대로 허용한다.
     */
    private void validateRegisteredValueType(
            CharacterFactType factType,
            String key,
            SettingValueType valueType,
            List<CharacterSettingSchema> schemas
    ) {
        List<CharacterSettingSchema> matchedSchemas = schemas.stream()
                .filter(schema -> schema.getFactType() == factType)
                .filter(schema -> schema.getSchemaKey().trim().equals(key))
                .toList();
        if (matchedSchemas.isEmpty()) {
            matchedSchemas = schemas.stream()
                    .filter(schema -> schema.getFactType() == factType)
                    .filter(schema -> matchesPattern(schema.getAttributePattern(), key))
                    .toList();
        }
        if (matchedSchemas.stream().anyMatch(schema -> schema.getValueType() != valueType)) {
            throw new AppException(CharacterErrorCode.CHARACTER_SETTING_VALUE_TYPE_MISMATCH);
        }
    }

    private boolean matchesPattern(String pattern, String key) {
        if (pattern == null || !pattern.trim().endsWith(".*")) {
            return false;
        }
        String normalizedPattern = pattern.trim();
        String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 1);
        return key.startsWith(prefix) && key.length() > prefix.length();
    }

    /**
     * 동일한 Fact 유형과 key가 요청에 중복되면 모호한 수정을 막기 위해 실패시킨다.
     */
    private void addDesiredFact(Map<FactIdentity, DesiredFact> desiredFacts, DesiredFact desiredFact) {
        FactIdentity identity = new FactIdentity(desiredFact.factType(), desiredFact.factKey());
        if (desiredFacts.putIfAbsent(identity, desiredFact) != null) {
            throw new AppException(CharacterErrorCode.CHARACTER_SETTING_KEY_DUPLICATED);
        }
    }

    /**
     * 단일 값 또는 속성 목록을 요청에 선언된 값 유형에 맞는 JSON 객체로 변환한다.
     */
    private JsonNode toValueJson(
            CharacterSettingUpdateRequest request,
            boolean includePrimaryValue
    ) {
        if (request.properties().isEmpty()) {
            ObjectNode valueJson = JsonNodeFactory.instance.objectNode();
            valueJson.set("value", toValueNode(request.value(), request.valueType()));
            return valueJson;
        }

        ObjectNode valueJson = JsonNodeFactory.instance.objectNode();
        if (includePrimaryValue && request.valueType() != SettingValueType.JSON) {
            valueJson.set("value", toValueNode(request.value(), request.valueType()));
        }
        Set<String> keys = new HashSet<>();
        for (CharacterSettingPropertyRequest property : request.properties()) {
            String key = property.key().trim();
            if (key.equals("value")) {
                throw new AppException(CharacterErrorCode.CHARACTER_SETTING_KEY_INVALID);
            }
            if (!keys.add(key)) {
                throw new AppException(CharacterErrorCode.CHARACTER_SETTING_KEY_DUPLICATED);
            }
            valueJson.set(key, toValueNode(property.value(), property.valueType()));
        }
        return valueJson;
    }

    /**
     * 문자열 입력을 선언된 설정 값 유형의 JSON 노드로 파싱한다.
     */
    private JsonNode toValueNode(String value, SettingValueType valueType) {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        String normalizedValue = value.trim();
        try {
            return switch (valueType) {
                case NUMBER -> JsonNodeFactory.instance.numberNode(new BigDecimal(normalizedValue));
                case BOOLEAN -> toBooleanNode(normalizedValue);
                case JSON -> objectMapper.readTree(normalizedValue);
                case STRING, UNKNOWN -> JsonNodeFactory.instance.textNode(normalizedValue);
            };
        } catch (NumberFormatException | JsonProcessingException exception) {
            throw new AppException(CharacterErrorCode.CHARACTER_SETTING_VALUE_INVALID);
        }
    }

    /**
     * true와 false만 허용해 문자열 입력을 boolean JSON 값으로 변환한다.
     */
    private JsonNode toBooleanNode(String value) {
        if (value.equalsIgnoreCase("true")) {
            return JsonNodeFactory.instance.booleanNode(true);
        }
        if (value.equalsIgnoreCase("false")) {
            return JsonNodeFactory.instance.booleanNode(false);
        }
        throw new AppException(CharacterErrorCode.CHARACTER_SETTING_VALUE_INVALID);
    }

    /**
     * 현재 Fact와 요청의 전체 목표 상태를 비교해 변경·삭제된 Fact는 historical로 전환하고,
     * 추가·변경된 값은 원문 근거가 없는 새로운 수동 정정 Fact로 저장한다.
     * 화면 편집 범위에 포함되지 않는 TIME Fact는 비교에서 제외해 그대로 유지한다.
     */
    private void applyManualCorrections(
            WorkCharacter character,
            List<CharacterFact> currentFacts,
            Map<FactIdentity, DesiredFact> desiredFacts
    ) {
        Map<FactIdentity, CharacterFact> currentByIdentity = new HashMap<>();
        for (CharacterFact currentFact : currentFacts) {
            if (currentFact.getFactType() == CharacterFactType.TIME) {
                continue;
            }
            currentByIdentity.put(
                    new FactIdentity(currentFact.getFactType(), currentFact.getFactKey()),
                    currentFact
            );
        }

        List<CharacterFact> manualFacts = new ArrayList<>();
        for (Map.Entry<FactIdentity, CharacterFact> entry : currentByIdentity.entrySet()) {
            DesiredFact desiredFact = desiredFacts.get(entry.getKey());
            if (desiredFact == null || !isSameValue(entry.getValue(), desiredFact)) {
                entry.getValue().markHistorical();
            }
        }
        for (Map.Entry<FactIdentity, DesiredFact> entry : desiredFacts.entrySet()) {
            CharacterFact currentFact = currentByIdentity.get(entry.getKey());
            DesiredFact desiredFact = entry.getValue();
            if (currentFact == null || !isSameValue(currentFact, desiredFact)) {
                CharacterFact manualFact = characterMapper.toManualFact(
                        character,
                        desiredFact.factType(),
                        desiredFact.factKey(),
                        desiredFact.factValue(),
                        desiredFact.valueJson()
                );
                manualFact.markCurrent();
                manualFacts.add(manualFact);
            }
        }
        characterFactRepository.saveAll(manualFacts);
    }

    /**
     * AGE/LEVEL은 구조화된 숫자를 우선 비교하고, 나머지는 표시값과 JSON 전체가 같거나
     * 레거시 scalar의 null JSON 표현만 다르면 동일한 설정값으로 판단한다.
     */
    private boolean isSameValue(CharacterFact currentFact, DesiredFact desiredFact) {
        if (isCoreNumericFact(currentFact.getFactType())) {
            BigDecimal currentNumber = resolveFactNumber(
                    currentFact.getValueJson(),
                    currentFact.getFactValue()
            );
            BigDecimal desiredNumber = resolveFactNumber(
                    desiredFact.valueJson(),
                    desiredFact.factValue()
            );
            if (currentNumber != null && desiredNumber != null) {
                return currentNumber.compareTo(desiredNumber) == 0;
            }
        }
        if (!Objects.equals(currentFact.getFactValue(), desiredFact.factValue())) {
            return false;
        }
        return isSameJson(currentFact.getValueJson(), desiredFact.valueJson())
                || isEquivalentLegacyScalar(currentFact, desiredFact);
    }

    private boolean isCoreNumericFact(CharacterFactType factType) {
        return factType == CharacterFactType.AGE || factType == CharacterFactType.LEVEL;
    }

    /**
     * AGE/LEVEL snapshot의 기준인 valueJson.value를 우선 사용하고, 없으면 표시값을 숫자로 해석한다.
     */
    private BigDecimal resolveFactNumber(JsonNode valueJson, String factValue) {
        JsonNode valueNode = valueJson;
        if (valueNode != null && valueNode.isObject()) {
            valueNode = valueNode.get("value");
        }
        if (valueNode != null && valueNode.isNumber()) {
            return valueNode.decimalValue();
        }
        if (factValue == null) {
            return null;
        }
        try {
            return new BigDecimal(factValue.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * valueJson이 없던 scalar Fact와 요청에서 재조립한 {"value": ...}만 의미상 같게 본다.
     * JSON 설정이나 추가 속성이 있는 값은 기존의 전체 JSON 비교를 유지한다.
     */
    private boolean isEquivalentLegacyScalar(CharacterFact currentFact, DesiredFact desiredFact) {
        JsonNode currentValueJson = currentFact.getValueJson();
        JsonNode desiredValueJson = desiredFact.valueJson();
        if ((currentValueJson != null && !currentValueJson.isNull())
                || desiredFact.valueType() == SettingValueType.JSON
                || desiredValueJson == null
                || !desiredValueJson.isObject()
                || desiredValueJson.size() != 1
                || !desiredValueJson.has("value")) {
            return false;
        }
        return isSameJson(
                desiredValueJson.get("value"),
                toValueNode(desiredFact.factValue(), desiredFact.valueType())
        );
    }

    /**
     * 숫자 표현 차이는 허용하면서 객체와 배열의 중첩 구조를 재귀적으로 비교한다.
     */
    private boolean isSameJson(JsonNode current, JsonNode desired) {
        if (current == null || desired == null) {
            return current == desired;
        }
        if (current.isNumber() && desired.isNumber()) {
            return current.decimalValue().compareTo(desired.decimalValue()) == 0;
        }
        if (current.isObject() && desired.isObject()) {
            if (current.size() != desired.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> entry : current.properties()) {
                if (!desired.has(entry.getKey()) || !isSameJson(entry.getValue(), desired.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (current.isArray() && desired.isArray()) {
            if (current.size() != desired.size()) {
                return false;
            }
            for (int index = 0; index < current.size(); index++) {
                if (!isSameJson(current.get(index), desired.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return current.equals(desired);
    }

    /**
     * 최종 current Fact를 조립해 캐릭터의 화면 조회용 대표값과 JSON snapshot을 교체한다.
     */
    private void replaceSnapshots(WorkCharacter character, List<CharacterFact> currentFacts) {
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

    /**
     * 선택 입력 문자열의 앞뒤 공백을 제거하고 빈 문자열은 값 없음으로 정규화한다.
     */
    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Fact 유형과 key로 현재 설정을 식별한다. */
    private record FactIdentity(CharacterFactType factType, String factKey) {
    }

    /** 수정 요청으로 만들 목표 Fact의 사용자 표시값과 구조화된 값을 보관한다. */
    private record DesiredFact(
            CharacterFactType factType,
            String factKey,
            String factValue,
            SettingValueType valueType,
            JsonNode valueJson
    ) {
    }
}
