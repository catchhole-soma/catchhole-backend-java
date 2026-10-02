package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;

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
        String attributeValue,

        @Schema(description = "완료된 후보에 대해 직접 선택한 저장 방식. 수동 단일 회차의 내용 수정은 생략 시 이전 선택 또는 현재값 반영을 저장하며 AI 재비교하지 않습니다.", nullable = true)
        CharacterFactConfirmApplicationMode reviewedApplicationMode,

        @Schema(description = "직접 저장 방식을 선택할 때 화면에서 확인한 후보의 updatedAt. reviewedApplicationMode가 있거나 완료된 수동 단일 회차의 값을 수정하면 필수입니다.", nullable = true)
        LocalDateTime expectedUpdatedAt
) {
    public SettingCandidateUpdateRequest(String attributeName, String attributeValue) {
        this(attributeName, attributeValue, null, null);
    }
}
