package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateValueValidationStatus;

@Schema(description = "현재 활성 schema 기준 설정 후보 값 검증 결과")
public record SettingCandidateValueValidationResponse(
        @Schema(
                description = "값 검증 상태",
                example = "VALID",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        SettingCandidateValueValidationStatus status,

        @Schema(
                description = "검증 실패 코드. INVALID가 아니면 null입니다.",
                example = "SETTING_CANDIDATE_VALUE_FORMAT_INVALID",
                nullable = true
        )
        String errorCode,

        @Schema(
                description = "사용자에게 표시할 검증 실패 메시지. INVALID가 아니면 null입니다.",
                example = "설정 후보의 표시값이 값 타입과 일치하지 않습니다.",
                nullable = true
        )
        String message
) {
}
