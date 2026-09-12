package org.monitoring.catchholebackend.domain.episode.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "작품의 회차 업로드 분량 제한과 검토 대기 안내")
public record EpisodeUploadPolicyResponse(
        @Schema(description = "현재 원문에 대한 분석을 성공한 서로 다른 단일 업로드 회차 수", example = "10",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long completedSingleEpisodeCount,
        @Schema(description = "호환용 필드. 다회차 업로드 선행 조건이 없어 항상 0입니다.", example = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int requiredSingleEpisodeCount,
        @Schema(description = "호환용 필드. 선행 분석 없이 다회차 업로드를 허용하므로 항상 true입니다.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean multiEpisodeUploadEnabled,
        @Schema(description = "검토 대기 중인 캐릭터 후보 수. 업로드를 차단하지 않는 안내용", example = "3",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long pendingCharacterCandidateCount,
        @Schema(description = "검토 대기 중인 세계관 후보 수. 업로드를 차단하지 않는 안내용", example = "2",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long pendingWorldSettingCandidateCount,
        @Schema(description = "공백을 포함한 원고 전체 Unicode 글자 수 상한. 설정집 첨부는 제외", example = "250000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int maxUploadCharacters
) {
}
