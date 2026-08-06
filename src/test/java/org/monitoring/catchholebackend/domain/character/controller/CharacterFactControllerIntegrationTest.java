package org.monitoring.catchholebackend.domain.character.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("CharacterFact 설정 검색·상세 API 통합 테스트")
class CharacterFactControllerIntegrationTest {

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
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Work work;
    private Work otherWork;
    private Episode firstEpisode;
    private Episode secondEpisode;
    private String accessToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        Member member = memberRepository.save(Member.register(
                "fact-writer@example.com",
                "encoded-password",
                "01011112222",
                "설정 작가"
        ));
        Member otherMember = memberRepository.save(Member.register(
                "fact-other@example.com",
                "encoded-password",
                "01033334444",
                "다른 작가"
        ));
        work = workRepository.save(Work.create(member, "검색 작품", WorkGenre.FANTASY, "검색 테스트"));
        otherWork = workRepository.save(Work.create(otherMember, "다른 작품", WorkGenre.MYSTERY, "다른 테스트"));
        firstEpisode = episodeRepository.save(episode(work, 1));
        secondEpisode = episodeRepository.save(episode(work, 2));
        accessToken = jwtTokenProvider.generateAccessToken(member);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    @DisplayName("검색어를 trim하고 대소문자 없이 검색하며 검색 제외 유형과 보관 캐릭터를 반환하지 않는다")
    void searchNormalizesQueryAndExcludesUnsupportedFacts() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        CharacterFact currentItem = saveFact(
                character,
                null,
                CharacterFactType.ITEM,
                "item.flame_potion",
                "Flame Potion",
                secondEpisode,
                2,
                true
        );
        CharacterFact historicalSkill = saveFact(
                character,
                null,
                CharacterFactType.SKILL,
                "skill.FLAME_slash",
                "베기",
                firstEpisode,
                1,
                false
        );
        saveFact(character, null, CharacterFactType.PROFILE, "profile.title", "Flame", firstEpisode, 1, true);
        saveFact(character, null, CharacterFactType.TIME, "time.flame", "Flame", firstEpisode, 1, true);

