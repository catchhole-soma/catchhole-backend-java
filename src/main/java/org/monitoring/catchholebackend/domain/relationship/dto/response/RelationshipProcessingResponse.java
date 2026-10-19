package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipProcessingStatus;

@Schema(description = "현재 확정 프로필의 가족 관계 처리 상태. 기존 설정 저장과 독립적으로 처리합니다")
public record RelationshipProcessingResponse(
        @Schema(description = "처리 상태") RelationshipProcessingStatus state,
        @Schema(description = "현재 요청된 입력 버전") long inputVersion,
        @Schema(description = "마지막으로 처리 완료한 입력 버전") long processedVersion,
        @Schema(description = "마지막 처리 완료 시각") LocalDateTime lastProcessedAt,
        @Schema(description = "정제된 오류 코드. 실패가 아니면 null") String failureCode,
        @Schema(description = "자동 재시도 가능 시각") LocalDateTime retryAt,
        @Schema(description = "마지막 처리에서 새로 자동 확정한 후보 수") int autoConfirmedCount,
        @Schema(description = "마지막 처리에서 발견한 현재 설정의 검토 대기 후보 수") int reviewRequiredCount,
        @Schema(description = "가족 표현을 발견했으나 지원하는 명시적 형식으로 읽지 못한 설정 수. 관계가 없다는 뜻이 아닙니다") int unsupportedSourceCount
) {}
