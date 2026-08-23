package org.monitoring.catchholebackend.domain.episode.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.episode.processor.EpisodeSourcePurgeProcessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class EpisodeSourcePurgeRequestedEventListener {

    private final EpisodeSourcePurgeProcessor processor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void processAfterReplacementCommit(EpisodeSourcePurgeRequestedEvent event) {
        try {
            processor.processRequest(event.requestId());
        } catch (RuntimeException exception) {
            // 요청 row는 이미 커밋되어 있으므로 스케줄러가 다시 처리한다.
            log.error("커밋 직후 회차 교체 원문 파기 시작 실패: requestId={}", event.requestId(), exception);
        }
    }
}
