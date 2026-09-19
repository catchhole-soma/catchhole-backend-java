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
@DisplayName("암호화 개인 이미지의 소유권·선택·저장 경계")
class PrivateWorldImageIntegrationTest {
    @Autowired org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository characters;
    @Autowired MockMvc mvc;
    @Autowired org.monitoring.catchholebackend.domain.worldimage.repository.PrivateImageVaultRepository vaults;
    @Autowired org.monitoring.catchholebackend.domain.work.repository.WorkPurgeRequestRepository purges;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Autowired org.monitoring.catchholebackend.domain.worldimage.service.PrivateWorldImageServiceImpl service;
    private Long ownerId;
    private Long strangerId;
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
        ownerId = owner.getId(); strangerId = stranger.getId();
        var work = works.save(Work.create(owner, "개인 이미지", WorkGenre.FANTASY, "설정"));
        workId = work.getId();
        otherWorkId = works.save(Work.create(owner, "다른 작품", WorkGenre.FANTASY, "설정")).getId();
        settingId = settings.saveAndFlush(WorldSetting.create(work, WorldSettingCategory.RACE, "고블린", "특징", "초록색")).getId();
        token = "Bearer " + jwt.generateAccessToken(owner);
        strangerToken = "Bearer " + jwt.generateAccessToken(stranger);
        base = "/api/v1/works/" + workId + "/private-world-images";
        when(storage.purgePrefixes(any())).thenReturn(new ObjectStoragePurgeResult(2, 2, 0));
    }
    @org.junit.jupiter.api.AfterEach
    void cleanupFixture() {
        transaction.executeWithoutResult(status -> {
            for (UUID id : List.of(workId, otherWorkId)) {
                jdbc.update("DELETE FROM world_setting_images WHERE world_setting_id IN (SELECT id FROM world_settings WHERE work_id=?)", id);
                jdbc.update("DELETE FROM character_images WHERE character_id IN (SELECT id FROM characters WHERE work_id=?)", id);
                jdbc.update("DELETE FROM private_world_images WHERE work_id=?", id);
                jdbc.update("DELETE FROM world_settings WHERE work_id=?", id);
                jdbc.update("DELETE FROM characters WHERE work_id=?", id);
                jdbc.update("DELETE FROM works WHERE id=?", id);
            }
            jdbc.update("DELETE FROM work_purge_requests WHERE member_id=?", ownerId);
            jdbc.update("DELETE FROM private_image_vaults WHERE member_id=?", ownerId);
            members.deleteById(ownerId); members.deleteById(strangerId);
        });
    }
    @Test
    @DisplayName("예전 방식 업로드의 암호화 정보 누락은 저장 전에 입력 오류로 거절한다")
    void missingLegacyMetadataReturnsBadRequest() throws Exception {
        mvc.perform(multipart(base)
                .file(new MockMultipartFile("metadata", "metadata.json", "application/json",
                        ("{\"id\":\"" + imageId + "\",\"vaultId\":\"" + vaultId + "\"}").getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("image", "image.enc", "application/octet-stream", envelope))
                .file(new MockMultipartFile("thumbnail", "thumbnail.enc", "application/octet-stream", envelope))
                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        assertThat(images.existsById(imageId)).isFalse();
        verifyNoInteractions(storage);
    }

    private UUID attemptId() { return images.findById(imageId).orElseThrow().getStorageAttemptId(); }
    private String encoded() { return Base64.getEncoder().encodeToString(envelope); }
    private ResultActions createVault() throws Exception {
        return mvc.perform(post("/api/v1/private-image-vaults").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
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

    private byte[] png() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    private ResultActions uploadAccountImage(String auth, byte[] bytes) throws Exception {
        return mvc.perform(multipart(base).file(new MockMultipartFile("metadata", "metadata.json", "application/json",
                        ("{\"id\":\"" + imageId + "\",\"name\":\"내 그림.png\"}").getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("image", "image.png", "image/png", bytes))
                .file(new MockMultipartFile("thumbnail", "thumbnail.png", "image/png", png()))
                .header("Authorization", auth));
    }

    @Test
    @DisplayName("별도 보관함이나 코드 없이 이미지를 저장하고 로그인 소유권으로 조회·선택한다")
    void accountImageRoundtripWithoutVault() throws Exception {
        byte[] png = png();
        uploadAccountImage(token, png).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vaultId").doesNotExist())
                .andExpect(jsonPath("$.data.name").value("내 그림.png"));
        assertThat(vaults.findByMemberId(ownerId)).isEmpty();
        assertThat(images.findById(imageId).orElseThrow().getVault()).isNull();
        when(storage.getBytes(PrivateWorldImagePaths.key(workId, imageId, attemptId(), false))).thenReturn(png);
        mvc.perform(get(base + "/" + imageId + "/image").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(content().bytes(png)).andExpect(header().string("Cache-Control", "no-store, private"));
        mvc.perform(get(base + "/" + imageId + "/image").header("Authorization", strangerToken)).andExpect(status().isNotFound());
        select(imageId, 0).andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("PRIVATE"));
        uploadAccountImage(token, png).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("정상 PNG는 5MiB까지 허용하고 1바이트라도 초과하면 저장하지 않는다")
    void accountImageFiveMiBBoundary() throws Exception {
        byte[] valid = png();
        uploadAccountImage(token, java.util.Arrays.copyOf(valid, 5 * 1024 * 1024 + 1))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(storage);
        uploadAccountImage(token, java.util.Arrays.copyOf(valid, 5 * 1024 * 1024))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("이전 암호화 업로드도 5MiB에 암호화 부가정보를 더한 크기로 제한한다")
    void legacyUploadUsesSameFiveMiBLimit() throws Exception {
        createVault().andExpect(status().isOk());
        upload(token, java.util.Arrays.copyOf(envelope, 5 * 1024 * 1024 + 33))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(storage);
        upload(token, java.util.Arrays.copyOf(envelope, 5 * 1024 * 1024 + 32))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("새 업로드도 타인 작품·위장 파일·잘못된 크기를 거절한다")
    void accountImageRejectsForeignOwnerAndInvalidBytes() throws Exception {
        uploadAccountImage(strangerToken, png()).andExpect(status().isNotFound());
        uploadAccountImage(token, envelope).andExpect(status().isBadRequest());
        uploadAccountImage(token, "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        uploadAccountImage(token, new byte[5 * 1024 * 1024 + 1]).andExpect(status().isBadRequest());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("키 없이 암호문만 저장하고 인증된 소유자에게 캐시 금지로 반환한다")
    void ciphertextRoundtrip() throws Exception {
        createVault().andExpect(status().isOk()).andExpect(jsonPath("$.data.keyCheck").value(encoded()));
        createVault().andExpect(status().isOk()); // Same request is safe to retry.
        mvc.perform(post("/api/v1/private-image-vaults").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + UUID.randomUUID() + "\",\"keyCheck\":\"" + encoded() + "\"}"))
                .andExpect(status().isConflict());
        upload(token, envelope).andExpect(status().isOk()).andExpect(jsonPath("$.data.encryptedMetadata").value(encoded()))
                .andExpect(jsonPath("$.data.filename").doesNotExist()).andExpect(jsonPath("$.data.key").doesNotExist());
        verify(storage).putBytes(PrivateWorldImagePaths.key(workId, imageId, attemptId(), false), envelope, "application/octet-stream");
        verify(storage).putBytes(PrivateWorldImagePaths.key(workId, imageId, attemptId(), true), envelope, "application/octet-stream");
        when(storage.getBytes(PrivateWorldImagePaths.key(workId, imageId, attemptId(), false))).thenReturn(envelope);
        mvc.perform(get(base + "/" + imageId + "/image").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(content().bytes(envelope)).andExpect(header().string("Cache-Control", "no-store, private"));
        upload(token, envelope).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("인증 없음·타인·다른 작품은 조회·업로드·삭제·선택할 수 없다")
    void ownershipBoundaries() throws Exception {
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/private-image-vaults")).andExpect(status().isUnauthorized());
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
        transaction.executeWithoutResult(status -> works.getReferenceById(workId).updateInfo("개인 이미지", WorkGenre.SPORTS, "장르 변경"));
        mvc.perform(get("/api/v1/works/" + workId + "/world-settings/" + settingId).header("Authorization", token))
                .andExpect(jsonPath("$.data.image.source").value("PRIVATE"))
                .andExpect(jsonPath("$.data.image.privateImageId").value(imageId.toString()))
                .andExpect(jsonPath("$.data.image.version").value(1));
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
        UUID uploadedAttempt = attemptId();
        mvc.perform(delete(base + "/" + imageId).header("Authorization", token)).andExpect(status().isOk());
        verify(storage).purgePrefixes(List.of(PrivateWorldImagePaths.prefix(workId, imageId, uploadedAttempt)));
        assertThat(images.findById(imageId)).isEmpty();
    }

    @Test
    @DisplayName("평문 파일과 누락된 보관함·한도 초과 업로드는 저장하지 않는다")
    void rejectPlaintextAndMissingVault() throws Exception {
        upload(token, envelope).andExpect(status().isBadRequest());
        createVault().andExpect(status().isOk());
        upload(token, "plain image".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        upload(token, new byte[5 * 1024 * 1024 + 33]).andExpect(status().isBadRequest());
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

    @Test
    @DisplayName("저장소 읽기·쓰기·삭제는 DB 트랜잭션을 점유하지 않는다")
    void storageCallsOutsideTransactions() throws Exception {
        createVault().andExpect(status().isOk());
        when(storage.putBytes(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // 업로드 예약은 이미 커밋됐지만 목록·선택에는 노출되지 않는다.
            assertThat(images.findById(imageId).orElseThrow().getStatus().name()).isEqualTo("UPLOADING");
            mvc.perform(get(base).header("Authorization", token)).andExpect(jsonPath("$.data.totalElements").value(0));
            select(imageId, 0).andExpect(status().isNotFound());
            return new org.monitoring.catchholebackend.global.storage.StoredObject(invocation.getArgument(0), null);
        });
        when(storage.getBytes(any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return envelope;
        });
        when(storage.purgePrefixes(any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(images.findById(imageId).orElseThrow().getStatus().name()).isEqualTo("DELETING");
            return new ObjectStoragePurgeResult(2, 2, 0);
        });
        upload(token, envelope).andExpect(status().isOk());
        mvc.perform(get(base + "/" + imageId + "/image").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(delete(base + "/" + imageId).header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("삭제 실패는 숨겨진 삭제 대기로 남고 재시도 후 정리된다")
    void failedDeletionIsRecoverable() throws Exception {
        createVault().andExpect(status().isOk()); upload(token, envelope).andExpect(status().isOk());
        when(storage.purgePrefixes(any())).thenReturn(new ObjectStoragePurgeResult(2, 1, 1));
        mvc.perform(delete(base + "/" + imageId).header("Authorization", token)).andExpect(status().isInternalServerError());
        assertThat(images.findById(imageId).orElseThrow().getStatus().name()).isEqualTo("DELETING");
        mvc.perform(get(base).header("Authorization", token)).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get(base + "/" + imageId + "/image").header("Authorization", token)).andExpect(status().isNotFound());
        select(imageId, 0).andExpect(status().isNotFound());
        when(storage.purgePrefixes(any())).thenReturn(new ObjectStoragePurgeResult(0, 0, 0));
        service.retryPendingCleanup();
        assertThat(images.findById(imageId)).isEmpty();
    }

    @Test
    @DisplayName("정리 실패 20건 뒤의 삭제 대기와 오래된 업로드도 다음 배치에서 처리한다")
    void failedCleanupBatchDoesNotStarveLaterImages() throws Exception {
        createVault().andExpect(status().isOk());
        var failedPrefixes = new java.util.HashMap<String, Integer>();
        var ids = new java.util.ArrayList<UUID>();
        var old = java.time.LocalDateTime.now().minusHours(3);
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < 23; i++) {
                UUID id = UUID.randomUUID();
                UUID attempt = UUID.randomUUID();
                var image = org.monitoring.catchholebackend.domain.worldimage.entity.PrivateWorldImage.create(id,
                        works.getReferenceById(workId), vaults.getReferenceById(vaultId), encoded(), envelope.length, envelope.length);
                image.reserveUpload(attempt);
                if (i < 21) image.requestDeletion();
                images.saveAndFlush(image);
                // 20건의 실패 뒤에 삭제 1건, 오래된 업로드 1건, 아직 진행 중인 업로드 1건을 배치한다.
                if (i < 22) jdbc.update("UPDATE private_world_images SET updated_at=? WHERE id=?", old.plusSeconds(i), id);
                if (i < 20) failedPrefixes.put(PrivateWorldImagePaths.prefix(workId, id, attempt), i);
                ids.add(id);
            }
        });
        var visited = new java.util.ArrayList<String>();
        when(storage.purgePrefixes(any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> prefixes = invocation.getArgument(0);
            String prefix = prefixes.getFirst();
            visited.add(prefix);
            Integer failedIndex = failedPrefixes.get(prefix);
            if (failedIndex == null) return new ObjectStoragePurgeResult(1, 1, 0);
            if (failedIndex % 2 == 0) throw new IllegalStateException("저장소 장애 재현");
            return new ObjectStoragePurgeResult(1, 0, 1);
        });

        service.retryPendingCleanup();
        assertThat(visited).hasSize(20).containsOnlyOnceElementsOf(failedPrefixes.keySet());
        for (UUID id : ids.subList(0, 20)) {
            assertThat(images.findById(id).orElseThrow().getCleanupAttemptedAt()).isAfter(old.plusHours(2));
        }
        assertThat(images.countByWorkId(workId)).isEqualTo(23);

        visited.clear();
        service.retryPendingCleanup();
        assertThat(visited).hasSize(20);
        assertThat(images.findById(ids.get(20))).isEmpty();
        assertThat(images.findById(ids.get(21))).isEmpty();
        assertThat(images.findById(ids.get(22)).orElseThrow().getStatus().name()).isEqualTo("UPLOADING");
        assertThat(images.countByWorkId(workId)).isEqualTo(21);
    }

    @Test
    @DisplayName("지연된 실패 업로드 정리는 같은 ID로 재시도한 파일과 행을 지우지 않는다")
    void lateCleanupCannotDeleteRetriedUpload() throws Exception {
        createVault().andExpect(status().isOk());
        var files = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(storage.putBytes(any(), any(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.endsWith("thumbnail.enc") && fail.getAndSet(false)) throw new IllegalStateException("저장소 장애 재현");
            files.put(key, invocation.getArgument(1));
            return new org.monitoring.catchholebackend.global.storage.StoredObject(key, null);
        });
        when(storage.purgePrefixes(any())).thenReturn(new ObjectStoragePurgeResult(1, 0, 1));
        upload(token, envelope).andExpect(status().isInternalServerError());
        UUID oldAttempt = attemptId();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var delayFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(storage.purgePrefixes(any())).thenAnswer(invocation -> {
            if (delayFirst.getAndSet(false)) {
                started.countDown();
                assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            List<String> prefixes = invocation.getArgument(0);
            files.keySet().removeIf(key -> prefixes.stream().anyMatch(key::startsWith));
            return new ObjectStoragePurgeResult(1, 1, 0);
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var lateCleanup = executor.submit(service::retryPendingCleanup);
            try {
                assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                // 다른 정리 요청이 먼저 끝나면 같은 이미지 ID로 다시 업로드할 수 있다.
                mvc.perform(delete(base + "/" + imageId).header("Authorization", token)).andExpect(status().isOk());
                upload(token, envelope).andExpect(status().isOk());
                UUID nextAttempt = attemptId();
                assertThat(nextAttempt).isNotEqualTo(oldAttempt);
                release.countDown();
                lateCleanup.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(attemptId()).isEqualTo(nextAttempt);
                assertThat(files).containsOnlyKeys(PrivateWorldImagePaths.key(workId, imageId, nextAttempt, false),
                        PrivateWorldImagePaths.key(workId, imageId, nextAttempt, true));
            } finally { release.countDown(); }
        }
    }

    @Test
    @DisplayName("잘못된 페이지와 이미지 선택 조합은 입력 오류로 응답한다")
    void invalidRequestsReturnClientErrors() throws Exception {
        for (String value : List.of("0", "51")) {
            mvc.perform(get(base).header("Authorization", token).param("size", value)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(base).header("Authorization", token).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/works/" + workId + "/world-settings/" + settingId + "/image")
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"useAutomatic\":true,\"catalogId\":\"race-goblin\",\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("WORLD_IMAGE_SELECTION_CONFLICT"));
    }
    @Test
    @DisplayName("V65 이전 개인 이미지는 기존 저장소 경로로 계속 조회한다")
    void legacyImageKeepsItsStoragePath() throws Exception {
        createVault().andExpect(status().isOk());
        transaction.executeWithoutResult(status -> images.saveAndFlush(
                org.monitoring.catchholebackend.domain.worldimage.entity.PrivateWorldImage.create(imageId,
                        works.getReferenceById(workId), vaults.getReferenceById(vaultId), encoded(), envelope.length, envelope.length)));
        assertThat(attemptId()).isNull();
        when(storage.getBytes(PrivateWorldImagePaths.key(workId, imageId, false))).thenReturn(envelope);
        mvc.perform(get(base + "/" + imageId + "/image").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().bytes(envelope));
    }

    @Test
    @DisplayName("작품 전체 파기는 진행 중인 이미지 업로드가 마무리된 뒤에만 시작한다")
    void workPurgeWaitsForImageWriter() throws Exception {
        createVault().andExpect(status().isOk());
        var request = transaction.execute(status -> {
            var image = org.monitoring.catchholebackend.domain.worldimage.entity.PrivateWorldImage.create(imageId,
                    works.getReferenceById(workId), vaults.getReferenceById(vaultId), encoded(), envelope.length, envelope.length);
            image.reserveUpload(UUID.randomUUID());
            images.saveAndFlush(image);
            return purges.saveAndFlush(org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest.request(ownerId, workId, null));
        });
        transaction.executeWithoutResult(status -> {
            assertThat(purges.findReadyForUpdate(org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus.REQUESTED,
                    java.time.LocalDateTime.now(), org.springframework.data.domain.PageRequest.of(0, 100)))
                    .extracting(org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest::getId).doesNotContain(request.getId());
            images.findById(imageId).orElseThrow().completeUpload();
            images.flush();
            assertThat(purges.findReadyForUpdate(org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus.REQUESTED,
                    java.time.LocalDateTime.now(), org.springframework.data.domain.PageRequest.of(0, 100)))
                    .extracting(org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest::getId).contains(request.getId());
        });
    }
}
