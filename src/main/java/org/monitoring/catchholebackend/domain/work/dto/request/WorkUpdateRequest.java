package org.monitoring.catchholebackend.domain.work.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;

@Schema(description = "작품 수정 요청")
public record WorkUpdateRequest(
        @Schema(
                description = "작품 제목",
                example = "빛나는 검사 로맨스",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "작품 제목은 필수입니다.")
        @Size(max = 100, message = "작품 제목은 100자 이하로 입력해주세요.")
        String title,

        @Schema(
                description = "작품 장르",
                example = "로맨스",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "작품 장르는 필수입니다.")
        WorkGenre genre,

        @Schema(
                description = "작품 목록에 한 줄로 표시할 짧은 소개",
                example = "검사 주인공의 성장 로맨스",
                maxLength = 50,
                nullable = true
        )
        @Size(max = 50, message = "작품 설명은 50자 이하로 입력해주세요.")
        String description
) {
}
