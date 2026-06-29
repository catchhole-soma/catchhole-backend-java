package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "검토 대기 설정 후보의 사용자 보정 요청")
public record SettingCandidateUpdateRequest(
        @Schema(description = "보정할 설정 대상 이름. 저장 전 앞뒤 공백을 제거합니다.", example = "아리아")
        @NotBlank(message = "설정 대상 이름은 필수입니다.")
        @Size(max = 100, message = "설정 대상 이름은 100자 이하로 입력해주세요.")
        String entityName,

        @Schema(description = "보정할 설정 속성명. 저장 전 앞뒤 공백을 제거합니다.", example = "level")
        @NotBlank(message = "설정 속성명은 필수입니다.")
        @Size(max = 100, message = "설정 속성명은 100자 이하로 입력해주세요.")
        String attributeName,

        @Schema(description = "목록/검색 표시용 보정 값. null이면 표시용 값을 비웁니다.", example = "23", nullable = true)
        String attributeValue,

        @Schema(description = "설정 값 타입", example = "NUMBER")
        @NotNull(message = "설정 값 타입은 필수입니다.")
        SettingValueType valueType,

        @Schema(description = "보정된 구조화 설정 값 JSON. null이면 구조화 값을 비웁니다.", nullable = true)
        Object valueJson,

        @Schema(description = "보정된 원문 근거 span JSON. null이면 근거 값을 비웁니다.", nullable = true)
        Object evidenceSpans
) {
}
