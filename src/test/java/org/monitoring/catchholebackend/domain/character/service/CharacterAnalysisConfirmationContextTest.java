package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("캐릭터 확정의 인물 전체 비교 문맥 검증")
class CharacterAnalysisConfirmationContextTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("인물 묶음은 모든 타입의 현재값을 검증하고 기존 묶음은 해당 타입만 검증한다")
    void confirmationValidatesAllTypesOnlyForWholePersonBatch(boolean legacy) {
        var json = JsonNodeFactory.instance;
        Work work = mock(Work.class);
        when(work.getId()).thenReturn(UUID.randomUUID());
        WorkCharacter character = WorkCharacter.create(work, "서린", null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        var profile = json.objectNode().set("profile.species", json.objectNode().put("value", "인간"));
        var status = json.objectNode().set("status.부상", json.objectNode().put("value", "부상"));
        character.replaceCurrentSnapshots(null, null, profile, null, null, null, status);
        var frozen = new CharacterAnalysisStateMapper().toState(character,
                new CharacterSnapshotAccessor().read(character, Map.of()), Map.of());
        var batch = mock(CharacterFactComparisonBatch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batch.getCanonicalFactType()).thenReturn(legacy ? CharacterFactType.STATUS : null);
        when(batch.getAnalysisContextSnapshotJson()).thenReturn(frozen);
        var candidate = mock(SettingCandidate.class);
        when(candidate.getCharacterComparisonBatch()).thenReturn(batch);
        when(candidate.isComparisonCompleted()).thenReturn(true);
        when(candidate.getWork()).thenReturn(work);
        when(candidate.getMatchedCharacterId()).thenReturn(character.getId());
        var characters = mock(WorkCharacterRepository.class);
        when(characters.findByIdAndWorkIdForUpdate(character.getId(), work.getId())).thenReturn(Optional.of(character));
        var confirmation = new CharacterAnalysisConfirmation(characters, mock(SettingCandidateRepository.class),
                mock(CharacterSnapshotSourceRepository.class), new CharacterSnapshotAccessor(),
                mock(CharacterFactRepository.class), mock(CharacterSettingSchemaRepository.class),
                new SettingCandidateSchemaResolver(), mock(AnalysisCandidateSourceGuard.class));

        assertThat(confirmation.hasCurrentContext(candidate, List.of())).isTrue();
        var changedProfile = json.objectNode().set("profile.species", json.objectNode().put("value", "엘프"));
        character.replaceCurrentSnapshots(null, null, changedProfile, null, null, null, status);
        assertThat(confirmation.hasCurrentContext(candidate, List.of())).isEqualTo(legacy);
    }
}
