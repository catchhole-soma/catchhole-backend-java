package org.monitoring.catchholebackend.domain.worldsetting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                .andExpect(jsonPath("$.data.properties['사회 구조']").value("부족 단위로 생활"))
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
                                new WorldSettingPropertyCreateRequest("특징", "전투 종족", 0)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.properties.서식지").value("혹한 지역"))
                .andExpect(jsonPath("$.data.properties.특징").value("전투 종족"));

        mockMvc.perform(patch("/api/v1/works/{workId}/world-settings/{settingId}/properties",
                        work.getId(), setting.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorldSettingPropertyUpdateRequest("서식지", "생활 지역", "극지방", 1)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.properties.서식지").doesNotExist())
                .andExpect(jsonPath("$.data.properties['생활 지역']").value("극지방"))
                .andExpect(jsonPath("$.data.properties.특징").value("전투 종족"));
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
                                new WorldSettingPropertyCreateRequest("사회 구조", "부족", 0)
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WORLD_SETTING_VERSION_CONFLICT"));

        WorldSetting unchanged = worldSettingRepository.findById(setting.getId()).orElseThrow();
        assertThat(unchanged.getPropertyValue("사회 구조")).isNull();
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
