package org.monitoring.catchholebackend.domain.character.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
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
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(activeCharacter.getId().toString()))
                .andExpect(jsonPath("$.data[0].name").value("수아"))
                .andExpect(jsonPath("$.data[0].currentAge").value(23))
                .andExpect(jsonPath("$.data[0].representativeAttributeLabel").value("레벨"))
                .andExpect(jsonPath("$.data[0].representativeAttributeValue").value("15"))
                .andExpect(jsonPath("$.data[0].firstAppearanceEpisodeNo").value(1));
    }

    @Test
    @DisplayName("첫 등장 회차가 없는 캐릭터도 목록에서 조회한다")
    void getCharactersReturnsCharacterWithoutFirstAppearanceEpisode() throws Exception {
        WorkCharacter character = workCharacterRepository.saveAndFlush(character(work, "수아", 23, 15));

        mockMvc.perform(get("/api/v1/works/{workId}/characters", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(character.getId().toString()))
                .andExpect(jsonPath("$.data[0].name").value("수아"))
                .andExpect(jsonPath("$.data[0].firstAppearanceEpisodeNo").doesNotExist());
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
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].firstAppearanceEpisodeNo").doesNotExist());
    }

    @Test
    @DisplayName("캐릭터 상세에서 현재 설정을 사용자용 항목 목록으로 응답한다")
    void getCharacterReturnsCurrentSettingLists() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "수아", 23, 15));
        character.updateFirstAppearanceEpisodeId(firstEpisode.getId());
        workCharacterRepository.saveAndFlush(character);
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
        characterFactRepository.saveAllAndFlush(List.of(gender, strength));

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
                .andExpect(jsonPath("$.data.profile[0].characterFactId").value(gender.getId().toString()))
                .andExpect(jsonPath("$.data.profile[0].key").value("profile.gender"))
                .andExpect(jsonPath("$.data.profile[0].displayName").value("성별"))
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
                                    }
                                  ],
                                  "stats": [
                                    {
                                      "key": "stats.strength",
                                      "value": "42",
                                      "valueType": "NUMBER",
                                      "properties": []
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
                                  "statuses": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("캐릭터가 수정되었습니다."))
                .andExpect(jsonPath("$.data.name").value("수아 리안"))
                .andExpect(jsonPath("$.data.roleLabel").value("주인공"))
                .andExpect(jsonPath("$.data.currentAge").value(23))
                .andExpect(jsonPath("$.data.currentLevel").value(15))
                .andExpect(jsonPath("$.data.profile[0].hasEvidence").value(false))
                .andExpect(jsonPath("$.data.stats[0].value").value("42"))
                .andExpect(jsonPath("$.data.skills[0].displayName").value("기본 검술"))
                .andExpect(jsonPath("$.data.skills[0].properties[1].displayName").value("레벨"))
                .andExpect(jsonPath("$.data.skills[0].properties[1].valueType").value("NUMBER"));

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
        assertThat(savedCharacter.getStatsJson().get("stats.strength").get("value").asInt()).isEqualTo(42);
        assertThat(savedCharacter.getSkillsJson().get("skill.기본 검술").get("level").asInt()).isEqualTo(3);
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
                .andExpect(jsonPath("$.data.length()").value(0));

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
    @DisplayName("OpenAPI 문서에 캐릭터 조회와 수정, 삭제 경로를 노출한다")
    void openApiContainsCharacterPaths() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters/{characterId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters/{characterId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/characters/{characterId}'].delete").exists());
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
            ObjectNode valueJson
    ) {
        CharacterFact fact = CharacterFact.createManual(character, factType, factKey, factValue, valueJson);
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

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
