package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateCharacterMatchResolutionType;

@Schema(description = "작가가 선택한 캐릭터 설정 후보만 한 대상에 연결하는 요청")
public record SettingCandidateSelectedCharacterMatchRequest(
        @Schema(description = "후보가 속한 업로드 묶음 ID", example = "0198a3f0-0000-7000-8000-000000000101")
        @NotNull(message = "업로드 묶음 ID는 필수입니다.")
        UUID batchId,

        @ArraySchema(
                arraySchema = @Schema(description = "연결할 검토 대기 후보 ID 목록. 1~100개이며 중복할 수 없습니다. "
                        + "같은 이름 묶음일 필요가 없고, 응답 후보 순서는 이 목록 순서를 따릅니다."),
                schema = @Schema(description = "설정 후보 ID", example = "0198a3f0-0000-7000-8000-000000000301"),
                uniqueItems = true
        )
        @NotNull(message = "연결할 설정 후보를 하나 이상 선택해 주세요.")
        @Size(min = 1, max = 100, message = "설정 후보는 한 번에 1~100개를 선택해 주세요.")
        List<@NotNull(message = "설정 후보 ID는 비어 있을 수 없습니다.") UUID> candidateIds,

        @Schema(description = "선택한 후보에 공통 적용할 캐릭터 연결 방식", example = "MATCH_EXISTING")
        @NotNull(message = "캐릭터 연결 해소 방식은 필수입니다.")
        SettingCandidateCharacterMatchResolutionType resolutionType,

        @Schema(
                description = "MATCH_EXISTING일 때 연결할 characters.id",
                example = "0198a3f0-0000-7000-8000-000000000401",
                nullable = true
        )
        UUID matchedCharacterId,

        @Schema(
                description = "CREATE_NEW일 때 새 캐릭터 등록 예정 이름. 같은 작품의 기존 캐릭터와 이름이 겹치면 거절합니다.",
                example = "아리아",
                nullable = true
        )
        @Size(max = 100, message = "설정 대상 이름은 100자 이하로 입력해주세요.")
        String entityName
) {
}
