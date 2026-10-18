package org.monitoring.catchholebackend.domain.relationship.processor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipProcessingInput;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipProcessingStateRepository;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipProcessingService;
import org.springframework.transaction.TransactionSystemException;

@DisplayName("가족 관계 처리 스케줄러의 실패 입력 세대 보존")
class RelationshipProcessingSchedulerTest {

    @Test
    @DisplayName("트랜잭션 커밋 실패도 큐에서 잡은 동일 입력 세대로 기록하고 다음 작품을 처리한다")
    void commitFailureRecordsCapturedGenerationAndContinuesNextWork() {
        RelationshipProcessingStateRepository queue = mock(RelationshipProcessingStateRepository.class);
        RelationshipProcessingService service = mock(RelationshipProcessingService.class);
        RelationshipProcessingInput failed = new RelationshipProcessingInput(UUID.randomUUID(), 7L);
        RelationshipProcessingInput next = new RelationshipProcessingInput(UUID.randomUUID(), 19L);
        when(queue.findReadyInputs(any(), any())).thenReturn(List.of(failed, next));
        // 서비스 본문 밖의 커밋 단계 예외도 scheduler가 같은 세대에 귀속해야 한다.
        doThrow(new TransactionSystemException("synthetic commit failure"))
                .when(service).process(failed.workId(), failed.inputVersion());

        new RelationshipProcessingScheduler(queue, service).processPending();

        var calls = inOrder(service);
        calls.verify(service).process(failed.workId(), failed.inputVersion());
        calls.verify(service).recordFailure(failed.workId(), failed.inputVersion());
        calls.verify(service).process(next.workId(), next.inputVersion());
        verifyNoMoreInteractions(service);
    }
}
