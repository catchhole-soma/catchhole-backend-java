package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "Worker 세계관 설정 1차 추출 후보 게시 요청")
public record WorkerWorldSettingCandidatePublishRequest(
        @Valid
        @NotNull(message = "세계관 설정 후보 목록은 필수입니다.")
        List<Candidate> candidates
) {

    private static final List<BigDecimal> SUPPORTED_CONFIDENCE_VALUES = List.of(
            new BigDecimal("0.65"),
            new BigDecimal("0.80"),
            new BigDecimal("0.95")
    );

    public record Candidate(
            @NotNull(message = "세계관 설정 분류는 필수입니다.")
            WorldSettingCategory category,

            @NotBlank(message = "세계관 대상명은 필수입니다.")
            @Size(max = 100, message = "세계관 대상명은 100자 이하여야 합니다.")
            String subjectName,

            @NotBlank(message = "세계관 설정명은 필수입니다.")
            @Size(max = 100, message = "세계관 설정명은 100자 이하여야 합니다.")
            String settingName,

            @NotBlank(message = "세계관 설정값은 필수입니다.")
            String extractedValue,

            @Valid
            @NotEmpty(message = "세계관 설정 근거는 한 개 이상이어야 합니다.")
            List<EvidenceSpan> evidenceSpans,

            @NotNull(message = "추출 신뢰도는 필수입니다.")
            BigDecimal extractionConfidence,

            Map<String, Object> rawExtractionJson
    ) {

        @AssertTrue(message = "추출 신뢰도는 0.65, 0.80, 0.95 중 하나여야 합니다.")
        public boolean hasSupportedExtractionConfidence() {
            return extractionConfidence == null || SUPPORTED_CONFIDENCE_VALUES.stream()
                    .anyMatch(supported -> supported.compareTo(extractionConfidence) == 0);
        }
    }

    public record EvidenceSpan(
            @NotBlank(message = "세계관 설정 근거 인용문은 필수입니다.")
            String quote,

            @PositiveOrZero(message = "세계관 설정 근거 시작 위치는 0 이상이어야 합니다.")
            Integer startOffset,

            @PositiveOrZero(message = "세계관 설정 근거 종료 위치는 0 이상이어야 합니다.")
            Integer endOffset
    ) {
    }
}
