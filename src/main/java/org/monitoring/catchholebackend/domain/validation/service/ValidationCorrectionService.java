package org.monitoring.catchholebackend.domain.validation.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationCorrectionRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationCorrectionReceipt;

public interface ValidationCorrectionService {
    ValidationCorrectionReceipt correctSettingAndLinkBaseline(Long memberId,UUID workId,UUID reportId,ValidationCorrectionRequest request);
}
