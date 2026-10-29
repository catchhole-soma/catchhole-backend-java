package org.monitoring.catchholebackend.domain.validation.service;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
public interface ValidationWorkerService {
    void queuePrepared(ValidationReport report);
    Map<String,Object> claimValidationJob(Claim capabilities);
    Map<String,Object> heartbeatValidationJob(UUID id,UUID lease,Lease command);
    Map<String,Object> loadValidationCheckpoint(UUID id,UUID lease);
    ValidationStagingResponse retryValidation(Long member,UUID work,UUID report,Retry command);
}
