package org.monitoring.catchholebackend.domain.character.service;

import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;

public record SettingCandidateConfirmResult(
        SettingCandidateReviewStatusResponse response,
        boolean recomparisonRequired
) {

    public static SettingCandidateConfirmResult confirmed(SettingCandidateReviewStatusResponse response) {
        return new SettingCandidateConfirmResult(response, false);
    }

    public static SettingCandidateConfirmResult recomparisonRequired(
            SettingCandidateReviewStatusResponse response
    ) {
        return new SettingCandidateConfirmResult(response, true);
    }
}
