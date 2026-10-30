package org.monitoring.catchholebackend.domain.validation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "모든 항목을 판단한 뒤의 명시적 검토 완료 요청. 서버가 검사 완료·판단·현재 원고/기준을 다시 확인한 뒤 설정 추출 Job을 함께 만듭니다.")
public record ValidationReviewCompletionRequest(
        @Schema(description = "검토 요약에서 읽은 reportVersion. 현재 값과 다르면 409", example = "7")
        @NotNull @PositiveOrZero Long expectedReportVersion,
        @Schema(description = "같은 작품 안에서 이 요청을 식별하는 키. 같은 키·같은 입력은 같은 receipt를 반환합니다.", example = "review-complete-01")
        @NotBlank @Size(max = 100) String idempotencyKey) {}
