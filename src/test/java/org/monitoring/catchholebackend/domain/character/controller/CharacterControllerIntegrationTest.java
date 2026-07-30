package org.monitoring.catchholebackend.domain.character.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("캐릭터 현재 설정 API 통합 테스트")
class CharacterControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private WorkCharacterRepository workCharacterRepository;

    @Autowired
    private CharacterFactRepository characterFactRepository;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private UploadFileRepository uploadFileRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Member otherMember;
    private Work work;
    private Work otherWork;
    private Episode firstEpisode;
    private String accessToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        member = memberRepository.save(Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        ));
        otherMember = memberRepository.save(Member.register(
                "other@example.com",
                "encoded-password",
                "01087654321",
                "다른 작가"
        ));
        work = workRepository.save(Work.create(member, "내 작품", WorkGenre.FANTASY, "내 설명"));
        otherWork = workRepository.save(Work.create(otherMember, "다른 작품", WorkGenre.MARTIAL_ARTS, "다른 설명"));
        firstEpisode = episodeRepository.save(Episode.create(
                work,
                null,
                1,
                "입학식",
                "works/%s/episodes/1.txt".formatted(work.getId()),
                "version-1",
                "hash-1",
                100
        ));
        characterSettingSchemaRepository.saveAll(List.of(
                settingSchema("profile.gender", null, "성별", CharacterFactType.PROFILE, SettingValueType.STRING),
                settingSchema("stats.strength", null, "근력", CharacterFactType.STAT, SettingValueType.NUMBER),
                settingSchema("skills.skill", "skill.*", "스킬", CharacterFactType.SKILL, SettingValueType.JSON)
        ));
        accessToken = jwtTokenProvider.generateAccessToken(member);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        characterFactRepository.deleteAll();
        settingCandidateRepository.deleteAll();
        workCharacterRepository.deleteAll();
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        characterSettingSchemaRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("활성 캐릭터 카드만 최신 생성순으로 조회한다")
    void getCharactersReturnsOnlyActiveCharacters() throws Exception {
        WorkCharacter activeCharacter = workCharacterRepository.save(character(work, "수아", 23, 15));
        activeCharacter.updateFirstAppearanceEpisodeId(firstEpisode.getId());
        WorkCharacter archivedCharacter = workCharacterRepository.save(character(work, "보관 인물", 30, 8));
        archivedCharacter.archive();
        workCharacterRepository.saveAllAndFlush(List.of(activeCharacter, archivedCharacter));

        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(activeCharacter.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].name").value("수아"))
                .andExpect(jsonPath("$.data.content[0].currentAge").value(23))
                .andExpect(jsonPath("$.data.content[0].representativeAttributeLabel").value("레벨"))
                .andExpect(jsonPath("$.data.content[0].representativeAttributeValue").value("15"))
                .andExpect(jsonPath("$.data.content[0].firstAppearanceEpisodeNo").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(24))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("첫 등장 회차가 없는 캐릭터도 목록에서 조회한다")
    void getCharactersReturnsCharacterWithoutFirstAppearanceEpisode() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", 23, 15));

        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(character.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].name").value("수아"))
                .andExpect(jsonPath("$.data.content[0].firstAppearanceEpisodeNo").doesNotExist());
    }

    @Test
    @DisplayName("캐릭터 목록은 다른 작품 회차를 첫 등장 회차로 노출하지 않는다")
    void getCharactersDoesNotExposeAnotherWorkEpisode() throws Exception {
        Episode otherEpisode = episodeRepository.save(Episode.create(
                otherWork,
                null,
                1,
                "다른 작품 1화",
                "works/%s/episodes/1.txt".formatted(otherWork.getId()),
                "version-other",
                "hash-other",
                100
        ));
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", 23, 15));
        character.updateFirstAppearanceEpisodeId(otherEpisode.getId());
        workCharacterRepository.saveAndFlush(character);

        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].firstAppearanceEpisodeNo").doesNotExist());
    }

    @Test
    @DisplayName("캐릭터 목록을 요청 크기로 나누고 페이지 메타데이터를 응답한다")
    void getCharactersReturnsRequestedPage() throws Exception {
        WorkCharacter first = workCharacterRepository.saveAndFlush(character(work, "첫 번째", 20, 1));
        WorkCharacter second = workCharacterRepository.saveAndFlush(character(work, "두 번째", 21, 2));
        WorkCharacter third = workCharacterRepository.saveAndFlush(character(work, "세 번째", 22, 3));

        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .queryParam("page", "0")
                        .queryParam("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(third.getId().toString()))
                .andExpect(jsonPath("$.data.content[1].id").value(second.getId().toString()))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .queryParam("page", "1")
                        .queryParam("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(first.getId().toString()))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("캐릭터 목록의 페이지 번호와 크기 범위를 검증한다")
    void getCharactersRejectsInvalidPageRequest() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .queryParam("page", "-1")
                        .queryParam("size", "25")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.length()").value(2));
    }

    @Test
    @DisplayName("숫자가 아닌 페이지 파라미터를 잘못된 요청으로 응답한다")
    void getCharactersRejectsMalformedPageRequest() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .queryParam("page", "abc")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("캐릭터 상세에서 현재 설정을 사용자용 항목 목록으로 응답한다")
    void getCharacterReturnsCurrentSettingLists() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", 23, 15));
        character.updateFirstAppearanceEpisodeId(firstEpisode.getId());
        workCharacterRepository.saveAndFlush(character);
        SettingCandidate ageCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "age",
                "23",
                SettingValueType.NUMBER,
                JsonNodeFactory.instance.objectNode().put("value", 23),
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아는 스물세 살이었다.")
                                .put("startOffset", 0)
                                .put("endOffset", 13)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact age = currentFact(
                character,
                ageCandidate,
                CharacterFactType.AGE,
                "age",
                "23",
                JsonNodeFactory.instance.objectNode().put("value", 23)
        );
        CharacterFact level = currentFact(
                character,
                CharacterFactType.LEVEL,
                "level",
                "15",
                JsonNodeFactory.instance.objectNode().put("value", 15)
        );
        CharacterFact gender = currentFact(
                character,
                CharacterFactType.PROFILE,
                "profile.gender",
                "여성",
                JsonNodeFactory.instance.objectNode().put("value", "여성")
        );
        CharacterFact strength = currentFact(
                character,
                CharacterFactType.STAT,
                "stats.strength",
                "42",
                JsonNodeFactory.instance.objectNode().put("value", 42)
        );
        characterFactRepository.saveAllAndFlush(List.of(age, level, gender, strength));

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수아"))
                .andExpect(jsonPath("$.data.firstAppearanceEpisode.episodeNo").value(1))
                .andExpect(jsonPath("$.data.firstAppearanceEpisode.title").doesNotExist())
                .andExpect(jsonPath("$.data.currentAgeFact.characterFactId").value(age.getId().toString()))
                .andExpect(jsonPath("$.data.currentAgeFact.hasEvidence").value(true))
                .andExpect(jsonPath("$.data.currentLevelFact.characterFactId").value(level.getId().toString()))
                .andExpect(jsonPath("$.data.currentLevelFact.hasEvidence").value(false))
                .andExpect(jsonPath("$.data.profile[0].characterFactId").value(gender.getId().toString()))
                .andExpect(jsonPath("$.data.profile[0].key").value("profile.gender"))
                .andExpect(jsonPath("$.data.profile[0].displayName").value("성별"))
                .andExpect(jsonPath("$.data.profile[0].attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.profile[0].attributeNamePrefix").doesNotExist())
                .andExpect(jsonPath("$.data.profile[0].displayNameEditable").value(false))
                .andExpect(jsonPath("$.data.profile[0].value").value("여성"))
                .andExpect(jsonPath("$.data.profile[0].hasEvidence").value(false))
                .andExpect(jsonPath("$.data.stats[0].displayName").value("근력"))
                .andExpect(jsonPath("$.data.stats[0].valueType").value("NUMBER"))
                .andExpect(jsonPath("$.data.skills.length()").value(0));
    }

    @Test
    @DisplayName("현재 설정 전체 수정은 변경 Fact를 새로 만들고 기존 current Fact를 이력으로 전환한다")
    void updateCharacterCreatesManualCorrectionFacts() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", 17, null));
        CharacterFact oldAge = currentFact(
                character,
                CharacterFactType.AGE,
                "age",
                "17",
                JsonNodeFactory.instance.objectNode().put("value", 17)
        );
        CharacterFact oldStrength = currentFact(
                character,
                CharacterFactType.STAT,
                "stats.strength",
                "40",
                JsonNodeFactory.instance.objectNode().put("value", 40)
        );
        characterFactRepository.saveAllAndFlush(List.of(oldAge, oldStrength));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  수아 리안  ",
                                  "roleLabel": "  주인공  ",
                                  "currentAge": 23,
                                  "currentLevel": 15,
                                  "firstAppearanceEpisodeNo": 1,
                                  "profile": [
                                    {
                                      "key": "profile.gender",
                                      "value": "여성",
                                      "valueType": "STRING",
                                      "properties": []
                                    },
                                    {
                                      "key": "profile.manual_motto",
                                      "value": "끝까지 포기하지 않는다",
                                      "valueType": "STRING",
                                      "properties": [
                                        {"key": "name", "value": "좌우명", "valueType": "STRING"}
                                      ]
                                    }
                                  ],
                                  "stats": [
                                    {
                                      "key": "stats.strength",
                                      "value": "42",
                                      "valueType": "NUMBER",
                                      "properties": []
                                    },
                                    {
                                      "key": "stats.manual_luck",
                                      "value": "7",
                                      "valueType": "NUMBER",
                                      "properties": [
                                        {"key": "name", "value": "행운", "valueType": "STRING"}
                                      ]
                                    }
                                  ],
                                  "skills": [
                                    {
                                      "key": "skill.기본 검술",
                                      "value": "Lv.3",
                                      "valueType": "JSON",
                                      "properties": [
                                        {"key": "name", "value": "기본 검술", "valueType": "STRING"},
                                        {"key": "level", "value": "3", "valueType": "NUMBER"}
                                      ]
                                    }
                                  ],
                                  "items": [],
                                  "statuses": [
                                    {
                                      "key": "status.manual_injury",
                                      "value": "경상",
                                      "valueType": "JSON",
                                      "properties": [
                                        {"key": "name", "value": "부상", "valueType": "STRING"},
                                        {"key": "active", "value": "true", "valueType": "BOOLEAN"}
                                      ]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("캐릭터가 수정되었습니다."))
                .andExpect(jsonPath("$.data.name").value("수아 리안"))
                .andExpect(jsonPath("$.data.roleLabel").value("주인공"))
                .andExpect(jsonPath("$.data.currentAge").value(23))
                .andExpect(jsonPath("$.data.currentLevel").value(15))
                .andExpect(jsonPath("$.data.profile[0].hasEvidence").value(false))
                .andExpect(jsonPath("$.data.profile[1].displayName").value("좌우명"))
                .andExpect(jsonPath("$.data.profile[1].attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.profile[1].displayNameEditable").value(true))
                .andExpect(jsonPath("$.data.stats.length()").value(2))
                .andExpect(jsonPath("$.data.statuses[0].displayName").value("부상"))
                .andExpect(jsonPath("$.data.skills[0].displayName").value("기본 검술"))
                .andExpect(jsonPath("$.data.skills[0].key").value("skill.기본_검술"))
                .andExpect(jsonPath("$.data.skills[0].attributeNameEditable").value(true))
                .andExpect(jsonPath("$.data.skills[0].attributeNamePrefix").value("skill."))
                .andExpect(jsonPath("$.data.skills[0].displayNameEditable").value(true))
                .andExpect(jsonPath("$.data.skills[0].properties.length()").value(1));

        CharacterFact savedOldAge = characterFactRepository.findById(oldAge.getId()).orElseThrow();
        CharacterFact savedOldStrength = characterFactRepository.findById(oldStrength.getId()).orElseThrow();
        assertThat(savedOldAge.isCurrent()).isFalse();
        assertThat(savedOldStrength.isCurrent()).isFalse();

        WorkCharacter savedCharacter = workCharacterRepository.findById(character.getId()).orElseThrow();
        assertThat(savedCharacter.getName()).isEqualTo("수아 리안");
        assertThat(savedCharacter.getCurrentAge()).isEqualTo(23);
        assertThat(savedCharacter.getCurrentLevel()).isEqualTo(15);
        assertThat(savedCharacter.getFirstAppearanceEpisodeId()).isEqualTo(firstEpisode.getId());
        assertThat(savedCharacter.getProfileJson().get("profile.gender").get("value").asText()).isEqualTo("여성");
        assertThat(savedCharacter.getProfileJson().get("profile.manual_motto").get("value").asText())
                .isEqualTo("끝까지 포기하지 않는다");
        assertThat(savedCharacter.getStatsJson().get("stats.strength").get("value").asInt()).isEqualTo(42);
        assertThat(savedCharacter.getStatsJson().get("stats.manual_luck").get("value").asInt()).isEqualTo(7);
        assertThat(savedCharacter.getSkillsJson().get("skill.기본_검술"))
                .isEqualTo(JsonNodeFactory.instance.objectNode().put("name", "기본 검술"));
        assertThat(savedCharacter.getStatusesJson().get("status.manual_injury"))
                .isEqualTo(JsonNodeFactory.instance.objectNode().put("name", "부상"));

        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .filteredOn(fact -> fact.isCurrent() && fact.getFactKey().startsWith("profile.manual"))
                .allSatisfy(fact -> {
                    assertThat(fact.getSettingCandidate()).isNull();
                    assertThat(fact.getSourceEpisode()).isNull();
                    assertThat(fact.getSourceChunkId()).isNull();
                });
    }

    @Test
    @DisplayName("pattern 설정명 변경은 같은 prefix의 key를 정규화하고 최소 JSON의 새 Fact를 만든다")
    void updateCharacterRenamesPatternSettingWithMinimalJson() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        ObjectNode richValueJson = JsonNodeFactory.instance.objectNode()
                .put("name", "화염 검술")
                .put("level", 5)
                .put("description", "검에 불꽃을 두른다");
        SettingCandidate candidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "skill.화염_검술",
                "Lv.5",
                SettingValueType.JSON,
                richValueJson,
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아는 화염 검술을 펼쳤다.")
                                .put("startOffset", 0)
                                .put("endOffset", 16)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact oldSkill = currentFact(
                character,
                candidate,
                CharacterFactType.SKILL,
                "skill.화염_검술",
                "Lv.5",
                richValueJson
        );
        characterFactRepository.saveAndFlush(oldSkill);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest(
                                "수아",
                                """
                                        [
                                          {
                                            "key": "skill.서리 검술",
                                            "value": "Lv.6",
                                            "valueType": "JSON",
                                            "properties": [
                                              {"key": "name", "value": "신뢰하지 않을 이름", "valueType": "STRING"},
                                              {"key": "level", "value": "99", "valueType": "NUMBER"},
                                              {"key": "description", "value": "신뢰하지 않을 설명", "valueType": "STRING"}
                                            ]
                                          }
                                        ]
                                        """
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills[0].key").value("skill.서리_검술"))
                .andExpect(jsonPath("$.data.skills[0].displayName").value("서리 검술"))
                .andExpect(jsonPath("$.data.skills[0].attributeNameEditable").value(true))
                .andExpect(jsonPath("$.data.skills[0].attributeNamePrefix").value("skill."))
                .andExpect(jsonPath("$.data.skills[0].displayNameEditable").value(true))
                .andExpect(jsonPath("$.data.skills[0].value").value("Lv.6"))
                .andExpect(jsonPath("$.data.skills[0].properties.length()").value(1))
                .andExpect(jsonPath("$.data.skills[0].properties[0].value").value("서리 검술"))
                .andExpect(jsonPath("$.data.skills[0].hasEvidence").value(false));

        CharacterFact savedOldSkill = characterFactRepository.findById(oldSkill.getId()).orElseThrow();
        assertThat(savedOldSkill.isCurrent()).isFalse();
        assertThat(savedOldSkill.getValueJson()).isEqualTo(richValueJson);

        CharacterFact currentSkill = characterFactRepository
                .findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()).stream()
                .filter(CharacterFact::isCurrent)
                .findFirst()
                .orElseThrow();
        assertThat(currentSkill.getFactKey()).isEqualTo("skill.서리_검술");
        assertThat(currentSkill.getValueJson())
                .isEqualTo(JsonNodeFactory.instance.objectNode().put("name", "서리 검술"));
        assertThat(currentSkill.getSettingCandidate()).isNull();

        WorkCharacter savedCharacter = workCharacterRepository.findById(character.getId()).orElseThrow();
        assertThat(savedCharacter.getSkillsJson().has("skill.화염_검술")).isFalse();
        assertThat(savedCharacter.getSkillsJson().get("skill.서리_검술"))
                .isEqualTo(JsonNodeFactory.instance.objectNode().put("name", "서리 검술"));
    }

    @Test
    @DisplayName("scalar pattern 설정명과 값 변경은 typed value와 정규화한 name만 저장한다")
    void updateCharacterRenamesScalarPatternWithTypedValueAndName() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                "profile.attribute",
                "profile.*",
                "프로필",
                CharacterFactType.PROFILE,
                SettingValueType.STRING
        ));
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        CharacterFact eyeColor = currentFact(
                character,
                CharacterFactType.PROFILE,
                "profile.눈_색깔",
                "푸른색",
                JsonNodeFactory.instance.objectNode()
                        .put("value", "푸른색")
                        .put("name", "눈 색깔")
                        .put("description", "빛에 따라 색이 바뀐다")
        );
        characterFactRepository.saveAndFlush(eyeColor);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": null,
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [
                                    {
                                      "key": "profile.홍채 색상",
                                      "value": "금색",
                                      "valueType": "STRING",
                                      "properties": [
                                        {"key": "name", "value": "신뢰하지 않을 이름", "valueType": "STRING"},
                                        {"key": "description", "value": "신뢰하지 않을 설명", "valueType": "STRING"}
                                      ]
                                    }
                                  ],
                                  "stats": [],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile[0].key").value("profile.홍채_색상"))
                .andExpect(jsonPath("$.data.profile[0].displayName").value("홍채 색상"))
                .andExpect(jsonPath("$.data.profile[0].attributeNameEditable").value(true))
                .andExpect(jsonPath("$.data.profile[0].attributeNamePrefix").value("profile."))
                .andExpect(jsonPath("$.data.profile[0].value").value("금색"))
                .andExpect(jsonPath("$.data.profile[0].properties.length()").value(1))
                .andExpect(jsonPath("$.data.profile[0].properties[0].value").value("홍채 색상"));

        assertThat(characterFactRepository.findById(eyeColor.getId()).orElseThrow().isCurrent()).isFalse();
        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .filteredOn(CharacterFact::isCurrent)
                .singleElement()
                .satisfies(currentProfile -> {
                    assertThat(currentProfile.getFactKey()).isEqualTo("profile.홍채_색상");
                    assertThat(currentProfile.getValueJson())
                            .isEqualTo(JsonNodeFactory.instance.objectNode()
                                    .put("value", "금색")
                                    .put("name", "홍채 색상"));
                });
    }

    @Test
    @DisplayName("exact scalar 값 변경은 schema 표시명을 잠그고 typed value만 저장한다")
    void updateCharacterReplacesExactScalarWithTypedValueOnly() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        CharacterFact gender = currentFact(
                character,
                CharacterFactType.PROFILE,
                "profile.gender",
                "여성",
                JsonNodeFactory.instance.objectNode()
                        .put("value", "여성")
                        .put("name", "오래된 표시명")
                        .put("description", "복사하면 안 되는 설명")
        );
        characterFactRepository.saveAndFlush(gender);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": null,
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [
                                    {
                                      "key": "profile.gender",
                                      "value": "남성",
                                      "valueType": "STRING",
                                      "properties": [
                                        {"key": "name", "value": "조작된 표시명", "valueType": "STRING"},
                                        {"key": "description", "value": "조작된 설명", "valueType": "STRING"}
                                      ]
                                    }
                                  ],
                                  "stats": [],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile[0].displayName").value("성별"))
                .andExpect(jsonPath("$.data.profile[0].attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.profile[0].displayNameEditable").value(false))
                .andExpect(jsonPath("$.data.profile[0].properties.length()").value(0))
                .andExpect(jsonPath("$.data.profile[0].value").value("남성"));

        assertThat(characterFactRepository.findById(gender.getId()).orElseThrow().isCurrent()).isFalse();
        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .filteredOn(CharacterFact::isCurrent)
                .singleElement()
                .satisfies(currentGender -> {
                    assertThat(currentGender.getValueJson())
                            .isEqualTo(JsonNodeFactory.instance.objectNode().put("value", "남성"));
                    assertThat(currentGender.getSettingCandidate()).isNull();
                });
    }

    @Test
    @DisplayName("보관 캐릭터와 같은 이름으로 활성 캐릭터를 수정할 수 있다")
    void updateCharacterAllowsNameUsedOnlyByArchivedCharacter() throws Exception {
        WorkCharacter activeCharacter = workCharacterRepository.save(character(work, "현재 이름", null, null));
        WorkCharacter archivedCharacter = workCharacterRepository.save(character(work, "수아", null, null));
        archivedCharacter.archive();
        workCharacterRepository.saveAndFlush(archivedCharacter);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                activeCharacter.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": null,
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [],
                                  "stats": [],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수아"));

        assertThat(workCharacterRepository.findById(activeCharacter.getId()).orElseThrow().getStatus())
                .isEqualTo(CharacterStatus.ACTIVE);
        assertThat(workCharacterRepository.findById(archivedCharacter.getId()).orElseThrow().getStatus())
                .isEqualTo(CharacterStatus.ARCHIVED);
    }

    @Test
    @DisplayName("보관된 회차는 캐릭터의 새 첫 등장 회차로 지정할 수 없다")
    void updateCharacterRejectsArchivedFirstAppearanceEpisode() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", null, null));
        firstEpisode.archive();
        episodeRepository.saveAndFlush(firstEpisode);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest("수아", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EPISODE_NOT_FOUND"));

        WorkCharacter savedCharacter = workCharacterRepository.findById(character.getId()).orElseThrow();
        assertThat(savedCharacter.getFirstAppearanceEpisodeId()).isNull();
    }

    @Test
    @DisplayName("보관된 기존 첫 등장 회차는 변경 없는 전체 수정에서 유지한다")
    void updateCharacterKeepsArchivedCurrentFirstAppearanceEpisode() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", null, null));
        character.updateFirstAppearanceEpisodeId(firstEpisode.getId());
        workCharacterRepository.saveAndFlush(character);
        firstEpisode.archive();
        episodeRepository.saveAndFlush(firstEpisode);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest("수아", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstAppearanceEpisode.id")
                        .value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.firstAppearanceEpisode.episodeNo").value(1));

        WorkCharacter savedCharacter = workCharacterRepository.findById(character.getId()).orElseThrow();
        assertThat(savedCharacter.getFirstAppearanceEpisodeId()).isEqualTo(firstEpisode.getId());
    }

    @Test
    @DisplayName("보관 회차 번호가 재사용되어도 변경 없는 전체 수정은 기존 참조를 유지한다")
    void updateCharacterKeepsArchivedReferenceWhenEpisodeNumberIsReused() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", null, null));
        character.updateFirstAppearanceEpisodeId(firstEpisode.getId());
        workCharacterRepository.saveAndFlush(character);
        firstEpisode.archive();
        episodeRepository.saveAndFlush(firstEpisode);
        Episode replacementEpisode = episodeRepository.saveAndFlush(Episode.create(
                work,
                null,
                1,
                "새 1화",
                "works/%s/episodes/1-replacement.txt".formatted(work.getId()),
                "version-2",
                "hash-2",
                120
        ));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest("수아", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstAppearanceEpisode.id")
                        .value(firstEpisode.getId().toString()));

        WorkCharacter savedCharacter = workCharacterRepository.findById(character.getId()).orElseThrow();
        assertThat(savedCharacter.getFirstAppearanceEpisodeId())
                .isEqualTo(firstEpisode.getId())
                .isNotEqualTo(replacementEpisode.getId());
    }

    @Test
    @DisplayName("설정 속성에서 scalar envelope 예약 key인 value를 거절한다")
    void updateCharacterRejectsReservedValuePropertyKey() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", null, null));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": null,
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [
                                    {
                                      "key": "profile.manual",
                                      "value": "기사",
                                      "valueType": "STRING",
                                      "properties": [
                                        {"key": "value", "value": "숨겨진 값", "valueType": "STRING"}
                                      ]
                                    }
                                  ],
                                  "stats": [],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHARACTER_SETTING_KEY_INVALID"));
    }

    @Test
    @DisplayName("등록된 exact와 pattern schema의 값 타입이 다른 직접 수정을 거절한다")
    void updateCharacterRejectsRegisteredSchemaValueTypeMismatch() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        List<String> requests = List.of(
                """
                        {
                          "name": "변경될 이름",
                          "roleLabel": null,
                          "currentAge": null,
                          "currentLevel": null,
                          "firstAppearanceEpisodeNo": null,
                          "profile": [],
                          "stats": [
                            {
                              "key": "stats.strength",
                              "value": "42",
                              "valueType": "STRING",
                              "properties": []
                            }
                          ],
                          "skills": [],
                          "items": [],
                          "statuses": []
                        }
                        """,
                """
                        {
                          "name": "변경될 이름",
                          "roleLabel": null,
                          "currentAge": null,
                          "currentLevel": null,
                          "firstAppearanceEpisodeNo": null,
                          "profile": [],
                          "stats": [],
                          "skills": [
                            {
                              "key": "skill.기본 검술",
                              "value": "Lv.3",
                              "valueType": "STRING",
                              "properties": []
                            }
                          ],
                          "items": [],
                          "statuses": []
                        }
                        """
        );

        for (String request : requests) {
            mockMvc.perform(patch(
                                    "/api/v1/works/{workId}/characters/{characterId}",
                                    work.getId(),
                                    character.getId()
                            )
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value("CHARACTER_SETTING_VALUE_TYPE_MISMATCH"));
        }

        assertThat(workCharacterRepository.findById(character.getId()).orElseThrow().getName()).isEqualTo("수아");
        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("valueJson이 없던 scalar Fact를 그대로 저장하면 Fact와 근거를 유지한다")
    void updateCharacterKeepsLegacyScalarFactWithoutValueJson() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        SettingCandidate strengthCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "stats.strength",
                "42",
                SettingValueType.NUMBER,
                null,
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아의 근력은 42였다.")
                                .put("startOffset", 0)
                                .put("endOffset", 13)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact strength = currentFact(
                character,
                strengthCandidate,
                CharacterFactType.STAT,
                "stats.strength",
                "42",
                null
        );
        characterFactRepository.saveAndFlush(strength);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [],
                                  "stats": [
                                    {
                                      "key": "stats.strength",
                                      "value": "42",
                                      "valueType": "NUMBER",
                                      "properties": []
                                    }
                                  ],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats[0].characterFactId").value(strength.getId().toString()))
                .andExpect(jsonPath("$.data.stats[0].hasEvidence").value(true));

        List<CharacterFact> savedFacts =
                characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId());
        assertThat(savedFacts).hasSize(1);
        assertThat(savedFacts.getFirst().getId()).isEqualTo(strength.getId());
        assertThat(savedFacts.getFirst().isCurrent()).isTrue();
        assertThat(savedFacts.getFirst().getSettingCandidate().getId()).isEqualTo(strengthCandidate.getId());
    }

    @Test
    @DisplayName("primitive scalar Fact를 그대로 저장하면 raw 값과 근거를 유지한다")
    void updateCharacterKeepsPrimitiveScalarFact() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        JsonNode valueJson = JsonNodeFactory.instance.textNode("여성");
        SettingCandidate genderCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "profile.gender",
                "여성",
                SettingValueType.STRING,
                valueJson,
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아는 여성이었다.")
                                .put("startOffset", 0)
                                .put("endOffset", 10)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact gender = currentFact(
                character,
                genderCandidate,
                CharacterFactType.PROFILE,
                "profile.gender",
                "여성",
                valueJson
        );
        characterFactRepository.saveAndFlush(gender);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [
                                    {
                                      "key": "profile.gender",
                                      "value": "여성",
                                      "valueType": "STRING",
                                      "properties": []
                                    }
                                  ],
                                  "stats": [],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile[0].characterFactId").value(gender.getId().toString()))
                .andExpect(jsonPath("$.data.profile[0].hasEvidence").value(true));

        List<CharacterFact> savedFacts =
                characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId());
        assertThat(savedFacts).hasSize(1);
        assertThat(savedFacts.getFirst().getId()).isEqualTo(gender.getId());
        assertThat(savedFacts.getFirst().getValueJson()).isEqualTo(valueJson);
        assertThat(savedFacts.getFirst().getSettingCandidate().getId()).isEqualTo(genderCandidate.getId());
    }

    @Test
    @DisplayName("schema가 없는 custom 설정의 raw와 property envelope 타입 변경을 저장한다")
    void updateCharacterChangesSchemaLessCustomValueType() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        CharacterFact score = currentFact(
                character,
                CharacterFactType.STAT,
                "stats.custom_score",
                "42",
                JsonNodeFactory.instance.numberNode(42)
        );
        CharacterFact rank = currentFact(
                character,
                CharacterFactType.STAT,
                "stats.custom_rank",
                "7",
                JsonNodeFactory.instance.objectNode()
                        .put("value", 7)
                        .put("name", "순위")
        );
        characterFactRepository.saveAllAndFlush(List.of(score, rank));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [],
                                  "stats": [
                                    {
                                      "key": "stats.custom_rank",
                                      "value": "7",
                                      "valueType": "STRING",
                                      "properties": [
                                        {"key": "name", "value": "순위", "valueType": "STRING"}
                                      ]
                                    },
                                    {
                                      "key": "stats.custom_score",
                                      "value": "42",
                                      "valueType": "STRING",
                                      "properties": []
                                    }
                                  ],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.length()").value(2))
                .andExpect(jsonPath("$.data.stats[0].valueType").value("STRING"))
                .andExpect(jsonPath("$.data.stats[0].attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.stats[0].displayNameEditable").value(true))
                .andExpect(jsonPath("$.data.stats[1].valueType").value("STRING"));

        CharacterFact historicalScore = characterFactRepository.findById(score.getId()).orElseThrow();
        CharacterFact historicalRank = characterFactRepository.findById(rank.getId()).orElseThrow();
        assertThat(historicalScore.isCurrent()).isFalse();
        assertThat(historicalRank.isCurrent()).isFalse();

        List<CharacterFact> savedFacts =
                characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId());
        assertThat(savedFacts).hasSize(4);
        assertThat(savedFacts)
                .filteredOn(fact -> fact.isCurrent() && fact.getFactKey().equals("stats.custom_score"))
                .singleElement()
                .satisfies(currentScore -> {
                    assertThat(currentScore.getId()).isNotEqualTo(score.getId());
                    assertThat(currentScore.getValueJson())
                            .isEqualTo(JsonNodeFactory.instance.objectNode()
                                    .put("value", "42")
                                    .put("name", "custom score"));
                });
        assertThat(savedFacts)
                .filteredOn(fact -> fact.isCurrent() && fact.getFactKey().equals("stats.custom_rank"))
                .singleElement()
                .satisfies(currentRank -> {
                    assertThat(currentRank.getId()).isNotEqualTo(rank.getId());
                    assertThat(currentRank.getValueJson())
                            .isEqualTo(JsonNodeFactory.instance.objectNode()
                                    .put("value", "7")
                                    .put("name", "순위"));
                });
    }

    @Test
    @DisplayName("공개 속성이 있는 scalar value envelope를 그대로 저장하면 Fact와 근거를 유지한다")
    void updateCharacterKeepsScalarValueEnvelopeWithProperties() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                "profile.active",
                null,
                "활성 여부",
                CharacterFactType.PROFILE,
                SettingValueType.BOOLEAN
        ));
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        ObjectNode valueJson = JsonNodeFactory.instance.objectNode()
                .put("value", true)
                .put("name", "활성 여부");
        SettingCandidate activeCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "profile.active",
                "활성",
                SettingValueType.BOOLEAN,
                valueJson,
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아는 현재 활동 중이다.")
                                .put("startOffset", 0)
                                .put("endOffset", 13)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact active = currentFact(
                character,
                activeCandidate,
                CharacterFactType.PROFILE,
                "profile.active",
                "활성",
                valueJson
        );
        characterFactRepository.saveAndFlush(active);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [
                                    {
                                      "key": "profile.active",
                                      "value": "활성",
                                      "valueType": "BOOLEAN",
                                      "properties": [
                                        {"key": "name", "value": "활성 여부", "valueType": "STRING"}
                                      ]
                                    }
                                  ],
                                  "stats": [],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile[0].characterFactId").value(active.getId().toString()))
                .andExpect(jsonPath("$.data.profile[0].hasEvidence").value(true));

        List<CharacterFact> savedFacts =
                characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId());
        assertThat(savedFacts).hasSize(1);
        assertThat(savedFacts.getFirst().getId()).isEqualTo(active.getId());
        assertThat(savedFacts.getFirst().getValueJson()).isEqualTo(valueJson);
        assertThat(savedFacts.getFirst().getSettingCandidate().getId()).isEqualTo(activeCandidate.getId());
    }

    @Test
    @DisplayName("속성 없는 JSON Fact를 그대로 저장하면 raw JSON과 근거를 유지한다")
    void updateCharacterKeepsPropertylessJsonFact() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        SettingCandidate skillCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "skill.생존 감각",
                "Lv.3",
                SettingValueType.JSON,
                null,
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아는 생존 감각을 익혔다.")
                                .put("startOffset", 0)
                                .put("endOffset", 16)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact skill = currentFact(
                character,
                skillCandidate,
                CharacterFactType.SKILL,
                "skill.생존 감각",
                "Lv.3",
                null
        );
        characterFactRepository.saveAndFlush(skill);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest(
                                "수아 수정",
                                """
                                        [
                                          {
                                            "key": "skill.생존 감각",
                                            "value": "Lv.3",
                                            "valueType": "JSON",
                                            "properties": []
                                          }
                                        ]
                                        """
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수아 수정"))
                .andExpect(jsonPath("$.data.skills[0].characterFactId").value(skill.getId().toString()))
                .andExpect(jsonPath("$.data.skills[0].hasEvidence").value(true));

        List<CharacterFact> savedFacts =
                characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId());
        assertThat(savedFacts).hasSize(1);
        assertThat(savedFacts.getFirst().getId()).isEqualTo(skill.getId());
        assertThat(savedFacts.getFirst().getValueJson()).isNull();
        assertThat(savedFacts.getFirst().getSettingCandidate().getId()).isEqualTo(skillCandidate.getId());
    }

    @Test
    @DisplayName("Registry의 canonical 설정 key를 그대로 저장해도 현재 Fact를 유지한다")
    void updateCharacterAcceptsCanonicalRegistryKeys() throws Exception {
        characterSettingSchemaRepository.saveAll(List.of(
                settingSchema("items.item", "item.*", "아이템", CharacterFactType.ITEM, SettingValueType.JSON),
                settingSchema(
                        "statuses.condition",
                        "status.*",
                        "상태",
                        CharacterFactType.STATUS,
                        SettingValueType.JSON
                )
        ));
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        CharacterFact skill = currentFact(
                character,
                CharacterFactType.SKILL,
                "skills.skill",
                "검술",
                JsonNodeFactory.instance.objectNode().put("name", "검술")
        );
        CharacterFact item = currentFact(
                character,
                CharacterFactType.ITEM,
                "items.item",
                "치유 물약",
                JsonNodeFactory.instance.objectNode().put("name", "치유 물약")
        );
        CharacterFact status = currentFact(
                character,
                CharacterFactType.STATUS,
                "statuses.condition",
                "부상",
                JsonNodeFactory.instance.objectNode().put("name", "부상")
        );
        characterFactRepository.saveAllAndFlush(List.of(skill, item, status));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [],
                                  "stats": [],
                                  "skills": [
                                    {
                                      "key": "skills.skill",
                                      "value": "검술",
                                      "valueType": "JSON",
                                      "properties": [
                                        {"key": "name", "value": "검술", "valueType": "STRING"}
                                      ]
                                    }
                                  ],
                                  "items": [
                                    {
                                      "key": "items.item",
                                      "value": "치유 물약",
                                      "valueType": "JSON",
                                      "properties": [
                                        {"key": "name", "value": "치유 물약", "valueType": "STRING"}
                                      ]
                                    }
                                  ],
                                  "statuses": [
                                    {
                                      "key": "statuses.condition",
                                      "value": "부상",
                                      "valueType": "JSON",
                                      "properties": [
                                        {"key": "name", "value": "부상", "valueType": "STRING"}
                                      ]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills[0].characterFactId").value(skill.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].characterFactId").value(item.getId().toString()))
                .andExpect(jsonPath("$.data.statuses[0].characterFactId").value(status.getId().toString()));

        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .hasSize(3)
                .allMatch(CharacterFact::isCurrent);
    }

    @Test
    @DisplayName("공개 속성과 함께 저장된 JSON value envelope를 그대로 저장하면 Fact와 근거를 유지한다")
    void updateCharacterKeepsJsonValueEnvelopeWithProperties() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        ObjectNode valueJson = JsonNodeFactory.instance.objectNode();
        valueJson.set("value", JsonNodeFactory.instance.objectNode().put("power", 3));
        valueJson.put("name", "검술");
        SettingCandidate skillCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "skill.검술",
                "검술",
                SettingValueType.JSON,
                valueJson,
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아는 검술을 익혔다.")
                                .put("startOffset", 0)
                                .put("endOffset", 12)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact skill = currentFact(
                character,
                skillCandidate,
                CharacterFactType.SKILL,
                "skill.검술",
                "검술",
                valueJson
        );
        characterFactRepository.saveAndFlush(skill);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest(
                                "수아",
                                """
                                        [
                                          {
                                            "key": "skill.검술",
                                            "value": "검술",
                                            "valueType": "JSON",
                                            "properties": [
                                              {"key": "name", "value": "검술", "valueType": "STRING"}
                                            ]
                                          }
                                        ]
                                        """
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills[0].characterFactId").value(skill.getId().toString()))
                .andExpect(jsonPath("$.data.skills[0].hasEvidence").value(true));

        List<CharacterFact> savedFacts =
                characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId());
        assertThat(savedFacts).hasSize(1);
        assertThat(savedFacts.getFirst().getId()).isEqualTo(skill.getId());
        assertThat(savedFacts.getFirst().getValueJson()).isEqualTo(valueJson);
        assertThat(savedFacts.getFirst().getSettingCandidate().getId()).isEqualTo(skillCandidate.getId());
    }

    @Test
    @DisplayName("AGE와 LEVEL의 표시 문자열이 달라도 숫자가 같으면 Fact와 근거를 유지한다")
    void updateCharacterKeepsCoreNumericFactsByNumericValue() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", 23, 15));
        SettingCandidate ageCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "age",
                "23세",
                SettingValueType.NUMBER,
                JsonNodeFactory.instance.objectNode().put("value", 23),
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아는 스물세 살이었다.")
                                .put("startOffset", 0)
                                .put("endOffset", 14)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        SettingCandidate levelCandidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "level",
                "15.0",
                SettingValueType.NUMBER,
                JsonNodeFactory.instance.objectNode().put("value", 15),
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아의 레벨은 15였다.")
                                .put("startOffset", 0)
                                .put("endOffset", 13)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact age = currentFact(
                character,
                ageCandidate,
                CharacterFactType.AGE,
                "age",
                "23세",
                JsonNodeFactory.instance.objectNode().put("value", 23)
        );
        CharacterFact level = currentFact(
                character,
                levelCandidate,
                CharacterFactType.LEVEL,
                "level",
                "15.0",
                JsonNodeFactory.instance.objectNode().put("value", 15)
        );
        characterFactRepository.saveAllAndFlush(List.of(age, level));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": 23,
                                  "currentLevel": 15,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [],
                                  "stats": [],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentAgeFact.characterFactId").value(age.getId().toString()))
                .andExpect(jsonPath("$.data.currentAgeFact.hasEvidence").value(true))
                .andExpect(jsonPath("$.data.currentLevelFact.characterFactId").value(level.getId().toString()))
                .andExpect(jsonPath("$.data.currentLevelFact.hasEvidence").value(true));

        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .hasSize(2)
                .allMatch(CharacterFact::isCurrent);
    }

    @Test
    @DisplayName("schema 없는 object 설정을 JSON으로 응답하고 그대로 저장하면 근거를 유지한다")
    void updateCharacterKeepsSchemaLessObjectFact() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        JsonNode valueJson = JsonNodeFactory.instance.objectNode()
                .put("name", "특수 능력")
                .put("amount", 30);
        SettingCandidate candidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "수아",
                "stats.custom_power",
                "강함",
                SettingValueType.JSON,
                valueJson,
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "수아는 특수 능력을 발휘했다.")
                                .put("startOffset", 0)
                                .put("endOffset", 17)
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
        CharacterFact fact = currentFact(
                character,
                candidate,
                CharacterFactType.STAT,
                "stats.custom_power",
                "강함",
                valueJson
        );
        characterFactRepository.saveAndFlush(fact);

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats[0].valueType").value("JSON"))
                .andExpect(jsonPath("$.data.stats[0].properties.length()").value(2));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [],
                                  "stats": [
                                    {
                                      "key": "stats.custom_power",
                                      "value": "강함",
                                      "valueType": "JSON",
                                      "properties": [
                                        {"key": "name", "value": "특수 능력", "valueType": "STRING"},
                                        {"key": "amount", "value": "30", "valueType": "NUMBER"}
                                      ]
                                    }
                                  ],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats[0].characterFactId").value(fact.getId().toString()))
                .andExpect(jsonPath("$.data.stats[0].hasEvidence").value(true));

        List<CharacterFact> savedFacts =
                characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId());
        assertThat(savedFacts).hasSize(1);
        assertThat(savedFacts.getFirst().getId()).isEqualTo(fact.getId());
        assertThat(savedFacts.getFirst().getValueJson()).isEqualTo(valueJson);
    }

    @Test
    @DisplayName("현재 설정과 세부 속성 목록의 null 항목을 검증 오류로 응답한다")
    void updateCharacterRejectsNullSettingElements() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        List<String> requests = List.of(
                """
                        {
                          "name": "수아",
                          "roleLabel": null,
                          "currentAge": null,
                          "currentLevel": null,
                          "firstAppearanceEpisodeNo": null,
                          "profile": [],
                          "stats": [null],
                          "skills": [],
                          "items": [],
                          "statuses": []
                        }
                        """,
                """
                        {
                          "name": "수아",
                          "roleLabel": null,
                          "currentAge": null,
                          "currentLevel": null,
                          "firstAppearanceEpisodeNo": null,
                          "profile": [],
                          "stats": [
                            {
                              "key": "stats.strength",
                              "value": "42",
                              "valueType": "NUMBER",
                              "properties": [null]
                            }
                          ],
                          "skills": [],
                          "items": [],
                          "statuses": []
                        }
                        """
        );

        for (String request : requests) {
            mockMvc.perform(patch(
                                    "/api/v1/works/{workId}/characters/{characterId}",
                                    work.getId(),
                                    character.getId()
                            )
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("속성 없는 JSON 요청은 기존 value-only object와 primitive raw 값을 보존한다")
    void updateCharacterKeepsPropertylessObjectAndPrimitiveJson() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        JsonNode objectValue = JsonNodeFactory.instance.objectNode().set(
                "value",
                JsonNodeFactory.instance.objectNode()
                        .put("name", "숨은 구조")
                        .put("level", 3)
        );
        JsonNode primitiveValue = JsonNodeFactory.instance.numberNode(7);
        CharacterFact objectFact = currentFact(
                character,
                CharacterFactType.SKILL,
                "skill.객체",
                "객체 표시값",
                objectValue
        );
        CharacterFact primitiveFact = currentFact(
                character,
                CharacterFactType.SKILL,
                "skill.원시값",
                "일곱",
                primitiveValue
        );
        characterFactRepository.saveAllAndFlush(List.of(objectFact, primitiveFact));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest(
                                "수아",
                                """
                                        [
                                          {
                                            "key": "skill.객체",
                                            "value": "객체 표시값",
                                            "valueType": "JSON",
                                            "properties": []
                                          },
                                          {
                                            "key": "skill.원시값",
                                            "value": "일곱",
                                            "valueType": "JSON",
                                            "properties": []
                                          }
                                        ]
                                        """
                        )))
                .andExpect(status().isOk());

        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .hasSize(2);
        assertThat(characterFactRepository.findById(objectFact.getId()).orElseThrow().getValueJson())
                .isEqualTo(objectValue);
        assertThat(characterFactRepository.findById(primitiveFact.getId()).orElseThrow().getValueJson())
                .isEqualTo(primitiveValue);
    }

    @Test
    @DisplayName("표시값이 같은 JSON 설정은 누락된 숨은 속성과 기존 Fact를 보존한다")
    void updateCharacterKeepsVisibleJsonPropertiesWhenRequestOmitsThem() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        JsonNode visibleObject = JsonNodeFactory.instance.objectNode()
                .put("name", "생존 감각")
                .put("level", 3);
        CharacterFact skill = currentFact(
                character,
                CharacterFactType.SKILL,
                "skill.생존 감각",
                "Lv.3",
                visibleObject
        );
        characterFactRepository.saveAndFlush(skill);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest(
                                "변경될 이름",
                                """
                                        [
                                          {
                                            "key": "skill.생존 감각",
                                            "value": "Lv.3",
                                            "valueType": "JSON",
                                            "properties": []
                                          }
                                        ]
                                        """
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("변경될 이름"))
                .andExpect(jsonPath("$.data.skills[0].characterFactId").value(skill.getId().toString()));

        assertThat(workCharacterRepository.findById(character.getId()).orElseThrow().getName())
                .isEqualTo("변경될 이름");
        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .singleElement()
                .satisfies(savedFact -> {
                    assertThat(savedFact.getId()).isEqualTo(skill.getId());
                    assertThat(savedFact.isCurrent()).isTrue();
                    assertThat(savedFact.getValueJson()).isEqualTo(visibleObject);
                });
    }

    @Test
    @DisplayName("사용하지 않는 숨은 속성도 잘못된 JSON 타입 값이면 부수효과 없이 거절한다")
    void updateCharacterRejectsMalformedHiddenJsonPropertyWithoutSideEffects() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));

        String invalidSkills = """
                [
                  {
                    "key": "skill.복수_JSON",
                    "value": "표시값",
                    "valueType": "JSON",
                    "properties": [
                      {
                        "key": "payload",
                        "value": "{} {}",
                        "valueType": "JSON"
                      }
                    ]
                  }
                ]
                """;

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest("변경될 이름", invalidSkills)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHARACTER_SETTING_VALUE_INVALID"));

        assertThat(workCharacterRepository.findById(character.getId()).orElseThrow().getName()).isEqualTo("수아");
        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId())).isEmpty();
    }

    @Test
    @DisplayName("JSON 설정 표시값 변경은 숨은 속성을 복사하지 않은 새 수동 Fact로 저장한다")
    void updateCharacterReplacesChangedOpaqueJsonValue() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        CharacterFact skill = currentFact(
                character,
                CharacterFactType.SKILL,
                "skill.생존 감각",
                "Lv.3",
                null
        );
        characterFactRepository.saveAndFlush(skill);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(characterUpdateRequest(
                                "변경될 이름",
                                """
                                        [
                                          {
                                            "key": "skill.생존 감각",
                                            "value": "Lv.4",
                                            "valueType": "JSON",
                                            "properties": []
                                          }
                                        ]
                                        """
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("변경될 이름"))
                .andExpect(jsonPath("$.data.skills[0].value").value("Lv.4"))
                .andExpect(jsonPath("$.data.skills[0].properties[0].value").value("생존 감각"));

        assertThat(characterFactRepository.findById(skill.getId()).orElseThrow().isCurrent()).isFalse();
        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .filteredOn(CharacterFact::isCurrent)
                .singleElement()
                .satisfies(savedFact -> {
                    assertThat(savedFact.getId()).isNotEqualTo(skill.getId());
                    assertThat(savedFact.getValueJson())
                            .isEqualTo(JsonNodeFactory.instance.objectNode().put("name", "생존 감각"));
                    assertThat(savedFact.getSettingCandidate()).isNull();
                });
    }

    @Test
    @DisplayName("표시값과 key가 같으면 요청의 숨은 JSON 속성 변경을 신뢰하지 않는다")
    void updateCharacterIgnoresUntrustedStructuredJsonChange() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", null, null));
        CharacterFact oldSkill = currentFact(
                character,
                CharacterFactType.SKILL,
                "skill.기본 검술",
                "Lv.3",
                JsonNodeFactory.instance.objectNode()
                        .put("name", "기본 검술")
                        .put("level", 3)
        );
        characterFactRepository.saveAndFlush(oldSkill);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": null,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [],
                                  "stats": [],
                                  "skills": [
                                    {
                                      "key": "skill.기본 검술",
                                      "value": "Lv.3",
                                      "valueType": "JSON",
                                      "properties": [
                                        {"key": "name", "value": "기본 검술", "valueType": "STRING"},
                                        {"key": "level", "value": "4", "valueType": "NUMBER"}
                                      ]
                                    }
                                  ],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills[0].value").value("Lv.3"))
                .andExpect(jsonPath("$.data.skills[0].characterFactId").value(oldSkill.getId().toString()))
                .andExpect(jsonPath("$.data.skills[0].properties[1].value").value("3"));

        List<CharacterFact> savedFacts =
                characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId());
        assertThat(savedFacts).singleElement().satisfies(fact -> {
            assertThat(fact.getId()).isEqualTo(oldSkill.getId());
            assertThat(fact.isCurrent()).isTrue();
            assertThat(fact.getValueJson().get("level").asInt()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("동일한 현재 설정 재저장은 Fact를 중복 생성하지 않고 TIME Fact를 유지한다")
    void updateCharacterKeepsUnchangedAndTimeFacts() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", 23, null));
        CharacterFact age = currentFact(
                character,
                CharacterFactType.AGE,
                "age",
                "23",
                JsonNodeFactory.instance.objectNode().put("value", 23)
        );
        CharacterFact strength = currentFact(
                character,
                CharacterFactType.STAT,
                "stats.strength",
                "42",
                JsonNodeFactory.instance.objectNode().put("value", 42)
        );
        CharacterFact time = currentFact(
                character,
                CharacterFactType.TIME,
                "time.first_battle",
                "7화",
                JsonNodeFactory.instance.objectNode().put("episode", 7)
        );
        characterFactRepository.saveAllAndFlush(List.of(age, strength, time));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수아",
                                  "roleLabel": "주인공",
                                  "currentAge": 23,
                                  "currentLevel": null,
                                  "firstAppearanceEpisodeNo": null,
                                  "profile": [],
                                  "stats": [
                                    {
                                      "key": "stats.strength",
                                      "value": "42",
                                      "valueType": "NUMBER",
                                      "properties": []
                                    }
                                  ],
                                  "skills": [],
                                  "items": [],
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentAge").value(23))
                .andExpect(jsonPath("$.data.stats[0].characterFactId").value(strength.getId().toString()));

        assertThat(characterFactRepository.findAllByWorkCharacterIdOrderByCreatedAtDesc(character.getId()))
                .hasSize(3);
        assertThat(characterFactRepository.findById(time.getId()).orElseThrow().isCurrent()).isTrue();
        WorkCharacter savedCharacter = workCharacterRepository.findById(character.getId()).orElseThrow();
        assertThat(savedCharacter.getStatusesJson().get("time.first_battle").get("episode").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("삭제 버튼 API는 캐릭터를 보관하고 Fact를 유지하며 기본 조회에서 제외한다")
    void deleteCharacterArchivesWithoutDeletingFacts() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", 23, 15));
        CharacterFact fact = currentFact(
                character,
                CharacterFactType.STAT,
                "stats.strength",
                "42",
                JsonNodeFactory.instance.objectNode().put("value", 42)
        );
        characterFactRepository.saveAndFlush(fact);

        mockMvc.perform(delete(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("캐릭터가 삭제되었습니다."))
                .andExpect(jsonPath("$.data.id").value(character.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

        WorkCharacter archived = workCharacterRepository.findById(character.getId()).orElseThrow();
        assertThat(archived.getStatus()).isEqualTo(CharacterStatus.ARCHIVED);
        assertThat(characterFactRepository.existsById(fact.getId())).isTrue();

        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHARACTER_NOT_FOUND"));
    }

    @Test
    @DisplayName("보관함은 보관된 캐릭터만 페이지로 조회한다")
    void getArchivedCharactersReturnsOnlyArchivedCharacters() throws Exception {
        workCharacterRepository.saveAndFlush(character(work, "활성 인물", 23, 15));
        WorkCharacter archivedCharacter = workCharacterRepository.save(character(work, "보관 인물", 30, 8));
        archivedCharacter.updateFirstAppearanceEpisodeId(firstEpisode.getId());
        archivedCharacter.archive();
        workCharacterRepository.saveAndFlush(archivedCharacter);

        mockMvc.perform(get("/api/v1/works/{workId}/characters/archived", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(archivedCharacter.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].name").value("보관 인물"))
                .andExpect(jsonPath("$.data.content[0].firstAppearanceEpisodeNo").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(9))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("보관 캐릭터를 설정 이력과 함께 활성 상태로 복구한다")
    void restoreCharacterActivatesCharacterAndKeepsFacts() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", 23, 15));
        CharacterFact fact = currentFact(
                character,
                CharacterFactType.STAT,
                "stats.strength",
                "42",
                JsonNodeFactory.instance.objectNode().put("value", 42)
        );
        characterFactRepository.saveAndFlush(fact);
        character.archive();
        workCharacterRepository.saveAndFlush(character);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}/restore",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("캐릭터가 복구되었습니다."))
                .andExpect(jsonPath("$.data.id").value(character.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        WorkCharacter restored = workCharacterRepository.findById(character.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(CharacterStatus.ACTIVE);
        assertThat(characterFactRepository.existsById(fact.getId())).isTrue();
    }

    @Test
    @DisplayName("같은 작품에서 이름이 이미 사용 중이면 보관 캐릭터 복구를 거절한다")
    void restoreCharacterRejectsDuplicatedName() throws Exception {
        workCharacterRepository.saveAndFlush(character(work, "수아", 23, 15));
        WorkCharacter archivedCharacter = workCharacterRepository.save(character(work, "수아", 30, 8));
        archivedCharacter.archive();
        workCharacterRepository.saveAndFlush(archivedCharacter);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}/restore",
                                work.getId(),
                                archivedCharacter.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHARACTER_NAME_DUPLICATED"));

        assertThat(workCharacterRepository.findById(archivedCharacter.getId()).orElseThrow().getStatus())
                .isEqualTo(CharacterStatus.ARCHIVED);
    }

    @Test
    @DisplayName("같은 이름의 다른 보관 캐릭터가 있어도 복구할 수 있다")
    void restoreCharacterAllowsNameUsedOnlyByAnotherArchivedCharacter() throws Exception {
        WorkCharacter restoreTarget = workCharacterRepository.save(character(work, "수아", 23, 15));
        restoreTarget.archive();
        WorkCharacter otherArchivedCharacter = workCharacterRepository.save(character(work, "수아", 30, 8));
        otherArchivedCharacter.archive();
        workCharacterRepository.saveAllAndFlush(List.of(restoreTarget, otherArchivedCharacter));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}/restore",
                                work.getId(),
                                restoreTarget.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        assertThat(workCharacterRepository.findById(restoreTarget.getId()).orElseThrow().getStatus())
                .isEqualTo(CharacterStatus.ACTIVE);
        assertThat(workCharacterRepository.findById(otherArchivedCharacter.getId()).orElseThrow().getStatus())
                .isEqualTo(CharacterStatus.ARCHIVED);
    }

    @Test
    @DisplayName("활성 캐릭터는 복구 대상으로 처리하지 않는다")
    void restoreCharacterRejectsActiveCharacter() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", 23, 15));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/characters/{characterId}/restore",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHARACTER_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 회원 작품의 캐릭터 조회를 거절한다")
    void getCharactersRejectsOtherMemberWork() throws Exception {
        WorkCharacter otherCharacter = workCharacterRepository.save(character(otherWork, "다른 인물", 40, 20));

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}",
                                otherWork.getId(),
                                otherCharacter.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    @Test
    @DisplayName("캐릭터 목록 조회는 인증을 요구한다")
    void getCharactersRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("OpenAPI 문서에 캐릭터 조회와 수정, 보관, 복구 경로를 노출한다")
    void openApiContainsCharacterPaths() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters'].get").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/characters'].get.parameters"
                                + "[?(@.name == 'page' && @.in == 'query')]"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/characters'].get.parameters"
                                + "[?(@.name == 'size' && @.in == 'query')]"
                ).exists())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters/archived'].get").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/characters/archived'].get.parameters"
                                + "[?(@.name == 'page' && @.in == 'query')]"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/characters/archived'].get.parameters"
                                + "[?(@.name == 'size' && @.in == 'query')]"
                ).exists())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters/{characterId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters/{characterId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters/{characterId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters/{characterId}/restore'].patch")
                        .exists());
    }

    private WorkCharacter character(Work ownerWork, String name, Integer age, Integer level) {
        return WorkCharacter.create(
                ownerWork,
                name,
                "주인공",
                age,
                level,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private CharacterFact currentFact(
            WorkCharacter character,
            CharacterFactType factType,
            String factKey,
            String factValue,
            JsonNode valueJson
    ) {
        CharacterFact fact = CharacterFact.createManual(character, factType, factKey, factValue, valueJson);
        fact.markCurrent();
        return fact;
    }

    private CharacterFact currentFact(
            WorkCharacter character,
            SettingCandidate settingCandidate,
            CharacterFactType factType,
            String factKey,
            String factValue,
            JsonNode valueJson
    ) {
        CharacterFact fact = CharacterFact.create(
                character,
                settingCandidate,
                factType,
                factKey,
                factValue,
                factValue,
                valueJson,
                firstEpisode,
                settingCandidate.getSourceChunkId(),
                null,
                settingCandidate.getConfidence(),
                firstEpisode.getEpisodeNo()
        );
        fact.markCurrent();
        return fact;
    }

    private CharacterSettingSchema settingSchema(
            String schemaKey,
            String attributePattern,
            String displayName,
            CharacterFactType factType,
            SettingValueType valueType
    ) {
        return CharacterSettingSchema.create(
                null,
                schemaKey,
                attributePattern,
                displayName,
                factType,
                valueType,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                JsonNodeFactory.instance.arrayNode(),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }

    private String characterUpdateRequest(String name, String skills) {
        return """
                {
                  "name": "%s",
                  "roleLabel": "주인공",
                  "currentAge": null,
                  "currentLevel": null,
                  "firstAppearanceEpisodeNo": null,
                  "profile": [],
                  "stats": [],
                  "skills": %s,
                  "items": [],
                  "statuses": []
                }
                """.formatted(name, skills);
    }

    private String characterUpdateRequest(String name, Integer firstAppearanceEpisodeNo) {
        return """
                {
                  "name": "%s",
                  "roleLabel": "주인공",
                  "currentAge": null,
                  "currentLevel": null,
                  "firstAppearanceEpisodeNo": %d,
                  "profile": [],
                  "stats": [],
                  "skills": [],
                  "items": [],
                  "statuses": []
                }
                """.formatted(name, firstAppearanceEpisodeNo);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
