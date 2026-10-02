package org.monitoring.catchholebackend.domain.character.service;

import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;

public record SettingCandidateGroupPromotion(
        SettingCandidate candidate,
        CharacterFactConfirmApplicationMode applicationMode,
        boolean acceptedFinalResult
) {
    public SettingCandidateGroupPromotion(SettingCandidate candidate, CharacterFactConfirmApplicationMode applicationMode) {
        this(candidate, applicationMode, false);
    }
}
