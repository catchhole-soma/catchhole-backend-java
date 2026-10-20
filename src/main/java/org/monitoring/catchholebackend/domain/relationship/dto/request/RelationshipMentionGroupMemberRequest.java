package org.monitoring.catchholebackend.domain.relationship.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

@Schema(description = "같은 미등록 인물로 묶을 후보와 사용자가 확인한 버전")
public record RelationshipMentionGroupMemberRequest(
        @Schema(description = "현재 가계도에 표시 가능한 미등록 후보 ID") @NotNull(message = "필수 항목을 입력해 주세요.") UUID candidateId,
        @Schema(description = "조회 당시 후보 버전") @NotNull(message = "필수 항목을 입력해 주세요.") @PositiveOrZero(message = "버전은 0 이상이어야 합니다.") Long expectedVersion
) {}
