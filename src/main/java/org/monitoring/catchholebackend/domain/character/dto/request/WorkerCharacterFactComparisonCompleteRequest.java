package org.monitoring.catchholebackend.domain.character.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

@Schema(description = "Worker 캐릭터 설정 비교 완료 요청")
public record WorkerCharacterFactComparisonCompleteRequest(
        @NotNull(message = "캐릭터 설정 제안 방식은 필수입니다.")
        CharacterFactOperation operation,

        @Schema(description = "UPDATE/MERGE 대상입니다. REMOVE에서는 구버전 Worker 호환용입니다.", nullable = true)
        CharacterFactType targetFactType,

        @Schema(description = "UPDATE/MERGE 대상입니다. REMOVE에서는 구버전 Worker 호환용입니다.", nullable = true)
        @Size(max = 150, message = "대상 Fact key는 150자 이하여야 합니다.")
        String targetFactKey,

        @Schema(nullable = true)
        String proposedFactValue,

        @Schema(nullable = true, implementation = JsonNode.class)
        Object proposedValueJson,

        @Valid
        @NotNull(message = "제거할 snapshot 항목 목록은 필수입니다.")
        @Size(max = 30, message = "제거할 snapshot 항목은 최대 30개입니다.")
        @Schema(description = "현재 snapshot에서 제거할 STATUS 목록. 신규 REMOVE는 한 건 이상 필요합니다.")
        List<@NotNull @Valid SnapshotEntry> removedSnapshotEntries,

        @NotNull(message = "시간 범위 판단은 필수입니다.")
        CharacterFactTemporalScope temporalScope,

        @NotBlank(message = "비교 이유는 필수입니다.")
        String comparisonReason,

        @NotBlank(message = "비교 문맥 token은 필수입니다.")
        @Size(min = 64, max = 64, message = "비교 문맥 token은 64자여야 합니다.")
        String contextToken,

        @Schema(nullable = true)
        Map<String, Object> rawComparisonJson
) {

    @Schema(name = "WorkerCharacterRemovedSnapshotEntry")
    public record SnapshotEntry(
            @NotNull(message = "제거 대상 Fact 유형은 필수입니다.")
            CharacterFactType factType,

            @NotBlank(message = "제거 대상 Fact key는 필수입니다.")
            @Size(max = 150, message = "제거 대상 Fact key는 150자 이하여야 합니다.")
            String factKey
    ) {
    }
}
