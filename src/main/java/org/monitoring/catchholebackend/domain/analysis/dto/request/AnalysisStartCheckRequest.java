package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "새로 분석할 회차 번호. 업로드 전에도 확인할 수 있으며 실제 생성 시 서버가 대상을 다시 확인합니다.")
public record AnalysisStartCheckRequest(
        @NotNull @Size(min = 1, max = 1000)
        List<@NotNull @Min(1) Integer> episodeNumbers
) {}
