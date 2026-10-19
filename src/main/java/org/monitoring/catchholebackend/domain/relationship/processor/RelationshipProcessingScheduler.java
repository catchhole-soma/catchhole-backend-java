package org.monitoring.catchholebackend.domain.relationship.processor;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipProcessingStateRepository;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipProcessingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "relationship.processing", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class RelationshipProcessingScheduler {
    private final RelationshipProcessingStateRepository stateRepository;
    private final RelationshipProcessingService service;

    @Scheduled(fixedDelayString = "${relationship.processing.fixed-delay-ms:10000}")
    public void processPending() {
        for (var input : stateRepository.findReadyInputs(LocalDateTime.now(), PageRequest.of(0, 10))) {
            try {
                service.process(input.workId(), input.inputVersion());
            } catch (RuntimeException exception) {
                // 오류 원문에는 설정/이름이 포함될 수 있어 기록하지 않는다. 다음 작품은 계속 처리한다.
                log.warn("가족 관계 처리 실패; 별도 트랜잭션에서 재시도를 예약합니다.");
                try {
                    service.recordFailure(input.workId(), input.inputVersion());
                } catch (RuntimeException failure) {
                    log.warn("가족 관계 재시도 상태 저장 실패; 변경 버전으로 다시 시도합니다.");
                }
            }
        }
    }
}
