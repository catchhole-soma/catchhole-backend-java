package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.type.*;

/** Job 없는 검수를 별도 요청으로 표시하며 가짜 Job/성공 건수를 만들지 않는다. */
@Schema(description = "배치 카드의 현재 검수 report 요약. Job 수·성공 건수에 포함하지 않습니다.")
public record ValidationRequestSummaryResponse(UUID reportId, UUID episodeId, int sourceEpisodeNo,
        ValidationReportStatus status, ValidationPreparationStatus baselineStatus, String holdReason,
        @Schema(description = "파생 검수 단계(GH312 S4). 원고 목록·검수 조회와 같은 계산식입니다.", example = "APPLIED") ValidationReviewStage reviewStage) {}
