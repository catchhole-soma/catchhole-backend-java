package org.monitoring.catchholebackend.domain.validation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "검토를 완료한 회차의 추출 결과를 한 번에 반영하는 명시 요청. 서버가 모든 후보를 다시 평가하며 하나라도 반영할 수 없으면 보류 receipt를 반환합니다.")
public record ValidationSettingsApplicationRequest(
        @Schema(description = "검토 요약에서 읽은 reportVersion. 현재 값과 다르면 409", example = "9")
        @NotNull @PositiveOrZero Long expectedReportVersion,
        @Schema(description = "같은 작품 안에서 이 요청을 식별하는 키. 같은 키·같은 입력은 같은 receipt를 반환합니다.", example = "apply-settings-01")
        @NotBlank @Size(max = 100) String idempotencyKey) {}
