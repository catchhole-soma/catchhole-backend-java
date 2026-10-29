package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "오류 검사 우선 접수/기준 상태. validationJobId와 executionStatus는 실제 전용 검수 실행이며 검토 완료 release·설정 추출·후보는 별개입니다. errorReviewStatus는 작가 판단 진행이며 READY도 추출을 시작하지 않습니다.")
public record ValidationStagingResponse(UUID reportId, UUID baselineId, UUID batchId, UUID episodeId,
        boolean current, long revision, long reportVersion, long baselineRevision, ValidationReportMode mode,
        UUID previousReportId, ValidationApplicationReference previousApplicationReceipt, String baselineFingerprint, long targetRevision,
        UUID validationJobId, ValidationExecutionStatus executionStatus, UUID extractionJobId, UUID candidateGeneration, String candidateDigest,
        ValidationPreparationStatus extractionStatus, ValidationPreparationStatus candidateStatus,
        ValidationPreparationStatus errorReviewStatus, ValidationPreparationStatus baselineStatus,
        ValidationReportStatus status, String holdReason, String confirmedStateHash, String baselineHash,
        @Schema(description = "기준 READY는 오류 검사/검색 준비 완료가 아닙니다.") String sourcePreparationStatus) {
    /** errorReviewStatus는 ValidationReviewGate가 계산한다. NOT_STARTED 실행 전/중단, PENDING 판단 남음, READY 검토 종료 가능, INVALIDATED 무효 report. */
    public static ValidationStagingResponse from(ValidationReport r, ValidationPreparationStatus errorReviewStatus) {
        var application = r.getPreviousApplicationId() == null ? null : new ValidationApplicationReference(r.getPreviousApplicationKind(), r.getPreviousApplicationId(), r.getPreviousApplicationJobId(), r.getPreviousApplicationAt());
        return new ValidationStagingResponse(r.getId(), r.getId(), r.getBatch().getId(), r.getEpisode().getId(),
                r.isCurrent(), r.getRevision(), r.getVersion(), r.getBaselineRevision(), r.getMode(), r.getPreviousReportId(), application,
                r.getBaselineFingerprint(), r.getSourceContentRevision(), r.getValidationJob()==null?null:r.getValidationJob().getId(), r.getValidationJob()==null?ValidationExecutionStatus.NOT_STARTED:r.getValidationJob().getStatus(), null, null, null,
                ValidationPreparationStatus.NOT_STARTED, ValidationPreparationStatus.NOT_STARTED, errorReviewStatus,
                r.getBaselineStatus(), r.getStatus(), r.getHoldReason(), r.getConfirmedStateHash(), r.getBaselineHash(), "NOT_PREPARED");
    }
}
