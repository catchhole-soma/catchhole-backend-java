package org.monitoring.catchholebackend.domain.character.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateGroupActionResponse;

public record SettingCandidateGroupConfirmResult(
        SettingCandidateGroupActionResponse response,
        List<UUID> recomparisonCandidateIds
) {

    public static SettingCandidateGroupConfirmResult confirmed(SettingCandidateGroupActionResponse response) {
        return new SettingCandidateGroupConfirmResult(response, List.of());
    }

    public static SettingCandidateGroupConfirmResult recomparisonRequired(List<UUID> candidateIds) {
        return new SettingCandidateGroupConfirmResult(null, List.copyOf(candidateIds));
    }

    public boolean recomparisonRequired() {
        return !recomparisonCandidateIds.isEmpty();
    }
}
