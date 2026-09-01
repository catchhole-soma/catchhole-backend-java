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

@Schema(description = "Worker 세계관 설정 묶음 비교 완료 요청")
public record WorkerWorldSettingComparisonBatchCompleteRequest(
        @Valid
        @NotNull(message = "비교 문맥 version 목록은 필수입니다.")
        @Size(max = 20, message = "묶음 비교 대상은 최대 20개입니다.")
        List<ContextVersion> contextVersions,

        @Valid
        @NotEmpty(message = "최종 설정안은 한 개 이상이어야 합니다.")
        @Size(max = 20, message = "최종 설정안은 최대 20개입니다.")
        List<Decision> decisions,

        Map<String, Object> rawComparisonJson
) {

    public record ContextVersion(
            @NotNull(message = "비교 대상 ID는 필수입니다.")
            UUID worldSettingId,

            @PositiveOrZero(message = "비교 대상 version은 0 이상이어야 합니다.")
            long version
    ) {
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

            Map<String, Object> rawComparisonJson
    ) {
    }
}
