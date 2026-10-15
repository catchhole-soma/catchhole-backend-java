package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

@Schema(description = "현재 항목의 개별 적용 근거 시점. 본문 효력 시점과 원문 출처를 구분합니다.")
public record WorkerAnalysisCurrentSourceTimePayload(
        @Schema(nullable = true) AnalysisSourceKind sourceKind,
        @Schema(nullable = true) Integer sourceEpisodeNo,
        @Schema(nullable = true) SettingBookReferenceTimeKind referenceTimeKind,
        @Schema(nullable = true) Integer referenceEpisodeNo,
        @Schema(nullable = true) Integer effectiveFromEpisodeNo,
        CharacterFactTemporalScope temporalScope) {}
