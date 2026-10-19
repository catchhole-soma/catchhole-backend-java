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
    public void process(UUID workId, long expectedInputVersion) {
        var work = workRepository.findByIdForUpdate(workId).orElse(null);
        if (work == null || work.getLifecycleStatus() != WorkLifecycleStatus.ACTIVE) return;
        // 큐 조회 뒤 바뀐 입력은 다음 조회가 처리한다. 실패 시도와 다른 세대를 섞지 않는다.
        if (work.getRelationshipInputVersion() != expectedInputVersion) return;
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
    public void recordFailure(UUID workId, long attemptedVersion) {
        var work = workRepository.findByIdForUpdate(workId).orElse(null);
        if (work == null || work.getLifecycleStatus() != WorkLifecycleStatus.ACTIVE) return;
        // 처리 롤백과 이 트랜잭션 사이에 저장된 새 설정은 실패한 적이 없다.
        if (work.getRelationshipInputVersion() != attemptedVersion) return;
        var state = stateRepository.findByWorkId(workId).orElse(null);
        if (attemptedVersion <= (state == null ? 0 : state.getProcessedVersion())) return;
        if (state == null) state = stateRepository.save(RelationshipProcessingState.create(work));
        state.fail(attemptedVersion, LocalDateTime.now());
    }
}
