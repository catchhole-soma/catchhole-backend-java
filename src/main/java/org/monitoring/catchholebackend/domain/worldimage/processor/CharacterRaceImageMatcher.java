package org.monitoring.catchholebackend.domain.worldimage.processor;

import java.util.List;
import java.util.Set;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageCatalog;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CharacterRaceImageMatcher {
    private final CharacterSnapshotAccessor snapshots;
    private static final Set<String> SPECIES_KEYS = Set.of("profile.species", "species", "profile.race", "race");

    public WorldImageCatalog matchRace(WorkCharacter character, List<WorldImageCatalog> races) {
        var values = snapshots.read(character).values().stream()
                .filter(entry -> entry.slot().factType() == CharacterFactType.PROFILE && SPECIES_KEYS.contains(entry.slot().factKey()))
                .map(entry -> WorldImageSearch.normalize(entry.factValue())).filter(value -> !value.isBlank()).distinct().toList();
        // 설명·이름에서 부분 문자열을 찾지 않는다. 중복 종족 값이 모순되면 추측하지 않는다.
        if (values.size() != 1) return null;
        String value = values.getFirst();
        var matches = races.stream().filter(race -> WorldImageSearch.normalize(race.getName()).equals(value)
                || race.getAliases().stream().anyMatch(alias -> WorldImageSearch.normalize(alias).equals(value))).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }
}
