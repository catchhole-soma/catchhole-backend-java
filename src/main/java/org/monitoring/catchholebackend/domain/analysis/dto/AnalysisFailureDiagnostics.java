package org.monitoring.catchholebackend.domain.analysis.dto;

import java.util.Set;
import java.util.UUID;

/** 원문·오류 메시지 없이 전달하는 선택적 실패 원인 계약. 실행 식별자는 서버 엔티티가 소유한다. */
public record AnalysisFailureDiagnostics(
        int schemaVersion, String origin, String stage, String reasonCode, String sourceErrorCode,
        Integer httpStatus, String requestId, String traceId, UUID sourceAnalysisJobId,
        UUID sourceCandidateId, UUID sourceEpisodeId, Integer sourceEpisodeNo
) {
    public static final Set<String> ORIGINS = Set.of("SPRING", "PROVIDER", "WORKER");
    public static final Set<String> STAGES = Set.of("CHARACTER_COMPARE",
            "CHARACTER_CONTEXT",
            "CLAIM_INPUT",
            "EXTRACTION",
            "FINALIZE",
            "UNKNOWN",
            "WORLD_COMPARE",
            "WORLD_CONTEXT");
    public static final Set<String> REASONS = Set.of("COMPARISON_RESULT_INVALID",
            "CURRENT_EVIDENCE_BINDING_INVALID",
            "CURRENT_EVIDENCE_MANIFEST_INVALID",
            "CURRENT_EVIDENCE_OFFSET_INVALID",
            "CURRENT_EVIDENCE_QUOTE_NOT_FOUND",
            "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH",
            "CURRENT_EVIDENCE_SOURCE_READ_FAILED",
            "CURRENT_EVIDENCE_STRUCTURE_INVALID",
            "INTERNAL_API_HTTP_ERROR",
            "INTERNAL_API_TRANSPORT_ERROR",
            "LLM_OUTPUT_TRUNCATED",
            "LLM_PROVIDER_ERROR",
            "LLM_RATE_LIMIT",
            "LLM_TIMEOUT",
            "UNKNOWN");
    public static final Set<String> SOURCE_CODES = Set.of("AI_TOKEN_QUOTA_EXHAUSTED",
            "ANALYSIS_CURRENT_EVIDENCE_INVALID",
            "ANALYSIS_CURRENT_EVIDENCE_SOURCE_UNAVAILABLE",
            "ANALYSIS_JOB_CHECKPOINT_INCOMPLETE",
            "ANALYSIS_JOB_LEASE_CONFLICT",
            "ANALYSIS_JOB_STATUS_CONFLICT",
            "ANALYSIS_RUN_PREDECESSOR_INCOMPLETE",
            "ANALYSIS_RUN_STATE_CONFLICT",
            "AUTH_FORBIDDEN",
            "AUTH_UNAUTHORIZED",
            "COMMON_INTERNAL_SERVER_ERROR",
            "CONFLICT",
            "REQUEST_INVALID_ARGUMENT",
            "REQUEST_VALIDATION_FAILED",
            "RESOURCE_NOT_FOUND",
            "SETTING_CANDIDATE_COMPARISON_BATCH_NOT_FOUND",
            "SETTING_CANDIDATE_COMPARISON_BATCH_RESPONSE_INVALID",
            "SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID",
            "SETTING_CANDIDATE_COMPARISON_STALE",
            "SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT",
            "SETTING_CANDIDATE_COMPARISON_TARGET_INVALID",
            "SETTING_CANDIDATE_VALUE_INVALID",
            "SETTING_CANDIDATE_VALUE_JSON_INVALID",
            "SETTING_CANDIDATE_VALUE_TYPE_MISMATCH",
            "SETTING_CANDIDATE_WORKER_JOB_INVALID",
            "UNKNOWN",
            "WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE",
            "WORLD_SETTING_COMPARISON_BATCH_COMPLETION_CONFLICT",
            "WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT",
            "WORLD_SETTING_COMPARISON_TARGET_INVALID",
            "WORLD_SETTING_SUBJECT_RESOLUTION_INVALID",
            "WORLD_SETTING_SUBJECT_RESOLUTION_STALE",
            "WORLD_SETTING_WORKER_JOB_INVALID",
            "billing_hard_limit_reached",
            "billing_not_active",
            "content_filter",
            "insufficient_quota",
            "invalid_request_error",
            "max_output_tokens",
            "quota_exceeded",
            "rate_limit_exceeded",
            "server_error",
            "timeout",
            "usage_limit_reached");

    public AnalysisFailureDiagnostics withoutSourceIdentifiers() {
        return new AnalysisFailureDiagnostics(schemaVersion, origin, stage, reasonCode, sourceErrorCode,
                httpStatus, requestId, traceId, null, null, null, null);
    }
}
