package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic;

@Schema(description = "Worker 세계관 설정 묶음 비교 완료 요청")
public record WorkerWorldSettingComparisonBatchCompleteRequest(
        @Valid
        @NotNull(message = "비교 문맥 version 목록은 필수입니다.")
        @Size(max = 20, message = "묶음 비교 대상은 최대 20개입니다.")
        List<ContextVersion> contextVersions,

        @Valid
        @NotNull(message = "최종 설정안 목록은 필수입니다.")
        @Size(max = 20, message = "최종 설정안은 최대 20개입니다.")
        List<Decision> decisions,

        Map<String, Object> rawComparisonJson,
        @Pattern(regexp = "[0-9a-f]{64}") String contextToken,
        @Valid @Size(max = 20)
        @Schema(description = "자동 누적 분석에서만 허용하는 후보별 실패. 설정안과 함께 전체 후보를 중복 없이 덮어야 합니다.")
        List<@NotNull Failure> failures,
        @Valid @Size(max = 30) List<@NotNull WorldSettingComparisonDiagnostic> diagnostics
) {
    public WorkerWorldSettingComparisonBatchCompleteRequest {
        failures = failures == null ? List.of() : failures;
        diagnostics = diagnostics == null ? List.of() : diagnostics;
    }
    public WorkerWorldSettingComparisonBatchCompleteRequest(List<ContextVersion> contextVersions,
            List<Decision> decisions, Map<String, Object> rawComparisonJson, String contextToken, List<Failure> failures) {
        this(contextVersions, decisions, rawComparisonJson, contextToken, failures, List.of());
    }
    public WorkerWorldSettingComparisonBatchCompleteRequest(List<ContextVersion> contextVersions,
            List<Decision> decisions, Map<String, Object> rawComparisonJson, String contextToken) {
        this(contextVersions, decisions, rawComparisonJson, contextToken, List.of());
    }
    public WorkerWorldSettingComparisonBatchCompleteRequest(List<ContextVersion> contextVersions,
            List<Decision> decisions, Map<String, Object> rawComparisonJson) {
        this(contextVersions, decisions, rawComparisonJson, null);
    }

    @Schema(description = "독립적인 정상 설정안과 함께 저장할 후보 비교 실패")
    public record Failure(
            @NotEmpty @Size(max = 20) List<@NotNull @Pattern(regexp = "C[1-9][0-9]*") String> sourceCandidateRefs,
            @NotNull AnalysisFailureCode failureCode,
            @NotBlank @Size(max = 1000) String errorMessage,
            @Valid @Size(max = 30) List<@NotNull WorldSettingComparisonDiagnostic> diagnostics
    ) {
        public Failure {
            diagnostics = diagnostics == null ? List.of() : diagnostics;
        }
    }

    public record ContextVersion(
            UUID worldSettingId,

            @PositiveOrZero(message = "비교 대상 version은 0 이상이어야 합니다.")
            long version,
            @Size(max = 160) String provisionalSubjectKey
    ) {
        public ContextVersion(UUID worldSettingId, long version) {
            this(worldSettingId, version, null);
        }
        @jakarta.validation.constraints.AssertTrue(message = "실제 대상과 임시 대상 중 하나가 필요합니다.")
        public boolean isTargetReferenceValid() {
            return (worldSettingId == null) != (provisionalSubjectKey == null);
        }
    }

    @Schema(
            name = "WorkerWorldSettingComparisonBatchDecision",
            description = "묶음 비교가 확정한 하나의 canonical 설정안"
    )
    public record Decision(
            @NotBlank(message = "설정안 ref는 필수입니다.")
            @Pattern(regexp = "D[1-9][0-9]*", message = "설정안 ref 형식이 올바르지 않습니다.")
            @Size(max = 20)
            String decisionRef,

            @NotEmpty(message = "출처 후보 ref는 한 개 이상이어야 합니다.")
            @Size(max = 20, message = "출처 후보는 최대 20개입니다.")
            List<@Pattern(
                    regexp = "C[1-9][0-9]*",
                    message = "후보 ref 형식이 올바르지 않습니다."
            ) String> sourceCandidateRefs,

            @NotBlank(message = "canonical 대상명은 필수입니다.")
            @Size(max = 100)
            String canonicalSubjectName,

            UUID targetWorldSettingId,

            @Size(max = 100)
            String matchedScopeName,

            @Size(max = 100)
            String matchedPropertyName,

            @Size(max = 20, message = "이동할 기존 root 설정은 최대 20개입니다.")
            @Schema(
                    description = "ADD 확정 시 제안 범위 아래로 함께 이동할 기존 root 설정명",
                    nullable = true
            )
            List<@NotBlank @Size(max = 100) String> existingRootPropertyNamesToMove,

            @NotNull(message = "1차 추출값 정리 상태는 필수입니다.")
            WorldSettingConsolidationStatus consolidationStatus,

            @NotNull(message = "세계관 설정 제안 방식은 필수입니다.")
            WorldSettingSuggestedOperation suggestedOperation,

            @Schema(description = "검토 사유. GENERAL_UNCERTAINTY는 단일 출처의 원본 경로·값을 보존합니다. SCOPE_MISMATCH는 누적 분석의 단일 출처 후보와 기존 설정의 서로 다른 범위를 보존할 때만 허용합니다.")
            WorldSettingComparisonReviewReason comparisonReviewReason,

            @Size(max = 100)
            String proposedScopeName,

            @NotBlank(message = "제안 설정명은 필수입니다.")
            @Size(max = 100)
            String proposedSettingName,

            @NotBlank(message = "제안 설정값은 필수입니다.")
            String proposedValue,

            @NotBlank(message = "비교 이유는 필수입니다.")
            String comparisonReason,

            Map<String, Object> rawComparisonJson,
            @Size(max = 160) String provisionalSubjectKey
    ) {
        public Decision(String decisionRef, List<String> sourceCandidateRefs, String canonicalSubjectName,
                UUID targetWorldSettingId, String matchedScopeName, String matchedPropertyName,
                List<String> existingRootPropertyNamesToMove, WorldSettingConsolidationStatus consolidationStatus,
                WorldSettingSuggestedOperation suggestedOperation, WorldSettingComparisonReviewReason comparisonReviewReason,
                String proposedScopeName, String proposedSettingName, String proposedValue, String comparisonReason,
                Map<String, Object> rawComparisonJson) {
            this(decisionRef, sourceCandidateRefs, canonicalSubjectName, targetWorldSettingId, matchedScopeName,
                    matchedPropertyName, existingRootPropertyNamesToMove, consolidationStatus, suggestedOperation,
                    comparisonReviewReason, proposedScopeName, proposedSettingName, proposedValue, comparisonReason,
                    rawComparisonJson, null);
        }
        @jakarta.validation.constraints.AssertTrue(message = "실제 대상과 임시 대상을 동시에 지정할 수 없습니다.")
        public boolean isTargetReferenceValid() {
            return targetWorldSettingId == null || provisionalSubjectKey == null;
        }
    }
}
