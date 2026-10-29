package org.monitoring.catchholebackend.domain.validation.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/** 서버에 저장된 실제 자동 적용 또는 개별 확정 이력의 식별값. */
public record ValidationApplicationReference(String kind, UUID applicationId, UUID analysisJobId, LocalDateTime appliedAt) {}
