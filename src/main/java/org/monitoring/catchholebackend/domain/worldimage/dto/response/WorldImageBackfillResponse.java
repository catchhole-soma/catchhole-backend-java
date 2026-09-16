package org.monitoring.catchholebackend.domain.worldimage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "한 묶음의 매칭 예상/저장 결과. 기본 이미지도 처리 완료로 기록합니다.")
public record WorldImageBackfillResponse(int processed, int matched, int defaults, boolean applied) {}
