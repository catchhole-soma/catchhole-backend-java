package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateCharacterMatchResolutionType;

@Schema(description = "같은 이름의 캐릭터 설정 후보 그룹 일괄 연결 요청")
public record SettingCandidateGroupCharacterMatchRequest(
        @Schema(description = "후보가 속한 업로드 묶음 ID")
        @NotNull UUID batchId,

        @Schema(description = "현재 그룹에 속한 모든 검토 대기 후보 ID")
        @NotEmpty List<@NotNull UUID> candidateIds,

        @Schema(description = "그룹에 공통 적용할 캐릭터 연결 방식", example = "MATCH_EXISTING")
        @NotNull(message = "캐릭터 연결 해소 방식은 필수입니다.")
        SettingCandidateCharacterMatchResolutionType resolutionType,

        @Schema(description = "기존 캐릭터에 연결할 때 사용할 characters.id", nullable = true)
        UUID matchedCharacterId,

        @Schema(description = "새 캐릭터로 등록할 때 그룹 전체에 적용할 이름", example = "아리아", nullable = true)
        @Size(max = 100, message = "설정 대상 이름은 100자 이하로 입력해주세요.")
        String entityName
) {
}
