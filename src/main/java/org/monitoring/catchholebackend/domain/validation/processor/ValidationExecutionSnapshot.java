package org.monitoring.catchholebackend.domain.validation.processor;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationExecutionMapper;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationExecutionRepository;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class ValidationExecutionSnapshot {
    private final ValidationExecutionRepository results;
    private final ValidationExecutionMapper mapper;
    public Map<String,Object> readCheckpoint(ValidationJob job) {
        var branches=new EnumMap<ValidationBranch,ValidationCheckpoint>(ValidationBranch.class);
        for(var branch:ValidationBranch.values())results.checkpoint(job.getReport().getId(),branch).ifPresent(c->branches.put(branch,c));
        return mapper.toCheckpointResponse(job,results.stages(job.getReport().getId()),results.checks(job.getReport().getId()),branches);
    }
}
