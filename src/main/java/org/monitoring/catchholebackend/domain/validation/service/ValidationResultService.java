package org.monitoring.catchholebackend.domain.validation.service;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
public interface ValidationResultService {
    Map<String,Object> publishValidationPlan(UUID id,UUID lease,Publish command);
    Map<String,Object> publishValidationUnit(UUID id,UUID lease,Unit command);
    Map<String,Object> reviseValidationChecks(UUID id,UUID lease,Revise command);
    Map<String,Object> publishValidationCheckpoint(UUID id,UUID lease,Checkpoint command);
    Map<String,Object> completeValidationJob(UUID id,UUID lease,Complete command);
    Map<String,Object> getValidationProgress(Long member,UUID work,UUID report);
}
