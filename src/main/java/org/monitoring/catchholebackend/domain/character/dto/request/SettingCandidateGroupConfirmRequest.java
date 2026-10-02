package org.monitoring.catchholebackend.domain.character.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "같은 이름의 캐릭터 설정 후보 그룹 전체 확정 요청")
public record SettingCandidateGroupConfirmRequest(
        @NotNull UUID batchId,
        @Size(max = 64) String comparisonRevision,
        @NotEmpty List<@NotNull @Valid SettingCandidateGroupConfirmDecision> candidates,
        @Schema(description = "완료된 같은 회차의 수동 검토에서 표시된 최종 결과를 승인합니다. 모든 후보의 expectedUpdatedAt이 필요하며 수정·제외에 따른 그룹 내부 재비교는 수행하지 않습니다.")
        Boolean acceptDisplayedResults
) {
    public SettingCandidateGroupConfirmRequest(UUID batchId, String comparisonRevision,
            List<SettingCandidateGroupConfirmDecision> candidates) {
        this(batchId, comparisonRevision, candidates, null);
    }
    public SettingCandidateGroupConfirmRequest(
            UUID batchId,
            List<SettingCandidateGroupConfirmDecision> candidates
    ) {
        this(batchId, null, candidates, null);
    }
}
