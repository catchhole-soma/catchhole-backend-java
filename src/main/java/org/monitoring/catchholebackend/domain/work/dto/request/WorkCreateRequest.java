package org.monitoring.catchholebackend.domain.work.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "작품 생성 요청")
public record WorkCreateRequest(
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
                allowableValues = {"로맨스", "판타지", "무협", "현대", "미스터리", "기타"},
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "작품 장르는 필수입니다.")
        @Pattern(
                regexp = "^(?:\\s*|로맨스|판타지|무협|현대|미스터리|기타)$",
                message = "지원하지 않는 작품 장르입니다."
        )
        String genre,

        @Schema(description = "작품 설명", example = "검사 주인공의 성장과 로맨스를 다룬 웹소설입니다.", nullable = true)
        @Size(max = 1000, message = "작품 설명은 1000자 이하로 입력해주세요.")
        String description
) {
}
