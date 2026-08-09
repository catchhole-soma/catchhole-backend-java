package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonScope;

public record WorldSettingCandidateGroupConfirmResult(
        WorldSettingCandidateGroupActionResponse response,
        WorldSettingRecomparisonScope scope,
        WorldSettingRecomparisonReason reason,
        List<UUID> affectedCandidateIds
) {

    public static WorldSettingCandidateGroupConfirmResult confirmed(
            WorldSettingCandidateGroupActionResponse response
    ) {
        return new WorldSettingCandidateGroupConfirmResult(response, null, null, List.of());
    }

    public static WorldSettingCandidateGroupConfirmResult recomparisonRequired(
            WorldSettingRecomparisonScope scope,
            WorldSettingRecomparisonReason reason,
            List<UUID> affectedCandidateIds
    ) {
        return new WorldSettingCandidateGroupConfirmResult(null, scope, reason, affectedCandidateIds);
    }

    public boolean recomparisonRequired() {
        return scope != null;
    }
}
