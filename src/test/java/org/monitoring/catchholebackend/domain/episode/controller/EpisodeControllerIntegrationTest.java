package org.monitoring.catchholebackend.domain.episode.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
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
class EpisodeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private UploadFileRepository uploadFileRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ObjectStorage objectStorage;

    private Member member;
    private Member otherMember;
    private Work work;
    private Work otherWork;
    private String accessToken;

    @BeforeEach
    void setUp() {
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();

        member = memberRepository.save(Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        ));
        otherMember = memberRepository.save(Member.register(
                "other@example.com",
                "encoded-password",
                "01087654321",
                "다른 작가"
        ));
        work = workRepository.save(Work.create(member, "내 작품", "판타지", "내 설명"));
        otherWork = workRepository.save(Work.create(otherMember, "다른 작품", "무협", "다른 설명"));
        accessToken = jwtTokenProvider.generateAccessToken(member);

        when(objectStorage.putText(anyString(), anyString()))
                .thenAnswer(invocation -> new StoredObject(invocation.getArgument(0), "test-version"));
        when(objectStorage.putBytes(anyString(), any(byte[].class), any()))
                .thenAnswer(invocation -> new StoredObject(invocation.getArgument(0), "test-version"));
    }

    @Test
    void uploadSingleEpisodeCreatesEpisodeForAuthenticatedWork() throws Exception {
        MockMultipartFile data = jsonPart("""
                {
                  "uploadType": "SINGLE_EPISODE",
                  "episodeNo": 1,
                  "title": "튜토리얼"
                }
                """);
        MockMultipartFile episodeFile = textFile("episodeFiles", "episode-1.txt", "첫 문장입니다.");

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(data)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회차 원고가 업로드되었습니다."))
                .andExpect(jsonPath("$.data.uploadType").value("SINGLE_EPISODE"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.episodeCount").value(1))
                .andExpect(jsonPath("$.data.files", hasSize(1)))
                .andExpect(jsonPath("$.data.files[0].fileRole").value("EPISODE"))
                .andExpect(jsonPath("$.data.files[0].detectedEpisodeStartNo").value(1))
                .andExpect(jsonPath("$.data.files[0].detectedEpisodeEndNo").value(1))
                .andExpect(jsonPath("$.data.files[0].detectedEpisodeCount").value(1));
    }

    @Test
    void uploadMultiEpisodeSingleFileSplitsEpisodes() throws Exception {
        MockMultipartFile data = jsonPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE"
                }
                """);
        MockMultipartFile episodeFile = textFile(
                "episodeFiles",
                "episodes.txt",
                """
                        제 1화 시작
                        첫 번째 본문입니다.

                        제 2화 튜토리얼
                        두 번째 본문입니다.
                        """
        );

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(data)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploadType").value("MULTI_EPISODE_SINGLE_FILE"))
                .andExpect(jsonPath("$.data.episodeCount").value(2))
                .andExpect(jsonPath("$.data.files[0].detectedEpisodeStartNo").value(1))
                .andExpect(jsonPath("$.data.files[0].detectedEpisodeEndNo").value(2))
                .andExpect(jsonPath("$.data.files[0].detectedEpisodeCount").value(2));
    }

    @Test
    void uploadMultiEpisodeSingleFileRejectsDuplicateEpisodeNosInUploadFile() throws Exception {
        MockMultipartFile data = jsonPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE"
                }
                """);
        MockMultipartFile episodeFile = textFile(
                "episodeFiles",
                "episodes.txt",
                """
                        제 1화 시작
                        첫 번째 본문입니다.

                        제 2화 튜토리얼
                        두 번째 본문입니다.

                        제 1화 반복
                        세 번째 본문입니다.

                        제 2화 반복
                        네 번째 본문입니다.
                        """
        );

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(data)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("업로드 파일 안에서 중복된 회차: 1화, 2화.")))
                .andExpect(jsonPath("$.message").value(containsString("업로드 요청에 중복된 회차 번호가 있습니다.")))
                .andExpect(jsonPath("$.error.code").value("EPISODE_UPLOAD_DUPLICATED"))
                .andExpect(jsonPath("$.error.status").value(409))
                .andExpect(jsonPath("$.error.details", hasSize(0)));
    }

    @Test
    void uploadMultiEpisodeMultiFileRejectsExistingEpisodeNosInWork() throws Exception {
        episodeRepository.save(Episode.create(work, null, 2, "2화", "works/test/episodes/2.txt", "v1", "hash2", 20));
        episodeRepository.save(Episode.create(work, null, 4, "4화", "works/test/episodes/4.txt", "v1", "hash4", 40));
        MockMultipartFile data = jsonPart("""
                {
                  "uploadType": "MULTI_EPISODE_MULTI_FILE"
                }
                """);

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(data)
                        .file(textFile("episodeFiles", "episode-2.txt", "두 번째 본문입니다."))
                        .file(textFile("episodeFiles", "episode-3.txt", "세 번째 본문입니다."))
                        .file(textFile("episodeFiles", "episode-4.txt", "네 번째 본문입니다."))
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("이미 등록된 회차와 중복된 회차: 2화, 4화.")))
                .andExpect(jsonPath("$.message").value(containsString("업로드 요청에 중복된 회차 번호가 있습니다.")))
                .andExpect(jsonPath("$.error.code").value("EPISODE_UPLOAD_DUPLICATED"))
                .andExpect(jsonPath("$.error.status").value(409))
                .andExpect(jsonPath("$.error.details", hasSize(0)));
    }

    @Test
    void getEpisodesReturnsAuthenticatedWorkEpisodes() throws Exception {
        episodeRepository.save(Episode.create(work, null, 1, "1화", "works/test/episodes/1.txt", "v1", "hash1", 10));
        episodeRepository.save(Episode.create(work, null, 2, "2화", "works/test/episodes/2.txt", "v1", "hash2", 20));
        episodeRepository.save(Episode.create(otherWork, null, 3, "타인 3화", "works/other/episodes/3.txt", "v1", "hash3", 30));

        mockMvc.perform(get("/api/v1/works/{workId}/episodes", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].episodeNo").value(2))
                .andExpect(jsonPath("$.data[1].episodeNo").value(1));
    }

    @Test
    void getEpisodeReturnsContentForAuthenticatedWork() throws Exception {
        Episode episode = episodeRepository.save(Episode.create(
                work,
                null,
                1,
                "1화",
                "works/test/episodes/1.txt",
                "v1",
                "hash1",
                10
        ));
        when(objectStorage.getText(eq("works/test/episodes/1.txt"))).thenReturn("저장된 본문");

        mockMvc.perform(get("/api/v1/works/{workId}/episodes/{episodeId}", work.getId(), episode.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(episode.getId().toString()))
                .andExpect(jsonPath("$.data.episodeNo").value(1))
                .andExpect(jsonPath("$.data.title").value("1화"))
                .andExpect(jsonPath("$.data.content").value("저장된 본문"));
    }

    @Test
    void getEpisodesRejectsOtherMemberWork() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/episodes", otherWork.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    @Test
    void uploadEpisodeRejectsOtherMemberWork() throws Exception {
        MockMultipartFile data = jsonPart("""
                {
                  "uploadType": "SINGLE_EPISODE",
                  "episodeNo": 1
                }
                """);
        MockMultipartFile episodeFile = textFile("episodeFiles", "episode-1.txt", "첫 문장입니다.");

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", otherWork.getId())
                        .file(data)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    private MockMultipartFile jsonPart(String content) {
        return new MockMultipartFile(
                "data",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile textFile(String name, String filename, String content) {
        return new MockMultipartFile(
                name,
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
