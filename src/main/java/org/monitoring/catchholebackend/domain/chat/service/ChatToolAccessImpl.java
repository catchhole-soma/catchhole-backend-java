package org.monitoring.catchholebackend.domain.chat.service;

import static org.monitoring.catchholebackend.domain.chat.exception.ChatErrorCode.*;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.chat.mapper.ChatMapper;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.settingtool.dto.*;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ChatToolAccessImpl implements ChatToolAccess {
    private final ChatServiceImpl executions;
    private final ChatProperties properties;
    private final ChatMapper mapper;
    private final ChatRetrievalSources retrieval;

    @Transactional
    public RetrievalSources retrievalSources(UUID id, String token, UUID runner, RetrievalSourcesRequest request) {
        var r = executions.authorized(id, token);
        executions.running(r, runner);
        if (r.operational()) throw new AppException(CHAT_SCOPE_INVALID);
        if (r.getToolCount() >= properties.maxToolCalls()) throw new AppException(CHAT_LIMIT_EXCEEDED);
        if (request == null || request.sources() == null || request.sources().isEmpty())
            throw new AppException(CHAT_SCOPE_INVALID);
        retrieval.validate(r, request.sources());
        executions.running(r, runner);
        var receipts = mapper.receipts(r.getReceiptJson());
        request.sources().forEach(source -> receipts.add(retrieval.receipt(source)));
        r.recordTool(mapper.encode(receipts));
        return new RetrievalSources(1, List.copyOf(request.sources()));
    }

    @Transactional
    public Access begin(UUID id, String token, UUID runner, SettingToolQuery q) {
        var r = executions.authorized(id, token);
        executions.running(r, runner);
        if (r.operational()) throw new AppException(CHAT_SCOPE_INVALID);
        if (r.getToolCount() >= properties.maxToolCalls())
            throw new AppException(CHAT_LIMIT_EXCEEDED);
        var question = r.getQuestion();
        Integer from = q.episodeFrom(), to = q.episodeTo();
        boolean filtered =
                List.of(
                                SettingTool.CHARACTER_HISTORY,
                                SettingTool.WORLD_HISTORY,
                                SettingTool.CHARACTER_EVIDENCE,
                                SettingTool.WORLD_EVIDENCE)
                        .contains(q.tool());
        // Unranged field catalogs also serve current-value lookup. Only explicitly
        // ranged, subject-specific catalogs use the execution's source boundary.
        filtered |= q.targetId() != null
                && (q.tool() == SettingTool.CHARACTER_FIELDS || q.tool() == SettingTool.WORLD_FIELDS)
                && (from != null || to != null);
        if (filtered) {
            if (question.getEpisodeFrom() != null) {
                if (from != null && from < question.getEpisodeFrom())
                    throw new AppException(CHAT_SCOPE_INVALID);
                if (from == null) from = question.getEpisodeFrom();
            }
            if (question.getEpisodeTo() != null) {
                if (to != null && to > question.getEpisodeTo())
                    throw new AppException(CHAT_SCOPE_INVALID);
                if (to == null) to = question.getEpisodeTo();
            }
            if (from != null && to != null && from > to) throw new AppException(CHAT_SCOPE_INVALID);
        }
        r.recordTool(r.getReceiptJson());
        return new Access(
                r.getWork().getMember().getId(),
                r.getWork().getId(),
                new SettingToolQuery(
                        q.tool(),
                        q.targetId(),
                        q.query(),
                        q.settingKey(),
                        q.scopeName(),
                        from,
                        to,
                        q.page(),
                        q.size(),
                        q.factType(),
                        q.valueEquals(),
                        q.worldCategory(),
                        q.readToken()));
    }

    @Transactional
    public void complete(UUID id, String token, UUID runner, SettingToolResponse response) {
        var r = executions.authorized(id, token);
        executions.running(r, runner);
        var receipts = mapper.receipts(r.getReceiptJson());
        for (var item : response.items().content())
            if (item.evidence() != null
                    && item.evidence().status() == SettingToolResponse.EvidenceStatus.AVAILABLE)
                receipts.add(response.tool() + ":" + item.id());
        r.updateReceipts(mapper.encode(receipts));
    }
}
