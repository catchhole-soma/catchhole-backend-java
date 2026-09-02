package org.monitoring.catchholebackend.domain.character.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.stereotype.Component;

/** snapshot 값과 별도로 해당 값을 뒷받침하는 원본 Fact 목록을 slot 단위로 교체한다. */
@Component
@RequiredArgsConstructor
public class CharacterSnapshotSourceManager {

    private final CharacterSnapshotSourceRepository repository;

    public void replaceSources(
            WorkCharacter character,
            CharacterSnapshotSlot slot,
            List<CharacterFact> sourceFacts
    ) {
        List<CharacterSnapshotSource> existing = findSources(character, slot);
        repository.deleteAll(existing);
        repository.flush();
        saveSources(character, slot, sourceFacts);
    }

    public void mergeSource(
            WorkCharacter character,
            CharacterSnapshotSlot slot,
            CharacterFact newSourceFact
    ) {
        List<CharacterSnapshotSource> existing = findSources(character, slot);
        Set<java.util.UUID> existingIds = new HashSet<>();
        existing.forEach(source -> existingIds.add(source.getSourceFact().getId()));
        if (existingIds.add(newSourceFact.getId())) {
            repository.save(CharacterSnapshotSource.create(
                    character,
                    slot.factType(),
                    slot.factKey(),
                    newSourceFact,
                    existing.size()
            ));
        }
    }

    public void removeSources(WorkCharacter character, CharacterSnapshotSlot slot) {
        repository.deleteAll(findSources(character, slot));
        repository.flush();
    }

    /** 여러 snapshot slot의 provenance를 fact type별 bulk 조회한 뒤 한 번에 제거한다. */
    public void removeSources(WorkCharacter character, Collection<CharacterSnapshotSlot> slots) {
        if (slots.isEmpty()) {
            return;
        }
        Map<CharacterFactType, List<String>> keysByType = new LinkedHashMap<>();
        for (CharacterSnapshotSlot slot : new LinkedHashSet<>(slots)) {
            keysByType.computeIfAbsent(slot.factType(), ignored -> new ArrayList<>())
                    .add(slot.factKey());
        }
        List<CharacterSnapshotSource> sources = new ArrayList<>();
        keysByType.forEach((factType, factKeys) -> sources.addAll(
                repository.findAllByWorkCharacterIdAndFactTypeAndFactKeyIn(
                        character.getId(),
                        factType,
                        factKeys
                )
        ));
        repository.deleteAllInBatch(sources);
        repository.flush();
    }

    /** snapshot slot별 provenance를 저장된 source_order 순으로 반환한다. */
    public Map<CharacterSnapshotSlot, List<CharacterFact>> findSourceFactsBySlot(WorkCharacter character) {
        Map<CharacterSnapshotSlot, List<CharacterFact>> sourceFactsBySlot = new LinkedHashMap<>();
        repository.findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(character.getId())
                .forEach(source -> {
                    CharacterSnapshotSlot slot = new CharacterSnapshotSlot(
                            source.getFactType(),
                            source.getFactKey()
                    );
                    sourceFactsBySlot.computeIfAbsent(slot, ignored -> new ArrayList<>())
                            .add(source.getSourceFact());
                });
        return sourceFactsBySlot;
    }

    private List<CharacterSnapshotSource> findSources(
            WorkCharacter character,
            CharacterSnapshotSlot slot
    ) {
        return repository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderBySourceOrderAsc(
                character.getId(),
                slot.factType(),
                slot.factKey()
        );
    }

    private void saveSources(
            WorkCharacter character,
            CharacterSnapshotSlot slot,
            List<CharacterFact> sourceFacts
    ) {
        List<CharacterSnapshotSource> sources = new ArrayList<>();
        for (int index = 0; index < sourceFacts.size(); index++) {
            sources.add(CharacterSnapshotSource.create(
                    character,
                    slot.factType(),
                    slot.factKey(),
                    sourceFacts.get(index),
                    index
            ));
        }
        repository.saveAll(sources);
    }
}
