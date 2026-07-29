package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ObjectStorage objectStorage;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, byte[]> storedObjects = new HashMap<>();
    private Work work;
    private String accessToken;

    @BeforeEach
    void setUp() {
        clearDatabase();
        storedObjects.clear();

        Member member = memberRepository.save(Member.register(
                "setting-writer@example.com", "encoded-password", "01011112222", "설정 작가"));
        work = workRepository.save(Work.create(member, "설정집 작품", WorkGenre.FANTASY, null));
        accessToken = jwtTokenProvider.generateAccessToken(member);
        when(objectStorage.putBytes(anyString(), any(byte[].class), any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    storedObjects.put(key, invocation.getArgument(1));
                    return new StoredObject(key, "test-version");
                });
        when(objectStorage.putText(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    storedObjects.put(
                            key,
                            invocation.<String>getArgument(1).getBytes(StandardCharsets.UTF_8)
                    );
                    return new StoredObject(key, "test-version");
                });
        when(objectStorage.getBytes(anyString()))
                .thenAnswer(invocation -> storedObjects.get(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        clearDatabase();
    }

    @Test
    void settingBookCanBeAccumulatedReadUpdatedAndSoftDeleted() throws Exception {
        upload("작품설정.txt").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("작품설정.txt"))
                .andExpect(jsonPath("$.data.mimeType").value("text/plain; charset=UTF-8"))
                .andExpect(jsonPath("$.data.id").isNotEmpty());

        UploadFile firstSettingBook = uploadFileRepository.findAll().getFirst();
        String settingBookId = firstSettingBook.getId().toString();
        String originalStorageUrl = firstSettingBook.getStorageUrl();
        String contentStorageUrl = firstSettingBook.getContentStorageUrl();
        long originalFileSize = firstSettingBook.getFileSize();
        assertThat(contentStorageUrl)
                .isEqualTo("s3://works/" + work.getId() + "/setting-books/"
                        + settingBookId + "/작품설정.txt");

        upload("작품설정.txt").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("작품설정.txt"));
        int storedObjectCountBeforeEdits = storedObjects.size();

        mockMvc.perform(get("/api/v1/works/{workId}/setting-books", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].originalFilename").value("작품설정.txt"))
                .andExpect(jsonPath("$.data[0].mimeType").value("text/plain; charset=UTF-8"));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-books/{settingBookId}", work.getId(), settingBookId)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("작품설정.txt"))
                .andExpect(jsonPath("$.data.content").value("설정 본문"));

        String updatedContent = "수정된 첫 줄\n수정된 둘째 줄";
        mockMvc.perform(patch(
                        "/api/v1/works/{workId}/setting-books/{settingBookId}",
                        work.getId(),
                        settingBookId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", updatedContent))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(updatedContent))
                .andExpect(jsonPath("$.data.mimeType").value("text/plain; charset=UTF-8"))
                .andExpect(jsonPath("$.data.fileSize").value(originalFileSize));

        UploadFile updatedSettingBook = uploadFileRepository.findById(firstSettingBook.getId()).orElseThrow();
        assertThat(updatedSettingBook.getStorageUrl()).isEqualTo(originalStorageUrl);
        assertThat(updatedSettingBook.getContentStorageUrl()).isEqualTo(contentStorageUrl);
        assertThat(updatedSettingBook.getFileSize()).isEqualTo(originalFileSize);
        assertThat(storedObjects).hasSize(storedObjectCountBeforeEdits);
        assertThat(storedObjects).containsKeys(
                originalStorageUrl.substring("s3://".length()),
                contentStorageUrl.substring("s3://".length())
        );
        assertThat(storedObjects.get(contentStorageUrl.substring("s3://".length())))
                .isEqualTo(updatedContent.getBytes(StandardCharsets.UTF_8));

        String latestContent = "두 번째로 수정한 원문";
        mockMvc.perform(patch(
                        "/api/v1/works/{workId}/setting-books/{settingBookId}",
                        work.getId(),
                        settingBookId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", latestContent))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(latestContent))
                .andExpect(jsonPath("$.data.fileSize").value(originalFileSize));

        UploadFile twiceUpdatedSettingBook =
                uploadFileRepository.findById(firstSettingBook.getId()).orElseThrow();
        assertThat(twiceUpdatedSettingBook.getStorageUrl()).isEqualTo(originalStorageUrl);
        assertThat(twiceUpdatedSettingBook.getContentStorageUrl()).isEqualTo(contentStorageUrl);
        assertThat(storedObjects).hasSize(storedObjectCountBeforeEdits);
        assertThat(storedObjects.get(contentStorageUrl.substring("s3://".length())))
                .isEqualTo(latestContent.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-books/{settingBookId}", work.getId(), settingBookId)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(latestContent));

        mockMvc.perform(delete("/api/v1/works/{workId}/setting-books/{settingBookId}", work.getId(), settingBookId)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/works/{workId}/setting-books", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
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

    @Test
    void legacySettingBookWithoutEditableContentReadsOriginalText() throws Exception {
        MockMultipartFile legacyFile = new MockMultipartFile(
                "file",
                "기존설정.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "\uFEFF기존 설정집 원문".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/v1/works/{workId}/setting-books", work.getId())
                        .file(legacyFile)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        UploadFile settingBook = uploadFileRepository.findAll().getFirst();
        jdbcTemplate.update(
                "UPDATE upload_files SET content_storage_url = NULL, mime_type = NULL WHERE id = ?",
                settingBook.getId()
        );

        mockMvc.perform(get(
                        "/api/v1/works/{workId}/setting-books/{settingBookId}",
                        work.getId(),
                        settingBook.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mimeType").value("text/plain; charset=UTF-8"))
                .andExpect(jsonPath("$.data.content").value("기존 설정집 원문"));
    }

    @Test
    void docxOriginalIsPreservedWhileEditableTextUsesReadableStableKey() throws Exception {
        String decomposedFilename =
                Normalizer.normalize("세계관", Normalizer.Form.NFD) + ".docx";
        MockMultipartFile docx = docxFile(
                decomposedFilename,
                """
                        <w:document xmlns:w="urn:test"><w:body>
                          <w:p><w:r><w:t>DOCX 설정 원문</w:t></w:r></w:p>
                        </w:body></w:document>
                        """
        );
        mockMvc.perform(multipart("/api/v1/works/{workId}/setting-books", work.getId())
                        .file(docx)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mimeType")
                        .value("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

        UploadFile settingBook = uploadFileRepository.findAll().getFirst();
        String originalStorageUrl = settingBook.getStorageUrl();
        String contentStorageUrl = settingBook.getContentStorageUrl();
        long originalFileSize = settingBook.getFileSize();
        assertThat(contentStorageUrl)
                .isEqualTo("s3://works/" + work.getId() + "/setting-books/"
                        + settingBook.getId() + "/세계관.txt");
        byte[] originalBytes =
                storedObjects.get(originalStorageUrl.substring("s3://".length())).clone();
        int storedObjectCountBeforeEdit = storedObjects.size();
        mockMvc.perform(get(
                        "/api/v1/works/{workId}/setting-books/{settingBookId}",
                        work.getId(),
                        settingBook.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("DOCX 설정 원문"));

        String editedContent = "편집한 DOCX 설정 원문";
        mockMvc.perform(patch(
                        "/api/v1/works/{workId}/setting-books/{settingBookId}",
                        work.getId(),
                        settingBook.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", editedContent))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mimeType")
                        .value("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(jsonPath("$.data.fileSize").value(originalFileSize));

        UploadFile editedSettingBook =
                uploadFileRepository.findById(settingBook.getId()).orElseThrow();
        assertThat(editedSettingBook.getStorageUrl()).isEqualTo(originalStorageUrl);
        assertThat(editedSettingBook.getContentStorageUrl()).isEqualTo(contentStorageUrl);
        assertThat(storedObjects).hasSize(storedObjectCountBeforeEdit);
        assertThat(storedObjects.get(originalStorageUrl.substring("s3://".length())))
                .isEqualTo(originalBytes);
        assertThat(storedObjects.get(contentStorageUrl.substring("s3://".length())))
                .isEqualTo(editedContent.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get(
                        "/api/v1/works/{workId}/setting-books/{settingBookId}",
                        work.getId(),
                        settingBook.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(editedContent));
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

    private MockMultipartFile docxFile(String filename, String documentXml) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile(
                "file",
                filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                output.toByteArray()
        );
    }

    private void clearDatabase() {
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }
}
