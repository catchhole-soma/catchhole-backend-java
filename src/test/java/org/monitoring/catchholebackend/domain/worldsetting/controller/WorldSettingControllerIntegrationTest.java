package org.monitoring.catchholebackend.domain.worldsetting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("확정 세계관 설정 API 통합 테스트")
class WorldSettingControllerIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private WorldSettingRepository worldSettingRepository;

    @Autowired
    private WorldSettingCandidateRepository candidateRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Work work;
    private String accessToken;

    @BeforeEach
    void setUp() {
        clearWorldSettingData();
        workRepository.deleteAll();
        memberRepository.deleteAll();
        member = memberRepository.save(Member.register(
                "world-setting-writer@example.com",
                "encoded-password",
                "01033334444",
                "세계관 작가"
        ));
        work = workRepository.save(Work.create(member, "설원 전기", WorkGenre.FANTASY, "세계관 테스트"));
        accessToken = jwtTokenProvider.generateAccessToken(member);
    }

    @AfterEach
    void tearDown() {
        clearWorldSettingData();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("새 대상은 내부 공백을 보존한 첫 설정과 함께 생성된다")
    void createWorldSettingPreservesInternalSpaces() throws Exception {
        WorldSettingCreateRequest request = new WorldSettingCreateRequest(
                WorldSettingCategory.RACE,
                "  북부 바바리안  ",
                "  사회 구조  ",
                "  부족 단위로 생활  "
        );

        mockMvc.perform(post("/api/v1/works/{workId}/world-settings", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("RACE"))
                .andExpect(jsonPath("$.data.subjectName").value("북부 바바리안"))
                .andExpect(jsonPath("$.data.properties[0].scopeName").doesNotExist())
                .andExpect(jsonPath("$.data.properties[0].settingName").value("사회 구조"))
                .andExpect(jsonPath("$.data.properties[0].value").value("부족 단위로 생활"))
                .andExpect(jsonPath("$.data.propertyCount").value(1))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("작품 삭제 시 작품에 속한 확정 세계관 설정도 함께 삭제된다")
    void deleteWorkCascadesWorldSettings() throws Exception {
        worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
        ));

        mockMvc.perform(delete("/api/v1/works/{workId}", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk());

        assertThat(worldSettingRepository.countByWorkId(work.getId())).isZero();
        assertThat(workRepository.findById(work.getId())).isEmpty();
    }

    @Test
    @DisplayName("대상명·설정명·설정값을 서버에서 검색하고 일치 설정을 요약한다")
    void searchWorldSettingsAcrossSubjectAndProperties() throws Exception {
        worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
        ));
        worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.LOCATION,
                "황도",
                "기후",
                "온화함"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/world-settings", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("q", "혹한")
                        .queryParam("page", "0")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalWorldSettingCount").value(2))
                .andExpect(jsonPath("$.data.worldSettings.totalElements").value(1))
                .andExpect(jsonPath("$.data.worldSettings.content[0].subjectName").value("바바리안"))
                .andExpect(jsonPath("$.data.worldSettings.content[0].matchedSettingName").value("서식지"))
                .andExpect(jsonPath("$.data.worldSettings.content[0].matchedSettingValue").value("혹한 지역"));
    }

    @Test
    @DisplayName("속성 추가와 수정은 JSON 전체를 덮어쓰지 않고 버전을 증가시킨다")
    void addAndUpdateSinglePropertyPreserveOtherProperties() throws Exception {
        WorldSetting setting = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
        ));

        mockMvc.perform(post("/api/v1/works/{workId}/world-settings/{settingId}/properties",
                        work.getId(), setting.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorldSettingPropertyCreateRequest("특징", "전투 종족", 0L)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.properties[0].settingName").value("서식지"))
                .andExpect(jsonPath("$.data.properties[0].value").value("혹한 지역"))
                .andExpect(jsonPath("$.data.properties[1].settingName").value("특징"))
                .andExpect(jsonPath("$.data.properties[1].value").value("전투 종족"));

        mockMvc.perform(patch("/api/v1/works/{workId}/world-settings/{settingId}/properties",
                        work.getId(), setting.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorldSettingPropertyUpdateRequest("서식지", "생활 지역", "극지방", 1L)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.properties[0].settingName").value("특징"))
                .andExpect(jsonPath("$.data.properties[0].value").value("전투 종족"))
                .andExpect(jsonPath("$.data.properties[1].settingName").value("생활 지역"))
                .andExpect(jsonPath("$.data.properties[1].value").value("극지방"));
    }

    @Test
    @DisplayName("범위가 다른 동일 설정명을 저장하고 범위와 설정명을 함께 이동한다")
    void createsAndMovesScopedProperties() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/works/{workId}/world-settings", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorldSettingCreateRequest(
                                WorldSettingCategory.LOCATION,
                                "미궁",
                                "1층",
                                "방향별 몬스터 출몰 규칙",
                                "동쪽에서 고블린이 출몰한다."
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.properties[0].scopeName").value("1층"))
                .andExpect(jsonPath("$.data.properties[0].settingName")
                        .value("방향별 몬스터 출몰 규칙"))
                .andReturn();
        UUID worldSettingId = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .at("/data/id")
                .asText());

        mockMvc.perform(post("/api/v1/works/{workId}/world-settings/{settingId}/properties",
                        work.getId(), worldSettingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorldSettingPropertyCreateRequest(
                                "2층",
                                "방향별 몬스터 출몰 규칙",
                                "중앙부에서 언데드가 출몰한다.",
                                0L
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.propertyCount").value(2))
                .andExpect(jsonPath("$.data.properties[1].scopeName").value("2층"));

        mockMvc.perform(patch("/api/v1/works/{workId}/world-settings/{settingId}/properties",
                        work.getId(), worldSettingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorldSettingPropertyUpdateRequest(
                                "1층",
                                "방향별 몬스터 출몰 규칙",
                                "3층",
                                "구역별 몬스터 출몰 규칙",
                                "서쪽에서 오크가 출몰한다.",
                                1L
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.properties[1].scopeName").value("3층"))
                .andExpect(jsonPath("$.data.properties[1].settingName")
                        .value("구역별 몬스터 출몰 규칙"));

        WorldSetting stored = worldSettingRepository.findById(worldSettingId).orElseThrow();
        assertThat(stored.getPropertyValue("1층", "방향별 몬스터 출몰 규칙")).isNull();
        assertThat(stored.getPropertyValue("2층", "방향별 몬스터 출몰 규칙"))
                .isEqualTo("중앙부에서 언데드가 출몰한다.");
        assertThat(stored.getPropertyValue("3층", "구역별 몬스터 출몰 규칙"))
                .isEqualTo("서쪽에서 오크가 출몰한다.");
    }

    @Test
    @DisplayName("오래된 버전으로 직접 수정하면 입력을 덮어쓰지 않고 충돌을 응답한다")
    void updatePropertyRejectsStaleVersion() throws Exception {
        WorldSetting setting = WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
        );
        setting.addProperty("특징", "전투 종족");
        worldSettingRepository.save(setting);

        mockMvc.perform(post("/api/v1/works/{workId}/world-settings/{settingId}/properties",
                        work.getId(), setting.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorldSettingPropertyCreateRequest("사회 구조", "부족", 0L)
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WORLD_SETTING_VERSION_CONFLICT"));

        WorldSetting unchanged = worldSettingRepository.findById(setting.getId()).orElseThrow();
        assertThat(unchanged.getPropertyValue("사회 구조")).isNull();
    }

    @Test
    @DisplayName("직접 수정 요청은 현재 version을 반드시 포함해야 한다")
    void directMutationsRequireVersion() throws Exception {
        WorldSetting setting = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
        ));

        mockMvc.perform(patch("/api/v1/works/{workId}/world-settings/{settingId}/identity",
                        work.getId(), setting.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"LOCATION","subjectName":"북부 설원"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/works/{workId}/world-settings/{settingId}/properties",
                        work.getId(), setting.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settingName":"특징","settingValue":"전투 종족"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));

        mockMvc.perform(patch("/api/v1/works/{workId}/world-settings/{settingId}/properties",
                        work.getId(), setting.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentSettingName":"서식지","settingName":"생활 지역","settingValue":"극지방"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));

        WorldSetting unchanged = worldSettingRepository.findById(setting.getId()).orElseThrow();
        assertThat(unchanged.getCategory()).isEqualTo(WorldSettingCategory.RACE);
        assertThat(unchanged.getSubjectName()).isEqualTo("바바리안");
        assertThat(unchanged.getPropertyValue("서식지")).isEqualTo("혹한 지역");
        assertThat(unchanged.hasProperty("특징")).isFalse();
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백만 다른 같은 분류 대상은 Backend 전체 조회로 거절한다")
    void createRejectsNormalizedDuplicateSubject() throws Exception {
        worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.LOCATION,
                "North",
                "기후",
                "한랭"
        ));

        mockMvc.perform(post("/api/v1/works/{workId}/world-settings", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorldSettingCreateRequest(
                                WorldSettingCategory.LOCATION,
                                "  north  ",
                                "지형",
                                "설원"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WORLD_SETTING_SUBJECT_DUPLICATED"));
    }

    private void clearWorldSettingData() {
        candidateRepository.deleteAll();
        worldSettingRepository.deleteAll();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
