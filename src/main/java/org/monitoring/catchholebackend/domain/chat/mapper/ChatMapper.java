package org.monitoring.catchholebackend.domain.chat.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.entity.ChatRun;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ChatMapper {
    private final ObjectMapper json = new ObjectMapper();

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("채팅 계약 직렬화 실패");
        }
    }

    public Result result(String value) {
        try {
            return value == null ? null : json.readValue(value, Result.class);
        } catch (Exception e) {
            throw new IllegalStateException("채팅 계약 역직렬화 실패");
        }
    }

    public Set<String> receipts(String value) {
        try {
            return value == null
                    ? new TreeSet<>()
                    : json.readValue(value, new TypeReference<TreeSet<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("근거 계약 역직렬화 실패");
        }
    }

    public View view(ChatRun run, List<AiTokenUsage> calls) {
        return new View(
                1,
                run.getQuestion() == null ? null : run.getQuestion().getId(),
                run.getId(),
                run.getGeneration(),
                run.getState(),
                result(run.getResultJson()),
                run.getFailure(),
                calls.stream().anyMatch(u -> u.getStatus() == AiTokenUsageStatus.RESERVED),
                calls.stream().anyMatch(u -> u.getStatus() == AiTokenUsageStatus.UNKNOWN),
                run.getDeadline());
    }

    public CallView call(AiTokenUsage u) {
        return new CallView(
                u.getRequestId(),
                u.getStatus(),
                u.getReservedTokens(),
                u.getInputTokens(),
                u.getCachedInputTokens(),
                u.getOutputTokens());
    }

    public Context toContext(
            ChatRun r,
            org.monitoring.catchholebackend.global.config.chat.ChatProperties properties) {
        var q = r.getQuestion();
        return new Context(
                1,
                q == null ? null : q.getId(),
                r.getId(),
                r.getWork().getId(),
                r.getWork().getMember().getId(),
                q == null ? null : q.getQuestion(),
                q == null ? null : q.getEpisodeFrom(),
                q == null ? null : q.getEpisodeTo(),
                r.getDeadline(),
                r.getTokenBudget(),
                properties.maxCalls(),
                properties.maxToolCalls(),
                r.operational());
    }

    public Dispatch toDispatch(ChatRun run, String capability) {
        return new Dispatch(1, run.getId(), capability, run.getDeadline());
    }
}
