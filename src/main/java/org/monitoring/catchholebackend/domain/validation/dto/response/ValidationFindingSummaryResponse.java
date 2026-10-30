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
        Summary summary,
        @Schema(description = "#314 B2. 설정 항목이 가리키는 고정 기준 대상. 판정 저장 때 report 고정 기준과 질문 대상(subject_refs)으로 계산하며 현재 설정값을 읽지 않습니다. 오탈자·대상이 기준 항목으로 이어지지 않는 질문·V102 이전 계산 불가 항목은 null입니다.", nullable = true)
        Subject subject,
        @Schema(description = "#314 B2. 질문 속성이 대상의 고정 설정 하나와 정확히 맞을 때의 표시명. 캐릭터는 schema 표시명, 세계관은 \"범위 / 설정명\" 또는 \"설정명\"입니다. 맞는 설정이 없거나 여럿이면, 그리고 오탈자·V102 이전 항목은 null입니다.", example = "나이", nullable = true)
        String settingName,
        @Schema(description = "#314 B2. settingName의 원래 키. 캐릭터 \"factType:factKey\", 세계관 저장 경로 JSON 배열 [\"범위\",\"설정명\"]·[null,\"설정명\"]입니다.", example = "AGE:age", nullable = true)
        String settingKey) {
    @Schema(name = "ValidationFindingJudgment", description = "작가 판단. 저장한 적 없으면 PENDING/version 0")
    public record Judgment(ValidationJudgmentValue value, long version, LocalDateTime decidedAt) {}
    @Schema(name = "ValidationFindingSummary", description = "목록 표시용 요약. impact는 설정 항목의 SEVERE/CAUTION/REFERENCE, replacement는 오탈자 제안")
    public record Summary(String impact, String reason, String replacement, List<Span> spans) {}
    @Schema(name = "ValidationFindingSpan", description = "검증된 원문 위치. Unicode code point 기준")
    public record Span(String sourceRef, Integer start, Integer end, String quote) {}
    @Schema(name = "ValidationFindingSubject", description = "#314 B2. finding의 대상. 이름은 고정 기준 당시 이름입니다.")
    public record Subject(@Schema(description = "CHARACTER 또는 WORLD_SETTING") ValidationSubjectType type,
            @Schema(description = "실제 캐릭터·세계관 ID. 고정 기준 항목이 실제 대상에 연결되지 않았으면 null이며 이름으로 연결하지 않습니다.", nullable = true) UUID id,
            @Schema(description = "고정 기준 당시 대상 이름", example = "카일") String name) {}
}
