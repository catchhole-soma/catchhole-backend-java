package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("설정 후보 확정 데이터 반영 Service 테스트")
class SettingCandidatePromotionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private SettingCandidatePromotionService promotionService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    @Autowired
    private WorkCharacterRepository workCharacterRepository;

    @Autowired
    private CharacterFactRepository characterFactRepository;

    private Work work;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.save(Member.register(
                uniqueEmail("writer"),
                "encoded-password",
                uniquePhoneNumber(),
                "작가"
        ));
        work = workRepository.save(Work.create(member, "은빛 검사", "판타지", "검사 성장물"));
    }

    @Test
    @DisplayName("이른 회차 후 늦은 회차를 확정하면 늦은 회차 fact가 current가 된다")
    void promoteSelectsLaterEpisodeFactAsCurrent() {
        Episode episode3 = episode(3);
        Episode episode10 = episode(10);

        promote(candidate(episode3, "level", "3"));
        promote(candidate(episode10, "level", "10"));

        WorkCharacter character = character("아리아");
        CharacterFact currentFact = currentFact(character, CharacterFactType.LEVEL, "level");
        assertThat(currentFact.getFactValue()).isEqualTo("10");
        assertThat(currentFact.getEffectiveFromEpisodeNo()).isEqualTo(10);
        assertThat(character.getCurrentLevel()).isEqualTo(10);
    }

    @Test
    @DisplayName("늦은 회차 후 이른 회차를 확정해도 늦은 회차 fact가 current로 유지된다")
    void promoteKeepsLaterEpisodeCurrentWhenOlderEpisodeIsConfirmedLater() {
        Episode episode3 = episode(3);
        Episode episode10 = episode(10);

        promote(candidate(episode10, "level", "10"));
        promote(candidate(episode3, "level", "3"));

        WorkCharacter character = character("아리아");
        CharacterFact currentFact = currentFact(character, CharacterFactType.LEVEL, "level");
        assertThat(currentFact.getFactValue()).isEqualTo("10");
        assertThat(currentFact.getEffectiveFromEpisodeNo()).isEqualTo(10);
        assertThat(character.getCurrentLevel()).isEqualTo(10);
        assertThat(character.getFirstAppearanceEpisodeId()).isEqualTo(episode3.getId());
    }

    @Test
    @DisplayName("같은 회차의 같은 key는 나중에 확정된 fact가 current가 된다")
    void promoteSelectsLaterConfirmedFactWithinSameEpisode() {
        Episode episode3 = episode(3);

        promote(candidate(episode3, "level", "3"));
        promote(candidate(episode3, "level", "4"));

        WorkCharacter character = character("아리아");
        CharacterFact currentFact = currentFact(character, CharacterFactType.LEVEL, "level");
        assertThat(currentFact.getFactValue()).isEqualTo("4");
        assertThat(character.getCurrentLevel()).isEqualTo(4);
    }

    @Test
    @DisplayName("episode 없는 후보는 episode 있는 후보보다 current 우선순위가 낮다")
    void promoteTreatsMissingEpisodeAsOlderThanEpisodeFact() {
        Episode episode3 = episode(3);

        promote(candidate(episode3, "level", "3"));
        promote(candidate(null, "level", "99"));

        WorkCharacter character = character("아리아");
        CharacterFact currentFact = currentFact(character, CharacterFactType.LEVEL, "level");
        assertThat(currentFact.getFactValue()).isEqualTo("3");
        assertThat(currentFact.getEffectiveFromEpisodeNo()).isEqualTo(3);
        assertThat(character.getCurrentLevel()).isEqualTo(3);
    }

    @Test
    @DisplayName("current fact 기준으로 WorkCharacter JSON snapshot을 갱신한다")
    void promoteAppliesCurrentJsonSnapshot() {
        Episode episode3 = episode(3);
        JsonNode skillsJson = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("name", "은월참")
                        .put("level", 3));

        promote(candidate(episode3, "skills", "은월참", SettingValueType.JSON, skillsJson));

        WorkCharacter character = character("아리아");
        CharacterFact currentFact = currentFact(character, CharacterFactType.SKILL, "skills");
        assertThat(currentFact.getValueJson()).isEqualTo(skillsJson);
        assertThat(character.getSkillsJson()).isEqualTo(skillsJson);
    }

    @Test
    @DisplayName("지원하지 않는 속성은 확정 데이터 반영을 거절한다")
    void promoteRejectsUnsupportedAttributeName() {
        Episode episode3 = episode(3);
        SettingCandidate candidate = candidate(episode3, "profile", "북부 기사단");

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_FACT_TYPE_UNSUPPORTED));

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
    }

    private void promote(SettingCandidate candidate) {
        candidate.confirm();
        promotionService.promote(candidate);
    }

    private WorkCharacter character(String name) {
        return workCharacterRepository.findByWorkIdAndName(work.getId(), name).orElseThrow();
    }

    private CharacterFact currentFact(WorkCharacter character, CharacterFactType factType, String factKey) {
        return characterFactRepository
                .findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                        character.getId(),
                        factType,
                        factKey
                )
                .stream()
                .filter(CharacterFact::isCurrent)
                .findFirst()
                .orElseThrow();
    }

    private SettingCandidate candidate(Episode episode, String attributeName, String attributeValue) {
        return candidate(episode, attributeName, attributeValue, SettingValueType.NUMBER, valueJson(attributeValue));
    }

    private SettingCandidate candidate(
            Episode episode,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson
    ) {
        return settingCandidateRepository.save(SettingCandidate.create(
                work,
                episode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                attributeName,
                attributeValue,
                valueType,
                valueJson,
                objectMapper.createArrayNode(),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", attributeValue)
        ));
    }

    private JsonNode valueJson(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return objectMapper.createObjectNode().put("value", value);
        }
        return objectMapper.createObjectNode().put("value", Integer.parseInt(digits));
    }

    private Episode episode(int episodeNo) {
        return episodeRepository.save(Episode.create(
                work,
                null,
                episodeNo,
                episodeNo + "화",
                "works/%s/episodes/%d.txt".formatted(work.getId(), episodeNo),
                "version-" + episodeNo,
                "hash-" + episodeNo,
                100 + episodeNo
        ));
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private String uniquePhoneNumber() {
        return "010" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
