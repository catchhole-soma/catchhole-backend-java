package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.episode.repository.*;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationStartRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EpisodeValidationService {
    private final WorkRepository works;
    private final EpisodeRepository episodes;
    private final UploadFileRepository files;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final ValidationBaselineService baselines;
    private final ValidationReportRepository reports;
    private final ValidationRequestKeyRepository keys;
    private final AnalysisStateJournal journal;
    private final EntityManager em;

    @Transactional
    public ValidationStagingResponse start(Long memberId, UUID workId, ValidationStartRequest request) {
        var work = works.getOwnedWorkForUpdate(workId, memberId);
        var input = JsonNodeFactory.instance.objectNode();
        input.put("batchId", request.batchId().toString()).put("episodeId", request.episodeId().toString())
                .put("contentHash", request.expectedContentHash()).put("key", request.expectedContentS3Key())
                .put("version", request.expectedContentS3Version());
        String hash = journal.hash(input);
        var keyed = keys.findByWorkIdAndRequestKey(workId, request.idempotencyKey());
        if (keyed.isPresent()) return sameInput(keyed.get().getReport(), hash);
        var prior = reports.findByEpisodeId(request.episodeId());
        if (prior.isPresent()) {
            if (!prior.get().getWork().getId().equals(workId)) throw error(ValidationErrorCode.VALIDATION_TARGET_INVALID);
            var response = sameInput(prior.get(), hash);
            keys.save(ValidationRequestKey.create(work, request.idempotencyKey(), prior.get()));
            return response;
        }
        var episode = episodes.findByIdAndWorkIdAndStatusNot(request.episodeId(), workId, EpisodeStatus.ARCHIVED)
                .orElseThrow(() -> error(ValidationErrorCode.VALIDATION_TARGET_INVALID));
        var file = episode.getSourceFileId() == null ? null : files.findById(episode.getSourceFileId()).orElse(null);
        if (file == null || file.getFileRole() != org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.EPISODE || !file.getBatch().getWork().getId().equals(workId)
                || !file.getBatch().getId().equals(request.batchId()) || file.getBatch().getUploadType() != UploadType.SINGLE_EPISODE
                || !Objects.equals(file.getEpisodeCount(), 1) || !episode.isValidationUploadEligible()
                || !episodes.findFirstByWorkIdAndStatusNotOrderByEpisodeNoDesc(workId, EpisodeStatus.ARCHIVED)
                        .map(latest -> latest.getId().equals(episode.getId())).orElse(false)
                || purges.existsByEpisodeId(episode.getId())
                || em.createQuery("select count(j) from AnalysisJob j where j.episode.id = :id or exists (select target.id from AnalysisJob owner join owner.targetEpisodes target where owner.id = j.id and target.id = :id)", Long.class)
                        .setParameter("id", episode.getId()).getSingleResult() != 0) {
            throw error(ValidationErrorCode.VALIDATION_TARGET_INVALID);
        }
        if (!Objects.equals(request.expectedContentHash(), episode.getContentHash())
                || !Objects.equals(request.expectedContentS3Key(), episode.getContentS3Key())
                || !Objects.equals(request.expectedContentS3Version(), episode.getContentS3Version())) {
            throw error(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
        }
        boolean priorEpisode = episodes.findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(workId, EpisodeStatus.ARCHIVED)
                .stream().anyMatch(e -> e.getEpisodeNo() < episode.getEpisodeNo());
        if (!priorEpisode && !hasComparisonMaterial(workId)) throw error(ValidationErrorCode.VALIDATION_COMPARISON_MATERIAL_REQUIRED);
        var report = reports.save(ValidationReport.create(work, episode, file.getBatch(), hash));
        baselines.prepare(report);
        keys.save(ValidationRequestKey.create(work, request.idempotencyKey(), report));
        return ValidationStagingResponse.from(report);
    }

    public ValidationStagingResponse get(Long memberId, UUID workId, UUID reportId) {
        works.getOwnedWork(workId, memberId);
        return ValidationStagingResponse.from(reports.findByIdAndWorkId(reportId, workId)
                .orElseThrow(() -> error(ValidationErrorCode.VALIDATION_NOT_FOUND)));
    }
    private boolean hasComparisonMaterial(UUID workId) {
        return em.createQuery("select count(c) from WorkCharacter c where c.work.id = :id and c.status = 'ACTIVE'", Long.class)
                .setParameter("id", workId).getSingleResult() > 0
                || em.createQuery("select count(s) from WorldSetting s where s.work.id = :id", Long.class)
                        .setParameter("id", workId).getSingleResult() > 0
                || em.createQuery("select count(f) from UploadFile f where f.batch.work.id = :id and f.fileRole = 'SETTING_BOOK' and f.archivedAt is null and f.currentSettingBookRevision is not null", Long.class)
                        .setParameter("id", workId).getSingleResult() > 0;
    }
    private ValidationStagingResponse sameInput(ValidationReport report, String hash) {
        if (!report.getRequestHash().equals(hash)) throw error(ValidationErrorCode.VALIDATION_IDEMPOTENCY_CONFLICT);
        return ValidationStagingResponse.from(report);
    }
    private AppException error(ValidationErrorCode code) { return new AppException(code); }
}
