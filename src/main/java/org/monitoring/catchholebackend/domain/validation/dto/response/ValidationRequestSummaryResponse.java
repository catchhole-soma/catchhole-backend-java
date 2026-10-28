package org.monitoring.catchholebackend.domain.validation.dto.response;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.type.*;

/** Job 없는 검수를 별도 요청으로 표시하며 가짜 Job/성공 건수를 만들지 않는다. */
public record ValidationRequestSummaryResponse(UUID reportId, UUID episodeId, int sourceEpisodeNo,
        ValidationReportStatus status, ValidationPreparationStatus baselineStatus, String holdReason) {}
