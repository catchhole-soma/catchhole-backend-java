package org.monitoring.catchholebackend.domain.validation.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReportStatus;

/** 회차 목록은 큰 seed/entries 없이 검수 표시 상태만 읽는다. */
public record ValidationReportState(UUID episodeId, UUID reportId, ValidationReportStatus status) {}
