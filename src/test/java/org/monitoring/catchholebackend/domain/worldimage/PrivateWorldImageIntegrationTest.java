package org.monitoring.catchholebackend.domain.worldimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldimage.repository.PrivateWorldImageRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.monitoring.catchholebackend.global.storage.PrivateWorldImagePaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("암호화 개인 이미지의 소유권·선택·저장 경계")
class PrivateWorldImageIntegrationTest {
    @Autowired org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository characters;
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired WorldSettingRepository settings;
    @Autowired PrivateWorldImageRepository images;
    @Autowired JwtTokenProvider jwt;
    @MockitoBean ObjectStorage storage;
    private UUID workId;
    private UUID otherWorkId;
    private UUID settingId;
    private String token;
    private String strangerToken;
    private String base;
    private final UUID vaultId = UUID.randomUUID();
    private final UUID imageId = UUID.randomUUID();
    // Header-valid opaque fixture: only the browser can authenticate/decrypt image contents.
    private final byte[] envelope = ("CHI1" + "x".repeat(40)).getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void prepare() {
        org.mockito.Mockito.clearInvocations(storage);
        var owner = members.save(Member.register("private-image@example.com", "encoded", "01077771111", "작가"));
        var stranger = members.save(Member.register("private-other@example.com", "encoded", "01077772222", "타인"));
        var work = works.save(Work.create(owner, "개인 이미지", WorkGenre.FANTASY, "설정"));
        workId = work.getId();
        otherWorkId = works.save(Work.create(owner, "다른 작품", WorkGenre.FANTASY, "설정")).getId();
        settingId = settings.saveAndFlush(WorldSetting.create(work, WorldSettingCategory.RACE, "고블린", "특징", "초록색")).getId();
        token = "Bearer " + jwt.generateAccessToken(owner);
        strangerToken = "Bearer " + jwt.generateAccessToken(stranger);
        base = "/api/v1/works/" + workId + "/private-world-images";
        when(storage.purgePrefixes(any())).thenReturn(new ObjectStoragePurgeResult(2, 2, 0));
    }
    private String encoded() { return Base64.getEncoder().encodeToString(envelope); }
    private ResultActions createVault() throws Exception {
        return mvc.perform(post("/api/v1/private-image-vault").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + vaultId + "\",\"keyCheck\":\"" + encoded() + "\"}"));
    }
    private ResultActions upload(String auth, byte[] bytes) throws Exception {
        return mvc.perform(multipart(base).file(new MockMultipartFile("metadata", "metadata.enc.json", "application/json",
                        ("{\"id\":\"" + imageId + "\",\"vaultId\":\"" + vaultId + "\",\"encryptedMetadata\":\"" + encoded() + "\"}").getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("image", "image.enc", "application/octet-stream", bytes))
                .file(new MockMultipartFile("thumbnail", "thumbnail.enc", "application/octet-stream", envelope))
                .header("Authorization", auth));
    }
    private ResultActions select(UUID id, long version) throws Exception {
        return mvc.perform(patch("/api/v1/works/" + workId + "/world-settings/" + settingId + "/image")
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"privateImageId\":" + (id == null ? "null" : "\"" + id + "\"") + ",\"version\":" + version + "}"));
    }

    @Test
    @DisplayName("키 없이 암호문만 저장하고 인증된 소유자에게 캐시 금지로 반환한다")
    void ciphertextRoundtrip() throws Exception {
        createVault().andExpect(status().isOk()).andExpect(jsonPath("$.data.keyCheck").value(encoded()));
        createVault().andExpect(status().isOk()); // Same request is safe to retry.
        mvc.perform(post("/api/v1/private-image-vault").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + UUID.randomUUID() + "\",\"keyCheck\":\"" + encoded() + "\"}"))
                .andExpect(status().isConflict());
        upload(token, envelope).andExpect(status().isOk()).andExpect(jsonPath("$.data.encryptedMetadata").value(encoded()))
                .andExpect(jsonPath("$.data.filename").doesNotExist()).andExpect(jsonPath("$.data.key").doesNotExist());
        verify(storage).putBytes(PrivateWorldImagePaths.key(workId, imageId, false), envelope, "application/octet-stream");
        verify(storage).putBytes(PrivateWorldImagePaths.key(workId, imageId, true), envelope, "application/octet-stream");
        when(storage.getBytes(PrivateWorldImagePaths.key(workId, imageId, false))).thenReturn(envelope);
        mvc.perform(get(base + "/" + imageId + "/image").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(content().bytes(envelope)).andExpect(header().string("Cache-Control", "no-store, private"));
        upload(token, envelope).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("인증 없음·타인·다른 작품은 조회·업로드·삭제·선택할 수 없다")
    void ownershipBoundaries() throws Exception {
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/private-image-vault")).andExpect(status().isUnauthorized());
        mvc.perform(get(base).header("Authorization", strangerToken)).andExpect(status().isNotFound());
        upload(strangerToken, envelope).andExpect(status().isNotFound());
        mvc.perform(delete(base + "/" + imageId).header("Authorization", strangerToken)).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
        createVault().andExpect(status().isOk()); upload(token, envelope).andExpect(status().isOk());
        mvc.perform(get(base + "/" + imageId + "/thumbnail").header("Authorization", strangerToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/works/" + otherWorkId + "/private-world-images/" + imageId + "/image").header("Authorization", token)).andExpect(status().isNotFound());
        var otherSetting = settings.saveAndFlush(WorldSetting.create(works.getReferenceById(otherWorkId), WorldSettingCategory.RACE, "엘프", "특징", "귀"));
        mvc.perform(patch("/api/v1/works/" + otherWorkId + "/world-settings/" + otherSetting.getId() + "/image").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"privateImageId\":\"" + imageId + "\",\"version\":0}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("개인 이미지 선택은 내용 버전과 분리되며 사용 중 삭제와 오래된 덮어쓰기를 거절한다")
    void selectionAndDeletion() throws Exception {
        createVault().andExpect(status().isOk()); upload(token, envelope).andExpect(status().isOk());
        select(imageId, 0).andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("PRIVATE"))
                .andExpect(jsonPath("$.data.vaultId").value(vaultId.toString())).andExpect(jsonPath("$.data.version").value(1));
        select(imageId, 1).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        select(null, 0).andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/works/" + workId + "/world-settings/" + settingId + "/identity")
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"LOCATION\",\"subjectName\":\"고블린 숲\",\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(delete(base + "/" + imageId).header("Authorization", token)).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/works/" + workId + "/world-settings/" + settingId).header("Authorization", token))
                .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.image.privateImageId").value(imageId.toString()))
                .andExpect(jsonPath("$.data.image.version").value(1));
        select(null, 1).andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("DEFAULT"));
        mvc.perform(delete(base + "/" + imageId).header("Authorization", token)).andExpect(status().isOk());
        verify(storage).purgePrefixes(List.of(PrivateWorldImagePaths.prefix(workId, imageId)));
        assertThat(images.findById(imageId)).isEmpty();
    }

    @Test
    @DisplayName("평문 파일과 누락된 보관함·한도 초과 업로드는 저장하지 않는다")
    void rejectPlaintextAndMissingVault() throws Exception {
        upload(token, envelope).andExpect(status().isBadRequest());
        createVault().andExpect(status().isOk());
        upload(token, "plain image".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        upload(token, new byte[8 * 1024 * 1024 + 33]).andExpect(status().isBadRequest());
        assertThat(images.countByWorkId(workId)).isZero();
        verifyNoInteractions(storage);
    }
    @Test
    @DisplayName("같은 작품의 개인 이미지를 세계관과 캐릭터에서 공유하고 모든 사용이 해제될 때만 삭제한다")
    void characterAndWorldSharePrivateImage() throws Exception {
        createVault().andExpect(status().isOk()); upload(token, envelope).andExpect(status().isOk());
        var character = characters.saveAndFlush(org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(
                works.getReferenceById(workId), "인물", null, null, null, null, null, null, null, null, null));
        String characterPath = "/api/v1/works/" + workId + "/characters/" + character.getId() + "/image";
        mvc.perform(patch(characterPath).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"privateImageId\":\"" + imageId + "\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("PRIVATE"));
        select(imageId, 0).andExpect(status().isOk());
        select(null, 1).andExpect(status().isOk());
        mvc.perform(delete(base + "/" + imageId).header("Authorization", token)).andExpect(status().isConflict());
        mvc.perform(patch(characterPath).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")).andExpect(status().isOk());
        mvc.perform(delete(base + "/" + imageId).header("Authorization", token)).andExpect(status().isOk());
    }

}
