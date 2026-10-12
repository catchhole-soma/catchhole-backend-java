package org.monitoring.catchholebackend.domain.settingtool.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

@Schema(name = "SettingToolResponseV1", description = "설정 조회 계약 v1. partial과 limitations를 확인한 뒤 완전성을 판단합니다.")
public record SettingToolResponse(
        int schemaVersion, SettingTool tool, PageResponse<Item> items,
        boolean partial, List<String> limitations
) {
    public enum Origin { CONFIRMED_ANALYSIS, USER_INPUT, UNKNOWN }
    public enum EvidenceStatus {
        AVAILABLE, LEGACY_UNVERIFIED, NO_SOURCE, SOURCE_MISSING, SOURCE_PURGED,
        SOURCE_CHANGED, STORAGE_UNAVAILABLE, OUT_OF_RANGE, INVALID_QUOTE
    }

    @Schema(name = "SettingToolItemV1", description = "현재값·이력·탐색 대상·직접 근거. kind에 따라 사용하지 않는 필드는 null입니다.")
    public record Item(
            String kind, UUID id, UUID subjectId, String subjectName,
            String category, String scopeName, String settingKey, String displayName,
            String value,
            @Schema(description = "구조화된 설정의 JSON 문자열. 상한 초과 시 생략합니다.") String structuredValueJson,
            boolean valueTruncated, Boolean contributesToCurrent, String operation, Boolean historyOnly, Long settingVersion,
            Integer effectiveFromEpisodeNo, UUID sourceEpisodeId, Integer sourceEpisodeNo, Origin origin,
            List<Reference> sources, boolean sourcesTruncated, Evidence evidence
    ) {}

    @Schema(name = "SettingToolReferenceV1", description = "원문을 포함하지 않는 직접 근거 참조. 근거 도구에서 접근 가능 여부를 다시 확인합니다.")
    public record Reference(SettingTool tool, UUID targetId) {}

    @Schema(name = "SettingToolEvidenceV1", description = "검증된 인용. 원문 전체·S3 key는 반환하지 않습니다.")
    public record Evidence(
            EvidenceStatus status, UUID candidateId, UUID episodeId, Integer sourceEpisodeNo,
            UUID sourceChunkId, String sourceHash, String sourceVersion,
            @Schema(description = "현재 원문과 일치 여부. 레거시에서 검증 불가하면 null") Boolean matchesCurrentSource,
            List<Quote> quotes, boolean truncated
    ) {}

    @Schema(name = "SettingToolQuoteV1", description = "전체 원문 기준 Unicode code point의 0-based 반열린 구간. 레거시 위치 확인 불가 시 null")
    public record Quote(String text, Integer startOffset, Integer endOffset) {}
}
