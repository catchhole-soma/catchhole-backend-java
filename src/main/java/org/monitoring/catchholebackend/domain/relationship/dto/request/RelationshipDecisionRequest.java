package org.monitoring.catchholebackend.domain.relationship.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "조회한 버전에 대한 명시적 검토 결정")
public record RelationshipDecisionRequest(
        @Schema(description = "화면에서 조회한 버전", example = "0") @NotNull(message = "필수 항목을 입력해 주세요.") @PositiveOrZero(message = "버전은 0 이상이어야 합니다.") Long expectedVersion
) {}
