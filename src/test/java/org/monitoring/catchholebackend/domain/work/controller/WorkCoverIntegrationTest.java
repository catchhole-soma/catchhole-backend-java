package org.monitoring.catchholebackend.domain.work.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.entity.WorkCoverImage;
import org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest;
import org.monitoring.catchholebackend.domain.work.repository.*;
import org.monitoring.catchholebackend.domain.work.service.WorkCoverService;
import org.monitoring.catchholebackend.domain.work.type.*;
import org.monitoring.catchholebackend.global.storage.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("작품 표지의 인증·원자 저장·파일 정리")
class WorkCoverIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired WorkCoverImageRepository images;
    @Autowired WorkPurgeRequestRepository purges;
    @Autowired WorkCoverService service;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @MockitoBean ObjectStorage storage;
    private UUID workId;
    private Long ownerId;
    private String token;
    private String strangerToken;
    private String base;

    @BeforeEach
    void prepare() {
        var owner = members.save(Member.register(UUID.randomUUID() + "@cover.invalid", "encoded", null, "표지 작가"));
        var stranger = members.save(Member.register(UUID.randomUUID() + "@cover.invalid", "encoded", null, "다른 작가"));
        ownerId = owner.getId();
        workId = works.save(Work.create(owner, "원래 작품", WorkGenre.FANTASY, "소개")).getId();
        token = "Bearer " + jwt.generateAccessToken(owner);
        strangerToken = "Bearer " + jwt.generateAccessToken(stranger);
        base = "/api/v1/works/" + workId + "/cover";
        when(storage.purgePrefixes(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ObjectStoragePurgeResult(1, 1, 0);
        });
        when(storage.putBytes(anyString(), any(), eq("image/png"))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("등록·조회·교체·삭제가 표지 버전만 바꾸고 이전 파일을 정리한다")
    void savesReplacesAndRemovesCover() throws Exception {
        save("REPLACE", 0, png(), token).andExpect(status().isOk()).andExpect(jsonPath("$.data.coverVersion").value(1));
        UUID first = coverId();
        when(storage.getBytes(WorkCoverImagePaths.key(workId, first))).thenReturn(png());
        mvc.perform(get(base + "/" + first).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        save("REPLACE", 1, png(), token).andExpect(status().isOk());
        UUID second = coverId();
        assertThat(second).isNotEqualTo(first);
        assertThat(images.existsById(first)).isFalse();
        mvc.perform(get(base + "/" + first).header("Authorization", token)).andExpect(status().isNotFound());
        save("REMOVE", 2, null, token).andExpect(status().isOk()).andExpect(jsonPath("$.data.coverImageId").doesNotExist());
        assertThat(coverId()).isNull();
        assertThat(images.countByWorkId(workId)).isZero();
        assertThat(works.findById(workId).orElseThrow().getRelationshipInputVersion()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_jobs where work_id = ?", Long.class, workId)).isZero();
        verify(storage).purgePrefixes(java.util.List.of(WorkCoverImagePaths.prefix(workId, first)));
        verify(storage).purgePrefixes(java.util.List.of(WorkCoverImagePaths.prefix(workId, second)));
    }

    @Test
    @DisplayName("타인과 비로그인 사용자의 조회·교체·삭제를 파일 접근 전에 거절한다")
    void rejectsUnauthorizedAccess() throws Exception {
        save("REPLACE", 0, png(), token).andExpect(status().isOk());
        UUID id = coverId();
        clearInvocations(storage);
        save("REPLACE", 1, png(), strangerToken).andExpect(status().isNotFound());
        save("REMOVE", 1, null, strangerToken).andExpect(status().isNotFound());
        mvc.perform(get(base + "/" + id).header("Authorization", strangerToken)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/" + id)).andExpect(status().isUnauthorized());
        save("REPLACE", 1, png(), "").andExpect(status().isUnauthorized());
        assertThat(coverId()).isEqualTo(id);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("업로드 실패는 기존 표지·작품 정보를 보존하고 새 파일만 정리한다")
    void preservesOldCoverOnUploadFailure() throws Exception {
        save("REPLACE", 0, png(), token).andExpect(status().isOk());
        UUID old = coverId();
        when(storage.putBytes(anyString(), any(), anyString())).thenThrow(new IllegalStateException("합성 저장소 장애"));
        save("REPLACE", 1, png(), token).andExpect(status().is5xxServerError());
        assertThat(coverId()).isEqualTo(old);
        assertThat(images.countByWorkId(workId)).isEqualTo(1);
        assertThat(works.findById(workId).orElseThrow().getCoverVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 확정 실패는 새 파일을 정리하고 기존 표지와 정보를 함께 롤백한다")
    void rollsBackCoverAndInfoWhenCommitFails() throws Exception {
        save("REPLACE", 0, png(), token).andExpect(status().isOk());
        UUID old = coverId();
        jdbc.execute("alter table works add constraint cover_test_title check (title <> '실패할 제목')");
        try {
            save("REPLACE", 1, png(), token, "실패할 제목").andExpect(status().is5xxServerError());
            assertThat(coverId()).isEqualTo(old);
            assertThat(works.findById(workId).orElseThrow().getTitle()).isEqualTo("원래 작품");
            assertThat(images.countByWorkId(workId)).isEqualTo(1);
        } finally { jdbc.execute("alter table works drop constraint cover_test_title"); }
    }

    @Test
    @DisplayName("파일 삭제 실패를 재시도해도 새 표지는 유지한다")
    void retriesFailedCleanup() throws Exception {
        save("REPLACE", 0, png(), token).andExpect(status().isOk());
        UUID old = coverId();
        when(storage.purgePrefixes(any())).thenReturn(new ObjectStoragePurgeResult(1, 0, 1));
        save("REPLACE", 1, png(), token).andExpect(status().isOk());
        UUID current = coverId();
        assertThat(images.findById(old).orElseThrow().getStatus()).isEqualTo(WorkCoverImageStatus.DELETING);
        when(storage.purgePrefixes(any())).thenReturn(new ObjectStoragePurgeResult(1, 1, 0));
        service.retryPendingCleanup();
        assertThat(images.existsById(old)).isFalse();
        assertThat(coverId()).isEqualTo(current);
    }

    @Test
    @DisplayName("오래된 버전과 업로드 도중 다른 저장을 감지해 현재 표지를 덮어쓰지 않는다")
    void refusesStaleAndConcurrentUpdates() throws Exception {
        save("REPLACE", 0, png(), token).andExpect(status().isOk());
        UUID old = coverId();
        save("REMOVE", 0, null, token).andExpect(status().isConflict());
        when(storage.putBytes(anyString(), any(), anyString())).thenAnswer(call -> {
            // 별도 연결이 업로드 도중 커밋한 변경. OSIV의 이전 작품 snapshot도 무효화해야 한다.
            jdbc.update("update works set cover_version = cover_version + 1 where id = ?", workId);
            return null;
        });
        save("REPLACE", 1, png(), token).andExpect(status().isConflict());
        assertThat(coverId()).isEqualTo(old);
        assertThat(images.countByWorkId(workId)).isEqualTo(1);
    }

    @Test
    @DisplayName("위장 파일·초과 크기·해상도·손상 파일을 서버에서 거절한다")
    void rejectsInvalidImages() throws Exception {
        save("REPLACE", 0, "<svg/>".getBytes(), token).andExpect(status().isBadRequest());
        save("REPLACE", 0, new byte[5 * 1024 * 1024 + 1], token).andExpect(status().isBadRequest());
        save("REPLACE", 0, encodedImage("png", 4097, 1), token).andExpect(status().isBadRequest());
        save("REPLACE", 0, java.util.Arrays.copyOf(png(), 40), token).andExpect(status().isBadRequest());
        save("REPLACE", 0, null, token).andExpect(status().isBadRequest());
        save("REMOVE", 0, png(), token).andExpect(status().isBadRequest());
        assertThat(images.countByWorkId(workId)).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("JPG·PNG·WebP의 실제 픽셀을 읽고 PNG로 정규화한다")
    void acceptsAllSupportedFormats() throws Exception {
        save("REPLACE", 0, encodedImage("jpeg", 40, 60), token).andExpect(status().isOk());
        try (var stream = getClass().getResourceAsStream("/work-cover/cover.webp")) {
            assertThat(stream).isNotNull();
            save("REPLACE", 1, stream.readAllBytes(), token).andExpect(status().isOk());
        }
        save("REPLACE", 2, png(), token).andExpect(status().isOk());
        var captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(storage, times(3)).putBytes(anyString(), captor.capture(), eq("image/png"));
        for (byte[] normalized : captor.getAllValues()) assertThat(ImageIO.read(new java.io.ByteArrayInputStream(normalized))).isNotNull();
    }

    @Test
    @DisplayName("예약 회수 뒤 늦게 끝난 업로드도 파일을 다시 정리한다")
    void cleansUploadAfterReservationWasReclaimed() throws Exception {
        when(storage.putBytes(anyString(), any(), anyString())).thenAnswer(call -> {
            jdbc.update("delete from work_cover_images where work_id = ?", workId);
            return null;
        });
        save("REPLACE", 0, png(), token).andExpect(status().isConflict());
        verify(storage).purgePrefixes(any());
        assertThat(coverId()).isNull();
    }

    @Test
    @DisplayName("진행 중 업로드가 작품 파기를 막고 중단된 예약은 회수한다")
    void waitsForUploadsAndReclaimsAbandonedFiles() {
        var pending = transaction.execute(status -> images.saveAndFlush(WorkCoverImage.reserve(works.getReferenceById(workId))));
        var purge = purges.save(WorkPurgeRequest.request(ownerId, workId, null));
        transaction.executeWithoutResult(status -> assertThat(purges.findReadyForUpdate(WorkPurgeStatus.REQUESTED,
                LocalDateTime.now(), PageRequest.of(0, 100))).extracting(WorkPurgeRequest::getId).doesNotContain(purge.getId()));
        jdbc.update("update work_cover_images set updated_at = ? where id = ?", LocalDateTime.now().minusHours(2), pending.getId());
        service.retryPendingCleanup();
        assertThat(images.existsById(pending.getId())).isFalse();
        transaction.executeWithoutResult(status -> assertThat(purges.findReadyForUpdate(WorkPurgeStatus.REQUESTED,
                LocalDateTime.now(), PageRequest.of(0, 100))).extracting(WorkPurgeRequest::getId).contains(purge.getId()));
    }

    private UUID coverId() { return works.findById(workId).orElseThrow().getCoverImageId(); }
    private byte[] png() throws Exception { return encodedImage("png", 40, 60); }
    private byte[] encodedImage(String format, int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
    private ResultActions save(String action, int version, byte[] bytes, String authorization) throws Exception {
        return save(action, version, bytes, authorization, "원래 작품");
    }
    private ResultActions save(String action, int version, byte[] bytes, String authorization, String title) throws Exception {
        String metadata = """
            {"work":{"title":"%s","genre":"판타지","description":"소개"},"action":"%s","expectedCoverVersion":%d}
            """.formatted(title, action, version);
        var request = multipart(HttpMethod.PATCH, base)
                .file(new MockMultipartFile("metadata", "metadata.json", "application/json", metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (bytes != null) request.file(new MockMultipartFile("image", "untrusted.jpg", "image/jpeg", bytes));
        return mvc.perform(request.header("Authorization", authorization));
    }
}
