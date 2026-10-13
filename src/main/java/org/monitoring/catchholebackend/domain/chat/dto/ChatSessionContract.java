package org.monitoring.catchholebackend.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import java.time.*;
import java.util.UUID;

public final class ChatSessionContract {
    private ChatSessionContract() {}

    @Schema(name = "ChatSessionCreateV1", description = "새 대화 생성. 응답 유실 시 같은 requestId로 재전송합니다.")
    public record Create(@NotNull UUID requestId) {}

    @Schema(name = "ChatSessionV1", description = "활성 일회성 대화. 마지막 질문·답변부터 2시간 뒤 만료됩니다.")
    public record Session(
            UUID id, UUID workId, LocalDateTime createdAt, Instant expiresAt, long questionCount) {}

    @Schema(name = "ChatMessageV1", description = "순서가 고정된 질문과 최신 실행 답변 한 쌍. 본문이 무효화되면 null입니다.")
    public record Message(
            UUID questionId,
            long sequence,
            Instant submittedAt,
            String question,
            ChatContract.View execution) {}
}
