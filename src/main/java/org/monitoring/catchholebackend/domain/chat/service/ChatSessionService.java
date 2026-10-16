package org.monitoring.catchholebackend.domain.chat.service;

import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.Context;
import org.monitoring.catchholebackend.domain.chat.dto.ChatSessionContract.*;
import org.monitoring.catchholebackend.domain.chat.entity.ChatQuestion;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

import java.util.UUID;

public interface ChatSessionService {
    Session createSession(Long member, UUID work, UUID requestId);

    Session getSession(Long member, UUID work, UUID session);

    PageResponse<Session> listSessions(Long member, UUID work, int page, int size);

    PageResponse<Message> listMessages(Long member, UUID work, UUID session, int page, int size);

    void closeSession(Long member, UUID work, UUID session);

    void attachQuestion(ChatQuestion question, UUID session);

    void requireOpen(ChatQuestion question);

    void requireIdle(ChatQuestion question);

    void touchAfterAnswer(ChatQuestion question);

    Context addConversation(Context context, ChatQuestion question);

    void closeWorkSessions(UUID work);

    void expireSession(UUID session);

    java.util.List<UUID> expiredSessionIds();
}
