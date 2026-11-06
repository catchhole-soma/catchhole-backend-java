package org.monitoring.catchholebackend.global.config.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=1KB", "spring.servlet.multipart.max-request-size=8KB"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("회차 감지·등록 실패의 안전한 운영 진단 로그")
class UploadFailureLoggingIntegrationTest {

    private static final String PRIVATE_TITLE = "로그에 남기지 않을 원고 제목";
    private static final String PRIVATE_FILENAME = "비공개파일\r\nforged-log.txt";
    private static final String PRIVATE_CONTENT = "외부에 기록하지 않을 한글 본문";
    private final Logger logger = (Logger) LoggerFactory.getLogger(
            "org.monitoring.catchholebackend.global.config.logging.UploadFailureLogger");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository members;
    @Autowired private WorkRepository works;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private Environment environment;
    @MockitoBean private ObjectStorage objectStorage;
    private Work work;
    private String token;

    @BeforeEach
    void setUp() {
        Member member = members.save(Member.register("upload-log-" + UUID.randomUUID() + "@example.com",
                "password", null, "작가"));
        work = works.save(Work.create(member, "진단 테스트", WorkGenre.FANTASY, null));
        token = tokens.generateAccessToken(member);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/detect"})
    @DisplayName("실제 인코딩 실패를 요청 ID·작품·파일 형식·크기와 연결하되 비밀값을 기록하지 않는다")
    void correlatesEncodingFailureWithoutPrivateInput(String suffix) throws Exception {
        byte[] bytes = PRIVATE_CONTENT.getBytes(Charset.forName("MS949"));
        MvcResult result = mockMvc.perform(multipart(path(suffix))
                        .file(metadata(singleMetadata()))
                        .file(new MockMultipartFile("episodeFiles", PRIVATE_FILENAME, "text/plain", bytes))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Cookie", "private-cookie")
                        .queryParam("privateQuery", "private-query-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_DOCUMENT_INVALID"))
                .andExpect(jsonPath("$.error.context").isEmpty())
                .andReturn();

        ILoggingEvent event = onlyFailure(result);
        assertThat(event.getFormattedMessage()).contains("workId=" + work.getId(), "status=400",
                "operation=" + (suffix.isEmpty() ? "UPLOAD" : "DETECT"),
                "errorCode=UPLOAD_DOCUMENT_INVALID", "uploadType=SINGLE_EPISODE", "fileCount=1",
                "format=TXT", "bytes=" + bytes.length, "causeType=MalformedInputException")
                .doesNotContain(PRIVATE_TITLE, PRIVATE_FILENAME, "forged-log", PRIVATE_CONTENT,
                        token, "private-cookie", "private-query-value");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/detect"})
    @DisplayName("실제 회차 제목 오인식은 문서 읽기 실패와 구분되는 오류 코드로 기록한다")
    void distinguishesHeadingFailureFromDocumentFailure(String suffix) throws Exception {
        MvcResult result = mockMvc.perform(multipart(path(suffix))
                        .file(metadata(singleMetadata()))
                        .file(textFile("1화.txt", "1화 시작\n1회용 컵을 내려놓았다."))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_SINGLE_EPISODE_COUNT_INVALID"))
                .andReturn();

        assertThat(onlyFailure(result).getFormattedMessage())
                .contains("errorCode=UPLOAD_SINGLE_EPISODE_COUNT_INVALID", "uploadType=SINGLE_EPISODE")
                .doesNotContain("1회용 컵", PRIVATE_TITLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"uploadType\":null}", "{\"uploadType\":\"SINGLE_EPISODE\",\"singleEpisodeNo\":0}"})
    @DisplayName("컨트롤러 진입 전 DTO 검증 실패도 원고 없이 기록한다")
    void logsValidationBeforeController(String json) throws Exception {
        MvcResult result = mockMvc.perform(multipart(path("/detect"))
                        .file(metadata(json)).file(textFile(PRIVATE_FILENAME, PRIVATE_CONTENT))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"))
                .andReturn();

        assertThat(onlyFailure(result).getFormattedMessage())
                .contains("workId=" + work.getId(), "errorCode=REQUEST_VALIDATION_FAILED", "uploadType=UNKNOWN")
                .contains("invalidFields=[" + (json.contains("null") ? "uploadType" : "singleEpisodeNo") + "]")
                .doesNotContain(PRIVATE_FILENAME, PRIVATE_CONTENT);
    }

    @Test
    @DisplayName("후속 요청의 JSON·필수 part 오류에 앞선 요청의 파일 메타데이터가 섞이지 않는다")
    void keepsContextInsideOneRequest() throws Exception {
        mockMvc.perform(multipart(path("/detect")).file(metadata(singleMetadata()))
                        .file(textFile("1화.txt", "1화 시작\n2화 다음"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
        logs.list.clear();

        MvcResult malformed = mockMvc.perform(multipart(path("/detect"))
                        .file(metadata("{\"uploadType\":\"비밀 잘못된 값\"}"))
                        .file(textFile(PRIVATE_FILENAME, PRIVATE_CONTENT))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID_ARGUMENT"))
                .andReturn();
        assertThat(onlyFailure(malformed).getFormattedMessage())
                .contains("uploadType=UNKNOWN", "fileCount=-1")
                .doesNotContain("비밀 잘못된 값", PRIVATE_FILENAME, PRIVATE_CONTENT, "format=TXT");
        logs.list.clear();

        MvcResult missing = mockMvc.perform(multipart(path(""))
                        .file(metadata(singleMetadata())).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID_ARGUMENT"))
                .andReturn();
        assertThat(onlyFailure(missing).getFormattedMessage()).contains("uploadType=UNKNOWN", "fileCount=-1");
    }

    @Test
    @DisplayName("많은 파일과 긴 파일명에서도 진단 로그 크기는 제한한다")
    void boundsFileDiagnostics() throws Exception {
        var request = multipart(path("/detect"))
                .file(metadata("{\"uploadType\":\"MULTI_EPISODE_MULTI_FILE\"}"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (int i = 0; i < 30; i++) {
            request.file(new MockMultipartFile("episodeFiles", "비밀".repeat(300) + i + ".txt", "text/plain",
                    PRIVATE_CONTENT.getBytes(Charset.forName("MS949"))));
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isBadRequest()).andReturn();

        String message = onlyFailure(result).getFormattedMessage();
        assertThat(message).contains("fileCount=30", "uploadType=MULTI_EPISODE_MULTI_FILE").doesNotContain("비밀");
        assertThat(message.length()).isLessThan(2000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"literal-segment", "uuid"})
    @DisplayName("Spring이 허용하는 URL 인코딩에서도 같은 API와 작품 UUID로 오류를 연결한다")
    void recognizesEncodedRoutes(String encodedPart) throws Exception {
        String workId = work.getId().toString();
        String encodedPath = encodedPart.equals("literal-segment")
                ? path("/detect").replace("/episodes", "/ep%69sodes")
                : path("/detect").replace(workId, "%" + Integer.toHexString(workId.charAt(0)) + workId.substring(1));
        MvcResult result = mockMvc.perform(multipart(URI.create(encodedPath))
                        .file(metadata(singleMetadata()))
                        .file(new MockMultipartFile("episodeFiles", PRIVATE_FILENAME, "text/plain",
                                PRIVATE_CONTENT.getBytes(Charset.forName("MS949"))))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_DOCUMENT_INVALID"))
                .andReturn();
        assertThat(onlyFailure(result).getFormattedMessage()).contains("workId=" + workId, "operation=DETECT");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("실제 HTTP multipart 크기 제한으로 컨트롤러 진입 전 실패해도 요청 ID와 원인을 기록한다")
    void logsRealServletMultipartLimit() throws Exception {
        String boundary = "upload-diagnostics-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + singleMetadata() + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"episodeFiles\"; filename=\"private-http-filename.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + "private-http-body".repeat(220) + "\r\n--" + boundary + "--\r\n";
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path("/detect")))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("UPLOAD_SIZE_LIMIT_EXCEEDED");
        assertThat(onlyFailure(response.headers().firstValue("X-Request-ID").orElseThrow()).getFormattedMessage())
                .contains("workId=" + work.getId(), "errorCode=UPLOAD_SIZE_LIMIT_EXCEEDED",
                        "exceptionType=MaxUploadSizeExceededException", "uploadType=UNKNOWN", "fileCount=-1")
                .doesNotContain("private-http-filename", "private-http-body", PRIVATE_TITLE, token);
    }

    @Test
    @DisplayName("정상 감지와 업로드 이외 경로에는 실패 진단을 추가하지 않는다")
    void leavesSuccessfulAndUnrelatedRequestsAlone() throws Exception {
        mockMvc.perform(multipart(path("/detect")).file(metadata(singleMetadata()))
                        .file(textFile("1화.txt", "1화 시작\n정상 본문입니다."))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/works/not-a-uuid/episodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
        assertThat(logs.list).isEmpty();
    }

    private ILoggingEvent onlyFailure(MvcResult result) {
        return onlyFailure(result.getResponse().getHeader("X-Request-ID"));
    }

    private ILoggingEvent onlyFailure(String requestId) {
        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(UUID.fromString(requestId).version()).isEqualTo(4);
        assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo(requestId);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(MDC.get("requestId")).isNull();
        return event;
    }

    private String path(String suffix) {
        return "/api/v1/works/" + work.getId() + "/episodes" + suffix;
    }

    private String singleMetadata() {
        return "{\"uploadType\":\"SINGLE_EPISODE\",\"singleEpisodeNo\":1,\"singleEpisodeTitle\":\"" + PRIVATE_TITLE + "\"}";
    }

    private MockMultipartFile metadata(String json) {
        return new MockMultipartFile("metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile textFile(String name, String content) {
        return new MockMultipartFile("episodeFiles", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }
}
