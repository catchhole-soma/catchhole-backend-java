package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

@Schema(description = "업로드 묶음별 설정 후보 검토 목록 응답")
public record SettingCandidateListResponse(
        @Schema(description = "검토 대상 업로드 묶음 ID", example = "0198a3f0-0000-7000-8000-000000000101")
        UUID batchId,

        @Schema(description = "검토 대상 시작 회차 번호", example = "1", nullable = true)
        Integer episodeStartNo,

        @Schema(description = "검토 대상 종료 회차 번호", example = "5", nullable = true)
        Integer episodeEndNo,

        @Schema(description = "검토 대상 회차 수", example = "5")
        long episodeCount,

        @Schema(description = "현재 필터와 무관한 묶음 전체 후보 수", example = "48")
        long totalCandidateCount,

        @Schema(description = "확정 또는 무시까지 완료한 후보 수", example = "20")
        long reviewedCandidateCount,

        @Schema(description = "검토 대기 후보 수", example = "28")
        long pendingCandidateCount,

        @Schema(description = "검토 대기이면서 캐릭터 연결이 필요한 후보 수", example = "3")
        long matchRequiredCandidateCount,

        @Schema(description = "필터·페이지와 무관한 묶음 전체의 확인 필요 후보 수. 검토 대기 중 연결이 모호하거나 비교 실패·재비교가 필요한 후보를 중복 없이 셉니다.",
                example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
        long attentionRequiredCandidateCount,

        @Schema(description = "작품 설정 또는 이력에 반영한 후보 수; 필터·페이지와 무관한 묶음 전체", requiredMode = Schema.RequiredMode.REQUIRED)
        long confirmedCandidateCount,
        @Schema(description = "제외한 후보 수; 필터·페이지와 무관한 묶음 전체", requiredMode = Schema.RequiredMode.REQUIRED)
        long dismissedCandidateCount,
        @Schema(description = "비교 대기·진행과 회차 자동 반영 대기를 제외하고 직접 확인할 미확정 후보 수; 필터·페이지와 무관한 묶음 전체", requiredMode = Schema.RequiredMode.REQUIRED)
        long directReviewCandidateCount,
        @Schema(description = "비교 대기·진행 또는 회차 자동 반영을 기다리는 미확정 후보 수; 필터·페이지와 무관한 묶음 전체", requiredMode = Schema.RequiredMode.REQUIRED)
        long processingCandidateCount,
        @Schema(description = "현재 필터를 적용한 캐릭터 이름별 후보 그룹 페이지")
        PageResponse<SettingCandidateGroupResponse> groups,

        @Deprecated
        @Schema(description = "단건 화면 호환용 후보 페이지. 신규 화면은 groups를 사용합니다.", deprecated = true)
        PageResponse<SettingCandidateResponse> candidates
) {
}
