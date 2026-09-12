package org.monitoring.catchholebackend.domain.character.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisStateSource;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Component;

/** 일반 단일 회차의 입력 경로와 분리된 누적 실행 S0 capture다. */
@Component
@RequiredArgsConstructor
public class CharacterAnalysisStateSource implements AnalysisStateSource {

    private final WorkCharacterRepository characterRepository;
    private final CharacterSnapshotSourceRepository sourceRepository;
    private final CharacterSnapshotAccessor snapshotAccessor;
    private final CharacterAnalysisStateMapper mapper;
    private final SettingCandidateRepository candidateRepository;

    @Override
    public String domain() {
        return "characters";
    }

    @Override
    public JsonNode capture(Work work) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var discoveries = candidateRepository.findConfirmedDiscoveries(work.getId());
        for (WorkCharacter character : characterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(
                work.getId(), CharacterStatus.ACTIVE
        )) {
            Map<CharacterSnapshotSlot, List<CharacterFact>> facts = new LinkedHashMap<>();
            for (CharacterSnapshotSource source : sourceRepository
                    .findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(character.getId())) {
                CharacterSnapshotSlot slot = new CharacterSnapshotSlot(source.getFactType(), source.getFactKey());
                facts.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(source.getSourceFact());
            }
            ObjectNode state = mapper.toState(
                    character, snapshotAccessor.read(character, facts), facts
            );
            mapper.addConfirmedIdentity(state, discoveries.stream()
                    .filter(candidate -> character.getId().equals(candidate.getMatchedCharacterId())).toList());
            result.set(CharacterAnalysisStateMapper.persistedRef(character.getId()), state);
        }
        return result;
    }
}
