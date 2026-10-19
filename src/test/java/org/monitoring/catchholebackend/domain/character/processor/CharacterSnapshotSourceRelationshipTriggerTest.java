package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("현재 프로필 provenance 변경의 관계 재처리 표지")
class CharacterSnapshotSourceRelationshipTriggerTest {

    private final CharacterSnapshotSourceRepository repository = mock(CharacterSnapshotSourceRepository.class);
    private final CharacterSnapshotSourceManager manager = new CharacterSnapshotSourceManager(repository);
    private Work work;
    private WorkCharacter character;
    private final CharacterSnapshotSlot family = new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.family");

    @BeforeEach
    void setUp() {
        work = Work.create(null, "프로필 출처 변경", WorkGenre.FANTASY, null);
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        character = WorkCharacter.create(work, "아리아", null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
    }

    @Test
    @DisplayName("프로필 출처 교체는 재처리 세대를 올리며 같은 작품의 다른 설정 교체는 올리지 않는다")
    void replacingProfileSourcesMarksWorkButOtherSettingsDoNot() {
        manager.replaceSources(character, family, List.of(fact(family)));
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);

        CharacterSnapshotSlot skill = new CharacterSnapshotSlot(CharacterFactType.SKILL, "skill.sword");
        manager.replaceSources(character, skill, List.of(fact(skill)));
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("프로필 병합은 새 근거가 추가된 경우만 표시하고 같은 Fact 재전송은 건너뛴다")
    void mergeMarksNewSupportOnly() {
        CharacterFact fact = fact(family);
        manager.mergeSource(character, family, fact);
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);

        when(repository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderBySourceOrderAsc(
                character.getId(), family.factType(), family.factKey()))
                .thenReturn(List.of(CharacterSnapshotSource.create(character, family.factType(), family.factKey(), fact, 0)));
        manager.mergeSource(character, family, fact);
        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("프로필 단일 삭제는 출처가 이미 없는 레거시 slot도 재처리 대상으로 표시한다")
    void removingProfileSlotMarksInputEvenWhenProvenanceWasMissing() {
        manager.removeSources(character, family);
        manager.removeSources(character, new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.injury"));

        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("프로필이 섞인 일괄 삭제는 작품 세대를 한 번 올리고 중복 slot을 합친다")
    void bulkRemovalMarksProfileOnce() {
        CharacterSnapshotSlot otherProfile = new CharacterSnapshotSlot(CharacterFactType.PROFILE, "profile.family2");
        when(repository.findAllByWorkCharacterIdAndFactTypeAndFactKeyIn(eq(character.getId()), any(), any()))
                .thenReturn(List.of());

        manager.removeSources(character, List.of(family, family, otherProfile,
                new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.injury")));

        assertThat(work.getRelationshipInputVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 삭제와 프로필 외 일괄 삭제 및 출처 조회는 재처리를 만들지 않는다")
    void emptyOrUnrelatedRemovalAndReadsDoNotMarkWork() {
        manager.removeSources(character, List.of());
        manager.removeSources(character, List.of(new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.injury")));
        manager.findSourceFactsBySlot(character);

        assertThat(work.getRelationshipInputVersion()).isZero();
    }

    private CharacterFact fact(CharacterSnapshotSlot slot) {
        CharacterFact fact = CharacterFact.createManual(character, slot.factType(), slot.factKey(), "설정", null);
        ReflectionTestUtils.setField(fact, "id", UUID.randomUUID());
        return fact;
    }
}
