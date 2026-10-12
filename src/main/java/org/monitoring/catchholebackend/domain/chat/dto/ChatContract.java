package org.monitoring.catchholebackend.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Reference;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatContract {
    private ChatContract() {}

    public enum Outcome {
        ANSWER,
        CLARIFICATION,
        INSUFFICIENT_EVIDENCE
    }

    public enum Failure {
        PROVIDER_FAILURE,
        NETWORK_FAILURE,
        QUOTA_EXHAUSTED,
        HANDLER_UNAVAILABLE,
        INTERRUPTED
    }

    public enum ReportKind {
        SETTLE,
        RELEASE,
        UNKNOWN
    }

    @Schema(name = "ChatSubmitV1", description = "단일 질문과 근거 출처 회차 범위. 현재 상태의 시점 복원이 아닙니다.")
    public record Submit(
            @NotBlank @Size(max = 4000) String question,
            @Min(1) Integer episodeFrom,
            @Min(1) Integer episodeTo) {}

    @Schema(name = "ChatRetryV1", description = "실패한 시도 ID에 대한 멱등 재시도")
    public record Retry(@NotNull UUID previousAttemptId) {}

    @Schema(name = "ChatResultV1", description = "완료 결과와 실제 조회한 직접 근거 참조")
    public record Result(
            @NotNull Outcome outcome,
            @NotBlank @Size(max = 16000) String text,
            @NotNull @Size(max = 50) List<@Valid Reference> sources,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 200) String> limitations) {}

    @Schema(name = "ChatFinishV1", description = "결과 또는 실패 중 정확히 하나를 보고")
    public record Finish(@Valid Result result, Failure failure) {}

    @Schema(
            name = "ChatExecutionV1",
            description = "질문의 최신 실행 상태와 저장 결과. usagePending과 usageUnknown은 답변 상태와 독립입니다.")
    public record View(
            int schemaVersion,
            @Schema(description = "재시도 간 유지되는 논리 질문 ID") UUID questionId,
            @Schema(description = "각 실행 시도 ID") UUID attemptId,
            int generation,
            ChatState state,
            Result result,
            Failure failure,
            boolean usagePending,
            boolean usageUnknown,
            Instant deadline) {}

    @Schema(name = "ChatDispatchV1", description = "Spring이 발급하는 실행 capability. 로그에 출력하지 않습니다")
    public record Dispatch(
            int schemaVersion, UUID attemptId, String capability, Instant deadline) {}

    @Schema(name = "ChatContextV1", description = "Spring이 실행에 고정한 인증 범위")
    public record Context(
            int schemaVersion,
            @Schema(description = "재시도 간 유지되는 논리 질문 ID") UUID questionId,
            @Schema(description = "각 실행 시도 ID") UUID attemptId,
            UUID workId,
            Long memberId,
            String question,
            Integer episodeFrom,
            Integer episodeTo,
            Instant deadline,
            long tokenBudget,
            int maxCalls,
            int maxToolCalls,
            boolean operational) {}

    @Schema(name = "ChatReserveV1", description = "provider 단일 호출 전 최대량 예약")
    public record Reserve(
            @Schema(description = "실제 provider 호출마다 새 UUID") @NotNull UUID callId,
            @NotNull AiTokenPurpose purpose,
            @NotBlank @Size(max = 100) String model,
            @Min(1) long reservedTokens) {}

    @Schema(name = "ChatUsageV1", description = "실측 정산·호출 전 해제·사용량 미확인 보고")
    public record Usage(
            @NotNull ReportKind kind,
            @Min(0) Long inputTokens,
            @Min(0) Long cachedInputTokens,
            @Min(0) Long outputTokens,
            AiTokenUsageOutcome outcome) {}

    @Schema(name = "ChatCallViewV1", description = "실제 provider 호출의 예약과 정산 상태")
    public record CallView(
            UUID callId,
            AiTokenUsageStatus status,
            long reservedTokens,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens) {}

    @Schema(name = "ChatOperationV1", description = "운영 백필의 작품·작업자·비과금 정책·예약 예산")
    public record Operation(
            @NotNull UUID workId,
            @NotBlank @Size(max = 100) String actor,
            @NotBlank @Size(max = 100) String policy,
            @Min(1) @Max(10000000) long tokenBudget) {}
}
