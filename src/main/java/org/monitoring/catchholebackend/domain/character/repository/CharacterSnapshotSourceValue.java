package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

/** #381 snapshot slot 근거 Fact의 표시값. legacy raw snapshot의 factValue 보충에만 쓰며 Fact·후보 엔티티는 읽지 않는다. */
public record CharacterSnapshotSourceValue(UUID characterId, CharacterFactType factType, String factKey, String factValue) {
}
