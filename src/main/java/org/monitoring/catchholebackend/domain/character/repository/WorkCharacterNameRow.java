package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;

/** Scalar profile name; never hydrates the character's JSON snapshot columns. */
public interface WorkCharacterNameRow {
    UUID getId();

    String getName();
}
