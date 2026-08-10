package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "세계관 설정 전체 경로와 값")
public record WorldSettingPropertyResponse(
        @Schema(description = "대상 아래의 선택적 한 단계 범위", nullable = true, example = "1층")
        String scopeName,
        @Schema(description = "설정명", example = "방향별 몬스터 출몰 규칙")
        String settingName,
        @Schema(description = "설정값", example = "동서남북에 따라 출몰 몬스터가 달라진다.")
        String value
) {
}
