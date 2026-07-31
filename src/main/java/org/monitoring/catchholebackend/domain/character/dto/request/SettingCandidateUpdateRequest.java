package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "검토 대기 설정 후보의 사용자 보정 요청")
public record SettingCandidateUpdateRequest(
        @Schema(
                description = "보정할 설정 속성명. 고정 schema key는 변경할 수 없고, 동적 key는 같은 pattern 안에서 이름만 바꿀 수 있습니다.",
                example = "skill.화염_검술"
        )
        @NotBlank(message = "설정 속성명은 필수입니다.")
        @Size(max = 100, message = "설정 속성명은 100자 이하로 입력해주세요.")
        String attributeName,

        @Schema(description = "목록/검색 표시용 보정 값. null이면 표시용 값을 비웁니다.", example = "23", nullable = true)
        String attributeValue
) {
}
