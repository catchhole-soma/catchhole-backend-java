package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

@Schema(description = "설정DB 검색 결과 카드 응답")
public record CharacterFactSearchResponse(
        @Schema(
                description = "CharacterFact 식별자",
                example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333"
        )
        UUID characterFactId,

        @Schema(
                description = "검색 가능한 설정 유형",
                example = "ITEM",
                allowableValues = {"AGE", "LEVEL", "STAT", "SKILL", "ITEM", "STATUS"}
        )
        CharacterFactType factType,

        @Schema(description = "설정 유형 한글 표시명", example = "아이템")
        String factTypeLabel,

        @Schema(description = "사용자용 설정명", example = "체력 물약")
        String displayName,

        @Schema(description = "사용자용 설정 표시값", example = "체력 물약", nullable = true)
        String factValue,

        @Schema(
                description = "호환용 현재 설정 여부. contributesToCurrentSnapshot과 같은 값입니다.",
                example = "true",
                deprecated = true
        )
        @Deprecated
        boolean isCurrent,

        @Schema(description = "현재 캐릭터 snapshot 구성에 사용되는 Fact인지 여부", example = "true")
        boolean contributesToCurrentSnapshot,

        @Schema(
                description = "설정 소유 캐릭터 식별자",
                example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111"
        )
        UUID characterId,

        @Schema(description = "설정 소유 캐릭터명", example = "아리아")
        String characterName,

        @Schema(
                description = "출처 회차 식별자",
                example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d444",
                nullable = true
        )
        UUID sourceEpisodeId,

        @Schema(description = "출처 회차 번호", example = "12", nullable = true)
        Integer sourceEpisodeNo,

        @Schema(description = "설정 적용 시작 회차 번호", example = "12", nullable = true)
        Integer effectiveFromEpisodeNo
) {
}
