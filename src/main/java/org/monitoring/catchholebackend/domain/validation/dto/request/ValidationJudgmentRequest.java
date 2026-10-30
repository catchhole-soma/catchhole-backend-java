package org.monitoring.catchholebackend.domain.validation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.monitoring.catchholebackend.domain.validation.type.ValidationJudgmentValue;

@Schema(description = "현재 발견 항목의 작가 판단 저장. 원고·설정을 수정하거나 설정 추출을 시작하지 않습니다.")
public record ValidationJudgmentRequest(
        @Schema(description = "설정 항목은 PENDING/NEEDS_FIX/NOT_CONFLICT, 오탈자는 PENDING/NEEDS_FIX/KEEP_ORIGINAL", example = "NOT_CONFLICT")
        @NotNull ValidationJudgmentValue judgment,
        @Schema(description = "현재 판단 version. 판단을 저장한 적 없는 항목(version 0)은 null 또는 0", example = "1")
        @PositiveOrZero Long expectedJudgmentVersion,
        @Schema(description = "조회한 항목의 checkRevision. 오탈자 항목은 null", example = "1")
        @Positive Long expectedCheckRevision) {}
