package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactEvidenceSpanResponse;

@Schema(description = "사용자 미확정 신규 캐릭터. 실제 캐릭터 ID로 사용하지 않습니다.")
public record WorkerAnalysisProvisionalCharacterPayload(
        @Schema(description = "실행에서 검증한 임시 대상 참조") String provisionalSubjectKey,
        @Schema(description = "원문에서 발견된 이름") String name,
        @Schema(description = "검증한 별칭") List<String> aliases,
        @Schema(description = "발견 출처 회차") int sourceEpisodeNo,
        @Schema(description = "출처를 보존한 활성 상태") List<WorkerAnalysisKnownCharacterPayload.ActiveStatus> activeStatuses,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @Schema(description = "동명이인 구분을 위해 발견 당시 보존한 원문 근거")
        List<CharacterFactEvidenceSpanResponse> identityEvidence
) {
}
