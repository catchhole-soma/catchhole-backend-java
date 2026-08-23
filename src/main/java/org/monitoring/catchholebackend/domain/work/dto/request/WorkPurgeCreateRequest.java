package org.monitoring.catchholebackend.domain.work.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "작품 영구 삭제 요청")
public record WorkPurgeCreateRequest(
        @Schema(
                description = "복구할 수 없는 삭제임을 확인하기 위한 고정 문구",
                example = "영구 삭제",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "영구 삭제 확인 문구를 입력해주세요.")
        @Pattern(regexp = "영구 삭제", message = "영구 삭제 확인 문구가 일치하지 않습니다.")
        String confirmation
) {
}
