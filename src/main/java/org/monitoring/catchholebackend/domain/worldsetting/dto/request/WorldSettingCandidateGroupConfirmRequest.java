package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;

@Schema(description = "같은 세계관 대상 그룹의 선택 key 확정 요청")
public record WorldSettingCandidateGroupConfirmRequest(
        @NotNull UUID batchId,
        @NotEmpty @Size(max = 100) List<@Valid Decision> candidates
) {

    @Schema(description = "그룹 안 후보 한 건의 최종 결정")
    public record Decision(
            @NotNull UUID candidateId,
            @NotNull WorldSettingOperation operation,
            @NotNull WorldSettingCategory category,
            @NotBlank @Size(max = 100) String subjectName,
            @Size(max = 100) @Schema(description = "최종 선택적 한 단계 범위", nullable = true)
            String scopeName,
            @NotBlank @Size(max = 100) String settingName,
            @NotBlank String value,
            @Schema(description = "서로 다른 추출 내용을 사용자가 최종값으로 정리했는지 여부", nullable = true)
            Boolean conflictResolved,
            String reviewNote
    ) {

        public Decision(
                UUID candidateId,
                WorldSettingOperation operation,
                WorldSettingCategory category,
                String subjectName,
                String settingName,
                String value,
                Boolean conflictResolved,
                String reviewNote
        ) {
            this(
                    candidateId,
                    operation,
                    category,
                    subjectName,
                    null,
                    settingName,
                    value,
                    conflictResolved,
                    reviewNote
            );
        }
    }
}
