package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "발견 항목 상세. 내부 evidence_bundle·catalogue·호출 기록은 포함하지 않습니다.")
public record ValidationFindingDetailResponse(UUID findingId, ValidationBranch branch, String unitRef, UUID checkId, Long checkRevision,
        @Schema(description = "질문의 현재 revision. 오탈자는 null") Long currentCheckRevision,
        String outcome,
        @Schema(description = "현재 revision이 아닌 이전 항목이면 true. 판단 저장은 거절됩니다.") boolean stale,
        boolean changed,
        @Schema(description = "판정 사유·영향·원문 위치·제안 등 공개 판정 내용") Map<String, Object> payload,
        @Schema(description = "저장 때 검증한 인용 근거") List<Evidence> evidence,
        ValidationFindingSummaryResponse.Judgment judgment,
        List<ValidationJudgmentValue> allowedJudgments,
        @Schema(description = "이 항목과 같은 질문 이전 revision 항목의 판단 이력") List<History> judgmentHistory,
        @Schema(description = "설정 항목의 이전 revision과 당시 판단·변경 사유") List<PriorRevision> priorRevisions) {
    @Schema(name = "ValidationFindingEvidence", description = "저장 때 검증한 인용. Unicode code point 위치")
    public record Evidence(String sourceRef, int start, int end, String quote) {}
    @Schema(name = "ValidationFindingJudgmentHistory", description = "판단 전이 이력. REVISION_SUPERSEDED는 changeReason을 함께 제공")
    public record History(UUID findingId, Long checkRevision, ValidationJudgmentValue fromJudgment, ValidationJudgmentValue toJudgment,
            ValidationJudgmentHistoryReason reason, @Schema(description = "REVISION_SUPERSEDED의 질문 변경 사유") String changeReason, LocalDateTime createdAt) {}
    @Schema(name = "ValidationFindingPriorRevision", description = "같은 설정 질문의 이전 revision 결과와 당시 판단")
    public record PriorRevision(long checkRevision, String outcome, String changeReason, UUID findingId, ValidationFindingSummaryResponse.Judgment judgment) {}
}
