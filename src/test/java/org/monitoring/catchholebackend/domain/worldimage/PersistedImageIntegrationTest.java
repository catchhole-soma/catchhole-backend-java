package org.monitoring.catchholebackend.domain.worldimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.type.MemberRole;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldimage.repository.*;
import org.monitoring.catchholebackend.domain.worldimage.service.*;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.WorldImageBackfillRequest;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@DisplayName("확정 이미지 영속화·기존 데이터 보정")
class PersistedImageIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired MemberRepository members;
    @Autowired WorkRepository works; @Autowired WorldSettingRepository settings;
    @Autowired WorldSettingImageRepository selections; @Autowired JwtTokenProvider jwt;
    @Autowired WorldImageBackfillService backfill; @Autowired AutomaticImageService automaticImages;
    @Autowired WorkCharacterRepository characters; @Autowired CharacterImageRepository characterImages;
    @MockitoBean ObjectStorage storage;
    @Autowired org.monitoring.catchholebackend.domain.work.service.WorkService workService;
    Work work; String token; String base;
    @BeforeEach void setUp() {
        var owner = members.save(Member.register("persisted@example.com", "encoded", "01077770091", "작가"));
        work = works.saveAndFlush(Work.create(owner, "저장 이미지", WorkGenre.FANTASY, "설정"));
        token = "Bearer " + jwt.generateAccessToken(owner);
        base = "/api/v1/works/" + work.getId() + "/world-settings";
        for (String[] row : new String[][]{{"forest", "숲"}, {"cave", "동굴"}}) {
            jdbc.update("INSERT INTO world_image_catalog(id,category,name,search_text,is_default,active,thumbnail_sha,image_sha,created_at,updated_at) VALUES (?,'LOCATION',?,?,false,true,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",row[0],row[1],row[1],"a".repeat(64),"b".repeat(64));
            jdbc.update("INSERT INTO world_image_recommendations(catalog_id,theme) VALUES (?,'fantasy')", row[0]);
        }
    }
    String create(String name) throws Exception {
        var response = mvc.perform(post(base).header("Authorization",token).contentType("application/json")
            .content("{\"category\":\"LOCATION\",\"subjectName\":\""+name+"\",\"settingName\":\"특징\",\"settingValue\":\"넓다\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("id").asText();
    }
    @Test @DisplayName("정식 대상 생성 때 매칭 결과를 저장하고 이름 변경은 자동 사진만 갱신한다")
    void createAndRenameWithManualPriority() throws Exception {
        String id=create("고블린 숲");
        var image=selections.findById(UUID.fromString(id)).orElseThrow();
        assertThat(image.getCatalog().getId()).isEqualTo("forest");
        assertThat(image.getSelectionSource()).isEqualTo("AUTO");
        mvc.perform(patch(base+"/"+id+"/identity").header("Authorization",token).contentType("application/json")
            .content("{\"category\":\"LOCATION\",\"subjectName\":\"얼음 동굴\",\"version\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.image.catalogId").value("cave"));
        mvc.perform(patch(base+"/"+id+"/image").header("Authorization",token).contentType("application/json")
            .content("{\"catalogId\":\"forest\",\"version\":"+image.getVersion()+"}"))
            .andExpect(status().isOk());
        mvc.perform(patch(base+"/"+id+"/identity").header("Authorization",token).contentType("application/json")
            .content("{\"category\":\"LOCATION\",\"subjectName\":\"새 동굴\",\"version\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.image.catalogId").value("forest"));
        mvc.perform(patch(base+"/"+id+"/image").header("Authorization",token).contentType("application/json")
            .content("{\"useAutomatic\":true,\"version\":"+image.getVersion()+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.catalogId").value("cave")).andExpect(jsonPath("$.data.source").value("AUTO"));
    }
    @Test @DisplayName("보정은 미처리만 제한해 저장하며 미일치·직접 기본도 다시 처리하지 않는다")
    void boundedIdempotentBackfill() throws Exception {
        var forest = settings.saveAndFlush(WorldSetting.create(work,WorldSettingCategory.LOCATION,"고블린숲","설정","값"));
        var unknown = settings.saveAndFlush(WorldSetting.create(work,WorldSettingCategory.LOCATION,"알 수 없는 장소","설정","값"));
        var pinned = settings.saveAndFlush(WorldSetting.create(work,WorldSettingCategory.LOCATION,"수호자의 동굴","설정","값"));
        mvc.perform(patch(base+"/"+pinned.getId()+"/image").header("Authorization",token).contentType("application/json")
            .content("{\"version\":0}" )).andExpect(status().isOk());
        var kind=WorldImageBackfillRequest.Kind.WORLD_SETTING;
        var preview=backfill.backfillImages(new WorldImageBackfillRequest(work.getId(),kind,500,false));
        assertThat(preview.processed()).isEqualTo(2); assertThat(preview.matched()).isEqualTo(1);
        assertThat(selections.findById(forest.getId())).isEmpty();
        var first=backfill.backfillImages(new WorldImageBackfillRequest(work.getId(),kind,1,true));
        assertThat(first.processed()).isEqualTo(1);
        assertThat(backfill.backfillImages(new WorldImageBackfillRequest(work.getId(),kind,500,true)).processed()).isEqualTo(1);
        assertThat(backfill.backfillImages(new WorldImageBackfillRequest(work.getId(),kind,500,true)).processed()).isZero();
        assertThat(selections.findById(forest.getId()).orElseThrow().getCatalog().getId()).isEqualTo("forest");
        assertThat(selections.findById(unknown.getId()).orElseThrow().getSelectionSource()).isEqualTo("AUTO");
        assertThat(selections.findById(pinned.getId()).orElseThrow().getSelectionSource()).isEqualTo("DEFAULT");
        assertThat(forest.getVersion()).isZero();
    }
    @Test @DisplayName("장르 변경은 저장된 자동 결과만 갱신하고 기본 이미지 고정은 유지한다")
    void genreChangeRefreshesOnlyAutomatic() throws Exception {
        String id=create("엘프 숲");
        var image=selections.findById(UUID.fromString(id)).orElseThrow();
        workService.updateWork(work.getMember().getId(), work.getId(), new org.monitoring.catchholebackend.domain.work.dto.request.WorkUpdateRequest("저장 이미지", WorkGenre.SPORTS, "설정"));
        assertThat(image.getCatalog()).isNull();
        assertThat(image.getSelectionSource()).isEqualTo("AUTO");
        mvc.perform(patch(base+"/"+id+"/image").header("Authorization",token).contentType("application/json")
            .content("{\"version\":"+image.getVersion()+"}" )).andExpect(status().isOk());
        workService.updateWork(work.getMember().getId(), work.getId(), new org.monitoring.catchholebackend.domain.work.dto.request.WorkUpdateRequest("저장 이미지", WorkGenre.FANTASY, "설정"));
        assertThat(image.getCatalog()).isNull();
        assertThat(image.getSelectionSource()).isEqualTo("DEFAULT");
    }

    @Test @DisplayName("일반 작가와 인증 없는 요청은 운영자 보정에 접근할 수 없다")
    void backfillRequiresAdmin() throws Exception {
        var body="{\"workId\":\""+work.getId()+"\",\"kind\":\"WORLD_SETTING\",\"apply\":true}";
        mvc.perform(post("/api/v1/admin/world-images/backfill").header("Authorization",token).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/world-images/backfill").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    }

    @Test @DisplayName("운영자는 기존 캐릭터의 미처리·이전 자동 상태만 보정하고 재실행해도 직접 선택을 유지한다")
    void adminBackfillsLegacyCharacters() throws Exception {
        jdbc.update("INSERT INTO world_image_catalog(id,category,name,search_text,is_default,active,thumbnail_sha,image_sha,created_at,updated_at) VALUES ('elf','RACE','엘프','엘프',false,true,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "a".repeat(64), "b".repeat(64));
        var elf = characters.saveAndFlush(WorkCharacter.create(work, "엘프 검사", null, null, null, null, null, null, null, null, null));
        var profile = JsonNodeFactory.instance.objectNode().put("profile.species", "엘프");
        elf.replaceCurrentSnapshots(null, null, profile, null, null, null, null);
        characters.flush();
        jdbc.update("INSERT INTO character_images(character_id,version,created_at,updated_at) VALUES (?,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", elf.getId());
        var unknown = characters.saveAndFlush(WorkCharacter.create(work, "종족 불명", null, null, null, null, null, null, null, null, null));
        var pinned = characters.saveAndFlush(WorkCharacter.create(work, "기본 고정", null, null, null, null, null, null, null, null, null));
        mvc.perform(patch("/api/v1/works/"+work.getId()+"/characters/"+pinned.getId()+"/image")
                .header("Authorization", token).contentType("application/json").content("{\"useDefault\":true,\"version\":0}"))
                .andExpect(status().isOk());
        var admin = members.save(Member.register("image-admin@example.com", "encoded", "01077770092", "운영자"));
        ReflectionTestUtils.setField(admin, "role", MemberRole.ADMIN);
        members.flush();
        String adminToken = "Bearer " + jwt.generateAccessToken(admin);
        String endpoint = "/api/v1/admin/world-images/backfill";
        String body = "{\"workId\":\""+work.getId()+"\",\"kind\":\"CHARACTER\",\"apply\":";
        mvc.perform(post(endpoint).header("Authorization", adminToken).contentType("application/json").content(body+"false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.processed").value(2)).andExpect(jsonPath("$.data.matched").value(1));
        assertThat(characterImages.findById(unknown.getId())).isEmpty();
        mvc.perform(post(endpoint).header("Authorization", adminToken).contentType("application/json").content(body+"true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.processed").value(2)).andExpect(jsonPath("$.data.defaults").value(1));
        assertThat(characterImages.findById(elf.getId()).orElseThrow().getCatalog().getId()).isEqualTo("elf");
        assertThat(characterImages.findById(elf.getId()).orElseThrow().getVersion()).isEqualTo(4);
        assertThat(characterImages.findById(unknown.getId()).orElseThrow().getSelectionSource()).isEqualTo("AUTO");
        assertThat(characterImages.findById(pinned.getId()).orElseThrow().getSelectionSource()).isEqualTo("DEFAULT");
        mvc.perform(post(endpoint).header("Authorization", adminToken).contentType("application/json").content(body+"true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.processed").value(0));
    }
}