        WorkCharacter archived = workCharacterRepository.save(character(work, "보관 인물"));
        saveFact(archived, null, CharacterFactType.ITEM, "item.flame", "Flame", secondEpisode, 2, true);
        archived.archive();
        workCharacterRepository.saveAndFlush(archived);

        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("q", "  flame  ")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].characterFactId").value(currentItem.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].factType").value("ITEM"))
                .andExpect(jsonPath("$.data.content[0].factTypeLabel").value("아이템"))
                .andExpect(jsonPath("$.data.content[0].displayName").value("flame potion"))
                .andExpect(jsonPath("$.data.content[0].factValue").value("Flame Potion"))
                .andExpect(jsonPath("$.data.content[0].isCurrent").value(true))
                .andExpect(jsonPath("$.data.content[0].characterId").value(character.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].characterName").value("아리아"))
                .andExpect(jsonPath("$.data.content[0].sourceEpisodeId").value(secondEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].sourceEpisodeNo").value(2))
                .andExpect(jsonPath("$.data.content[0].effectiveFromEpisodeNo").value(2))
                .andExpect(jsonPath("$.data.content[1].characterFactId").value(historicalSkill.getId().toString()))
                .andExpect(jsonPath("$.data.content[1].isCurrent").value(false))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.content[0].factKey").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].valueJson").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].evidenceQuotes").doesNotExist());
    }

    @Test
    @DisplayName("LIKE 와일드카드 문자와 escape 문자를 literal 검색한다")
    void searchTreatsLikeWildcardsAsLiterals() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        CharacterFact percent = saveFact(
                character, null, CharacterFactType.STAT, "stats.percent", "체력 100%", firstEpisode, 1, true
        );
        saveFact(character, null, CharacterFactType.STAT, "stats.percentText", "체력 100점", firstEpisode, 1, true);
        CharacterFact underscore = saveFact(
                character, null, CharacterFactType.STAT, "stats.a_b", "밑줄", firstEpisode, 1, true
        );
        saveFact(character, null, CharacterFactType.STAT, "stats.axb", "다른 키", firstEpisode, 1, true);
        CharacterFact backslash = saveFact(
                character, null, CharacterFactType.ITEM, "item.path\\name", "역슬래시", firstEpisode, 1, true
        );
        saveFact(character, null, CharacterFactType.ITEM, "item.pathXname", "다른 경로", firstEpisode, 1, true);

        assertSingleSearchResult("%", percent);
        assertSingleSearchResult("_", underscore);
        assertSingleSearchResult("\\", backslash);
    }

    @Test
    @DisplayName("스키마 표시명과 공백이 포함된 동적 설정명, 기존 설정값을 검색한다")
    void searchMatchesDisplayNamesAndFactValues() throws Exception {
        characterSettingSchemaRepository.save(schema(work, "stats.combat_power", "전투지수"));
        characterSettingSchemaRepository.save(schema(work, " stats.supernatural ", "이능"));
        characterSettingSchemaRepository.save(schema(otherWork, "stats.mental", "전투지수"));

        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        CharacterFact combatPower = saveFact(
                character, null, CharacterFactType.STAT, "stats.combat_power", "68", firstEpisode, 1, true
        );
        CharacterFact supernatural = saveFact(
                character, null, CharacterFactType.STAT, "stats.supernatural", "1", firstEpisode, 1, true
        );
        CharacterFact injury = saveFact(
                character, null, CharacterFactType.STATUS, "status.오른발  부상", "오른발 파괴", firstEpisode, 1, true
        );
        CharacterFact mental = saveFact(
                character, null, CharacterFactType.STAT, "stats.mental", "36", firstEpisode, 1, true
        );

        assertSingleSearchResult("전투 지수", combatPower);
        assertSingleSearchResult("이능", supernatural);
        assertSingleSearchResult("오른발 부상", injury);
        assertSingleSearchResult("36", mental);
        assertSearchResultCount("존재하지 않는 설정", 0);
    }

    @Test
    @DisplayName("빈 검색어와 유형·현재 과거 필터, 정렬, 페이지네이션을 적용한다")
    void searchAppliesFiltersOrderingAndPagination() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        CharacterFact currentSecond = saveFact(
                character, null, CharacterFactType.STAT, "stats.second", "두 번째", secondEpisode, 2, true
        );
        CharacterFact currentFirst = saveFact(
                character, null, CharacterFactType.STAT, "stats.first", "첫 번째", firstEpisode, 1, true
        );
        CharacterFact currentWithoutEpisode = saveFact(
                character, null, CharacterFactType.STAT, "stats.unknown", "회차 없음", null, null, true
        );
        CharacterFact historical = saveFact(
                character, null, CharacterFactType.STAT, "stats.old", "과거", secondEpisode, 2, false
        );
        saveFact(character, null, CharacterFactType.ITEM, "item.other", "다른 유형", secondEpisode, 2, true);

        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("q", "   ")
                        .queryParam("factType", "STAT")
                        .queryParam("scope", "CURRENT")
                        .queryParam("page", "0")
                        .queryParam("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].characterFactId").value(currentSecond.getId().toString()))
                .andExpect(jsonPath("$.data.content[1].characterFactId").value(currentFirst.getId().toString()))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("factType", "STAT")
                        .queryParam("scope", "CURRENT")
                        .queryParam("page", "1")
                        .queryParam("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].characterFactId")
                        .value(currentWithoutEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("factType", "STAT")
                        .queryParam("scope", "HISTORICAL")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].characterFactId").value(historical.getId().toString()));
    }

    @Test
    @DisplayName("상세는 후보 근거 인용문 순서를 유지하고 Fact 출처가 없으면 후보 회차를 사용한다")
    void detailReturnsEvidenceAndCandidateEpisodeFallback() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        SettingCandidate candidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                secondEpisode,
                UUID.randomUUID(),
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "item.체력_물약",
                "체력 물약",
                SettingValueType.JSON,
                JsonNodeFactory.instance.objectNode().put("name", "체력 물약"),
                JsonNodeFactory.instance.arrayNode()
                        .add(JsonNodeFactory.instance.objectNode()
                                .put("quote", "아리아는 물약을 꺼냈다.")
                                .put("startOffset", 12)
                                .put("endOffset", 25))
                        .add(JsonNodeFactory.instance.objectNode()
                                .put("quote", "체력이 회복되었다.")
                                .put("startOffset", 30)
                                .put("endOffset", 40)),
                new BigDecimal("0.9100"),
                JsonNodeFactory.instance.objectNode().put("raw", "숨김")
        ));
        CharacterFact fact = saveFact(
                character,
                candidate,
                CharacterFactType.ITEM,
                "item.체력_물약",
                "체력 물약",
                null,
                2,
                true
        );

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/character-facts/{characterFactId}",
                                work.getId(),
                                fact.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.characterFactId").value(fact.getId().toString()))
                .andExpect(jsonPath("$.data.factType").value("ITEM"))
                .andExpect(jsonPath("$.data.factTypeLabel").value("아이템"))
                .andExpect(jsonPath("$.data.factKey").value("item.체력_물약"))
                .andExpect(jsonPath("$.data.displayName").value("체력 물약"))
                .andExpect(jsonPath("$.data.factValue").value("체력 물약"))
                .andExpect(jsonPath("$.data.isCurrent").value(true))
                .andExpect(jsonPath("$.data.effectiveFromEpisodeNo").value(2))
                .andExpect(jsonPath("$.data.characterId").value(character.getId().toString()))
                .andExpect(jsonPath("$.data.characterName").value("아리아"))
                .andExpect(jsonPath("$.data.sourceCandidateId").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.sourceEpisodeId").value(secondEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.sourceEpisodeNo").value(2))
                .andExpect(jsonPath("$.data.evidenceQuotes[0]").value("아리아는 물약을 꺼냈다."))
                .andExpect(jsonPath("$.data.evidenceQuotes[1]").value("체력이 회복되었다."))
                .andExpect(jsonPath("$.data.valueJson").doesNotExist())
                .andExpect(jsonPath("$.data.normalizedValue").doesNotExist())
                .andExpect(jsonPath("$.data.rawAiResultJson").doesNotExist())
                .andExpect(jsonPath("$.data.sourceChunkId").doesNotExist())
                .andExpect(jsonPath("$.data.evidenceSpans").doesNotExist());
    }

    @Test
    @DisplayName("상세 출처는 후보 회차보다 CharacterFact 출처 회차를 우선한다")
    void detailPrefersFactSourceEpisode() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        SettingCandidate candidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                secondEpisode,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "status.부상",
                "부상",
                SettingValueType.STRING,
                JsonNodeFactory.instance.objectNode().put("value", "부상"),
                JsonNodeFactory.instance.arrayNode(),
                null,
                null
        ));
        CharacterFact fact = saveFact(
                character,
                candidate,
                CharacterFactType.STATUS,
                "status.부상",
                "부상",
                firstEpisode,
                1,
                false
        );

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/character-facts/{characterFactId}",
                                work.getId(),
                                fact.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceEpisodeId").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.sourceEpisodeNo").value(1))
                .andExpect(jsonPath("$.data.evidenceQuotes.length()").value(0));
    }

    @Test
    @DisplayName("근거가 없는 수동 Fact 상세는 nullable 출처와 빈 인용문 목록을 반환한다")
    void detailReturnsEmptyEvidenceForManualFact() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        CharacterFact fact = saveFact(
                character,
                null,
                CharacterFactType.LEVEL,
                "level",
                null,
                null,
                null,
                true
        );

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/character-facts/{characterFactId}",
                                work.getId(),
                                fact.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.factValue").doesNotExist())
                .andExpect(jsonPath("$.data.sourceCandidateId").doesNotExist())
                .andExpect(jsonPath("$.data.sourceEpisodeId").doesNotExist())
                .andExpect(jsonPath("$.data.sourceEpisodeNo").doesNotExist())
                .andExpect(jsonPath("$.data.evidenceQuotes.length()").value(0));
    }

    @Test
    @DisplayName("다른 작품 Fact, 존재하지 않는 Fact, 보관 캐릭터 Fact 상세를 찾을 수 없음으로 처리한다")
    void detailRejectsUnavailableFacts() throws Exception {
        WorkCharacter archived = workCharacterRepository.save(character(work, "보관 인물"));
        CharacterFact archivedFact = saveFact(
                archived, null, CharacterFactType.LEVEL, "level", "10", firstEpisode, 1, true
        );
        archived.archive();
        workCharacterRepository.saveAndFlush(archived);

        Episode otherEpisode = episodeRepository.save(episode(otherWork, 1));
        WorkCharacter otherCharacter = workCharacterRepository.save(character(otherWork, "다른 인물"));
        CharacterFact otherFact = saveFact(
                otherCharacter, null, CharacterFactType.LEVEL, "level", "99", otherEpisode, 1, true
        );

        assertFactNotFound(archivedFact.getId());
        assertFactNotFound(otherFact.getId());
        assertFactNotFound(UUID.randomUUID());
    }

    @Test
    @DisplayName("검색은 작품 소유권·인증과 허용된 필터·페이지 범위를 검증한다")
    void searchValidatesAccessAndParameters() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", otherWork.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("factType", "PROFILE")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID_ARGUMENT"));

        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("scope", "UNKNOWN")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID_ARGUMENT"));

        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("page", "-1")
                        .queryParam("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.length()").value(2));
    }

    @Test
    @DisplayName("타임라인 요약은 PROFILE을 포함하고 TIME을 제외해 유형·회차·수동 Fact를 집계한다")
    void timelineSummaryCountsSupportedFacts() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        saveFact(character, null, CharacterFactType.PROFILE, "profile.race", "인간", firstEpisode, 1, true);
        saveFact(character, null, CharacterFactType.STATUS, "status.injury", "부상", secondEpisode, 2, false);
        saveFact(character, null, CharacterFactType.ITEM, "item.potion", "회복 물약", null, null, true);
        saveFact(character, null, CharacterFactType.TIME, "time.flashback", "과거", firstEpisode, 1, true);

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}/timeline/summary",
                                work.getId(),
                                character.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalFactCount").value(3))
                .andExpect(jsonPath("$.data.totalEpisodeCount").value(2))
                .andExpect(jsonPath("$.data.filteredFactCount").value(3))
                .andExpect(jsonPath("$.data.manualFactCount").value(1))
                .andExpect(jsonPath("$.data.factTypeCounts.length()").value(7))
                .andExpect(jsonPath("$.data.factTypeCounts[0].factType").value("PROFILE"))
                .andExpect(jsonPath("$.data.factTypeCounts[0].count").value(1))
                .andExpect(jsonPath("$.data.episodes[0].episodeNo").value(1))
                .andExpect(jsonPath("$.data.episodes[1].episodeNo").value(2));

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}/timeline/summary",
                                work.getId(),
                                character.getId()
                        )
                        .queryParam("factType", "STATUS")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedFactType").value("STATUS"))
                .andExpect(jsonPath("$.data.filteredFactCount").value(1))
                .andExpect(jsonPath("$.data.episodes.length()").value(1))
                .andExpect(jsonPath("$.data.episodes[0].episodeNo").value(2));
    }

    @Test
    @DisplayName("타임라인은 회차와 첫 근거 offset 순으로 조회하고 cursor 다음 묶음에 수동 Fact를 반환한다")
    void timelineOrdersFactsAndContinuesWithCursor() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        SettingCandidate laterCandidate = saveCandidate(secondEpisode, "나중 문장", 50);
        SettingCandidate earlierCandidate = saveCandidate(secondEpisode, "앞 문장", 10);
        CharacterFact later = saveFact(
                character, laterCandidate, CharacterFactType.STATUS, "status.later", "나중", secondEpisode, 2, true
        );
        CharacterFact earlier = saveFact(
                character, earlierCandidate, CharacterFactType.STATUS, "status.earlier", "앞", secondEpisode, 2, false
        );
        CharacterFact manual = saveFact(
                character, null, CharacterFactType.STATUS, "status.manual", "수동", null, null, true
        );

        String firstResponse = mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}/timeline",
                                work.getId(),
                                character.getId()
                        )
                        .queryParam("factType", "STATUS")
                        .queryParam("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].characterFactId").value(earlier.getId().toString()))
                .andExpect(jsonPath("$.data.content[1].characterFactId").value(later.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].sourceType").value("EPISODE"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = objectMapper.readTree(firstResponse).path("data").path("nextCursor").asText();

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}/timeline",
                                work.getId(),
                                character.getId()
                        )
                        .queryParam("factType", "STATUS")
                        .queryParam("cursor", cursor)
                        .queryParam("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].characterFactId").value(manual.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("타임라인은 다른 필터 cursor와 cursor·시작 회차 동시 전달을 거절한다")
    void timelineRejectsInvalidCursorContext() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        saveFact(character, null, CharacterFactType.STATUS, "status.one", "하나", firstEpisode, 1, true);
        saveFact(character, null, CharacterFactType.STATUS, "status.two", "둘", secondEpisode, 2, true);

        String response = mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}/timeline",
                                work.getId(),
                                character.getId()
                        )
                        .queryParam("factType", "STATUS")
                        .queryParam("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = objectMapper.readTree(response).path("data").path("nextCursor").asText();

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}/timeline",
                                work.getId(),
                                character.getId()
                        )
                        .queryParam("factType", "ITEM")
                        .queryParam("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHARACTER_TIMELINE_CURSOR_INVALID"));

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/characters/{characterId}/timeline",
                                work.getId(),
                                character.getId()
                        )
                        .queryParam("factType", "STATUS")
                        .queryParam("cursor", cursor)
                        .queryParam("fromEpisodeNo", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHARACTER_TIMELINE_CURSOR_INVALID"));
    }

    @Test
    @DisplayName("OpenAPI에 CharacterFact 검색·상세와 캐릭터 타임라인 계약을 노출한다")
    void openApiContainsCharacterFactContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/character-facts/search'].get.operationId"
                ).value("searchCharacterFacts"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/character-facts/search'].get.parameters[*].name"
                ).value(org.hamcrest.Matchers.containsInAnyOrder(
                        "workId", "q", "factType", "scope", "page", "size"
                )))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/character-facts/{characterFactId}'].get.operationId"
                ).value("getCharacterFact"))
                .andExpect(jsonPath(
                        "$.components.schemas.PageResponseCharacterFactSearchResponse.properties.page.description"
                ).value("0부터 시작하는 현재 페이지 번호"))
                .andExpect(jsonPath(
                        "$.components.schemas.CharacterFactSearchResponse.properties.valueJson"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.components.schemas.CharacterFactSearchResponse.properties.displayName"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.CharacterFactDetailResponse.properties.displayName"
                ).exists())
                .andExpect(jsonPath(
                        "$.components.schemas.CharacterFactDetailResponse.properties.evidenceQuotes"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/character-facts/{characterFactId}']"
                                + ".get.responses['404'].content['application/json'].schema['$ref']"
                ).value("#/components/schemas/CommonErrorResponse"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/characters/{characterId}/timeline/summary']"
                                + ".get.operationId"
                ).value("getCharacterTimelineSummary"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/characters/{characterId}/timeline']"
                                + ".get.operationId"
                ).value("getCharacterTimeline"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/works/{workId}/characters/{characterId}/timeline']"
                                + ".get.parameters[*].name"
                ).value(org.hamcrest.Matchers.containsInAnyOrder(
                        "workId", "characterId", "factType", "cursor", "fromEpisodeNo", "size"
                )))
                .andExpect(jsonPath(
                        "$.components.schemas.CharacterTimelineFactResponse.properties.hasEvidence.type"
                ).value("boolean"))
                .andExpect(jsonPath(
                        "$.components.schemas.CharacterTimelineSummaryResponse.properties.episodes.items['$ref']"
                ).value("#/components/schemas/CharacterTimelineEpisodeResponse"));
    }

    private void assertSingleSearchResult(String query, CharacterFact expected) throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("q", query)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].characterFactId").value(expected.getId().toString()));
    }

    private void assertSearchResultCount(String query, int expectedCount) throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/character-facts/search", work.getId())
                        .queryParam("q", query)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(expectedCount));
    }

    private void assertFactNotFound(UUID characterFactId) throws Exception {
        mockMvc.perform(get(
                                "/api/v1/works/{workId}/character-facts/{characterFactId}",
                                work.getId(),
                                characterFactId
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHARACTER_FACT_NOT_FOUND"));
    }

    private CharacterFact saveFact(
            WorkCharacter character,
            SettingCandidate candidate,
            CharacterFactType factType,
            String factKey,
            String factValue,
            Episode sourceEpisode,
            Integer effectiveFromEpisodeNo,
            boolean current
    ) {
        CharacterFact fact = CharacterFact.create(
                character,
                candidate,
                factType,
                factKey,
                factValue,
                factValue,
                factValue == null ? null : JsonNodeFactory.instance.objectNode().put("value", factValue),
                sourceEpisode,
                candidate == null ? null : candidate.getSourceChunkId(),
                null,
                candidate == null ? null : candidate.getConfidence(),
                effectiveFromEpisodeNo
        );
        if (current) {
            fact.markCurrent();
        }
        return characterFactRepository.saveAndFlush(fact);
    }

    private SettingCandidate saveCandidate(Episode episode, String quote, int startOffset) {
        return settingCandidateRepository.saveAndFlush(SettingCandidate.create(
                work,
                episode,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "status.test",
                quote,
                SettingValueType.STRING,
                JsonNodeFactory.instance.objectNode().put("value", quote),
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", quote)
                                .put("startOffset", startOffset)
                                .put("endOffset", startOffset + quote.length())
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        ));
    }

    private WorkCharacter character(Work ownerWork, String name) {
        return WorkCharacter.create(
                ownerWork,
                name,
                "주인공",
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

    private CharacterSettingSchema schema(Work ownerWork, String schemaKey, String displayName) {
        return CharacterSettingSchema.create(
                ownerWork,
                schemaKey,
                null,
                displayName,
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                CharacterSettingValueSemantics.BASE_VALUE,
                CharacterSettingMergePolicy.REPLACE,
                JsonNodeFactory.instance.arrayNode(),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }

    private Episode episode(Work ownerWork, int episodeNo) {
        return Episode.create(
                ownerWork,
                null,
                episodeNo,
                episodeNo + "화",
                "works/%s/episodes/%d.txt".formatted(ownerWork.getId(), episodeNo),
                "version-" + episodeNo,
                "hash-" + episodeNo,
                100
        );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void cleanDatabase() {
        characterFactRepository.deleteAll();
        settingCandidateRepository.deleteAll();
        workCharacterRepository.deleteAll();
        episodeRepository.deleteAll();
        characterSettingSchemaRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }
}
