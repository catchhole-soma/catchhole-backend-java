package org.monitoring.catchholebackend.domain.character.processor;

import java.util.Objects;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

/** Character snapshot 안의 한 값을 fact type과 canonical key로 식별한다. */
public record CharacterSnapshotSlot(CharacterFactType factType, String factKey) {

    public CharacterSnapshotSlot {
        Objects.requireNonNull(factType);
        Objects.requireNonNull(factKey);
    }
}
