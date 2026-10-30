package org.monitoring.catchholebackend.domain.validation.service;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJob;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
public interface ValidationUsageService {
    Map<String,Object> reserveValidationUsage(UUID id,UUID lease,Reserve command);
    Map<String,Object> reportValidationUsage(UUID id,UUID lease,UUID request,Usage command);
    void releaseReservedValidationUsage(ValidationJob job,AiTokenUsageOutcome reason);
    void ensureValidationRetryCapacity(ValidationJob job);
    List<Map<String,Object>> getValidationUsage(UUID id);
}
