package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.*;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
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
        @Schema(description = "설정 항목의 이전 revision과 당시 판단·변경 사유") List<PriorRevision> priorRevisions,
        @Schema(description = "#314 B2. 목록과 같은 대상. 오탈자·대상 없는 질문은 null", nullable = true) ValidationFindingSummaryResponse.Subject subject,
        @Schema(description = "#314 B2. 목록과 같은 설정 표시명", example = "나이", nullable = true) String settingName,
        @Schema(description = "#314 B2. 목록과 같은 설정 키", example = "AGE:age", nullable = true) String settingKey,
        @Schema(description = "#314 B2. 이 질문이 비교한 고정 기준 항목 중 정정(POST /{reportId}/corrections)에 그대로 쓸 수 있는 항목. 실제 캐릭터·세계관에 연결된 항목만 기준 순서로 담습니다. 오탈자는 빈 배열입니다.")
        List<Basis> basis) {
    @Schema(name = "ValidationFindingEvidence", description = "저장 때 검증한 인용. Unicode code point 위치")
    public record Evidence(String sourceRef, int start, int end, String quote) {}
    @Schema(name = "ValidationFindingJudgmentHistory", description = "판단 전이 이력. REVISION_SUPERSEDED는 changeReason을 함께 제공")
    public record History(UUID findingId, Long checkRevision, ValidationJudgmentValue fromJudgment, ValidationJudgmentValue toJudgment,
            ValidationJudgmentHistoryReason reason, @Schema(description = "REVISION_SUPERSEDED의 질문 변경 사유") String changeReason, LocalDateTime createdAt) {}
    @Schema(name = "ValidationFindingPriorRevision", description = "같은 설정 질문의 이전 revision 결과와 당시 판단")
    public record PriorRevision(long checkRevision, String outcome, String changeReason, UUID findingId, ValidationFindingSummaryResponse.Judgment judgment) {}
    @Schema(name = "ValidationFindingBasis", description = "#314 B2. 정정 요청에 그대로 옮기는 고정 기준 항목. entryRef→entryRef, entryHash→expectedEntryHash, targetId→character/worldSetting.targetId, currentVersion→expectedSnapshotVersion(캐릭터)·expectedVersion(세계관)입니다. baselineId·expectedBaselineRevision·expectedBaselineHash·expectedReportVersion은 GET /{reportId}의 baselineId·baselineRevision·baselineHash·reportVersion을 씁니다.")
    public record Basis(@Schema(description = "고정 기준 항목 참조", example = "B1") String entryRef,
            @Schema(description = "고정 기준 항목 자체의 hash(정정 요청 expectedEntryHash)", example = "0000000000000000000000000000000000000000000000000000000000000000") String entryHash,
            @Schema(description = "CHARACTER면 정정 요청 character, WORLD_SETTING이면 worldSetting을 채웁니다") ValidationSubjectType subjectType,
            @Schema(description = "실제 캐릭터·세계관 ID") UUID targetId,
            @Schema(description = "고정 기준 당시 대상 이름", example = "카일") String subjectName,
            @Schema(description = "캐릭터: finding의 settingKey가 이 항목의 slot을 가리킬 때의 factType. 그 밖에는 null이며 작가가 편집한 slot을 사용합니다.", nullable = true) CharacterFactType factType,
            @Schema(description = "캐릭터: 위와 같은 조건의 factKey", example = "age", nullable = true) String factKey,
            @Schema(description = "세계관: settingKey가 이 항목의 저장 경로를 가리킬 때의 범위명(WorldSettingPropertyUpdateRequest.currentScopeName). root 설정과 그 밖은 null", nullable = true) String scopeName,
            @Schema(description = "세계관: 같은 조건의 저장 설정명(currentSettingName). 캐릭터와 그 밖은 null", nullable = true) String settingName,
            @Schema(description = "고정 기준 당시 캐릭터 snapshotVersion 또는 세계관 version. 저장되지 않은 legacy 항목은 null", nullable = true) Long currentVersion) {}
}
