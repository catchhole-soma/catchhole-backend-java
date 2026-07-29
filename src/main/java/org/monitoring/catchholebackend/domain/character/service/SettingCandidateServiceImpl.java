package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidateMapper;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateBatchCounts;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
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
    private final SettingCandidateMapper settingCandidateMapper;
    private final SettingCandidatePromotionService settingCandidatePromotionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public SettingCandidateListResponse getSettingCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            SettingCandidateReviewStatus reviewStatus,
            SettingCandidateMatchStatus matchStatus,
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
                matchStatus,
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
                        settingCandidateMapper.toResponseList(candidatePage.getContent())
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
        return settingCandidateMapper.toResponse(candidate);
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
