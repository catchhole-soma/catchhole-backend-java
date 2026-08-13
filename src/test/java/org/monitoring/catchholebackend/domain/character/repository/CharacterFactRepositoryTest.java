package org.monitoring.catchholebackend.domain.character.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CharacterFactRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private WorkCharacterRepository workCharacterRepository;

    @Autowired
    private CharacterFactRepository characterFactRepository;

    @Autowired
    private CharacterSnapshotSourceRepository characterSnapshotSourceRepository;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    @Autowired
    private EntityManager entityManager;

    private Work work;
    private Episode episode;
    private AnalysisJob analysisJob;
    private WorkCharacter character;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.save(Member.register(
                uniqueEmail("writer"),
                "encoded-password",
                uniquePhoneNumber(),
                "작가"
        ));
        work = workRepository.save(Work.create(member, "은빛 검사", WorkGenre.FANTASY, "검사 성장물"));
        episode = episodeRepository.save(Episode.create(
                work,
                null,
                3,
                "3화",
                "works/%s/episodes/3.txt".formatted(work.getId()),
                "version-3",
                "hash-3",
                320
        ));
        analysisJob = analysisJobRepository.save(AnalysisJob.create(
                work,
                null,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        ));
        character = workCharacterRepository.save(WorkCharacter.create(
                work,
                "아리아",
                "protagonist",
                17,
                23,
                null,
                null,
                null,
                null,
                null,
                episode.getId()
        ));
    }

    @Test
    void saveAndFindCharacterFactWithSourceAndJsonValue() {
        UUID sourceChunkId = UUID.randomUUID();
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("name", "검은단검")
                .put("quantity", 1)
                .put("state", "OWNED");
        JsonNode evidenceSpans = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("quote", "아리아는 검은단검을 집어 들었다."));
        SettingCandidate settingCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                episode,
                sourceChunkId,
                analysisJob,
                SettingEntityType.CHARACTER,
                "아리아",
                "item.검은단검",
                "검은단검",
                SettingValueType.JSON,
                valueJson,
                evidenceSpans,
                new BigDecimal("0.9100"),
                objectMapper.createObjectNode()
        ));

        CharacterFact fact = CharacterFact.create(
                character,
                settingCandidate,
                CharacterFactType.ITEM,
                "item.검은단검.quantity",
                "1",
                "1",
                valueJson,
                episode,
                sourceChunkId,
                analysisJob,
                new BigDecimal("0.9100"),
                3
        );
        CharacterFact saved = characterFactRepository.saveAndFlush(fact);
        characterSnapshotSourceRepository.saveAndFlush(CharacterSnapshotSource.create(
                character,
                saved.getFactType(),
                saved.getFactKey(),
                saved,
                0
        ));
        UUID savedFactId = saved.getId();
        entityManager.clear();

        CharacterFact found = characterFactRepository.findById(savedFactId).orElseThrow();

        assertThat(found.getWorkCharacter().getId()).isEqualTo(character.getId());
        assertThat(found.getSettingCandidate().getId()).isEqualTo(settingCandidate.getId());
        assertThat(found.getSettingCandidate().getEvidenceSpans()).isEqualTo(evidenceSpans);
        assertThat(found.getFactType()).isEqualTo(CharacterFactType.ITEM);
        assertThat(found.getFactKey()).isEqualTo("item.검은단검.quantity");
        assertThat(found.getFactValue()).isEqualTo("1");
        assertThat(found.getNormalizedValue()).isEqualTo("1");
        assertThat(found.getValueJson()).isEqualTo(valueJson);
        assertThat(found.getSourceEpisode().getId()).isEqualTo(episode.getId());
        assertThat(found.getSourceChunkId()).isEqualTo(sourceChunkId);
        assertThat(found.getExtractedByJob().getId()).isEqualTo(analysisJob.getId());
        assertThat(found.getConfidence()).isEqualByComparingTo("0.9100");
        assertThat(found.getEffectiveFromEpisodeNo()).isEqualTo(3);

        entityManager.clear();
        CharacterFact currentFact = characterSnapshotSourceRepository
                .findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(character.getId())
                .getFirst()
                .getSourceFact();

        assertThat(entityManager.getEntityManagerFactory()
                .getPersistenceUnitUtil()
                .isLoaded(currentFact, "settingCandidate")).isTrue();
    }

    @Test
    void snapshotSourcesReturnOnlyFactsThatContributeToCurrentSnapshot() {
        CharacterFact currentLevel = fact(CharacterFactType.LEVEL, "level", "23", "23", 3);
        CharacterFact currentStrength = fact(CharacterFactType.STAT, "strength", "42", "42", 3);
        CharacterFact historicalLevel = fact(CharacterFactType.LEVEL, "level", "20", "20", 1);

        characterFactRepository.saveAll(List.of(currentLevel, currentStrength, historicalLevel));
        characterSnapshotSourceRepository.save(CharacterSnapshotSource.create(
                character,
                currentLevel.getFactType(),
                currentLevel.getFactKey(),
                currentLevel,
                0
        ));
        characterSnapshotSourceRepository.save(CharacterSnapshotSource.create(
                character,
                currentStrength.getFactType(),
                currentStrength.getFactKey(),
                currentStrength,
                0
        ));

        List<CharacterFact> currentFacts = characterSnapshotSourceRepository
                .findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(character.getId())
                .stream()
                .map(CharacterSnapshotSource::getSourceFact)
                .toList();

        assertThat(currentFacts)
                .extracting(CharacterFact::getFactKey)
                .containsExactly("level", "strength");
        assertThat(currentFacts).doesNotContain(historicalLevel);
    }

    @Test
    void removingSnapshotSourceKeepsAppendOnlyFactHistory() {
        CharacterFact currentLevel = fact(CharacterFactType.LEVEL, "level", "23", "23", 3);
        characterFactRepository.save(currentLevel);
        CharacterSnapshotSource source = characterSnapshotSourceRepository.save(CharacterSnapshotSource.create(
                character,
                currentLevel.getFactType(),
                currentLevel.getFactKey(),
                currentLevel,
                0
        ));

        characterSnapshotSourceRepository.delete(source);
        characterSnapshotSourceRepository.flush();

        assertThat(characterSnapshotSourceRepository.existsBySourceFactId(currentLevel.getId())).isFalse();
        assertThat(characterFactRepository.findById(currentLevel.getId())).isPresent();
    }

    @Test
    void findFactHistoryOrdersByEffectiveEpisodeNoDesc() {
        characterFactRepository.save(fact(CharacterFactType.LEVEL, "level", "12", "12", 1));
        characterFactRepository.save(fact(CharacterFactType.LEVEL, "level", "18", "18", 2));
        characterFactRepository.save(fact(CharacterFactType.LEVEL, "level", "23", "23", 3));
        characterFactRepository.save(fact(CharacterFactType.STAT, "strength", "42", "42", 3));

        List<CharacterFact> history =
                characterFactRepository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                        character.getId(),
                        CharacterFactType.LEVEL,
                        "level"
                );

        assertThat(history)
                .extracting(CharacterFact::getFactValue)
                .containsExactly("23", "18", "12");
    }

    @Test
    void factHistoryDoesNotIncludeOtherCharacterFactsWithSameKey() {
        WorkCharacter otherCharacter = workCharacterRepository.save(WorkCharacter.create(
                work,
                "세이라",
                "supporter",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                episode.getId()
        ));
        characterFactRepository.save(fact(CharacterFactType.LEVEL, "level", "23", "23", 3));
        characterFactRepository.save(CharacterFact.create(
                otherCharacter,
                null,
                CharacterFactType.LEVEL,
                "level",
                "99",
                "99",
                objectMapper.createObjectNode().put("value", "99"),
                episode,
                null,
                analysisJob,
                new BigDecimal("0.8000"),
                3
        ));

        List<CharacterFact> history =
                characterFactRepository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                        character.getId(),
                        CharacterFactType.LEVEL,
                        "level"
                );

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getWorkCharacter().getId()).isEqualTo(character.getId());
        assertThat(history.getFirst().getFactValue()).isEqualTo("23");
    }

    private CharacterFact fact(
            CharacterFactType factType,
            String factKey,
            String factValue,
            String normalizedValue,
            int effectiveFromEpisodeNo
    ) {
        return CharacterFact.create(
                character,
                null,
                factType,
                factKey,
                factValue,
                normalizedValue,
                objectMapper.createObjectNode().put("value", factValue),
                episode,
                null,
                analysisJob,
                new BigDecimal("0.8000"),
                effectiveFromEpisodeNo
        );
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String uniquePhoneNumber() {
        return "010" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
