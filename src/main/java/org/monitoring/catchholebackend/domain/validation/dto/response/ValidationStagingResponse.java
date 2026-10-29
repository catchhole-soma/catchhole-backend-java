package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "오류 검사 우선 접수/기준 상태. validationJobId와 executionStatus는 실제 전용 검수 실행입니다. extractionJobId/extractionStatus는 현재 report의 검토 종료 receipt가 만든 설정 추출 Job이며 종료 전에는 null/NOT_STARTED입니다. 후보 세대·확정은 별개입니다.")
public record ValidationStagingResponse(UUID reportId, UUID baselineId, UUID batchId, UUID episodeId,
        boolean current, long revision, long reportVersion, long baselineRevision, ValidationReportMode mode,
        UUID previousReportId, ValidationApplicationReference previousApplicationReceipt, String baselineFingerprint, long targetRevision,
        UUID validationJobId, ValidationExecutionStatus executionStatus, UUID extractionJobId, UUID candidateGeneration, String candidateDigest,
        ValidationPreparationStatus extractionStatus, ValidationPreparationStatus candidateStatus,
        ValidationPreparationStatus errorReviewStatus, ValidationPreparationStatus baselineStatus,
        ValidationReportStatus status, String holdReason, String confirmedStateHash, String baselineHash,
        @Schema(description = "기준 READY는 오류 검사/검색 준비 완료가 아닙니다.") String sourcePreparationStatus) {
    /**
     * errorReviewStatus·extraction은 ValidationReviewGate가 계산한다. errorReviewStatus: NOT_STARTED 실행 전/중단, PENDING 판단 남음,
     * READY 검토 종료 가능 또는 종료됨, INVALIDATED 무효 report. extractionStatus: 종료 전 NOT_STARTED, release Job 진행 PENDING, 성공 READY, 실패·취소 FAILED.
     */
    public static ValidationStagingResponse from(ValidationReport r, ValidationPreparationStatus errorReviewStatus, UUID extractionJobId, ValidationPreparationStatus extractionStatus) {
        var application = r.getPreviousApplicationId() == null ? null : new ValidationApplicationReference(r.getPreviousApplicationKind(), r.getPreviousApplicationId(), r.getPreviousApplicationJobId(), r.getPreviousApplicationAt());
        return new ValidationStagingResponse(r.getId(), r.getId(), r.getBatch().getId(), r.getEpisode().getId(),
                r.isCurrent(), r.getRevision(), r.getVersion(), r.getBaselineRevision(), r.getMode(), r.getPreviousReportId(), application,
                r.getBaselineFingerprint(), r.getSourceContentRevision(), r.getValidationJob()==null?null:r.getValidationJob().getId(), r.getValidationJob()==null?ValidationExecutionStatus.NOT_STARTED:r.getValidationJob().getStatus(), extractionJobId, null, null,
                extractionStatus, ValidationPreparationStatus.NOT_STARTED, errorReviewStatus,
                r.getBaselineStatus(), r.getStatus(), r.getHoldReason(), r.getConfirmedStateHash(), r.getBaselineHash(), "NOT_PREPARED");
    }
}
