package org.monitoring.catchholebackend.domain.worldimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("세계관 공용 이미지 도감과 수동 선택")
class WorldImageIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired WorldSettingRepository settings;
    @Autowired JwtTokenProvider jwt;
    @MockitoBean ObjectStorage storage;
    private WorldSetting setting;
    private String token;
    private String base;
    private static final String SHA = "a".repeat(64);

    @BeforeEach
    void setUp() {
        Member member = members.save(Member.register("image-test@example.com", "encoded", "01077778888", "도감 작가"));
        Work work = works.save(Work.create(member, "이미지 테스트", WorkGenre.FANTASY, "도감"));
        setting = settings.saveAndFlush(WorldSetting.create(work, WorldSettingCategory.RACE, "북부 고블린", "성격", "경계한다"));
        token = "Bearer " + jwt.generateAccessToken(member);
        base = "/api/v1/works/" + work.getId() + "/world-settings/" + setting.getId();
        seed("race-default", "RACE", "종족 기본", "종족기본", true);
        seed("race-goblin", "RACE", "고블린", "고블린홉고블린", false);
        seed("location-forest", "LOCATION", "숲", "숲삼림", false);
        seed("location-default", "LOCATION", "장소 기본", "장소기본", true);
        jdbc.update("INSERT INTO world_image_aliases(catalog_id, alias) VALUES (?, ?)", "race-goblin", "홉고블린");
    }

    private void themeAsset(String theme, String purpose, String slot, String sha) {
        jdbc.update("INSERT INTO world_image_theme_assets(id,theme,purpose,slot,name,thumbnail_sha,image_sha,created_at,updated_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                theme + purpose + slot, theme, purpose, slot, "테마 기본", sha, sha);
    }

    @Test
    @DisplayName("장르가 바뀌면 기본 그림만 바뀌고 직접 선택·설정 내용·이미지 버전은 보존한다")
    void genreDefaultsPreserveManualSelection() throws Exception {
        themeAsset("fantasy", "DEFAULT", "RACE", "b".repeat(64));
        themeAsset("sports", "DEFAULT", "RACE", "c".repeat(64));
        mvc.perform(get(base).header("Authorization", token)).andExpect(jsonPath("$.data.image.imageUrl").value("/api/v1/world-image-assets/" + "b".repeat(64) + ".webp"));
        choose("race-goblin", 0).andExpect(status().isOk());
        setting.getWork().updateInfo("이미지 테스트", WorkGenre.SPORTS, "도감");
        mvc.perform(get(base).header("Authorization", token)).andExpect(jsonPath("$.data.image.catalogId").value("race-goblin"))
                .andExpect(jsonPath("$.data.image.version").value(1));
        choose(null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").value("/api/v1/world-image-assets/" + "c".repeat(64) + ".webp"))
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(get(base).header("Authorization", token)).andExpect(jsonPath("$.data.properties[0].value").value("경계한다"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("하나의 원본을 여러 장르가 추천하고 전체 도감과 직접 선택에는 장르 제한이 없다")
    void sharedRecommendationsAndFullCatalog() throws Exception {
        jdbc.update("INSERT INTO world_image_recommendations(catalog_id,theme) VALUES ('race-goblin','fantasy'),('race-goblin','horror')");
        String workId = setting.getWork().getId().toString();
        for (WorkGenre genre : new WorkGenre[]{WorkGenre.FANTASY, WorkGenre.HORROR}) {
            setting.getWork().updateInfo("이미지 테스트", genre, "도감");
            mvc.perform(get("/api/v1/world-image-catalog").header("Authorization", token).param("category", "RACE")
                    .param("workId", workId).param("recommended", "true").param("q", "홉 고블린").param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value("race-goblin"));
        }
        setting.getWork().updateInfo("이미지 테스트", WorkGenre.SPORTS, "도감");
        mvc.perform(get("/api/v1/world-image-catalog").header("Authorization", token).param("category", "RACE")
                .param("workId", workId).param("recommended", "true")).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/world-image-catalog").header("Authorization", token).param("category", "RACE")
                .param("workId", workId)).andExpect(jsonPath("$.data.totalElements").value(1));
        choose("race-goblin", 0).andExpect(status().isOk());
        mvc.perform(get("/api/v1/world-image-catalog").header("Authorization", token).param("category", "RACE")
                .param("recommended", "true")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("일상·로맨스·코미디·기타가 같은 구성을 공유하고 테마 자산도 등록된 SHA만 공개한다")
    void commonThemeAndPublishedAssets() throws Exception {
        themeAsset("modern-common", "OVERVIEW", "ALL", "d".repeat(64));
        themeAsset("modern-common", "DEFAULT", "RACE", "d".repeat(64));
        String endpoint = "/api/v1/works/" + setting.getWork().getId() + "/world-image-theme";
        for (WorkGenre genre : new WorkGenre[]{WorkGenre.ROMANCE, WorkGenre.COMEDY, WorkGenre.SLICE_OF_LIFE, WorkGenre.ETC}) {
            setting.getWork().updateInfo("이미지 테스트", genre, "도감");
            mvc.perform(get(endpoint).header("Authorization", token)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.theme").value("modern-common"))
                    .andExpect(jsonPath("$.data.overview.ALL.imageUrl").value("/api/v1/world-image-assets/" + "d".repeat(64) + ".webp"));
        }
        when(storage.getBytes("world-image-catalog/v1/" + "d".repeat(64) + ".webp")).thenReturn(new byte[]{1});
        commitFixture();
        mvc.perform(get("/api/v1/world-image-assets/" + "d".repeat(64) + ".webp")).andExpect(status().isOk());
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        Member stranger = members.save(Member.register("theme-other@example.com", "encoded", "01077776666", "다른 작가"));
        String otherToken = "Bearer " + jwt.generateAccessToken(stranger);
        mvc.perform(get(endpoint).header("Authorization", otherToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/world-image-catalog").header("Authorization", otherToken).param("category", "RACE")
                .param("workId", setting.getWork().getId().toString()).param("recommended", "true")).andExpect(status().isNotFound());
    }

    private void commitFixture() {
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupCommittedFixture() {
        if (org.springframework.test.context.transaction.TestTransaction.isActive()) return;
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM world_settings WHERE work_id=?", setting.getWork().getId());
            jdbc.update("DELETE FROM works WHERE id=?", setting.getWork().getId());
            jdbc.update("DELETE FROM members WHERE email IN ('image-test@example.com','theme-other@example.com')");
            jdbc.update("DELETE FROM world_image_aliases WHERE catalog_id IN ('race-default','race-goblin','location-forest','location-default')");
            jdbc.update("DELETE FROM world_image_catalog WHERE id IN ('race-default','race-goblin','location-forest','location-default')");
            jdbc.update("DELETE FROM world_image_theme_assets WHERE theme='modern-common'");
        });
    }

    private void seed(String id, String category, String name, String search, boolean isDefault) {
        jdbc.update("INSERT INTO world_image_catalog(id,category,name,search_text,is_default,active,thumbnail_sha,image_sha,created_at,updated_at) VALUES (?,?,?,?,?,true,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, category, name, search, isDefault, SHA, SHA);
    }

    private ResultActions choose(String id, long version) throws Exception {
        String catalog = id == null ? "null" : '"' + id + '"';
        return mvc.perform(patch(base + "/image").header("Authorization", token).contentType("application/json")
                .content("{\"catalogId\":" + catalog + ",\"version\":" + version + "}"));
    }

    @Test
    @DisplayName("기본 이미지에서 선택·조회·해제까지 이미지 버전만 변경한다")
    void selectAndResetWithoutChangingContent() throws Exception {
        var originalUpdatedAt = setting.getUpdatedAt();
        mvc.perform(get(base).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image.catalogId").value("race-default"))
                .andExpect(jsonPath("$.data.image.version").value(0));
        choose("race-goblin", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get(base).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image.catalogId").value("race-goblin"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.properties[0].value").value("경계한다"));
        mvc.perform(get(base.substring(0, base.lastIndexOf('/'))).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.worldSettings.content[0].image.catalogId").value("race-goblin"));
        choose("race-goblin", 1).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        choose(null, 1).andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("DEFAULT"))
                .andExpect(jsonPath("$.data.catalogId").value("race-default"))
                .andExpect(jsonPath("$.data.version").value(2));
        choose("race-goblin", 0).andExpect(status().isConflict());
        assertThat(setting.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(jdbc.queryForObject("SELECT version FROM world_setting_images WHERE world_setting_id=?", Long.class, setting.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("분류가 다른 이미지·없는 이미지·오래된 선택은 저장하지 않는다")
    void invalidSelection() throws Exception {
        choose("location-forest", 0).andExpect(status().isBadRequest());
        choose("missing", 0).andExpect(status().isNotFound());
        choose("race-goblin", 0).andExpect(status().isOk());
        choose(null, 0).andExpect(status().isConflict());
        mvc.perform(get(base).header("Authorization", token)).andExpect(jsonPath("$.data.image.catalogId").value("race-goblin"));
    }

    @Test
    @DisplayName("대상 분류를 바꾸면 이전 분류의 선택을 해제하고 이미지 버전을 올린다")
    void categoryChangeClearsSelection() throws Exception {
        choose("race-goblin", 0).andExpect(status().isOk());
        mvc.perform(patch(base + "/identity").header("Authorization", token).contentType("application/json")
                .content("{\"category\":\"LOCATION\",\"subjectName\":\"고블린 숲\",\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(get(base).header("Authorization", token)).andExpect(jsonPath("$.data.image.catalogId").value("location-default"))
                .andExpect(jsonPath("$.data.image.version").value(2));
    }

    @Test
    @DisplayName("같은 분류의 별칭을 공백 없이 검색하고 기본 이미지와 다른 분류는 제외한다")
    void searchAliases() throws Exception {
        mvc.perform(get("/api/v1/world-image-catalog").header("Authorization", token)
                        .param("category", "RACE").param("q", " 홉 고블린 ").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].aliases[0]").value("홉고블린"));
        mvc.perform(get("/api/v1/world-image-catalog").header("Authorization", token).param("category", "RACE").param("q", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/world-image-catalog").header("Authorization", token).param("category", "RACE").param("size", "61"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없는 도감 접근과 타인 작품의 이미지 변경을 거절한다")
    void authorization() throws Exception {
        mvc.perform(get("/api/v1/world-image-catalog").param("category", "RACE")).andExpect(status().isUnauthorized());
        mvc.perform(patch(base + "/image").contentType("application/json").content("{\"catalogId\":null,\"version\":0}"))
                .andExpect(status().isUnauthorized());
        Member stranger = members.save(Member.register("image-other@example.com", "encoded", "01077779999", "다른 작가"));
        token = "Bearer " + jwt.generateAccessToken(stranger);
        choose("race-goblin", 0).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("등록된 이미지에만 공개 접근을 허용하며 임의 저장소 키는 읽지 않는다")
    void publicWhitelist() throws Exception {
        commitFixture();
        mvc.perform(get("/api/v1/world-image-assets/" + "b".repeat(64) + ".webp")).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
        when(storage.getBytes("world-image-catalog/v1/" + SHA + ".webp")).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new byte[]{1, 2, 3};
        });
        mvc.perform(get("/api/v1/world-image-assets/" + SHA + ".webp")).andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(header().string("ETag", '"' + SHA + '"'));
    }
}
