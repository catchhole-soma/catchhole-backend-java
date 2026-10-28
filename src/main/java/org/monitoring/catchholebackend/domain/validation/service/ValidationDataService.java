package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;

public interface ValidationDataService {
    JsonNode readValidationData(Long memberId, UUID workId, UUID reportId, ValidationDataRequest request);
}
