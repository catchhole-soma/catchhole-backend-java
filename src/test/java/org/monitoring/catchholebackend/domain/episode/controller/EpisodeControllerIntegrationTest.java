package org.monitoring.catchholebackend.domain.episode.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
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
import org.springframework.test.web.servlet.ResultActions;

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
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private UploadFileRepository uploadFileRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private ObjectStorage objectStorage;

    private Member member;
    private Member otherMember;
    private Work work;
    private Work otherWork;
    private String accessToken;

    @BeforeEach
    void setUp() {
        analysisJobRepository.deleteAll();
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
        work = workRepository.save(Work.create(member, "내 작품", WorkGenre.FANTASY, "내 설명"));
        otherWork = workRepository.save(Work.create(otherMember, "다른 작품", WorkGenre.MARTIAL_ARTS, "다른 설명"));
        accessToken = jwtTokenProvider.generateAccessToken(member);

        when(objectStorage.putText(anyString(), anyString()))
                .thenAnswer(invocation -> new StoredObject(invocation.getArgument(0), "test-version"));
        when(objectStorage.putBytes(anyString(), any(byte[].class), any()))
                .thenAnswer(invocation -> new StoredObject(invocation.getArgument(0), "test-version"));
    }

    @Test
    void uploadSingleEpisodeCreatesEpisodeForAuthenticatedWork() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "SINGLE_EPISODE",
                  "singleEpisodeNo": 1,
                  "singleEpisodeTitle": "튜토리얼"
                }
                """);
        MockMultipartFile episodeFile = textFile("episodeFiles", "episode-1.txt", "첫 문장입니다.");

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(metadata)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회차 원고가 업로드되었습니다."))
                .andExpect(jsonPath("$.data.uploadType").value("SINGLE_EPISODE"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.episodeCount").value(1))
                .andExpect(jsonPath("$.data.createdEpisodes", hasSize(1)))
                .andExpect(jsonPath("$.data.createdEpisodes[0].episodeNo").value(1))
                .andExpect(jsonPath("$.data.files", hasSize(1)))
                .andExpect(jsonPath("$.data.files[0].fileRole").value("EPISODE"))
                .andExpect(jsonPath("$.data.files[0].episodeStartNo").value(1))
                .andExpect(jsonPath("$.data.files[0].episodeEndNo").value(1))
                .andExpect(jsonPath("$.data.files[0].episodeCount").value(1));
    }

    @Test
    @DisplayName("명시적으로 첨부한 빈 설정집 파일을 누락된 part로 취급하지 않고 거절한다")
    void uploadEpisodesRejectsExplicitEmptySettingBook() throws Exception {
        MockMultipartFile emptySettingBook = new MockMultipartFile(
                "settingBookFile",
                "empty-setting.txt",
                MediaType.TEXT_PLAIN_VALUE,
                new byte[0]
        );

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(metadataPart("""
                                {
                                  "uploadType": "SINGLE_EPISODE",
                                  "singleEpisodeNo": 1
                                }
                                """))
                        .file(textFile("episodeFiles", "episode-1.txt", "첫 문장입니다."))
                        .file(emptySettingBook)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_FILE_EMPTY"));

        assertThat(episodeRepository.count()).isZero();
        assertThat(uploadBatchRepository.count()).isZero();
    }

    @Test
    void uploadMultiEpisodeSingleFileSplitsEpisodes() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE",
                  "episodeConfirmations": [
                    {"detectionOrder": 0, "episodeNo": 1, "title": "시작"},
                    {"detectionOrder": 1, "episodeNo": 2, "title": "튜토리얼"}
                  ]
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
                        .file(metadata)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploadType").value("MULTI_EPISODE_SINGLE_FILE"))
                .andExpect(jsonPath("$.data.episodeCount").value(2))
                .andExpect(jsonPath("$.data.files[0].episodeStartNo").value(1))
                .andExpect(jsonPath("$.data.files[0].episodeEndNo").value(2))
                .andExpect(jsonPath("$.data.files[0].episodeCount").value(2));
    }

    @Test
    void detectEpisodesReturnsDetectedContractWithoutTemporaryId() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE"
                }
                """);
        MockMultipartFile episodeFile = textFile(
                "episodeFiles",
                "episodes.txt",
                """
                        제 1화 감지 제목
                        첫 번째 본문입니다.

                        제 2화 다음 제목
                        두 번째 본문입니다.
                        """
        );

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes/detect", work.getId())
                        .file(metadata)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.episodeCount").value(2))
                .andExpect(jsonPath("$.data.detectedEpisodes", hasSize(2)))
                .andExpect(jsonPath("$.data.detectedEpisodes[0].detectionOrder").value(0))
                .andExpect(jsonPath("$.data.detectedEpisodes[0].sourceFileIndex").value(0))
                .andExpect(jsonPath("$.data.detectedEpisodes[0].episodeNo").value(1))
                .andExpect(jsonPath("$.data.detectedEpisodes[0].title").value("감지 제목"))
                .andExpect(jsonPath("$.data.detectedEpisodes[0].sourceHeading").value("제 1화 감지 제목"))
                .andExpect(jsonPath("$.data.detectedEpisodes[0].tempId").doesNotExist())
                .andExpect(jsonPath("$.data.episodes").doesNotExist());
    }

    @Test
    void uploadEpisodesAppliesConfirmedNumbersAndTitles() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE",
                  "episodeConfirmations": [
                    {
                      "detectionOrder": 0,
                      "episodeNo": 10,
                      "title": "수정한 첫 제목"
                    },
                    {
                      "detectionOrder": 1,
                      "episodeNo": 11,
                      "title": "수정한 둘째 제목"
                    }
                  ]
                }
                """);
        MockMultipartFile episodeFile = textFile(
                "episodeFiles",
                "episodes.txt",
                """
                        제 1화 원래 첫 제목
                        첫 번째 본문입니다.

                        제 2화 원래 둘째 제목
                        두 번째 본문입니다.
                        """
        );

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(metadata)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdEpisodes", hasSize(2)))
                .andExpect(jsonPath("$.data.createdEpisodes[0].episodeNo").value(10))
                .andExpect(jsonPath("$.data.createdEpisodes[0].title").value("수정한 첫 제목"))
                .andExpect(jsonPath("$.data.createdEpisodes[1].episodeNo").value(11))
                .andExpect(jsonPath("$.data.createdEpisodes[1].title").value("수정한 둘째 제목"));

        assertThat(episodeRepository.findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(
                work.getId(),
                EpisodeStatus.ARCHIVED
        ))
                .extracting(Episode::getEpisodeNo)
                .containsExactly(11, 10);
    }

    @Test
    @DisplayName("확정 목록의 detectionOrder가 감지 순서와 다르면 업로드를 거절한다")
    void uploadEpisodesRejectsMismatchedDetectionOrder() throws Exception {
        uploadTwoDetectedEpisodesWithConfirmations("""
                [
                  {"detectionOrder": 0, "episodeNo": 10, "title": "첫 제목"},
                  {"detectionOrder": 2, "episodeNo": 11, "title": "둘째 제목"}
                ]
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EPISODE_CONFIRMATION_INVALID"));
    }

    @Test
    @DisplayName("감지 회차 수와 확정 목록 수가 다르면 업로드를 거절한다")
    void uploadEpisodesRejectsConfirmationCountMismatch() throws Exception {
        uploadTwoDetectedEpisodesWithConfirmations("""
                [
                  {"detectionOrder": 0, "episodeNo": 10, "title": "첫 제목"}
                ]
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EPISODE_CONFIRMATION_INVALID"));
    }

    @Test
    @DisplayName("확정 회차 번호가 원문 순서대로 증가하지 않으면 업로드를 거절한다")
    void uploadEpisodesRejectsDescendingConfirmedEpisodeNumbers() throws Exception {
        uploadTwoDetectedEpisodesWithConfirmations("""
                [
                  {"detectionOrder": 0, "episodeNo": 11, "title": "첫 제목"},
                  {"detectionOrder": 1, "episodeNo": 10, "title": "둘째 제목"}
                ]
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EPISODE_CONFIRMATION_INVALID"));
    }

    @Test
    @DisplayName("다회차 업로드에서 확정 목록을 생략하면 업로드를 거절한다")
    void uploadEpisodesRejectsMissingEpisodeConfirmationsForMultiUpload() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE"
                }
                """);

        performTwoEpisodeUpload(metadata)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EPISODE_CONFIRMATION_REQUIRED"));
    }

    @Test
    @DisplayName("다회차 업로드에서 확정 목록이 비어 있으면 업로드를 거절한다")
    void uploadEpisodesRejectsEmptyEpisodeConfirmationsForMultiUpload() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE",
                  "episodeConfirmations": []
                }
                """);

        performTwoEpisodeUpload(metadata)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EPISODE_CONFIRMATION_REQUIRED"));
    }

    @Test
    @DisplayName("단일 회차 업로드에서는 확정 목록을 사용할 수 없다")
    void uploadSingleEpisodeRejectsEpisodeConfirmations() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "SINGLE_EPISODE",
                  "singleEpisodeNo": 1,
                  "episodeConfirmations": [
                    {"detectionOrder": 0, "episodeNo": 1, "title": "확정 제목"}
                  ]
                }
                """);

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(metadata)
                        .file(textFile("episodeFiles", "episode-1.txt", "첫 번째 본문입니다."))
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EPISODE_CONFIRMATION_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("단일 회차 업로드는 파일명에서 번호를 감지할 수 있어도 명시적인 회차 번호가 필요하다")
    void uploadSingleEpisodeRequiresExplicitEpisodeNo() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "SINGLE_EPISODE"
                }
                """);

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(metadata)
                        .file(textFile("episodeFiles", "episode-1.txt", "첫 번째 본문입니다."))
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EPISODE_NO_REQUIRED"));
    }

    @Test
    @DisplayName("확정 목록의 null 항목은 요청값 검증 실패로 응답하고 500 오류를 내지 않는다")
    void uploadEpisodesRejectsNullEpisodeConfirmationElement() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE",
                  "episodeConfirmations": [
                    null,
                    {"detectionOrder": 1, "episodeNo": 2, "title": "둘째 제목"}
                  ]
                }
                """);

        performTwoEpisodeUpload(metadata)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void uploadMultiEpisodeSingleFileRejectsEpisodeNosThatAreNotStrictlyAscending() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE",
                  "episodeConfirmations": [
                    {"detectionOrder": 0, "episodeNo": 1},
                    {"detectionOrder": 1, "episodeNo": 2},
                    {"detectionOrder": 2, "episodeNo": 3},
                    {"detectionOrder": 3, "episodeNo": 4}
                  ]
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
                        .file(metadata)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("파일 안의 회차 번호는 중복 없이 오름차순이어야 합니다."))
                .andExpect(jsonPath("$.error.code").value("UPLOAD_EPISODE_ORDER_INVALID"))
                .andExpect(jsonPath("$.error.status").value(400))
                .andExpect(jsonPath("$.error.details", hasSize(0)));
    }

    @Test
    void uploadMultiEpisodeMultiFileRejectsExistingEpisodeNosInWork() throws Exception {
        episodeRepository.save(Episode.create(work, null, 2, "2화", "works/test/episodes/2.txt", "v1", "hash2", 20));
        episodeRepository.save(Episode.create(work, null, 4, "4화", "works/test/episodes/4.txt", "v1", "hash4", 40));
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_MULTI_FILE",
                  "episodeConfirmations": [
                    {"detectionOrder": 0, "episodeNo": 2},
                    {"detectionOrder": 1, "episodeNo": 3},
                    {"detectionOrder": 2, "episodeNo": 4}
                  ]
                }
                """);

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                        .file(metadata)
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
    @DisplayName("분석된 회차의 새 활성 작업은 완료 상태보다 우선하고 형제 회차에는 전파되지 않는다")
    void targetedAnalysisStatusDoesNotLeakToSiblingEpisodeInSameBatch() throws Exception {
        UploadBatch batch = uploadBatchRepository.save(UploadBatch.create(
                work, member, UploadType.MULTI_EPISODE_SINGLE_FILE, UploadSourceType.FILE));
        UploadFile sourceFile = uploadFileRepository.save(UploadFile.create(
                batch, UploadFileRole.EPISODE, "episodes.txt", MediaType.TEXT_PLAIN_VALUE,
                "s3://episodes.txt", 100));
        sourceFile.markEpisodesParsed(1, 2, 2);
        Episode firstEpisode = episodeRepository.save(Episode.create(
                work, sourceFile.getId(), 1, "첫 회차", "works/1.txt", "v1", "hash-1", 10));
        Episode secondEpisode = episodeRepository.save(Episode.create(
                work, sourceFile.getId(), 2, "둘째 회차", "works/2.txt", "v1", "hash-2", 10));
        firstEpisode.markAnalyzed();
        episodeRepository.save(firstEpisode);
        analysisJobRepository.save(AnalysisJob.create(
                work, batch, firstEpisode, AnalysisJobType.EPISODE_VALIDATION));

        mockMvc.perform(get("/api/v1/works/{workId}/episodes", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(secondEpisode.getId().toString()))
                .andExpect(jsonPath("$.data[0].analysisStatus").value("REANALYSIS_REQUIRED"))
                .andExpect(jsonPath("$.data[1].id").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data[1].analysisStatus").value("IN_PROGRESS"));

        mockMvc.perform(delete("/api/v1/works/{workId}/episodes/{episodeId}", work.getId(), secondEpisode.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("활성 분석 중에는 원문 수정을 막지만 제목 수정과 원문 변경 시각은 유지한다")
    void activeAnalysisBlocksContentEditButAllowsTitleEdit() throws Exception {
        UploadBatch batch = uploadBatchRepository.save(UploadBatch.create(
                work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE));
        UploadFile sourceFile = uploadFileRepository.save(UploadFile.create(
                batch, UploadFileRole.EPISODE, "episode.txt", MediaType.TEXT_PLAIN_VALUE,
                "s3://episode.txt", 100));
        sourceFile.markEpisodesParsed(1, 1, 1);
        Episode episode = episodeRepository.save(Episode.create(
                work, sourceFile.getId(), 1, "기존 제목", "works/original.txt", "v1", "hash", 10));
        var originalContentUpdatedAt =
                episodeRepository.findById(episode.getId()).orElseThrow().getContentUpdatedAt();
        analysisJobRepository.save(AnalysisJob.create(
                work, batch, episode, AnalysisJobType.EPISODE_VALIDATION));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/episodes/{episodeId}/title",
                                work.getId(),
                                episode.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "수정한 제목"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("수정한 제목"));

        Episode titleUpdated = episodeRepository.findById(episode.getId()).orElseThrow();
        assertThat(titleUpdated.getContentUpdatedAt()).isEqualTo(originalContentUpdatedAt);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/episodes/{episodeId}",
                                work.getId(),
                                episode.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "episodeNo": 1,
                                  "title": "수정한 제목",
                                  "content": "분석 중 바꾸려는 원문"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EPISODE_ANALYSIS_IN_PROGRESS"));

        verify(objectStorage, never()).putText(anyString(), anyString());
    }

    @Test
    @DisplayName("원문 수정은 contentUpdatedAt을 갱신한다")
    void contentEditUpdatesContentTimestamp() throws Exception {
        Episode episode = episodeRepository.save(Episode.create(
                work, null, 1, "기존 제목", "works/original.txt", "v1", "hash", 10));
        var originalContentUpdatedAt =
                episodeRepository.findById(episode.getId()).orElseThrow().getContentUpdatedAt();
        Thread.sleep(10);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/episodes/{episodeId}",
                                work.getId(),
                                episode.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "episodeNo": 1,
                                  "title": "수정한 제목",
                                  "content": "새롭게 수정한 원문"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentUpdatedAt").isNotEmpty());

        Episode updated = episodeRepository.findById(episode.getId()).orElseThrow();
        assertThat(updated.getContentUpdatedAt()).isAfter(originalContentUpdatedAt);
        verify(objectStorage, never()).delete("works/original.txt");
    }

    @Test
    @DisplayName("보관한 회차 번호를 재사용해도 S3 원문 key를 덮어쓰지 않는다")
    void reusedArchivedEpisodeNumberGetsUniqueReadableStorageKey() throws Exception {
        uploadSingleEpisode(10, "old-10.txt", "기존 10화 원문")
                .andExpect(status().isOk());
        Episode archivedEpisode = episodeRepository
                .findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(work.getId(), EpisodeStatus.ARCHIVED)
                .getFirst();

        mockMvc.perform(delete(
                                "/api/v1/works/{workId}/episodes/{episodeId}",
                                work.getId(),
                                archivedEpisode.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk());

        uploadSingleEpisode(10, "new-10.txt", "새로운 10화 원문")
                .andExpect(status().isOk());

        assertThat(episodeRepository.findAll())
                .extracting(Episode::getContentS3Key)
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allMatch(key -> key.matches(
                        "works/" + work.getId() + "/episodes/10/[0-9a-f-]{36}/10\\.txt"
                ));
    }

    @Test
    @DisplayName("회차 목록 메타데이터는 회차 수와 무관한 고정 쿼리 수로 일괄 조회한다")
    void getEpisodesBulkLoadsMetadata() throws Exception {
        UploadBatch batch = uploadBatchRepository.save(UploadBatch.create(
                work, member, UploadType.MULTI_EPISODE_SINGLE_FILE, UploadSourceType.FILE));
        UploadFile sourceFile = uploadFileRepository.save(UploadFile.create(
                batch, UploadFileRole.EPISODE, "episodes.txt", MediaType.TEXT_PLAIN_VALUE,
                "s3://episodes.txt", 100));
        sourceFile.markEpisodesParsed(1, 30, 30);
        List<Episode> episodes = new ArrayList<>();
        for (int episodeNo = 1; episodeNo <= 30; episodeNo++) {
            episodes.add(Episode.create(
                    work,
                    sourceFile.getId(),
                    episodeNo,
                    episodeNo + "화",
                    "works/" + episodeNo + ".txt",
                    "v1",
                    "hash-" + episodeNo,
                    10
            ));
        }
        episodes = episodeRepository.saveAll(episodes);
        AnalysisJob batchJob = AnalysisJob.create(
                work, batch, null, AnalysisJobType.EPISODE_VALIDATION);
        batchJob.addTargetEpisodes(episodes);
        analysisJobRepository.save(batchJob);

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        mockMvc.perform(get("/api/v1/works/{workId}/episodes", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(30)));

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(6);
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
    void replaceEpisodeFileKeepsIdentityAndTitleButChangesSourceAndAnalysisState() throws Exception {
        UploadBatch originalBatch = uploadBatchRepository.save(UploadBatch.create(
                work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE));
        UploadFile originalFile = uploadFileRepository.save(UploadFile.create(
                originalBatch, UploadFileRole.EPISODE, "old.txt", MediaType.TEXT_PLAIN_VALUE,
                "s3://old.txt", 10));
        originalFile.markEpisodesParsed(7, 7, 1);
        Episode episode = episodeRepository.save(Episode.create(
                work, originalFile.getId(), 7, "유지되는 제목", "works/old.txt", "v1", "old-hash", 10));

        MockMultipartFile replacement = textFile("file", "new-source.txt", "새 원문 입니다.\n");
        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes/{episodeId}/file", work.getId(), episode.getId())
                        .file(replacement)
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(episode.getId().toString()))
                .andExpect(jsonPath("$.data.episodeNo").value(7))
                .andExpect(jsonPath("$.data.title").value("유지되는 제목"))
                .andExpect(jsonPath("$.data.originalFilename").value("new-source.txt"))
                .andExpect(jsonPath("$.data.charCount").value(7))
                .andExpect(jsonPath("$.data.analysisStatus").value("REANALYSIS_REQUIRED"));

        Episode replaced = episodeRepository.findById(episode.getId()).orElseThrow();
        assertThat(replaced.getSourceFileId()).isNotEqualTo(originalFile.getId());
        assertThat(replaced.getEpisodeNo()).isEqualTo(7);
        assertThat(replaced.getTitle()).isEqualTo("유지되는 제목");
        assertThat(replaced.getStatus()).isEqualTo(EpisodeStatus.UPLOADED);
        verify(objectStorage, never()).delete(anyString());
    }

    @Test
    void deleteEpisodeSoftDeletesWithoutRemovingStoredOriginal() throws Exception {
        Episode episode = episodeRepository.save(Episode.create(
                work, null, 9, "삭제 대상", "works/delete-target.txt", "v1", "hash", 5));

        mockMvc.perform(delete("/api/v1/works/{workId}/episodes/{episodeId}", work.getId(), episode.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk());

        assertThat(episodeRepository.findById(episode.getId()).orElseThrow().getStatus())
                .isEqualTo(EpisodeStatus.ARCHIVED);
        mockMvc.perform(get("/api/v1/works/{workId}/episodes", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(objectStorage, never()).delete(anyString());
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
    void uploadEpisodesRejectsOtherMemberWork() throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "SINGLE_EPISODE",
                  "singleEpisodeNo": 1
                }
                """);
        MockMultipartFile episodeFile = textFile("episodeFiles", "episode-1.txt", "첫 문장입니다.");

        mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", otherWork.getId())
                        .file(metadata)
                        .file(episodeFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    private MockMultipartFile metadataPart(String content) {
        return new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private ResultActions uploadTwoDetectedEpisodesWithConfirmations(
            String episodeConfirmationsJson
    ) throws Exception {
        MockMultipartFile metadata = metadataPart("""
                {
                  "uploadType": "MULTI_EPISODE_SINGLE_FILE",
                  "episodeConfirmations": %s
                }
                """.formatted(episodeConfirmationsJson));
        return performTwoEpisodeUpload(metadata);
    }

    private ResultActions performTwoEpisodeUpload(MockMultipartFile metadata) throws Exception {
        MockMultipartFile sourceEpisodeFile = textFile(
                "episodeFiles",
                "episodes.txt",
                """
                        제 1화 첫 제목
                        첫 번째 본문입니다.

                        제 2화 둘째 제목
                        두 번째 본문입니다.
                        """
        );

        return mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                .file(metadata)
                .file(sourceEpisodeFile)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    private ResultActions uploadSingleEpisode(
            int episodeNo,
            String filename,
            String content
    ) throws Exception {
        return mockMvc.perform(multipart("/api/v1/works/{workId}/episodes", work.getId())
                .file(metadataPart("""
                        {
                          "uploadType": "SINGLE_EPISODE",
                          "singleEpisodeNo": %d
                        }
                        """.formatted(episodeNo)))
                .file(textFile("episodeFiles", filename, content))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
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
