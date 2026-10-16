package org.monitoring.catchholebackend.domain.relationship.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

@Schema(description = "현재 프로필에 기여하는 Fact 근거")
public record RelationshipEvidenceRequest(
        @Schema(description = "현재 프로필 Fact ID") @NotNull(message = "필수 항목을 입력해 주세요.") UUID sourceFactId,
        @Schema(description = "relationship-sources에서 받은 현재 슬롯 토큰")
        @NotNull(message = "필수 항목을 입력해 주세요.") @Pattern(regexp = "[0-9a-f]{64}", message = "현재 프로필 조회에서 받은 근거 토큰을 전달해 주세요.") String sourceToken
) {}
