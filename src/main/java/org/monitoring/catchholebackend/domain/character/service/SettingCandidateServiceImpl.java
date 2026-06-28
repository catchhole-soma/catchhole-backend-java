package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
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
    private final SettingCandidateMapper settingCandidateMapper;
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
        validateEditable(candidate);

        candidate.updateReviewContent(
                request.entityName(),
                request.attributeName(),
                request.attributeValue(),
                request.valueType(),
                toJsonNode(request.valueJson()),
                toJsonNode(request.evidenceSpans())
        );
        return settingCandidateMapper.toResponse(candidate);
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

    private SettingCandidate getCandidateInWork(UUID candidateId, Work work) {
        return settingCandidateRepository.findByIdAndWorkId(candidateId, work.getId())
                .orElseThrow(() -> new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
    }

    private void validateEditable(SettingCandidate candidate) {
        if (!candidate.isPendingReview()) {
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_NOT_EDITABLE);
        }
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.valueToTree(value);
    }
}
