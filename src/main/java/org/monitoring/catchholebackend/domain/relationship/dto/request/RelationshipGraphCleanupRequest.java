package org.monitoring.catchholebackend.domain.relationship.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "관계도 1회 정리(GH391) 요청. 작품 하나만 처리합니다")
public record RelationshipGraphCleanupRequest(
        @Schema(description = "정리할 작품 ID") @NotNull(message = "필수 항목을 입력해 주세요.") UUID workId,
        @Schema(description = "true면 Work 잠금 아래 실제로 적용합니다. 생략하거나 false면 아무것도 쓰지 않는 dry-run입니다",
                nullable = true) Boolean apply
) {
    public boolean applyRequested() {
        return Boolean.TRUE.equals(apply);
    }
}
