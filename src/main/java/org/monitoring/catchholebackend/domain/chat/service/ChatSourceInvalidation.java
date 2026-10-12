package org.monitoring.catchholebackend.domain.chat.service;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.episode.event.EpisodeSourcePurgeRequestedEvent;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
@RequiredArgsConstructor
public class ChatSourceInvalidation {
    private final EpisodeSourcePurgeRequestRepository requests;
    private final ChatServiceImpl service;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void sourceChanged(EpisodeSourcePurgeRequestedEvent event) {
        requests.findById(event.requestId()).ifPresent(r -> service.invalidateWork(r.getWorkId()));
    }
}
