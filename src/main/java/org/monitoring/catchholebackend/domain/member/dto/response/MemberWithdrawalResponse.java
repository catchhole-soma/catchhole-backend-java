package org.monitoring.catchholebackend.domain.member.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.member.type.MemberWithdrawalStatus;

@Schema(description = "회원 즉시 탈퇴 요청 접수 결과")
public record MemberWithdrawalResponse(
        @Schema(description = "탈퇴 요청 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID requestId,

        @Schema(description = "탈퇴 처리 상태", example = "REQUESTED", requiredMode = Schema.RequiredMode.REQUIRED)
        MemberWithdrawalStatus status,

        @Schema(description = "탈퇴 요청 시각", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime requestedAt
) {
}
