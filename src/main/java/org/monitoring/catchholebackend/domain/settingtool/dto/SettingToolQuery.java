package org.monitoring.catchholebackend.domain.settingtool.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;

@Schema(description = "읽기 전용 설정 도구 입력. 사용자·작품 범위는 본문에 넣지 않습니다.")
public record SettingToolQuery(
        @NotNull SettingTool tool,
        @Schema(description = "현재/이력은 인물·세계관 ID, 근거는 Fact·세계관 후보 ID") UUID targetId,
        @Size(max = 100) String query,
        @Size(max = 150) String settingKey,
        @Schema(description = "세계관 경로. settingKey가 있으면 null은 루트 경로입니다.") @Size(max = 100) String scopeName,
        @Schema(description = "출처 회차 하한. 현재 설정 조회에는 사용할 수 없습니다.") @Min(1) Integer episodeFrom,
        @Min(1) Integer episodeTo,
        @Min(0) @Max(10000) int page,
        @Min(1) @Max(50) int size,
        @Schema(description = "인물 설정 유형. 현재값·이력 조회에서 페이지를 나누기 전에 적용합니다.") CharacterFactType factType,
        @Schema(description = "FIND_CHARACTERS의 현재 scalar 값 정확 일치 조건. factType·settingKey와 함께 사용합니다.")
        @Size(max = 200) String valueEquals
) {
    public SettingToolQuery(SettingTool tool, UUID targetId, String query, String settingKey, String scopeName,
                            Integer episodeFrom, Integer episodeTo, int page, int size) {
        this(tool, targetId, query, settingKey, scopeName, episodeFrom, episodeTo, page, size, null, null);
    }
    public SettingToolQuery(SettingTool tool, UUID targetId, String query, String settingKey, String scopeName,
                            Integer episodeFrom, Integer episodeTo, int page, int size, CharacterFactType factType) {
        this(tool, targetId, query, settingKey, scopeName, episodeFrom, episodeTo, page, size, factType, null);
    }
}
