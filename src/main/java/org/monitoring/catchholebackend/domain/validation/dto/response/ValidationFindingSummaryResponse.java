package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "현재 revision 발견 항목 요약. 근거 행과 단계 기록은 상세 조회에서만 제공합니다.")
public record ValidationFindingSummaryResponse(UUID findingId, ValidationBranch branch, String unitRef, UUID checkId, Long checkRevision,
        @Schema(description = "설정: CONFLICT/SUSPECTED_CONFLICT/NEEDS_CONTEXT, 오탈자: SPELLING/SPACING/TYPO") String outcome,
        Judgment judgment,
        @Schema(description = "같은 질문의 이전 revision이 있었는지 여부") boolean changed,
        Summary summary) {
    @Schema(name = "ValidationFindingJudgment", description = "작가 판단. 저장한 적 없으면 PENDING/version 0")
    public record Judgment(ValidationJudgmentValue value, long version, LocalDateTime decidedAt) {}
    @Schema(name = "ValidationFindingSummary", description = "목록 표시용 요약. impact는 설정 항목의 SEVERE/CAUTION/REFERENCE, replacement는 오탈자 제안")
    public record Summary(String impact, String reason, String replacement, List<Span> spans) {}
    @Schema(name = "ValidationFindingSpan", description = "검증된 원문 위치. Unicode code point 기준")
    public record Span(String sourceRef, Integer start, Integer end, String quote) {}
}
