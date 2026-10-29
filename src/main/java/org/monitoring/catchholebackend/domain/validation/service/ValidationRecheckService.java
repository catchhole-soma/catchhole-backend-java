package org.monitoring.catchholebackend.domain.validation.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationRecheckRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;

public interface ValidationRecheckService {
    ValidationStagingResponse recheckValidation(Long memberId,UUID workId,UUID reportId,ValidationRecheckRequest request);
    ValidationStagingResponse getCurrentValidation(Long memberId,UUID workId,UUID episodeId);
}
