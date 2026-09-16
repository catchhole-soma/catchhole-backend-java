package org.monitoring.catchholebackend.domain.worldimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
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
@DisplayName("캐릭터 이미지 자동 연결·수동 선택·권한")
class CharacterImageIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired WorkCharacterRepository characters;
    @Autowired JwtTokenProvider jwt;
    @Autowired org.monitoring.catchholebackend.domain.worldimage.service.AutomaticImageService automaticImages;
    @MockitoBean ObjectStorage storage;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.monitoring.catchholebackend.domain.worldimage.repository.WorldImageCatalogRepository imageCatalogs;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.monitoring.catchholebackend.domain.worldimage.processor.CharacterRaceImageMatcher imageMatcher;
    WorkCharacter character;
    String token;
    String stranger;
    String base;

    @BeforeEach
    void prepare() {
        var owner = members.save(Member.register("character-image@example.com", "encoded", "01077770001", "작가"));
        var other = members.save(Member.register("character-image-other@example.com", "encoded", "01077770002", "다른 작가"));
        var work = works.save(Work.create(owner, "캐릭터 이미지", WorkGenre.FANTASY, "설정"));
        character = characters.saveAndFlush(WorkCharacter.create(work, "고블린 사냥꾼", null, null, null, null, null, null, null, null, null));
        token = "Bearer " + jwt.generateAccessToken(owner);
        stranger = "Bearer " + jwt.generateAccessToken(other);
        base = "/api/v1/works/" + work.getId() + "/characters/" + character.getId();
        seed("race-elf", "RACE", "엘프");
        seed("race-goblin", "RACE", "고블린");
        seed("location-forest", "LOCATION", "숲");
        jdbc.update("INSERT INTO world_image_aliases(catalog_id,alias) VALUES ('race-elf','엘프족')");
    }
    void seed(String id, String category, String name) {
        jdbc.update("INSERT INTO world_image_catalog(id,category,name,search_text,is_default,active,thumbnail_sha,image_sha) VALUES (?,?,?,?,false,true,?,?)",
                id, category, name, name, "a".repeat(64), "b".repeat(64));
    }
    void species(String value) {
        var profile = JsonNodeFactory.instance.objectNode();
        if (value != null) profile.set("profile.species", JsonNodeFactory.instance.objectNode().put("value", value));
        character.replaceCurrentSnapshots(null, null, profile, null, null, null, null);
        characters.flush();
        automaticImages.refreshCharacterImages(java.util.List.of(character));
    }
    ResultActions read() throws Exception { return mvc.perform(get(base).header("Authorization", token)); }
    ResultActions choose(String payload) throws Exception {
        return mvc.perform(patch(base + "/image").header("Authorization", token).contentType("application/json").content(payload));
    }

    @Test @DisplayName("이름을 추측하지 않고 명시 종족만 연결하며 저장된 결과로 목록과 상세를 표시한다")
    void automaticOnlyFromSpecies() throws Exception {
        read().andExpect(status().isOk()).andExpect(jsonPath("$.data.image.catalogId").isEmpty()).andExpect(jsonPath("$.data.image.source").value("AUTO"));
        species(" 엘프 족 ");
        read().andExpect(jsonPath("$.data.image.catalogId").value("race-elf"));
        mvc.perform(get(base.substring(0, base.lastIndexOf('/'))).header("Authorization", token))
                .andExpect(jsonPath("$.data.content[0].image.catalogId").value("race-elf"));
        species("고블린을 사냥하는 인간");
        read().andExpect(jsonPath("$.data.image.catalogId").isEmpty());
        species("수인족");
        read().andExpect(jsonPath("$.data.image.catalogId").isEmpty());
        species(null);
        read().andExpect(jsonPath("$.data.image.catalogId").isEmpty());
        assertThat(jdbc.queryForObject("select count(*) from character_images", Long.class)).isEqualTo(1);
    }

    @Test @DisplayName("중복 별칭과 상충하는 종족 값은 기본 이미지로 표시한다")
    void ambiguousSpecies() throws Exception {
        jdbc.update("INSERT INTO world_image_aliases(catalog_id,alias) VALUES ('race-goblin','엘프족')");
        species("엘프족");
        automaticImages.refreshCharacterImages(java.util.List.of(character));
        read().andExpect(jsonPath("$.data.image.catalogId").isEmpty());
        var profile = JsonNodeFactory.instance.objectNode().put("profile.species", "엘프").put("species", "고블린");
        character.replaceCurrentSnapshots(null,null,profile,null,null,null,null);
        automaticImages.refreshCharacterImages(java.util.List.of(character));
        read().andExpect(jsonPath("$.data.image.catalogId").isEmpty());
    }

    @Test @DisplayName("직접 고른 종족·공통 기본은 설정 변화보다 우선하며 자동 연결로 복귀할 수 있다")
    void manualPriorityAndDefault() throws Exception {
        species("엘프");
        long snapshotVersion = character.getSnapshotVersion();
        var updatedAt = character.getUpdatedAt();
        choose("{\"catalogId\":\"race-goblin\",\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        choose("{\"catalogId\":\"race-goblin\",\"version\":1}").andExpect(jsonPath("$.data.version").value(1));
        assertThat(character.getSnapshotVersion()).isEqualTo(snapshotVersion);
        assertThat(character.getUpdatedAt()).isEqualTo(updatedAt);
        read().andExpect(jsonPath("$.data.image.catalogId").value("race-goblin")).andExpect(jsonPath("$.data.image.source").value("MANUAL"));
        species("인간");
        read().andExpect(jsonPath("$.data.image.catalogId").value("race-goblin"));
        choose("{\"useDefault\":true,\"version\":1}").andExpect(jsonPath("$.data.source").value("DEFAULT"));
        species("엘프");
        read().andExpect(jsonPath("$.data.image.catalogId").isEmpty());
        choose("{\"version\":2}").andExpect(jsonPath("$.data.source").value("AUTO")).andExpect(jsonPath("$.data.catalogId").value("race-elf"));
        choose("{\"useDefault\":true,\"version\":1}").andExpect(status().isConflict());
    }

    @Test @DisplayName("이미 저장된 자동 결과는 목록·상세 조회에서 도감·별칭 조회나 재매칭을 하지 않는다")
    void readsOnlyPersistedImage() throws Exception {
        species("엘프");
        org.mockito.Mockito.clearInvocations(imageCatalogs, imageMatcher);
        read().andExpect(jsonPath("$.data.image.catalogId").value("race-elf"));
        mvc.perform(get(base.substring(0, base.lastIndexOf('/'))).header("Authorization", token))
                .andExpect(jsonPath("$.data.content[0].image.catalogId").value("race-elf"));
        org.mockito.Mockito.verify(imageCatalogs, org.mockito.Mockito.never()).findRaceImagesWithAliases();
        org.mockito.Mockito.verifyNoInteractions(imageMatcher);
    }

    @Test @DisplayName("다른 분류·인증 없음·타인·보관 캐릭터의 변경을 거절한다")
    void invalidAndUnauthorized() throws Exception {
        choose("{\"catalogId\":\"location-forest\",\"version\":0}").andExpect(status().isBadRequest());
        choose("{\"catalogId\":\"race-elf\",\"useDefault\":true,\"version\":0}").andExpect(status().isBadRequest());
        choose("{\"catalogId\":\"missing\",\"version\":0}").andExpect(status().isNotFound());
        mvc.perform(patch(base + "/image").contentType("application/json").content("{\"version\":0}")).andExpect(status().isUnauthorized());
        mvc.perform(patch(base + "/image").header("Authorization",stranger).contentType("application/json").content("{\"version\":0}")).andExpect(status().isNotFound());
        character.archive();
        choose("{\"catalogId\":\"race-elf\",\"version\":0}").andExpect(status().isNotFound());
    }
}
