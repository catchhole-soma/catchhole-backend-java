package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaMatch;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateBatchCounts;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettingCandidateServiceImpl implements SettingCandidateService {

    private final WorkRepository workRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final SettingCandidateMapper settingCandidateMapper;
    private final SettingCandidatePromotionService settingCandidatePromotionService;
    private final SettingCandidateSchemaResolver settingCandidateSchemaResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public SettingCandidateListResponse getSettingCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            SettingCandidateReviewStatus reviewStatus,
            Set<SettingCandidateMatchStatus> matchStatuses,
            int page,
            int size
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        uploadBatchRepository.findByIdAndWorkId(batchId, work.getId())
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_BATCH_NOT_FOUND));

        Page<SettingCandidate> candidatePage = settingCandidateRepository.findReviewPage(
                work.getId(),
                batchId,
                reviewStatus,
                matchStatuses == null || matchStatuses.isEmpty()
                        ? EnumSet.allOf(SettingCandidateMatchStatus.class)
                        : EnumSet.copyOf(matchStatuses),
                PageRequest.of(page, size)
        );
        SettingCandidateBatchCounts counts = settingCandidateRepository.countReviewSummary(
                work.getId(),
                batchId,
                SettingCandidateReviewStatus.PENDING_REVIEW,
                SettingCandidateMatchStatus.AMBIGUOUS
        );
        AnalysisJobEpisodeRange episodeRange =
                analysisJobRepository.findEpisodeRangeByWorkIdAndBatchId(work.getId(), batchId);
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(work.getId());

        return new SettingCandidateListResponse(
                batchId,
                episodeRange.getEpisodeStartNo(),
                episodeRange.getEpisodeEndNo(),
                episodeRange.getEpisodeCount(),
                counts.getTotalCandidateCount(),
                counts.getReviewedCandidateCount(),
                counts.getPendingCandidateCount(),
                counts.getMatchRequiredCandidateCount(),
                PageResponse.from(
                        candidatePage,
                        candidatePage.getContent().stream()
                                .map(candidate -> toResponse(candidate, schemas))
                                .toList()
                )
        );
    }

    @Override
    public SettingCandidateResponse getSettingCandidate(
            Long memberId,
            UUID workId,
            UUID batchId,
            UUID candidateId
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        SettingCandidate candidate = settingCandidateRepository
                .findByIdAndWorkIdAndAnalysisJobBatchId(candidateId, work.getId(), batchId)
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(work.getId());
        return toResponse(candidate, schemas);
    }

    @Override
    @Transactional
    public SettingCandidateResponse updateSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.validateEditable();
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(candidate.getWork().getId());

        CandidateReviewContent reviewContent = resolveReviewContent(
                candidate,
                normalizeRequiredText(request.attributeName()),
                normalizeOptionalText(request.attributeValue()),
                schemas
        );
        if (reviewContent.updateRequired()) {
            candidate.updateReviewContent(
                    reviewContent.attributeName(),
                    reviewContent.attributeValue(),
                    reviewContent.valueJson()
            );
        }
        return toResponse(candidate, schemas);
    }

    @Override
    @Transactional
    public SettingCandidateResponse updateSettingCandidateCharacterMatch(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateCharacterMatchRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.validateEditable();

        // 사용자가 기존 캐릭터를 지정하면 즉시 MATCHED로, 신규로 판단하면 confirm 전까지 UNRESOLVED로 둔다.
        switch (request.resolutionType()) {
            case MATCH_EXISTING -> connectExistingCharacter(candidate, work, request.matchedCharacterId());
            case CREATE_NEW -> markCandidateAsNewCharacter(candidate, work, request.entityName());
        }

        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(candidate.getWork().getId());
        return toResponse(candidate, schemas);
    }

    @Override
    @Transactional
    public SettingCandidateReviewStatusResponse confirmSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        SettingCandidate candidate = getCandidateInWork(candidateId, work);

        // 최초 PENDING_REVIEW -> CONFIRMED 전이만 true다. 동일 confirm 재시도는 false로 Fact 중복 생성을 막는다.
        boolean newlyConfirmed = candidate.confirm();
        if (newlyConfirmed) {
            settingCandidatePromotionService.promote(candidate);
        }
        return settingCandidateMapper.toReviewStatusResponse(candidate);
    }

    @Override
    @Transactional
    public SettingCandidateReviewStatusResponse dismissSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.dismiss();
        return settingCandidateMapper.toReviewStatusResponse(candidate);
    }

    private void connectExistingCharacter(SettingCandidate candidate, Work work, UUID matchedCharacterId) {
        if (matchedCharacterId == null) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_REQUIRED);
        }
        WorkCharacter character = workCharacterRepository.findByIdAndWorkId(matchedCharacterId, work.getId())
                .orElseThrow(() -> new AppException(
                        CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID
                ));
        if (character.getStatus() != CharacterStatus.ACTIVE) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID);
        }
        candidate.matchExistingCharacter(character);
    }

    private void markCandidateAsNewCharacter(SettingCandidate candidate, Work work, String entityName) {
        String normalizedEntityName = normalizeRequiredCharacterName(entityName);
        if (workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                normalizedEntityName,
                CharacterStatus.ACTIVE
        ).isPresent()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }
        candidate.markAsNewCharacter(normalizedEntityName);
    }

    private SettingCandidate getCandidateInWork(UUID candidateId, Work work) {
        return settingCandidateRepository.findByIdAndWorkId(candidateId, work.getId())
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
    }

    private CandidateReviewContent resolveReviewContent(
            SettingCandidate candidate,
            String requestedAttributeName,
            String requestedAttributeValue,
            List<CharacterSettingSchema> schemas
    ) {
        SettingCandidateSchemaMatch currentMatch = settingCandidateSchemaResolver.resolve(
                candidate.getAttributeName(),
                candidate.getValueType(),
                schemas
        );
        boolean dynamic = isPatternMatch(currentMatch);
        String currentComparableAttributeName = dynamic
                ? normalizeStoredDynamicAttributeName(candidate.getAttributeName(), currentMatch.matchedSchema())
                : candidate.getAttributeName().trim();
        String nextAttributeName = dynamic
                ? resolveDynamicAttributeName(
                        requestedAttributeName,
                        candidate,
                        currentMatch,
                        schemas
                )
                : resolveFixedAttributeName(candidate, requestedAttributeName);
        String currentComparableAttributeValue = normalizeOptionalText(candidate.getAttributeValue());
        boolean semanticContentChanged = !currentComparableAttributeName.equals(nextAttributeName)
                || !Objects.equals(currentComparableAttributeValue, requestedAttributeValue);
        boolean storedContentNeedsNormalization =
                !candidate.getAttributeName().equals(nextAttributeName)
                        || !Objects.equals(candidate.getAttributeValue(), requestedAttributeValue);
        boolean storedCoreScalarNeedsRepair = hasIncompatibleCoreScalarValueJson(
                candidate,
                currentMatch
        );
        return new CandidateReviewContent(
                nextAttributeName,
                requestedAttributeValue,
                semanticContentChanged || storedCoreScalarNeedsRepair
                        ? rebuildValueJson(
                                candidate,
                                currentMatch,
                                nextAttributeName,
                                requestedAttributeValue,
                                dynamic
                        )
                        : candidate.getValueJson(),
                semanticContentChanged
                        || storedContentNeedsNormalization
                        || storedCoreScalarNeedsRepair
        );
    }

    /**
     * AGE/LEVEL의 숨은 대표값이 존재하지만 숫자가 아니면 같은 표시값 저장도 typed envelope로 수리한다.
     * 대표값 자체가 없거나 이미 숫자인 rich JSON은 기존 근거와 함께 no-op으로 보존한다.
     */
    private boolean hasIncompatibleCoreScalarValueJson(
            SettingCandidate candidate,
            SettingCandidateSchemaMatch schemaMatch
    ) {
        CharacterFactType factType = schemaMatch.matchedSchema().getFactType();
        if (factType != CharacterFactType.AGE && factType != CharacterFactType.LEVEL) {
            return false;
        }

        JsonNode valueNode = candidate.getValueJson();
        if (valueNode == null || valueNode.isNull()) {
            return false;
        }
        if (valueNode.isObject()) {
            valueNode = valueNode.get("value");
        }
        return valueNode != null && !valueNode.isNull() && !valueNode.isNumber();
    }

    private SettingCandidateResponse toResponse(
            SettingCandidate candidate,
            List<CharacterSettingSchema> schemas
    ) {
        AttributeNameEditMetadata metadata = resolveAttributeNameEditMetadata(candidate, schemas);
        return settingCandidateMapper.toResponse(
                candidate,
                metadata.attributeNameEditable(),
                metadata.attributeNamePrefix()
        );
    }

    private AttributeNameEditMetadata resolveAttributeNameEditMetadata(
            SettingCandidate candidate,
            List<CharacterSettingSchema> schemas
    ) {
        try {
            SettingCandidateSchemaMatch match = settingCandidateSchemaResolver.resolve(
                    candidate.getAttributeName(),
                    candidate.getValueType(),
                    schemas
            );
            if (!isPatternMatch(match)) {
                return AttributeNameEditMetadata.NOT_EDITABLE;
            }
            return new AttributeNameEditMetadata(
                    true,
                    dynamicPatternPrefix(match.matchedSchema())
            );
        } catch (AppException exception) {
            return AttributeNameEditMetadata.NOT_EDITABLE;
        }
    }

    private String resolveFixedAttributeName(SettingCandidate candidate, String requestedAttributeName) {
        String currentAttributeName = candidate.getAttributeName().trim();
        if (!currentAttributeName.equals(requestedAttributeName)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_NOT_EDITABLE);
        }
        return currentAttributeName;
    }

    private String resolveDynamicAttributeName(
            String requestedAttributeName,
            SettingCandidate candidate,
            SettingCandidateSchemaMatch currentMatch,
            List<CharacterSettingSchema> schemas
    ) {
        String normalizedAttributeName =
                normalizeDynamicAttributeName(requestedAttributeName, currentMatch.matchedSchema());
        SettingCandidateSchemaMatch requestedMatch;
        try {
            requestedMatch = settingCandidateSchemaResolver.resolve(
                    normalizedAttributeName,
                    candidate.getValueType(),
                    schemas
            );
        } catch (AppException exception) {
            if (exception.getResultCode()
                    == CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS) {
                throw exception;
            }
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        if (requestedMatch.matchedSchema() != currentMatch.matchedSchema()
                || !isPatternMatch(requestedMatch)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        return normalizedAttributeName;
    }

    private String normalizeDynamicAttributeName(String attributeName, CharacterSettingSchema schema) {
        String prefix = dynamicPatternPrefix(schema);
        String trimmedAttributeName = attributeName.trim();
        if (!trimmedAttributeName.startsWith(prefix)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        String suffix = trimmedAttributeName.substring(prefix.length()).trim();
        if (!StringUtils.hasText(suffix)
                || !StringUtils.hasText(suffix.replace('_', ' '))) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        return prefix + suffix.replaceAll("\\s+", "_");
    }

    private String normalizeStoredDynamicAttributeName(String attributeName, CharacterSettingSchema schema) {
        String prefix = dynamicPatternPrefix(schema);
        String trimmedAttributeName = attributeName.trim();
        String suffix = trimmedAttributeName.substring(prefix.length()).trim();
        return prefix + suffix.replaceAll("\\s+", "_");
    }

    private String dynamicPatternPrefix(CharacterSettingSchema schema) {
        String pattern = schema.getAttributePattern();
        if (pattern == null || !pattern.trim().endsWith(".*")) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID);
        }
        String trimmedPattern = pattern.trim();
        return trimmedPattern.substring(0, trimmedPattern.length() - 1);
    }

    private boolean isPatternMatch(SettingCandidateSchemaMatch match) {
        return !match.factKey().equals(match.matchedSchema().getSchemaKey().trim());
    }

    private JsonNode rebuildValueJson(
            SettingCandidate candidate,
            SettingCandidateSchemaMatch schemaMatch,
            String attributeName,
            String attributeValue,
            boolean dynamic
    ) {
        ObjectNode valueJson = objectMapper.createObjectNode();
        if (candidate.getValueType() == SettingValueType.JSON) {
            valueJson.put("name", resolveStructuredName(schemaMatch, attributeName, dynamic));
            return valueJson;
        }

        JsonNode scalarValue = toScalarValueNode(candidate, attributeValue);
        validateCoreEditedValue(schemaMatch.matchedSchema().getFactType(), scalarValue);
        valueJson.set("value", scalarValue);
        if (dynamic) {
            valueJson.put("name", dynamicDisplayName(schemaMatch.matchedSchema(), attributeName));
        }
        return valueJson;
    }

    private JsonNode toScalarValueNode(SettingCandidate candidate, String attributeValue) {
        if (attributeValue == null) {
            return NullNode.getInstance();
        }
        return switch (candidate.getValueType()) {
            case STRING, UNKNOWN -> objectMapper.getNodeFactory().textNode(attributeValue);
            case NUMBER -> toNumberNode(attributeValue);
            case BOOLEAN -> toBooleanNode(attributeValue);
            case JSON -> throw new IllegalStateException("JSON 후보는 scalar value로 변환할 수 없습니다.");
        };
    }

    private JsonNode toNumberNode(String attributeValue) {
        try {
            return objectMapper.getNodeFactory().numberNode(new BigDecimal(attributeValue));
        } catch (NumberFormatException exception) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_EDIT_VALUE_INVALID);
        }
    }

    private JsonNode toBooleanNode(String attributeValue) {
        if (attributeValue.equalsIgnoreCase("true")) {
            return objectMapper.getNodeFactory().booleanNode(true);
        }
        if (attributeValue.equalsIgnoreCase("false")) {
            return objectMapper.getNodeFactory().booleanNode(false);
        }
        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_EDIT_VALUE_INVALID);
    }

    private void validateCoreEditedValue(CharacterFactType factType, JsonNode valueNode) {
        if (factType != CharacterFactType.AGE && factType != CharacterFactType.LEVEL) {
            return;
        }
        if (valueNode == null || !valueNode.isNumber()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
        }
        try {
            if (valueNode.decimalValue().intValueExact() < 0) {
                throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
            }
        } catch (ArithmeticException exception) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_VALUE_INVALID);
        }
    }

    private String resolveStructuredName(
            SettingCandidateSchemaMatch schemaMatch,
            String attributeName,
            boolean dynamic
    ) {
        if (dynamic) {
            return dynamicDisplayName(schemaMatch.matchedSchema(), attributeName);
        }
        return schemaMatch.matchedSchema().getDisplayName().trim();
    }

    private String dynamicDisplayName(CharacterSettingSchema schema, String attributeName) {
        String prefix = dynamicPatternPrefix(schema);
        return attributeName.substring(prefix.length())
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeRequiredText(String value) {
        return value.trim();
    }

    private String normalizeRequiredCharacterName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_NEW_CHARACTER_NAME_REQUIRED);
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        return value == null ? null : value.trim();
    }

    private record CandidateReviewContent(
            String attributeName,
            String attributeValue,
            JsonNode valueJson,
            boolean updateRequired
    ) {
    }

    private record AttributeNameEditMetadata(
            boolean attributeNameEditable,
            String attributeNamePrefix
    ) {
        private static final AttributeNameEditMetadata NOT_EDITABLE =
                new AttributeNameEditMetadata(false, null);
    }
}
