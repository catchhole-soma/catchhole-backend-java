package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateCharacterMatchResolutionType;

@Schema(description = "검토 대기 설정 후보의 캐릭터 연결 해소 요청")
public record SettingCandidateCharacterMatchRequest(
        @Schema(description = "캐릭터 연결 해소 방식", example = "MATCH_EXISTING")
        @NotNull(message = "캐릭터 연결 해소 방식은 필수입니다.")
        SettingCandidateCharacterMatchResolutionType resolutionType,

        @Schema(description = "기존 캐릭터에 연결할 때 사용할 characters.id", nullable = true)
        UUID matchedCharacterId,

        @Schema(
                description = "CREATE_NEW로 confirm 전 새 캐릭터 등록 예정인 UNRESOLVED 상태를 지정할 때 사용할 이름",
                example = "아리아",
                nullable = true
        )
        @Size(max = 100, message = "설정 대상 이름은 100자 이하로 입력해주세요.")
        String entityName
) {
}
