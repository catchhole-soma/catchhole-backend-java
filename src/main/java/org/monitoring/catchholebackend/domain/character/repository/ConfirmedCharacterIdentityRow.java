package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;

/** Scalar projection of a confirmed name-to-profile decision; contains no candidate/job JSON. */
public interface ConfirmedCharacterIdentityRow {
    String getEntityName();

    UUID getCharacterId();
}
