package org.monitoring.catchholebackend.domain.character.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
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
        work = workRepository.save(Work.create(member, "은빛 검사", "판타지", "검사 성장물"));
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

        CharacterFact fact = CharacterFact.create(
                character,
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
        fact.markCurrent();
        CharacterFact saved = characterFactRepository.save(fact);

        CharacterFact found = characterFactRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getWorkCharacter().getId()).isEqualTo(character.getId());
        assertThat(found.getFactType()).isEqualTo(CharacterFactType.ITEM);
        assertThat(found.getFactKey()).isEqualTo("item.검은단검.quantity");
        assertThat(found.getFactValue()).isEqualTo("1");
        assertThat(found.getNormalizedValue()).isEqualTo("1");
        assertThat(found.getValueJson()).isEqualTo(valueJson);
        assertThat(found.getSourceEpisode().getId()).isEqualTo(episode.getId());
        assertThat(found.getSourceChunkId()).isEqualTo(sourceChunkId);
        assertThat(found.getExtractedByJob().getId()).isEqualTo(analysisJob.getId());
        assertThat(found.getConfidence()).isEqualByComparingTo("0.9100");
        assertThat(found.getReviewStatus()).isEqualTo(CharacterFactReviewStatus.PENDING_REVIEW);
        assertThat(found.isCurrent()).isTrue();
        assertThat(found.getEffectiveFromEpisodeNo()).isEqualTo(3);
    }

    @Test
    void findCurrentFactsReturnsOnlyCurrentFactsForCharacter() {
        CharacterFact currentLevel = fact(CharacterFactType.LEVEL, "level", "23", "23", 3);
        currentLevel.markCurrent();
        CharacterFact currentStrength = fact(CharacterFactType.STAT, "strength", "42", "42", 3);
        currentStrength.markCurrent();
        CharacterFact historicalLevel = fact(CharacterFactType.LEVEL, "level", "20", "20", 1);

        characterFactRepository.save(currentLevel);
        characterFactRepository.save(currentStrength);
        characterFactRepository.save(historicalLevel);

        List<CharacterFact> currentFacts =
                characterFactRepository.findAllByWorkCharacterIdAndIsCurrentTrueOrderByFactTypeAscFactKeyAsc(
                        character.getId()
                );

        assertThat(currentFacts)
                .extracting(CharacterFact::getFactKey)
                .containsExactly("level", "strength");
        assertThat(currentFacts).allMatch(CharacterFact::isCurrent);
    }

    @Test
    void markHistoricalRemovesFactFromCurrentFacts() {
        CharacterFact currentLevel = fact(CharacterFactType.LEVEL, "level", "23", "23", 3);
        currentLevel.markCurrent();
        characterFactRepository.save(currentLevel);

        currentLevel.markHistorical();

        List<CharacterFact> currentFacts =
                characterFactRepository.findAllByWorkCharacterIdAndIsCurrentTrueOrderByFactTypeAscFactKeyAsc(
                        character.getId()
                );

        assertThat(currentLevel.isCurrent()).isFalse();
        assertThat(currentFacts).isEmpty();
    }

    @Test
    void confirmAndDismissFactsAreFilteredByReviewStatus() {
        CharacterFact confirmed = fact(CharacterFactType.LEVEL, "level", "23", "23", 3);
        confirmed.confirm();
        characterFactRepository.save(confirmed);

        CharacterFact dismissed = fact(CharacterFactType.ITEM, "item.검은단검.state", "OWNED", "OWNED", 3);
        dismissed.markCurrent();
        dismissed.dismiss();
        characterFactRepository.save(dismissed);

        characterFactRepository.save(fact(CharacterFactType.STAT, "strength", "42", "42", 3));

        List<CharacterFact> confirmedFacts =
                characterFactRepository.findAllByWorkCharacterIdAndReviewStatusOrderByCreatedAtDesc(
                        character.getId(),
                        CharacterFactReviewStatus.CONFIRMED
                );
        List<CharacterFact> dismissedFacts =
                characterFactRepository.findAllByWorkCharacterIdAndReviewStatusOrderByCreatedAtDesc(
                        character.getId(),
                        CharacterFactReviewStatus.DISMISSED
                );
        List<CharacterFact> pendingFacts =
                characterFactRepository.findAllByWorkCharacterIdAndReviewStatusOrderByCreatedAtDesc(
                        character.getId(),
                        CharacterFactReviewStatus.PENDING_REVIEW
                );

        assertThat(confirmedFacts).hasSize(1);
        assertThat(confirmedFacts.getFirst().getFactKey()).isEqualTo("level");
        assertThat(dismissedFacts).hasSize(1);
        assertThat(dismissedFacts.getFirst().getFactKey()).isEqualTo("item.검은단검.state");
        assertThat(dismissedFacts.getFirst().isCurrent()).isFalse();
        assertThat(pendingFacts).hasSize(1);
        assertThat(pendingFacts.getFirst().getFactKey()).isEqualTo("strength");
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
