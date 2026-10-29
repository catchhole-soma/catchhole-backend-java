package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.episode.repository.*;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationRecheckRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.*;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReportStatus;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ValidationRecheckServiceImpl implements ValidationRecheckService {
    private final WorkRepository works;
    private final EpisodeRepository episodes;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final ValidationReportRepository reports;
    private final ValidationRequestKeyRepository keys;
    private final ValidationCorrectionRepository corrections;
    private final ValidationBaselineService baselines;
    private final AnalysisStateJournal journal;
    private final EntityManager em;
    private final ValidationWorkerService workers;
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewGate reviewGate;
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess access;

    @Override @Transactional
    public ValidationStagingResponse recheckValidation(Long memberId,UUID workId,UUID reportId,ValidationRecheckRequest request) {
        var work=works.getOwnedWorkForUpdate(workId,memberId);
        var input=JsonNodeFactory.instance.objectNode().put("reportId",reportId.toString())
                .put("expectedReportVersion",request.expectedReportVersion()).put("contentHash",request.expectedContentHash())
                .put("key",request.expectedContentS3Key()).put("version",request.expectedContentS3Version())
                .put("contentRevision",request.expectedContentRevision())
                .put("baselineHash",request.expectedBaselineHash()).put("correctionReceiptId",request.correctionReceiptId()==null?null:request.correctionReceiptId().toString());
        var hash=journal.hash(input); var keyed=keys.findByWorkIdAndRequestKey(workId,request.idempotencyKey());
        if(keyed.isPresent()) {
            if(!"RECHECK".equals(keyed.get().getRequestKind()) || !hash.equals(keyed.get().getRequestHash())) throw error(ValidationErrorCode.VALIDATION_IDEMPOTENCY_CONFLICT);
            workers.queuePrepared(keyed.get().getReport());
            return staging(keyed.get().getReport());
        }
        var previous=reports.findByIdAndWorkId(reportId,workId).orElseThrow(()->error(ValidationErrorCode.VALIDATION_NOT_FOUND));
        em.flush(); em.refresh(previous);
        if(!previous.isCurrent() || previous.getVersion()!=request.expectedReportVersion()) throw error(ValidationErrorCode.VALIDATION_REPORT_VERSION_CONFLICT);
        var target=episodes.findByIdAndWorkIdAndStatusNot(previous.getEpisode().getId(),workId,EpisodeStatus.ARCHIVED)
                .orElseThrow(()->error(ValidationErrorCode.VALIDATION_TARGET_INVALID));
        em.refresh(target);
        if(!episodes.findFirstByWorkIdAndStatusNotOrderByEpisodeNoDesc(workId,EpisodeStatus.ARCHIVED).map(e->e.getId().equals(target.getId())).orElse(false)
                || purges.blocksSource(target.getId(),target.getContentS3Key())) throw error(ValidationErrorCode.VALIDATION_TARGET_INVALID);
        if(!Objects.equals(target.getContentHash(),request.expectedContentHash()) || !Objects.equals(target.getContentS3Key(),request.expectedContentS3Key())
                || !Objects.equals(target.getContentS3Version(),request.expectedContentS3Version())
                || target.getContentRevision()!=request.expectedContentRevision()) throw error(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
        if(em.createQuery("select count(j) from AnalysisJob j where j.work.id=:work and j.status in ('PENDING','RUNNING') and (j.episode.id=:episode or exists (select t.id from AnalysisJob owner join owner.targetEpisodes t where owner.id=j.id and t.id=:episode))",Long.class)
                .setParameter("work",workId).setParameter("episode",target.getId()).getSingleResult()!=0) throw error(ValidationErrorCode.VALIDATION_TARGET_INVALID);
        if(request.correctionReceiptId()!=null) {
            var correction=corrections.findByIdAndWorkId(request.correctionReceiptId(),workId).orElseThrow(()->error(ValidationErrorCode.VALIDATION_CORRECTION_CONFLICT));
            if(!correction.getReport().getId().equals(reportId) || correction.getReportVersion()!=previous.getVersion()
                    || request.expectedBaselineHash()!=null && !request.expectedBaselineHash().equals(correction.getBaselineHash())) throw error(ValidationErrorCode.VALIDATION_CORRECTION_CONFLICT);
        } else if(!Objects.equals(request.expectedBaselineHash(),previous.getBaselineFingerprint())
                || previous.getBaselineFingerprint()==null && previous.getBaselineSeed()!=null) {
            throw error(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
        }
        if(previous.getBaselineFingerprint()!=null && previous.getStatus()!=ValidationReportStatus.INVALIDATED && target.getContentRevision()==previous.getSourceContentRevision()
                && Objects.equals(target.getContentHash(),previous.getSourceContentHash()) && Objects.equals(target.getContentS3Key(),previous.getSourceContentS3Key())
                && Objects.equals(target.getContentS3Version(),previous.getSourceContentS3Version())) throw error(ValidationErrorCode.VALIDATION_RECHECK_INPUT_UNCHANGED);
        var application=previousApplication(workId,target.getId());
        // GH312: 파일 교체 뒤에는 회차의 현재 원본이 새 batch에 있다. release Job이 같은 batch로 생성되도록 새 report를 그 batch에 묶는다.
        var currentBatch=access.currentUploadBatch(target.getId()).filter(b->b.getWork().getId().equals(workId))
                .orElseThrow(()->error(ValidationErrorCode.VALIDATION_TARGET_INVALID));
        baselines.retire(previous); em.flush(); // partial unique current index: old pointer must leave before the insert.
        var next=reports.save(ValidationReport.recheck(previous,currentBatch,hash,application));
        baselines.prepare(next); workers.queuePrepared(next); keys.save(ValidationRequestKey.create(work,request.idempotencyKey(),next,"RECHECK",hash)); em.flush();
        return staging(next);
    }

    @Override public ValidationStagingResponse getCurrentValidation(Long memberId,UUID workId,UUID episodeId) {
        works.getOwnedWork(workId,memberId);
        return staging(reports.findByEpisodeIdAndCurrentTrue(episodeId).filter(r->r.getWork().getId().equals(workId))
                .orElseThrow(()->error(ValidationErrorCode.VALIDATION_NOT_FOUND)));
    }

    private ValidationApplicationReference previousApplication(UUID workId,UUID episodeId) {
        // A successful extraction is not an application receipt. Only actual application/confirmation rows count.
        var automatic=em.createQuery("select j from AnalysisJob j where j.work.id=:work and j.automaticAppliedAt is not null and (j.episode.id=:episode or exists (select t.id from AnalysisJob owner join owner.targetEpisodes t where owner.id=j.id and t.id=:episode)) order by j.automaticAppliedAt desc,j.id desc",AnalysisJob.class)
                .setParameter("work",workId).setParameter("episode",episodeId).setMaxResults(1).getResultList();
        if(!automatic.isEmpty()) {var j=automatic.getFirst();return new ValidationApplicationReference("AUTOMATIC_APPLICATION",j.getId(),j.getId(),j.getAutomaticAppliedAt());}
        var character=em.createQuery("select c from SettingCandidate c left join c.analysisJob j where c.work.id=:work and c.reviewStatus='CONFIRMED' and (c.episode.id=:episode or j.episode.id=:episode) order by c.id desc",SettingCandidate.class)
                .setParameter("work",workId).setParameter("episode",episodeId).setMaxResults(1).getResultList();
        if(!character.isEmpty()) {var c=character.getFirst();return new ValidationApplicationReference("CHARACTER_CONFIRMATION",c.getId(),c.getAnalysisJob()==null?null:c.getAnalysisJob().getId(),null);}
        var world=em.createQuery("select c from WorldSettingCandidate c where c.work.id=:work and c.reviewStatus='CONFIRMED' and (c.sourceEpisode.id=:episode or c.analysisJob.episode.id=:episode) order by c.reviewedAt desc,c.id desc",WorldSettingCandidate.class)
                .setParameter("work",workId).setParameter("episode",episodeId).setMaxResults(1).getResultList();
        if(!world.isEmpty()) {var c=world.getFirst();return new ValidationApplicationReference("WORLD_SETTING_CONFIRMATION",c.getId(),c.getAnalysisJob()==null?null:c.getAnalysisJob().getId(),c.getReviewedAt());}
        return null;
    }
    private ValidationStagingResponse staging(ValidationReport report) {var extraction=reviewGate.extraction(report);return ValidationStagingResponse.from(report,reviewGate.errorReviewStatus(report),extraction.jobId(),extraction.status());}
    private AppException error(ValidationErrorCode code) {return new AppException(code);}
}
