package org.monitoring.catchholebackend.domain.upload.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingBookControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private AnalysisJobRepository analysisJobRepository;
    @Autowired private UploadBatchRepository uploadBatchRepository;
    @Autowired private UploadFileRepository uploadFileRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockitoBean private ObjectStorage objectStorage;

    private Work work;
    private String accessToken;

    @BeforeEach
    void setUp() {
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();

        Member member = memberRepository.save(Member.register(
                "setting-writer@example.com", "encoded-password", "01011112222", "설정 작가"));
        work = workRepository.save(Work.create(member, "설정집 작품", WorkGenre.FANTASY, null));
        accessToken = jwtTokenProvider.generateAccessToken(member);
        when(objectStorage.putBytes(anyString(), any(byte[].class), any()))
                .thenAnswer(invocation -> new StoredObject(invocation.getArgument(0), "test-version"));
        when(objectStorage.getBytes(anyString()))
                .thenReturn("첫 줄\n둘째 줄".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void settingBookCanBeUploadedListedReadSoftDeletedAndReused() throws Exception {
        upload("작품설정.txt").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("작품설정.txt"))
                .andExpect(jsonPath("$.data.id").isNotEmpty());

        String settingBookId = uploadFileRepository.findAll().getFirst().getId().toString();
        mockMvc.perform(get("/api/v1/works/{workId}/setting-books", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].originalFilename").value("작품설정.txt"));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-books/{settingBookId}", work.getId(), settingBookId)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("작품설정.txt"))
                .andExpect(jsonPath("$.data.content").value("첫 줄\n둘째 줄"));

        upload("작품설정.txt").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_SETTING_BOOK_DUPLICATED"));

        mockMvc.perform(delete("/api/v1/works/{workId}/setting-books/{settingBookId}", work.getId(), settingBookId)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/works/{workId}/setting-books", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        upload("작품설정.txt").andExpect(status().isOk());
    }

    @Test
    void settingBookUploadRejectsWhitespaceOnlyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", MediaType.TEXT_PLAIN_VALUE, " \n\t".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/works/{workId}/setting-books", work.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_FILE_EMPTY"));
    }

    private org.springframework.test.web.servlet.ResultActions upload(String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, MediaType.TEXT_PLAIN_VALUE, "설정 본문".getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/v1/works/{workId}/setting-books", work.getId())
                .file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer()));
    }

    private String bearer() {
        return "Bearer " + accessToken;
    }
}
