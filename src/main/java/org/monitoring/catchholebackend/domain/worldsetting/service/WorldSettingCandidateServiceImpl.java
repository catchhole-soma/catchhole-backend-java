package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateBatchCounts;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldSettingCandidateServiceImpl implements WorldSettingCandidateService {

    private final WorkRepository workRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final WorldSettingMapper worldSettingMapper;
    private final AiTokenService aiTokenService;

    @Override
    public WorldSettingCandidateListResponse getCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            WorldSettingReviewStatus reviewStatus,
            WorldSettingCategory category,
            WorldSettingOperation operation,
            int page,
            int size
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        validateBatch(work, batchId);
        Page<WorldSettingCandidate> candidatePage = worldSettingCandidateRepository.findReviewPage(
                work.getId(),
                batchId,
                reviewStatus,
                category,
                operation,
                PageRequest.of(page, size)
        );
        WorldSettingCandidateBatchCounts counts = worldSettingCandidateRepository.countReviewSummary(
                work.getId(),
                batchId,
                WorldSettingReviewStatus.PENDING_REVIEW,
                WorldSettingComparisonStatus.PENDING,
                WorldSettingComparisonStatus.PROCESSING,
                WorldSettingComparisonStatus.FAILED,
                WorldSettingComparisonStatus.RECOMPARISON_REQUIRED
        );
        AnalysisJobEpisodeRange episodeRange =
                analysisJobRepository.findEpisodeRangeByWorkIdAndBatchId(work.getId(), batchId);
        List<WorldSettingCandidateResponse> responses = candidatePage.getContent().stream()
                .map(worldSettingMapper::toCandidateResponse)
                .toList();
        return new WorldSettingCandidateListResponse(
                batchId,
                episodeRange.getEpisodeStartNo(),
                episodeRange.getEpisodeEndNo(),
                episodeRange.getEpisodeCount(),
                counts.getTotalCandidateCount(),
                counts.getReviewedCandidateCount(),
                counts.getPendingCandidateCount(),
                counts.getPendingComparisonCount(),
                counts.getProcessingComparisonCount(),
                counts.getFailedComparisonCount(),
                counts.getRecomparisonRequiredCount(),
                PageResponse.from(candidatePage, responses)
        );
    }

    @Override
    public WorldSettingCandidateResponse getCandidate(
            Long memberId,
            UUID workId,
            UUID batchId,
            UUID candidateId
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        validateBatch(work, batchId);
        WorldSettingCandidate candidate = worldSettingCandidateRepository
                .findByIdAndWorkIdAndAnalysisJobBatchId(candidateId, work.getId(), batchId)
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND));
        return worldSettingMapper.toCandidateResponse(candidate);
    }

    @Override
    @Transactional
    public WorldSettingCandidateResponse updateCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());
        candidate.updateExtractionIdentity(request.category(), request.subjectName(), request.settingName());
        worldSettingCandidateRepository.flush();
        return worldSettingMapper.toCandidateResponse(candidate);
    }

    @Override
    @Transactional
    public WorldSettingCandidateResponse retryComparison(Long memberId, UUID workId, UUID candidateId) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());
        AnalysisJob activeJob = analysisJobRepository
                .findFirstByWorldSettingCandidateIdAndStatusInOrderByCreatedAtDesc(
                        candidateId,
                        List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING)
                )
                .orElse(null);
        if (activeJob != null) {
            if (activeJob.getStatus() == AnalysisJobStatus.RUNNING) {
                throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT);
            }
            return worldSettingMapper.toCandidateResponse(candidate);
        }
        aiTokenService.ensureAnalysisCanStart(memberId);
        candidate.requestRecomparison();
        analysisJobRepository.save(AnalysisJob.createWorldSettingComparison(candidate));
        worldSettingCandidateRepository.flush();
        return worldSettingMapper.toCandidateResponse(candidate);
    }

    @Override
    @Transactional
    public WorldSettingCandidateConfirmResult confirmCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateConfirmRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());

        if (candidate.getReviewStatus() == WorldSettingReviewStatus.CONFIRMED) {
            candidate.confirm(
                    request.operation(),
                    request.category(),
                    request.subjectName(),
                    request.settingName(),
                    request.value(),
                    request.reviewNote(),
                    work.getMember(),
                    candidate.getTargetWorldSetting()
            );
            return WorldSettingCandidateConfirmResult.confirmed(
                    worldSettingMapper.toCandidateResponse(candidate)
            );
        }
        if (candidate.getReviewStatus() == WorldSettingReviewStatus.DISMISSED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT);
        }

        validateComparisonReadyAndOperation(candidate, request);
        WorldSetting comparedTarget = candidate.getTargetWorldSetting();
        WorldSetting currentTarget;
        if (comparedTarget == null) {
            if (!matchesComparedIdentity(candidate, null, request)) {
                return markRecomparisonRequired(candidate);
            }
            String normalizedSubjectName = WorldSettingNameNormalizer.duplicateKey(request.subjectName());
            currentTarget = worldSettingRepository.findByIdentityForUpdate(
                    work.getId(),
                    request.category(),
                    normalizedSubjectName
            ).orElse(null);
            if (currentTarget != null) {
                return markRecomparisonRequired(candidate);
            }
        } else {
            currentTarget = worldSettingRepository.findByIdAndWorkIdForUpdate(
                    comparedTarget.getId(),
                    work.getId()
            ).orElse(null);
            if (currentTarget == null || !matchesComparedIdentity(candidate, currentTarget, request)) {
                return markRecomparisonRequired(candidate);
            }
        }

        WorldSetting appliedTarget;
        if (currentTarget == null) {
            if (request.operation() != WorldSettingOperation.ADD) {
                return markRecomparisonRequired(candidate);
            }
            appliedTarget = worldSettingRepository.saveAndFlush(
                    worldSettingMapper.toEntity(work, request)
            );
        } else {
            if (!isCurrentPropertyCompatible(candidate, currentTarget, request)) {
                return markRecomparisonRequired(candidate);
            }
            currentTarget.applyProperty(request.settingName(), request.value());
            worldSettingRepository.flush();
            appliedTarget = currentTarget;
        }

        candidate.confirm(
                request.operation(),
                request.category(),
                request.subjectName(),
                request.settingName(),
                request.value(),
                request.reviewNote(),
                work.getMember(),
                appliedTarget
        );
        worldSettingCandidateRepository.flush();
        return WorldSettingCandidateConfirmResult.confirmed(
                worldSettingMapper.toCandidateResponse(candidate)
        );
    }

    @Override
    @Transactional
    public WorldSettingCandidateResponse dismissCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateDismissRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSettingCandidate candidate = getCandidateForUpdate(candidateId, work.getId());
        candidate.dismiss(request.reviewNote(), work.getMember());
        worldSettingCandidateRepository.flush();
        return worldSettingMapper.toCandidateResponse(candidate);
    }

    private void validateComparisonReadyAndOperation(
            WorldSettingCandidate candidate,
            WorldSettingCandidateConfirmRequest request
    ) {
        if (candidate.getComparisonStatus() != WorldSettingComparisonStatus.COMPLETED) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        if (request.operation() == WorldSettingOperation.EXCLUDE) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_OPERATION_INVALID);
        }
    }

    private boolean matchesComparedIdentity(
            WorldSettingCandidate candidate,
            WorldSetting comparedTarget,
            WorldSettingCandidateConfirmRequest request
    ) {
        WorldSettingCategory comparedCategory = comparedTarget == null
                ? candidate.getCategory()
                : comparedTarget.getCategory();
        String comparedSubjectName = comparedTarget == null
                ? candidate.getSubjectName()
                : comparedTarget.getSubjectName();
        return comparedCategory == request.category()
                && sameName(comparedSubjectName, request.subjectName())
                && sameName(candidate.getProposedSettingName(), request.settingName());
    }

    private boolean isCurrentPropertyCompatible(
            WorldSettingCandidate candidate,
            WorldSetting currentTarget,
            WorldSettingCandidateConfirmRequest request
    ) {
        boolean propertyExists = currentTarget.hasProperty(request.settingName());
        if (request.operation() == WorldSettingOperation.ADD && propertyExists) {
            return false;
        }
        if ((request.operation() == WorldSettingOperation.UPDATE
                || request.operation() == WorldSettingOperation.MERGE)
                && !propertyExists) {
            return false;
        }

        String currentValue = currentTarget.getPropertyValue(request.settingName());
        boolean alreadyFinal = Objects.equals(currentValue, normalizeValue(request.value()));
        boolean comparisonStillCurrent = Objects.equals(currentValue, candidate.getBeforeValue());
        return alreadyFinal || comparisonStillCurrent;
    }

    private String normalizeValue(String value) {
        return WorldSettingNameNormalizer.displayName(value);
    }

    private boolean sameName(String left, String right) {
        return Objects.equals(
                WorldSettingNameNormalizer.duplicateKey(left),
                WorldSettingNameNormalizer.duplicateKey(right)
        );
    }

    private WorldSettingCandidateConfirmResult markRecomparisonRequired(WorldSettingCandidate candidate) {
        candidate.markRecomparisonRequired();
        worldSettingCandidateRepository.flush();
        return WorldSettingCandidateConfirmResult.recomparisonRequired(
                worldSettingMapper.toCandidateResponse(candidate)
        );
    }

    private void validateBatch(Work work, UUID batchId) {
        uploadBatchRepository.findByIdAndWorkId(batchId, work.getId())
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_BATCH_NOT_FOUND));
    }

    private WorldSettingCandidate getCandidateForUpdate(UUID candidateId, UUID workId) {
        return worldSettingCandidateRepository.findByIdAndWorkIdForUpdate(candidateId, workId)
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND));
    }
}
