package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettingCandidateServiceImpl implements SettingCandidateService {

    private final WorkRepository workRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final SettingCandidateMapper settingCandidateMapper;
    private final SettingCandidatePromotionService settingCandidatePromotionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<SettingCandidateResponse> getSettingCandidates(
            Long memberId,
            UUID workId,
            SettingCandidateReviewStatus reviewStatus,
            String entityName
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        List<SettingCandidate> candidates = findCandidates(work.getId(), reviewStatus, entityName);
        return settingCandidateMapper.toResponseList(candidates);
    }

    @Override
    public SettingCandidateResponse getSettingCandidate(Long memberId, UUID workId, UUID candidateId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        return settingCandidateMapper.toResponse(getCandidateInWork(candidateId, work));
    }

    @Override
    @Transactional
    public SettingCandidateResponse updateSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        SettingCandidate candidate = getCandidateInWork(candidateId, work);

        candidate.updateReviewContent(
                normalizeRequiredText(request.attributeName()),
                normalizeOptionalText(request.attributeValue()),
                request.valueType(),
                toJsonNode(request.valueJson()),
                toJsonNode(request.evidenceSpans())
        );
        return settingCandidateMapper.toResponse(candidate);
    }

    @Override
    @Transactional
    public SettingCandidateResponse updateSettingCandidateCharacterMatch(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateCharacterMatchRequest request
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.validateEditable();

        // 사용자가 기존 캐릭터를 지정하면 즉시 MATCHED로, 신규로 판단하면 confirm 전까지 UNRESOLVED로 둔다.
        switch (request.resolutionType()) {
            case MATCH_EXISTING -> connectExistingCharacter(candidate, work, request.matchedCharacterId());
            case CREATE_NEW -> markCandidateAsNewCharacter(candidate, work, request.entityName());
        }

        return settingCandidateMapper.toResponse(candidate);
    }

    @Override
    @Transactional
    public SettingCandidateReviewStatusResponse confirmSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
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
        Work work = workRepository.getOwnedWork(workId, memberId);
        SettingCandidate candidate = getCandidateInWork(candidateId, work);
        candidate.dismiss();
        return settingCandidateMapper.toReviewStatusResponse(candidate);
    }

    private List<SettingCandidate> findCandidates(
            UUID workId,
            SettingCandidateReviewStatus reviewStatus,
            String entityName
    ) {
        if (StringUtils.hasText(entityName) && reviewStatus != null) {
            return settingCandidateRepository.findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
                    workId,
                    entityName.trim(),
                    reviewStatus
            );
        }
        if (StringUtils.hasText(entityName)) {
            return settingCandidateRepository.findAllByWorkIdAndEntityNameOrderByCreatedAtDesc(
                    workId,
                    entityName.trim()
            );
        }
        if (reviewStatus != null) {
            return settingCandidateRepository.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(workId, reviewStatus);
        }
        return settingCandidateRepository.findAllByWorkIdOrderByCreatedAtDesc(workId);
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
        if (workCharacterRepository.findByWorkIdAndName(work.getId(), normalizedEntityName).isPresent()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED);
        }
        candidate.markAsNewCharacter(normalizedEntityName);
    }

    private SettingCandidate getCandidateInWork(UUID candidateId, Work work) {
        return settingCandidateRepository.findByIdAndWorkId(candidateId, work.getId())
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
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

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.valueToTree(value);
    }
}
