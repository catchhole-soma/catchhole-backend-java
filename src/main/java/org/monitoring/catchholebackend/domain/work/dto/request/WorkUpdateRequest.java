package org.monitoring.catchholebackend.domain.work.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
                allowableValues = {
                        "판타지", "로맨스", "추리", "코미디", "SF",
                        "스포츠", "호러", "무협", "일상", "기타"
                },
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "작품 장르는 필수입니다.")
        @Pattern(
                regexp = "^(?:\\s*|판타지|로맨스|추리|코미디|SF|스포츠|호러|무협|일상|기타)$",
                message = "지원하지 않는 작품 장르입니다."
        )
        String genre,

        @Schema(
                description = "작품 목록에 한 줄로 표시할 짧은 소개",
                example = "검사 주인공의 성장 로맨스",
                maxLength = 20,
                nullable = true
        )
        @Size(max = 20, message = "작품 설명은 20자 이하로 입력해주세요.")
        String description
) {
}
