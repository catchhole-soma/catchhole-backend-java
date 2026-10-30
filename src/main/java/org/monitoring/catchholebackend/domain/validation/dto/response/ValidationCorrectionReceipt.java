package org.monitoring.catchholebackend.domain.validation.dto.response;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationCorrection;

public record ValidationCorrectionReceipt(UUID correctionId, UUID reportId, long reportVersion, UUID baselineId,
        long baselineRevision, String baselineHash, String entryRef, long entityVersion) {
    public static ValidationCorrectionReceipt from(ValidationCorrection correction) {
        return new ValidationCorrectionReceipt(correction.getId(), correction.getReport().getId(), correction.getReportVersion(),
                correction.getReport().getId(), correction.getBaselineRevision(), correction.getBaselineHash(), correction.getEntryRef(), correction.getEntityVersion());
    }
}
