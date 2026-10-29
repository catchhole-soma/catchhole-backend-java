package org.monitoring.catchholebackend.domain.validation.dto;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.*;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.validation.type.*;

/** Versioned trusted-server wire. Never pass scope, IDs, leases or budgets as model arguments. */
public final class ValidationExecutionContract {
    private ValidationExecutionContract() {}
    @Schema(name="ValidationExecutionClaimV1",description="검수 기능을 광고하는 선점 요청")
    public record Claim(@Min(1) @Max(1) int contractVersion,@NotBlank @Size(max=100) String workerId,@NotEmpty Set<String> capabilities) {}
    @Schema(name="ValidationExecutionScopeV1",description="서버 발급 불변 입력과 실행 세대. 모델 인자가 아닙니다.")
    public record Scope(@Min(1) @Max(1) int contractVersion,@NotNull UUID reportId,@Min(1) long generation,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String inputDigest) {}
    @Schema(name="ValidationExecutionLeaseV1",description="현재 실행의 heartbeat 입력")
    public record Lease(@NotNull @Valid Scope scope) {}
    @Schema(name="ValidationExecutionCitationV1",description="실제 원문의 Unicode code point 인용. 위치를 바꾸어 보정하지 않습니다.")
    public record Citation(@NotBlank String sourceRef,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceHash,String sourceVersion,@Min(0) int start,@Min(1) int end,@NotEmpty String quote) {}
    @Schema(name="ValidationExecutionPublishV1",description="검증된 의미 계획 단계. 실패 단계는 failureCode로 별도 저장합니다.")
    public record Publish(@NotNull @Valid Scope scope,@NotBlank @Size(max=200) String idempotencyKey,@NotNull ValidationBranch branch,
            @NotNull ValidationStageKind stage,@NotBlank @Size(max=200) String ref,@NotNull Map<String,Object> payload,@NotNull @Valid List<Citation> citations,String failureCode) {}
    @Schema(name="ValidationExecutionUnitV1",description="현재 check 또는 typo 구간 결과. 정상 0건도 완료 기록이 필요합니다.")
    public record Unit(@NotNull @Valid Scope scope,@NotBlank @Size(max=200) String idempotencyKey,@NotNull ValidationBranch branch,
            @NotBlank @Size(max=200) String ref,UUID checkId,Long expectedCheckRevision,String checkInputDigest,
            @NotBlank String outcome,@NotNull Map<String,Object> payload,@NotNull @Valid List<Citation> citations) {}
    @Schema(name="ValidationExecutionChangeV1",description="새 단서가 바꾸는 검사 항목의 CAS와 전체 의미 상태")
    public record Change(@NotNull UUID checkId,@Min(1) long expectedRevision,@NotBlank String expectedInputDigest,@NotNull Map<String,Object> state) {}
    @Schema(name="ValidationExecutionReviseV1",description="영향 질문을 한 트랜잭션에서 revision 변경하는 요청")
    public record Revise(@NotNull @Valid Scope scope,@NotBlank @Size(max=200) String idempotencyKey,@NotBlank @Size(max=200) String reason,
            @NotEmpty @Valid List<Change> changes,@NotNull @Valid List<Citation> citations) {}
    @Schema(name="ValidationExecutionCheckpointV1",description="다른 분기와 독립적인 checkpoint version CAS")
    public record Checkpoint(@NotNull @Valid Scope scope,@NotBlank @Size(max=200) String idempotencyKey,@NotNull ValidationBranch branch,
            @Min(0) long expectedVersion,@NotNull Map<String,Object> payload,@NotNull @Valid List<Citation> citations) {}
    @Schema(name="ValidationExecutionCompleteV1",description="Worker 선언 대신 서버 필수 처리 집계로 종료하는 요청")
    public record Complete(@NotNull @Valid Scope scope,@NotBlank @Size(max=200) String idempotencyKey,@Size(max=100) String failureCode) {}
    @Schema(name="ValidationExecutionRetryV1",description="같은 입력에서 미완료만 다시 접수하는 공개 CAS 요청")
    public record Retry(@NotNull @Min(0) Long expectedReportVersion,@NotBlank @Size(max=100) String idempotencyKey) {}
    @Schema(name="ValidationExecutionReserveV1",description="서버가 고정한 역할·모델의 실제 호출 예약")
    public record Reserve(@NotNull @Valid Scope scope,@NotNull UUID requestId,@NotNull ValidationRole role,@NotBlank String model,@Min(1) long reservedTokens) {}
    public enum UsageKind { SETTLE, UNKNOWN, RELEASE }
    @Schema(name="ValidationExecutionUsageV1",description="원래 예약 lease·세대로 늦은 실제 사용량도 정산하는 요청")
    public record Usage(@NotNull @Valid Scope scope,@NotNull UsageKind kind,Long inputTokens,Long cachedInputTokens,Long outputTokens,AiTokenUsageOutcome outcome) {}
}
