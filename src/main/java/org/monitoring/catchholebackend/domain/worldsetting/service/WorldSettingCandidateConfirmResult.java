package org.monitoring.catchholebackend.domain.worldsetting.service;

import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;

public record WorldSettingCandidateConfirmResult(
        WorldSettingCandidateResponse candidate,
        boolean recomparisonRequired
) {

    public static WorldSettingCandidateConfirmResult confirmed(WorldSettingCandidateResponse candidate) {
        return new WorldSettingCandidateConfirmResult(candidate, false);
    }

    public static WorldSettingCandidateConfirmResult recomparisonRequired(
            WorldSettingCandidateResponse candidate
    ) {
        return new WorldSettingCandidateConfirmResult(candidate, true);
    }
}
