package org.monitoring.catchholebackend.domain.validation.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingVersion;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationCorrectionRepository;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingVersion;

@DisplayName("#381 live 설정 fingerprint 정규형과 주체 참조")
class ValidationLiveSettingsTest {
    final WorkCharacterRepository characters = mock(WorkCharacterRepository.class);
    final WorldSettingRepository worlds = mock(WorldSettingRepository.class);
    final AnalysisStateJournal journal = new AnalysisStateJournal();
    final ValidationLiveSettings live = new ValidationLiveSettings(characters, mock(CharacterSnapshotSourceRepository.class),
            mock(SettingCandidateRepository.class), worlds, mock(WorldSettingCandidateRepository.class), mock(ValidationCorrectionRepository.class),
            new CharacterSnapshotAccessor(), new CharacterAnalysisStateMapper(), new WorldSettingAnalysisStateMapper(), journal);
    final UUID work = UUID.randomUUID();
    final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    final UUID b = UUID.fromString("ffffffff-0000-0000-0000-00000000000b");
    final UUID w = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Test
    @DisplayName("정규형은 fingerprintVersion 1과 domain별 [id, version] 목록이며 각 목록은 id 문자열 순이다")
    void canonicalStateListsIdVersionPairsSortedByIdString() {
        var state = ValidationLiveSettings.fingerprintState(List.of(
                new ValidationLiveSettings.Subject(1, "characters", b, "나중", List.of("별칭"), 3),
                new ValidationLiveSettings.Subject(2, "characters", a, "먼저", List.of(), 7),
                new ValidationLiveSettings.Subject(3, "worldSettings", w, "엘프", List.of(), 2)));
        assertThat(state.toString()).isEqualTo("{\"fingerprintVersion\":1,"
                + "\"characters\":[[\"" + a + "\",7],[\"" + b + "\",3]],"
                + "\"worldSettings\":[[\"" + w + "\",2]]}");
    }

    @Test
    @DisplayName("조회 순서·이름·별칭과 무관하고 version·id 집합·domain이 바뀌면 달라진다")
    void fingerprintDependsOnlyOnDomainIdAndVersion() {
        when(characters.findSettingVersions(work, CharacterStatus.ACTIVE)).thenReturn(List.of(
                new CharacterSettingVersion(b, "나중", 3), new CharacterSettingVersion(a, "먼저", 7)));
        when(worlds.findSettingVersions(work)).thenReturn(List.of(new WorldSettingVersion(w, "엘프", 2)));
        String base = live.fingerprint(work);
        assertThat(base).matches("[0-9a-f]{64}");

        when(characters.findSettingVersions(work, CharacterStatus.ACTIVE)).thenReturn(List.of(
                new CharacterSettingVersion(a, "이름 변경", 7), new CharacterSettingVersion(b, "나중", 3)));
        assertThat(live.fingerprint(work)).isEqualTo(base);

        when(characters.findSettingVersions(work, CharacterStatus.ACTIVE)).thenReturn(List.of(
                new CharacterSettingVersion(a, "먼저", 8), new CharacterSettingVersion(b, "나중", 3)));
        assertThat(live.fingerprint(work)).isNotEqualTo(base);

        when(characters.findSettingVersions(work, CharacterStatus.ACTIVE)).thenReturn(List.of(new CharacterSettingVersion(a, "먼저", 7)));
        assertThat(live.fingerprint(work)).isNotEqualTo(base);

        var asWorld = ValidationLiveSettings.fingerprintState(List.of(new ValidationLiveSettings.Subject(1, "worldSettings", a, "x", List.of(), 7)));
        var asCharacter = ValidationLiveSettings.fingerprintState(List.of(new ValidationLiveSettings.Subject(1, "characters", a, "x", List.of(), 7)));
        assertThat(journal.hash(asWorld)).isNotEqualTo(journal.hash(asCharacter));
    }

    @Test
    @DisplayName("S·B 참조는 같은 번호의 주체를 가리키고 형식이 틀리거나 범위 밖이면 비어 있다")
    void indexFindsSubjectsBySubjectOrEntryRef() {
        var index = new ValidationLiveSettings.Index(work, List.of(
                new ValidationLiveSettings.Subject(1, "characters", a, "먼저", List.of(), 1),
                new ValidationLiveSettings.Subject(2, "worldSettings", w, "엘프", List.of(), 1)));
        assertThat(index.find("S2")).get().isSameAs(index.find("B2").orElseThrow());
        assertThat(index.find("S2").orElseThrow().stateRef()).isEqualTo("world:" + w);
        assertThat(index.find("B1").orElseThrow().stateRef()).isEqualTo("character:" + a);
        for (String invalid : List.of("S0", "S3", "E1", "S01", "s1", "S99999999999", ""))
            assertThat(index.find(invalid)).as(invalid).isEmpty();
        assertThat(index.find(null)).isEmpty();
    }

    @Test
    @DisplayName("전체 상태 주체가 100개를 넘으면 아무것도 읽기 전에 거절한다")
    void entriesRejectMoreThanOneHundredFullSubjects() {
        var subjects = new ArrayList<ValidationLiveSettings.Subject>();
        IntStream.rangeClosed(1, 101).forEach(n -> subjects.add(new ValidationLiveSettings.Subject(n, "characters", UUID.randomUUID(), "인물" + n, List.of(), 0)));
        var index = new ValidationLiveSettings.Index(work, List.copyOf(subjects));
        var refs = subjects.stream().map(ValidationLiveSettings.Subject::subjectRef).toList();

        assertThatThrownBy(() -> live.entries(index, refs)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(characters, worlds);
        assertThat(live.entries(index, refs.subList(0, 0))).hasSize(101);
    }
}
