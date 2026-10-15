package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "현재 상태 항목의 확정 여부와 출처")
public record WorkerAnalysisProvenancePayload(
        @Schema(description = "CONFIRMED 또는 PROVISIONAL") String confirmationStatus,
        @Schema(description = "실제 출처 회차. 직접 입력·legacy는 null입니다.", nullable = true) Integer sourceEpisodeNo,
        @Schema(description = "원문 근거로 연결되는 후보 ID") List<UUID> sourceCandidateIds,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "HUMAN은 사용자 확인, AUTOMATIC은 AI 자동 반영. 구기록은 생략합니다.", nullable = true) String reviewSource,
        @Schema(nullable = true) org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind sourceKind,
        @Schema(nullable = true) UUID sourceSettingBookId,
        @Schema(nullable = true) String sourceSettingBookName,
        @Schema(nullable = true) UUID sourceSettingBookRevisionId,
        @Schema(nullable = true) Integer sourceSettingBookRevisionNo,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(nullable = true) org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind sourceReferenceTimeKind,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(nullable = true) Integer sourceReferenceEpisodeNo,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(nullable = true, description = "본문에 명시된 효력 시작 회차. 원문 출처 회차와 별개입니다.") Integer effectiveFromEpisodeNo,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(nullable = true, description = "실제 현재 경로의 작가 직접 수정 보호") Boolean currentValueProtected,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        @Schema(description = "실제 현재값에 적용된 모든 근거 시점. 불명확한 근거는 UNKNOWN으로 보존합니다.") List<WorkerAnalysisCurrentSourceTimePayload> currentSourceTimes
) {
    public WorkerAnalysisProvenancePayload(String confirmationStatus, Integer sourceEpisodeNo, List<UUID> sourceCandidateIds,
            String reviewSource, org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind sourceKind,
            UUID sourceSettingBookId, String sourceSettingBookName, UUID sourceSettingBookRevisionId, Integer sourceSettingBookRevisionNo,
            org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind sourceReferenceTimeKind, Integer sourceReferenceEpisodeNo) {
        this(confirmationStatus, sourceEpisodeNo, sourceCandidateIds, reviewSource, sourceKind, sourceSettingBookId,
                sourceSettingBookName, sourceSettingBookRevisionId, sourceSettingBookRevisionNo,
                sourceReferenceTimeKind, sourceReferenceEpisodeNo, null, null, List.of());
    }
    public WorkerAnalysisProvenancePayload(String confirmationStatus, Integer sourceEpisodeNo, List<UUID> sourceCandidateIds,
            String reviewSource, org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind sourceKind,
            UUID sourceSettingBookId, String sourceSettingBookName, UUID sourceSettingBookRevisionId, Integer sourceSettingBookRevisionNo) {
        this(confirmationStatus, sourceEpisodeNo, sourceCandidateIds, reviewSource, sourceKind, sourceSettingBookId,
                sourceSettingBookName, sourceSettingBookRevisionId, sourceSettingBookRevisionNo,
                sourceKind == org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK
                        ? org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.UNSPECIFIED : null, null);
    }
    public WorkerAnalysisProvenancePayload(String confirmationStatus, Integer sourceEpisodeNo, List<UUID> sourceCandidateIds, String reviewSource) {
        this(confirmationStatus, sourceEpisodeNo, sourceCandidateIds, reviewSource, null, null, null, null, null, null, null, null, null, List.of());
    }

    public WorkerAnalysisProvenancePayload(String confirmationStatus, Integer sourceEpisodeNo, List<UUID> sourceCandidateIds) {
        this(confirmationStatus, sourceEpisodeNo, sourceCandidateIds, null, null, null, null, null, null, null, null, null, null, List.of());
    }
}
