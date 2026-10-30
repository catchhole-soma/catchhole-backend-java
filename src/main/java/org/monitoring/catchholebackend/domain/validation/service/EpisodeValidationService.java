package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationStartRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationEligibilityResponse;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationIntakeEligibility;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationNextEpisodeAdmission;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.ValidationEligibilityReason;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EpisodeValidationService {
    private final WorkRepository works;
    private final ValidationBaselineService baselines;
    private final ValidationReportRepository reports;
    private final ValidationRequestKeyRepository keys;
    private final AnalysisStateJournal journal;
    private final EntityManager em;
    private final ValidationWorkerService workers;
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewGate reviewGate;
    private final ValidationIntakeEligibility intakeEligibility;
    private final ValidationNextEpisodeAdmission nextEpisodeAdmission;

    @Transactional
    public ValidationStagingResponse start(Long memberId, UUID workId, ValidationStartRequest request) {
        var work = works.getOwnedWorkForUpdate(workId, memberId);
        var input = JsonNodeFactory.instance.objectNode();
        input.put("batchId", request.batchId().toString()).put("episodeId", request.episodeId().toString())
                .put("contentHash", request.expectedContentHash()).put("key", request.expectedContentS3Key())
                .put("version", request.expectedContentS3Version());
        String hash = journal.hash(input);
        var keyed = keys.findByWorkIdAndRequestKey(workId, request.idempotencyKey());
        if (keyed.isPresent()) {
            if (!"START".equals(keyed.get().getRequestKind()) || !hash.equals(keyed.get().getRequestHash())) throw error(ValidationErrorCode.VALIDATION_IDEMPOTENCY_CONFLICT);
            workers.queuePrepared(keyed.get().getReport());
            return staging(keyed.get().getReport());
        }
        // #314 B1: 접수 자격은 조회 전용 자격 조회와 같은 판정을 사용한다. 요청 값(batch·원고 버전) 검사만 여기서 한다.
        var intake = intakeEligibility.judge(workId, request.episodeId());
        if (intake.reason() == ValidationEligibilityReason.REPORT_EXISTS) {
            var prior = intake.currentReport();
            // 최초 접수의 새 키 복구는 최초 report에만 허용한다. 재검수 입력을 start로 대체하지 않는다.
            if (prior.getRevision() != 1) throw error(ValidationErrorCode.VALIDATION_TARGET_INVALID);
            var response = sameInput(prior, hash);
            keys.save(ValidationRequestKey.create(work, request.idempotencyKey(), prior));
            return response;
        }
        if (!intake.targetAccepted()) throw intake.toException();
        var episode = intake.episode();
        var file = intake.sourceFile();
        if (!file.getBatch().getId().equals(request.batchId())) throw error(ValidationErrorCode.VALIDATION_TARGET_INVALID);
        if (!Objects.equals(request.expectedContentHash(), episode.getContentHash())
                || !Objects.equals(request.expectedContentS3Key(), episode.getContentS3Key())
                || !Objects.equals(request.expectedContentS3Version(), episode.getContentS3Version())) {
            throw error(ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
        }
        if (!intake.eligible()) throw intake.toException();
        var report = reports.save(ValidationReport.create(work, episode, file.getBatch(), hash));
        baselines.prepare(report);
        workers.queuePrepared(report);
        keys.save(ValidationRequestKey.create(work, request.idempotencyKey(), report));
        em.flush();
        return staging(report);
    }

    public ValidationStagingResponse get(Long memberId, UUID workId, UUID reportId) {
        works.getOwnedWork(workId, memberId);
        return staging(reports.findByIdAndWorkId(reportId, workId)
                .orElseThrow(() -> error(ValidationErrorCode.VALIDATION_NOT_FOUND)));
    }
    /**
     * #314 B1 접수 전 자격 조회. 접수(start)와 같은 {@link ValidationIntakeEligibility} 판정을 읽기만 하며 report·Job·요청 키를 만들지 않는다.
     * episodeId가 없으면 작품 단위 다음 회차 등록 차단만 반환한다. 권위는 계속 접수 요청이다.
     */
    public ValidationEligibilityResponse getEpisodeValidationEligibility(Long memberId, UUID workId, UUID episodeId) {
        works.getOwnedWork(workId, memberId);
        if (episodeId == null) return ValidationEligibilityResponse.workOnly(nextEpisodeAdmission.blocking(workId));
        var intake = intakeEligibility.judge(workId, episodeId);
        var current = intake.currentReport();
        return ValidationEligibilityResponse.of(episodeId, intake,
                current == null ? null : new ValidationEligibilityResponse.CurrentReport(current.getId(), current.getRevision(), current.getMode(), reviewGate.reviewStage(current)));
    }
    private ValidationStagingResponse sameInput(ValidationReport report, String hash) {
        if (!report.getRequestHash().equals(hash)) throw error(ValidationErrorCode.VALIDATION_IDEMPOTENCY_CONFLICT);
        workers.queuePrepared(report);
        return staging(report);
    }
    private ValidationStagingResponse staging(ValidationReport report) { var extraction = reviewGate.extraction(report); return ValidationStagingResponse.from(report, reviewGate.errorReviewStatus(report), extraction.jobId(), extraction.status(), extraction.candidateStatus(), reviewGate.reviewStage(report)); }
    private AppException error(ValidationErrorCode code) { return new AppException(code); }
}
