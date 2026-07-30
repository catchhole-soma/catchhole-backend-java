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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
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

    @Autowired
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;

    private Work work;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.save(Member.register(
                uniqueEmail("writer"),
                "encoded-password",
                uniquePhoneNumber(),
                "작가"
        ));
        work = workRepository.save(Work.create(member, "은빛 검사", WorkGenre.FANTASY, "검사 성장물"));
        characterSettingSchemaRepository.save(settingSchema(
                null,
                "age",
                null,
                CharacterFactType.AGE,
                SettingValueType.NUMBER,
                true,
                CharacterSettingMergePolicy.REPLACE,
                "나이"
        ));
        characterSettingSchemaRepository.save(settingSchema(
                null,
                "level",
                null,
                CharacterFactType.LEVEL,
                SettingValueType.NUMBER,
                true,
                CharacterSettingMergePolicy.REPLACE,
                "레벨"
        ));
        characterSettingSchemaRepository.save(settingSchema(
                null,
                "stats.strength",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                true,
                CharacterSettingMergePolicy.REPLACE,
                "힘"
        ));
        characterSettingSchemaRepository.save(settingSchema(
                null,
                "stats.agility",
                null,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                true,
                CharacterSettingMergePolicy.REPLACE,
                "민첩"
        ));
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "skills.skill",
                "skill.*",
                CharacterFactType.SKILL,
                SettingValueType.JSON,
                true,
                CharacterSettingMergePolicy.UPSERT_BY_NAME
        ));
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "items.item",
                "item.*",
                CharacterFactType.ITEM,
                SettingValueType.JSON,
                true,
                CharacterSettingMergePolicy.UPSERT_BY_NAME
        ));
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "statuses.status",
                "status.*",
                CharacterFactType.STATUS,
                SettingValueType.JSON,
                true,
                CharacterSettingMergePolicy.UPSERT_BY_NAME
        ));
    }

    @Test
    @DisplayName("이른 회차 후 늦은 회차를 확정하면 늦은 회차 fact가 current가 된다")
    void promoteSelectsLaterEpisodeFactAsCurrent() {
        Episode episode3 = episode(3);
        Episode episode10 = episode(10);

        promote(candidate(episode3, "level", "3"));
        WorkCharacter character = character("아리아");
        promote(matchedCandidate(episode10, character, "level", "10"));

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
        WorkCharacter character = character("아리아");
        promote(matchedCandidate(episode3, character, "level", "3"));

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
        WorkCharacter character = character("아리아");
        promote(matchedCandidate(episode3, character, "level", "4"));

        CharacterFact currentFact = currentFact(character, CharacterFactType.LEVEL, "level");
        assertThat(currentFact.getFactValue()).isEqualTo("4");
        assertThat(character.getCurrentLevel()).isEqualTo(4);
    }

    @Test
    @DisplayName("episode 없는 후보는 episode 있는 후보보다 current 우선순위가 낮다")
    void promoteTreatsMissingEpisodeAsOlderThanEpisodeFact() {
        Episode episode3 = episode(3);

        promote(candidate(episode3, "level", "3"));
        WorkCharacter character = character("아리아");
        promote(matchedCandidate(null, character, "level", "99"));

        CharacterFact currentFact = currentFact(character, CharacterFactType.LEVEL, "level");
        assertThat(currentFact.getFactValue()).isEqualTo("3");
        assertThat(currentFact.getEffectiveFromEpisodeNo()).isEqualTo(3);
        assertThat(character.getCurrentLevel()).isEqualTo(3);
    }

    @Test
    @DisplayName("current fact 기준으로 WorkCharacter JSON snapshot을 갱신한다")
    void promoteAppliesCurrentJsonSnapshot() {
        Episode episode3 = episode(3);
        JsonNode skillJson = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("name", "은월참")
                        .put("level", 3));

        promote(candidate(episode3, "skill.은월참", "은월참", SettingValueType.JSON, skillJson));

        WorkCharacter character = character("아리아");
        CharacterFact currentFact = currentFact(character, CharacterFactType.SKILL, "skill.은월참");
        assertThat(currentFact.getValueJson()).isEqualTo(skillJson);
        assertThat(character.getSkillsJson().get("skill.은월참")).isEqualTo(skillJson);
    }

    @Test
    @DisplayName("서로 다른 factKey의 current 값을 타입별 JSON object map에 함께 유지한다")
    void promoteBuildsSnapshotsFromAllCurrentFacts() {
        Episode episode3 = episode(3);
        JsonNode skillJson = objectMapper.createObjectNode().put("name", "은월참").put("level", 3);
        JsonNode itemJson = objectMapper.createObjectNode().put("name", "회복포션").put("quantity", 2);
        JsonNode statusJson = objectMapper.createObjectNode().put("name", "부상").put("active", true);

        promote(candidate(episode3, "stats.strength", "12"));
        WorkCharacter character = character("아리아");
        promote(matchedCandidate(episode3, character, "stats.agility", "8"));
        promote(matchedCandidate(
                episode3,
                character,
                "skill.은월참",
                "은월참",
                SettingValueType.JSON,
                skillJson
        ));
        promote(matchedCandidate(
                episode3,
                character,
                "item.회복포션",
                "회복포션",
                SettingValueType.JSON,
                itemJson
        ));
        promote(matchedCandidate(
                episode3,
                character,
                "status.부상",
                "부상",
                SettingValueType.JSON,
                statusJson
        ));

        assertThat(character.getStatsJson().get("stats.strength")).isEqualTo(valueJson("12"));
        assertThat(character.getStatsJson().get("stats.agility")).isEqualTo(valueJson("8"));
        assertThat(character.getSkillsJson().get("skill.은월참")).isEqualTo(skillJson);
        assertThat(character.getItemsJson().get("item.회복포션")).isEqualTo(itemJson);
        assertThat(character.getStatusesJson().get("status.부상")).isEqualTo(statusJson);
    }

    @Test
    @DisplayName("과거 회차의 같은 JSON factKey를 나중에 확정해도 최신 current 값만 snapshot에 둔다")
    void promoteKeepsLatestCurrentValueForSameJsonFactKey() {
        Episode episode3 = episode(3);
        Episode episode10 = episode(10);
        JsonNode latestSkill = objectMapper.createObjectNode().put("name", "은월참").put("level", 10);
        JsonNode olderSkill = objectMapper.createObjectNode().put("name", "은월참").put("level", 3);

        promote(candidate(episode10, "skill.은월참", "은월참", SettingValueType.JSON, latestSkill));
        WorkCharacter character = character("아리아");
        promote(matchedCandidate(
                episode3,
                character,
                "skill.은월참",
                "은월참",
                SettingValueType.JSON,
                olderSkill
        ));

        assertThat(character.getSkillsJson().get("skill.은월참")).isEqualTo(latestSkill);
    }

    @Test
    @DisplayName("다음 confirm에서 legacy JSON snapshot을 전체 current Fact map으로 정규화한다")
    void promoteNormalizesLegacySnapshotsFromCurrentFacts() {
        WorkCharacter character = workCharacterRepository.save(WorkCharacter.create(
                work,
                "아리아",
                null,
                null,
                null,
                null,
                objectMapper.createArrayNode().add(1),
                objectMapper.createArrayNode().add(2),
                objectMapper.createArrayNode().add(3),
                objectMapper.createArrayNode().add(4),
                null
        ));
        JsonNode itemJson = objectMapper.createObjectNode().put("name", "회복포션").put("quantity", 2);
        CharacterFact itemFact = CharacterFact.create(
                character,
                null,
                CharacterFactType.ITEM,
                "item.회복포션",
                "회복포션",
                "회복포션",
                itemJson,
                null,
                UUID.randomUUID(),
                null,
                new BigDecimal("0.8000"),
                null
        );
        itemFact.markCurrent();
        characterFactRepository.saveAndFlush(itemFact);

        promote(matchedCandidate(episode(3), character, "level", "5"));

        assertThat(character.getStatsJson()).isNull();
        assertThat(character.getSkillsJson()).isNull();
        assertThat(character.getItemsJson().get("item.회복포션")).isEqualTo(itemJson);
        assertThat(character.getStatusesJson()).isNull();
    }

    @Test
    @DisplayName("AGE와 LEVEL current Fact는 기존 일반 컬럼 snapshot을 계속 갱신한다")
    void promoteKeepsAgeAndLevelSnapshots() {
        Episode episode3 = episode(3);

        promote(candidate(episode3, "age", "17"));
        WorkCharacter character = character("아리아");
        promote(matchedCandidate(episode3, character, "level", "5"));

        assertThat(character.getCurrentAge()).isEqualTo(17);
        assertThat(character.getCurrentLevel()).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(
            value = CharacterSettingMergePolicy.class,
            names = {"UPSERT_BY_SLOT", "APPEND", "DERIVED"}
    )
    @DisplayName("미지원 merge policy는 캐릭터와 Fact 생성 전에 거절한다")
    void promoteRejectsUnsupportedMergePolicyWithoutSideEffects(CharacterSettingMergePolicy mergePolicy) {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "status.log",
                null,
                CharacterFactType.STATUS,
                SettingValueType.JSON,
                true,
                mergePolicy
        ));
        SettingCandidate candidate = candidate(
                episode(3),
                "status.log",
                "기록",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "기록")
        );

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MERGE_POLICY_UNSUPPORTED));

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("alias로 매칭한 후보는 schema의 factType과 canonical factKey로 저장한다")
    void promoteStoresCanonicalFactFromAliasMatch() {
        Episode episode3 = episode(3);

        promote(candidate(episode3, "stats.힘", "12"));

        WorkCharacter character = character("아리아");
        CharacterFact currentFact = currentFact(character, CharacterFactType.STAT, "stats.strength");
        assertThat(currentFact.getFactValue()).isEqualTo("12");
    }

    @Test
    @DisplayName("활성 schema와 매칭되지 않는 속성은 확정 데이터 반영을 거절한다")
    void promoteRejectsUnmatchedAttributeName() {
        Episode episode3 = episode(3);
        SettingCandidate candidate = candidate(episode3, "profile", "북부 기사단");

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED));

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("후보와 schema의 valueType이 다르면 캐릭터와 fact를 생성하지 않는다")
    void promoteRejectsMismatchedValueTypeWithoutSideEffects() {
        Episode episode3 = episode(3);
        SettingCandidate candidate = candidate(
                episode3,
                "level",
                "높음",
                SettingValueType.STRING,
                objectMapper.createObjectNode().put("value", "높음")
        );

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_VALUE_TYPE_MISMATCH));

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("호환되는 value envelope와 공개 속성을 가진 scalar 후보를 확정한다")
    void promoteAcceptsRoundTrippableScalarProperties() {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "profile.rank",
                null,
                CharacterFactType.PROFILE,
                SettingValueType.STRING,
                true,
                CharacterSettingMergePolicy.REPLACE
        ));
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("value", "KNIGHT")
                .put("name", "등급");
        SettingCandidate candidate = candidate(
                episode(3),
                "profile.rank",
                "기사",
                SettingValueType.STRING,
                valueJson
        );

        promote(candidate);

        WorkCharacter character = character("아리아");
        CharacterFact currentFact = currentFact(character, CharacterFactType.PROFILE, "profile.rank");
        assertThat(currentFact.getFactValue()).isEqualTo("기사");
        assertThat(currentFact.getValueJson()).isEqualTo(valueJson);
        assertThat(character.getProfileJson().get("profile.rank")).isEqualTo(valueJson);
    }

    @Test
    @DisplayName("비활성 작품 schema는 확정 매칭에서 제외한다")
    void promoteExcludesDisabledWorkSchema() {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "profile.rank",
                null,
                CharacterFactType.STATUS,
                SettingValueType.STRING,
                false
        ));
        SettingCandidate candidate = candidate(
                episode(3),
                "profile.rank",
                "기사",
                SettingValueType.STRING,
                objectMapper.createObjectNode().put("value", "기사")
        );

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED));

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("다른 작품의 활성 schema는 확정 매칭에서 제외한다")
    void promoteExcludesOtherWorkSchema() {
        Member otherMember = memberRepository.save(Member.register(
                uniqueEmail("other"),
                "encoded-password",
                uniquePhoneNumber(),
                "다른 작가"
        ));
        Work otherWork = workRepository.save(Work.create(otherMember, "다른 작품", WorkGenre.FANTASY, "설명"));
        characterSettingSchemaRepository.save(settingSchema(
                otherWork,
                "profile.rank",
                null,
                CharacterFactType.STATUS,
                SettingValueType.STRING,
                true
        ));
        SettingCandidate candidate = candidate(
                episode(3),
                "profile.rank",
                "기사",
                SettingValueType.STRING,
                objectMapper.createObjectNode().put("value", "기사")
        );

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_SCHEMA_NOT_MATCHED));

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("MATCHED 후보는 entityName이 달라도 matchedCharacterId 캐릭터에 반영한다")
    void promoteMatchedCandidateUsesMatchedCharacter() {
        Episode episode3 = episode(3);
        WorkCharacter matchedCharacter = workCharacterRepository.save(workCharacter("이안"));
        SettingCandidate candidate = candidate(
                episode3,
                "아리아",
                matchedCharacter.getId(),
                SettingCandidateMatchStatus.MATCHED,
                "level",
                "5"
        );

        promote(candidate);

        CharacterFact currentFact = currentFact(matchedCharacter, CharacterFactType.LEVEL, "level");
        assertThat(currentFact.getFactValue()).isEqualTo("5");
        assertThat(workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                "아리아",
                CharacterStatus.ACTIVE
        )).isEmpty();
    }

    @Test
    @DisplayName("MATCHED 후보에 matchedCharacterId가 없으면 확정 반영을 거절한다")
    void promoteRejectsMatchedCandidateWithoutMatchedCharacterId() {
        Episode episode3 = episode(3);
        SettingCandidate candidate = candidate(
                episode3,
                "아리아",
                null,
                SettingCandidateMatchStatus.MATCHED,
                "level",
                "5"
        );

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT));
    }

    @Test
    @DisplayName("MATCHED 후보가 보관된 캐릭터를 가리키면 확정 반영을 거절한다")
    void promoteRejectsMatchedCandidateWithArchivedCharacter() {
        Episode episode3 = episode(3);
        WorkCharacter matchedCharacter = workCharacterRepository.save(workCharacter("이안"));
        matchedCharacter.archive();
        SettingCandidate candidate = candidate(
                episode3,
                "이안",
                matchedCharacter.getId(),
                SettingCandidateMatchStatus.MATCHED,
                "level",
                "5"
        );

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID));
    }

    @Test
    @DisplayName("UNRESOLVED 후보에 matchedCharacterId가 있으면 확정 반영을 거절한다")
    void promoteRejectsUnresolvedCandidateWithMatchedCharacterId() {
        Episode episode3 = episode(3);
        WorkCharacter matchedCharacter = workCharacterRepository.save(workCharacter("이안"));
        SettingCandidate candidate = candidate(
                episode3,
                "아리아",
                matchedCharacter.getId(),
                SettingCandidateMatchStatus.UNRESOLVED,
                "level",
                "5"
        );

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT));
    }

    @Test
    @DisplayName("UNRESOLVED 후보 이름과 같은 활성 캐릭터가 있으면 해당 캐릭터를 재사용한다")
    void promoteReusesActiveCharacterWithSameName() {
        Episode episode3 = episode(3);
        WorkCharacter existingCharacter = workCharacterRepository.save(workCharacter("아리아"));
        SettingCandidate candidate = candidate(episode3, "level", "5");

        promote(candidate);

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId()))
                .containsExactly(existingCharacter);
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(existingCharacter.getId());
        assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        CharacterFact fact = currentFact(existingCharacter, CharacterFactType.LEVEL, "level");
        assertThat(fact.getFactValue()).isEqualTo("5");
        assertThat(fact.getSettingCandidate().getId()).isEqualTo(candidate.getId());
    }

    @Test
    @DisplayName("UNRESOLVED 후보 이름과 같은 보관 캐릭터만 있으면 새 활성 캐릭터를 생성한다")
    void promoteCreatesActiveCharacterWhenOnlyArchivedCharacterHasSameName() {
        WorkCharacter archivedCharacter = workCharacterRepository.save(workCharacter("아리아"));
        archivedCharacter.archive();
        SettingCandidate candidate = candidate(episode(3), "level", "5");

        promote(candidate);

        WorkCharacter activeCharacter = workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                "아리아",
                CharacterStatus.ACTIVE
        ).orElseThrow();
        assertThat(activeCharacter.getId()).isNotEqualTo(archivedCharacter.getId());
        assertThat(archivedCharacter.getStatus()).isEqualTo(CharacterStatus.ARCHIVED);
        assertThat(candidate.getMatchedCharacterId()).isEqualTo(activeCharacter.getId());
        assertThat(currentFact(activeCharacter, CharacterFactType.LEVEL, "level").getFactValue())
                .isEqualTo("5");
    }

    @Test
    @DisplayName("신규 캐릭터 확정은 같은 이름의 미해소 형제 후보를 연결하고 이후 설정을 병합한다")
    void promoteMatchesPendingUnresolvedSiblingsForNewCharacter() {
        Episode episode3 = episode(3);
        SettingCandidate first = candidate(
                episode3,
                "아리아",
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                "stats.strength",
                "12"
        );
        SettingCandidate sibling = candidate(
                episode3,
                "  아리아  ",
                null,
                SettingCandidateMatchStatus.UNRESOLVED,
                "stats.agility",
                "8"
        );
        SettingCandidate ambiguous = candidate(
                episode3,
                "아리아",
                null,
                SettingCandidateMatchStatus.AMBIGUOUS,
                "level",
                "5"
        );

        promote(first);

        WorkCharacter character = character("아리아");
        assertThat(first.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(first.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(first.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(sibling.getEntityName()).isEqualTo("아리아");
        assertThat(sibling.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(sibling.getMatchStatus())
                .isEqualTo(SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
        assertThat(sibling.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(ambiguous.getMatchedCharacterId()).isNull();
        assertThat(ambiguous.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.AMBIGUOUS);

        promote(sibling);

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId()))
                .containsExactly(character);
        assertThat(character.getStatsJson().get("stats.strength")).isEqualTo(valueJson("12"));
        assertThat(character.getStatsJson().get("stats.agility")).isEqualTo(valueJson("8"));
    }

    @Test
    @DisplayName("AMBIGUOUS 후보는 해소 전 확정 반영을 거절한다")
    void promoteRejectsAmbiguousCandidate() {
        Episode episode3 = episode(3);
        SettingCandidate candidate = candidate(
                episode3,
                "미상",
                null,
                SettingCandidateMatchStatus.AMBIGUOUS,
                "level",
                "5"
        );

        assertThatThrownBy(() -> promote(candidate))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(CharacterErrorCode.SETTING_CANDIDATE_MATCH_STATUS_CONFLICT));
    }

    private void promote(SettingCandidate candidate) {
        candidate.confirm();
        promotionService.promote(candidate);
    }

    private WorkCharacter character(String name) {
        return workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                name,
                CharacterStatus.ACTIVE
        ).orElseThrow();
    }

    private WorkCharacter workCharacter(String name) {
        return WorkCharacter.create(
                work,
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
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

    private SettingCandidate matchedCandidate(
            Episode episode,
            WorkCharacter character,
            String attributeName,
            String attributeValue
    ) {
        return candidate(
                episode,
                character.getName(),
                character.getId(),
                SettingCandidateMatchStatus.MATCHED,
                attributeName,
                attributeValue
        );
    }

    private SettingCandidate matchedCandidate(
            Episode episode,
            WorkCharacter character,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode valueJson
    ) {
        return candidate(
                episode,
                character.getName(),
                character.getId(),
                SettingCandidateMatchStatus.MATCHED,
                attributeName,
                attributeValue,
                valueType,
                valueJson
        );
    }

    private SettingCandidate candidate(
            Episode episode,
            String entityName,
            UUID matchedCharacterId,
            SettingCandidateMatchStatus matchStatus,
            String attributeName,
            String attributeValue
    ) {
        return candidate(
                episode,
                entityName,
                matchedCharacterId,
                matchStatus,
                attributeName,
                attributeValue,
                SettingValueType.NUMBER,
                valueJson(attributeValue)
        );
    }

    private SettingCandidate candidate(
            Episode episode,
            String entityName,
            UUID matchedCharacterId,
            SettingCandidateMatchStatus matchStatus,
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
                entityName,
                entityName,
                matchedCharacterId,
                matchStatus,
                attributeName,
                attributeValue,
                valueType,
                valueJson,
                objectMapper.createArrayNode(),
                new BigDecimal("0.8000"),
                objectMapper.createObjectNode().put("raw_value", attributeValue)
        ));
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

    private CharacterSettingSchema settingSchema(
            Work schemaWork,
            String schemaKey,
            String attributePattern,
            CharacterFactType factType,
            SettingValueType valueType,
            boolean enabled,
            String... aliases
    ) {
        return settingSchema(
                schemaWork,
                schemaKey,
                attributePattern,
                factType,
                valueType,
                enabled,
                CharacterSettingMergePolicy.REPLACE,
                aliases
        );
    }

    private CharacterSettingSchema settingSchema(
            Work schemaWork,
            String schemaKey,
            String attributePattern,
            CharacterFactType factType,
            SettingValueType valueType,
            boolean enabled,
            CharacterSettingMergePolicy mergePolicy,
            String... aliases
    ) {
        var aliasesJson = objectMapper.createArrayNode();
        for (String alias : aliases) {
            aliasesJson.add(alias);
        }
        return CharacterSettingSchema.create(
                schemaWork,
                schemaKey,
                attributePattern,
                schemaKey,
                factType,
                valueType,
                CharacterSettingValueSemantics.BASE_VALUE,
                mergePolicy,
                aliasesJson,
                CharacterSettingSchemaSource.SYSTEM_SEED,
                enabled
        );
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
