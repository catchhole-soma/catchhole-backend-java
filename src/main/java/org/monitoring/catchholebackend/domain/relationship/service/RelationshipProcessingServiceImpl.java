package org.monitoring.catchholebackend.domain.relationship.service;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipProcessingResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipProcessingState;
import org.monitoring.catchholebackend.domain.relationship.mapper.RelationshipProcessingMapper;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipAutomationProcessor;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipProcessingStateRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationshipProcessingServiceImpl implements RelationshipProcessingService {
    private final WorkRepository workRepository;
    private final RelationshipProcessingStateRepository stateRepository;
    private final RelationshipAutomationProcessor processor;
    private final RelationshipProcessingMapper mapper;

    @Override
    public RelationshipProcessingResponse getStatus(Long memberId, UUID workId) {
        var work = workRepository.getOwnedWork(workId, memberId);
        return mapper.toResponse(work, stateRepository.findByWorkId(workId).orElse(null));
    }

    @Override
    @Transactional
    public RelationshipProcessingResponse requestProcessing(Long memberId, UUID workId) {
        var work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        var state = stateRepository.findByWorkId(workId).orElse(null);
        // 응답 유실/연속 클릭은 같은 대기를 공유한다. 실패 재시도도 새 후보 ID를 만들지 않는다.
        if (work.getRelationshipInputVersion() <= (state == null ? 0 : state.getProcessedVersion())) {
            work.markRelationshipInputChanged();
        }
        if (state != null) state.clearFailure();
        return mapper.toResponse(work, state);
    }

    @Override
    @Transactional
    public void process(UUID workId) {
        var work = workRepository.findByIdForUpdate(workId).orElse(null);
        if (work == null || work.getLifecycleStatus() != WorkLifecycleStatus.ACTIVE) return;
        var state = stateRepository.findByWorkId(workId).orElse(null);
        long processed = state == null ? 0 : state.getProcessedVersion();
        if (work.getRelationshipInputVersion() <= processed) return;
        if (state != null && state.getRetryAt() != null && state.getRetryAt().isAfter(LocalDateTime.now())
                && state.getAttemptedVersion() == work.getRelationshipInputVersion()) return;
        var result = processor.process(work);
        if (state == null) state = stateRepository.save(RelationshipProcessingState.create(work));
        state.complete(work.getRelationshipInputVersion(), result.autoConfirmed(), result.pending(),
                result.unsupported(), LocalDateTime.now());
    }

    @Override
    @Transactional
    public void recordFailure(UUID workId) {
        var work = workRepository.findByIdForUpdate(workId).orElse(null);
        if (work == null || work.getLifecycleStatus() != WorkLifecycleStatus.ACTIVE) return;
        var state = stateRepository.findByWorkId(workId)
                .orElseGet(() -> stateRepository.save(RelationshipProcessingState.create(work)));
        if (work.getRelationshipInputVersion() > state.getProcessedVersion()) {
            state.fail(work.getRelationshipInputVersion(), LocalDateTime.now());
        }
    }
}
