package org.monitoring.catchholebackend.domain.episode.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.parser.TextDocumentReader;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("회차 파일 파서 정규식 테스트")
class EpisodeFileParserTest {

    private final TextDocumentReader textDocumentReader = new TextDocumentReader();
    private final EpisodeFileParser episodeFileParser = new EpisodeFileParser(textDocumentReader);

    @Test
    @DisplayName("단일 파일 다중 회차 업로드에서 한글/영문 회차 heading을 분리한다")
    void parseMultiEpisodeSingleFileDetectsKoreanAndEnglishHeadings() {
        MockMultipartFile episodeFile = textFile(
                "episodes.txt",
                """
                        제 12 장 - 재회
                        열두 번째 본문입니다.

                        EP_13: 각성
                        열세 번째 본문입니다.

                        Episode 14 마무리
                        열네 번째 본문입니다.
                        """
        );

        List<DetectedEpisodeFile> detectedEpisodeFiles = parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,
                List.of(episodeFile)
        );

        assertThat(detectedEpisodeFiles).hasSize(1);
        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes())
                .extracting(DetectedEpisode::episodeNo)
                .containsExactly(12, 13, 14);
        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes())
                .extracting(DetectedEpisode::title)
                .containsExactly("재회", "각성", "마무리");
        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes())
                .extracting(DetectedEpisode::content)
                .containsExactly("열두 번째 본문입니다.", "열세 번째 본문입니다.", "열네 번째 본문입니다.");
    }

    @Test
    @DisplayName("한글 heading의 구두점과 제목 없는 회차를 처리한다")
    void parseMultiEpisodeSingleFileHandlesKoreanHeadingPunctuationAndDefaultTitle() {
        MockMultipartFile episodeFile = textFile(
                "episodes.txt",
                """
                        15화
                        제목 없는 본문입니다.

                        제 16회) 닫는 괄호 제목
                        열여섯 번째 본문입니다.

                        17편. 마침표 제목
                        열일곱 번째 본문입니다.

                        18장：콜론 제목
                        열여덟 번째 본문입니다.
                        """
        );

        List<DetectedEpisodeFile> detectedEpisodeFiles = parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,
                List.of(episodeFile)
        );

        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes())
                .extracting(DetectedEpisode::episodeNo)
                .containsExactly(15, 16, 17, 18);
        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes())
                .extracting(DetectedEpisode::title)
                .containsExactly(null, "닫는 괄호 제목", "마침표 제목", "콜론 제목");
    }

    @Test
    @DisplayName("본문 중간의 회차 표현은 heading으로 분리하지 않는다")
    void parseMultiEpisodeSingleFileIgnoresInlineEpisodeNoText() {
        MockMultipartFile episodeFile = textFile(
                "episodes.txt",
                """
                        제 1화 시작
                        첫 번째 본문입니다.
                        인물 대사에서 제 99화라는 표현이 나오지만 새 회차가 아닙니다.
                        마지막 문장입니다.

                        Chapter 2 다음
                        두 번째 본문입니다.
                        """
        );

        List<DetectedEpisodeFile> detectedEpisodeFiles = parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,
                List.of(episodeFile)
        );

        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes()).hasSize(2);
        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes().get(0).episodeNo()).isEqualTo(1);
        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes().get(0).content()).contains("제 99화");
    }

    @Test
    @DisplayName("여러 파일 업로드에서 파일명에 포함된 회차 번호를 감지한다")
    void parseMultiEpisodeMultiFileDetectsEpisodeNoFromFilenames() {
        List<DetectedEpisodeFile> detectedEpisodeFiles = parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_MULTI_FILE, List.of(
                        textFile("제 7화.txt", "일곱 번째 본문입니다."),
                        textFile("Episode_8.txt", "여덟 번째 본문입니다."),
                        textFile("EP-9.txt", "아홉 번째 본문입니다."),
                        textFile("ep.10.txt", "열 번째 본문입니다.")
                )
        );

        assertThat(detectedEpisodeFiles)
                .flatExtracting(DetectedEpisodeFile::detectedEpisodes)
                .extracting(DetectedEpisode::episodeNo)
                .containsExactly(7, 8, 9, 10);
    }

    @Test
    @DisplayName("파일명에 회차 번호가 없으면 본문에서 회차 번호를 감지한다")
    void parseMultiEpisodeMultiFileFallsBackToContentWhenFilenameHasNoEpisodeNo() {
        MockMultipartFile episodeFile = textFile(
                "draft.txt",
                """
                        Episode 42: 숨겨진 회차
                        마흔두 번째 본문입니다.
                        """
        );

        List<DetectedEpisodeFile> detectedEpisodeFiles = parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_MULTI_FILE,
                List.of(
                        episodeFile,
                        textFile("episode-43.txt", "마흔세 번째 본문입니다.")
                )
        );

        assertThat(detectedEpisodeFiles).hasSize(2);
        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes()).hasSize(1);
        assertThat(detectedEpisodeFiles.get(0).detectedEpisodes().get(0).episodeNo()).isEqualTo(42);
    }

    @Test
    @DisplayName("파일명과 본문 heading의 회차 번호가 다르면 충돌로 처리한다")
    void parseMultiEpisodeMultiFileRejectsFilenameAndContentEpisodeNoConflict() {
        MockMultipartFile episodeFile = textFile(
                "episode-5.txt",
                """
                        제 99화 본문 속 회차
                        파일명 회차 번호가 우선되어야 합니다.
                        """
        );

        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_MULTI_FILE,
                List.of(
                        episodeFile,
                        textFile("episode-100.txt", "백 번째 본문입니다.")
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_EPISODE_NO_CONFLICT));
    }

    @Test
    @DisplayName("여러 파일 업로드에서 한 파일에 회차 heading이 둘 이상이면 거부한다")
    void parseMultiEpisodeMultiFileRejectsMultipleEpisodeHeadingsInOneFile() {
        MockMultipartFile episodeFile = textFile(
                "episode-1.txt",
                """
                        제 1화 시작
                        첫 번째 본문입니다.

                        제 2화 다음
                        두 번째 본문입니다.
                        """
        );

        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_MULTI_FILE,
                List.of(episodeFile, textFile("episode-3.txt", "세 번째 본문입니다."))
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(UploadErrorCode.UPLOAD_MULTI_FILE_EPISODE_COUNT_INVALID));
    }

    @Test
    @DisplayName("파일명에서 0회차를 감지하면 유효하지 않은 회차 번호로 거부한다")
    void parseMultiEpisodeMultiFileRejectsZeroEpisodeNoInFilename() {
        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_MULTI_FILE,
                List.of(
                        textFile("episode-0.txt", "잘못된 회차 본문입니다."),
                        textFile("episode-2.txt", "두 번째 본문입니다.")
                )
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_EPISODE_NO_INVALID));
    }

    @Test
    @DisplayName("heading의 회차 번호가 int 범위를 넘으면 유효하지 않은 회차 번호로 거부한다")
    void parseMultiEpisodeSingleFileRejectsOverflowEpisodeNoInHeading() {
        MockMultipartFile episodeFile = textFile(
                "episodes.txt",
                """
                        제 2147483648화 시작
                        첫 번째 본문입니다.

                        제 2147483649화 다음
                        두 번째 본문입니다.
                        """
        );

        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,
                List.of(episodeFile)
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_EPISODE_NO_INVALID));
    }

    @Test
    @DisplayName("다회차 여러 파일 업로드는 DOCX를 거부한다")
    void parseMultiEpisodeMultiFileRejectsDocx() throws IOException {
        MockMultipartFile docx = docxFile("제 1화.docx", """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>첫 본문</w:t></w:r></w:p></w:body>
                </w:document>
                """);

        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_MULTI_FILE,
                List.of(docx, textFile("제 2화.txt", "둘째 본문"))
        ))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(UploadErrorCode.UPLOAD_MULTI_FILE_TYPE_NOT_SUPPORTED));
    }

    @Test
    @DisplayName("단일 파일 다중 회차 업로드에서 heading이 없으면 회차 번호 감지 실패로 처리한다")
    void parseMultiEpisodeSingleFileFailsWhenNoHeadingExists() {
        MockMultipartFile episodeFile = textFile("episodes.txt", "회차 heading이 없는 본문입니다.");

        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,
                List.of(episodeFile)
        ))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(UploadErrorCode.UPLOAD_EPISODE_NO_DETECTION_FAILED));
    }

    @Test
    @DisplayName("단일 파일 다중 회차 업로드에서 heading 사이 본문이 비어 있으면 파싱 실패로 처리한다")
    void parseMultiEpisodeSingleFileFailsWhenEpisodeContentIsBlank() {
        MockMultipartFile episodeFile = textFile(
                "episodes.txt",
                """
                        제 1화 시작
                        첫 번째 본문입니다.

                        제 2화 빈 회차

                        제 3화 다음
                        세 번째 본문입니다.
                        """
        );

        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,
                List.of(episodeFile)
        ))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED));
    }

    @Test
    @DisplayName("첫 회차 heading 앞에 미배정 원문이 있으면 파싱을 거부한다")
    void parseMultiEpisodeSingleFileRejectsUnassignedPrefix() {
        MockMultipartFile episodeFile = textFile("episodes.txt", """
                어느 회차에도 속하지 않는 머리말
                제 1화 시작
                첫 본문
                제 2화 다음
                둘째 본문
                """);

        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE, List.of(episodeFile)))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED));
    }

    @Test
    @DisplayName("다회차 heading 번호가 역순이면 거부한다")
    void parseMultiEpisodeSingleFileRejectsDescendingNumbers() {
        MockMultipartFile episodeFile = textFile("episodes.txt", """
                제 2화 먼저
                둘째 본문
                제 1화 나중
                첫 본문
                """);

        assertThatThrownBy(() -> parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE, List.of(episodeFile)))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_EPISODE_ORDER_INVALID));
    }

    @Test
    @DisplayName("UTF-8 BOM이 있는 TXT도 첫 heading부터 정상적으로 분리한다")
    void parseMultiEpisodeSingleFileStripsUtf8Bom() {
        MockMultipartFile episodeFile = textFile(
                "episodes.txt",
                "\uFEFF제 1화 시작\n첫 번째 본문입니다.\n\n제 2화 다음\n두 번째 본문입니다."
        );

        List<DetectedEpisodeFile> detectedEpisodeFiles = parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,
                List.of(episodeFile)
        );

        assertThat(detectedEpisodeFiles.getFirst().detectedEpisodes())
                .extracting(DetectedEpisode::episodeNo)
                .containsExactly(1, 2);
        assertThat(detectedEpisodeFiles.getFirst().detectedEpisodes().getFirst().content())
                .isEqualTo("첫 번째 본문입니다.");
    }

    @Test
    @DisplayName("다회차 방식에는 단일 회차 전용 메타데이터를 사용할 수 없다")
    void parseMultiEpisodeRejectsSingleEpisodeMetadata() {
        assertThatThrownBy(() -> episodeFileParser.parseEpisodeFiles(
                EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,
                10,
                null,
                List.of(textFile(
                        "episodes.txt",
                        "제 1화 시작\n첫 본문\n제 2화 다음\n둘째 본문"
                ))
        )).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode())
                        .isEqualTo(UploadErrorCode.UPLOAD_SINGLE_EPISODE_METADATA_NOT_ALLOWED));
    }

    @Test
    @DisplayName("공백만 있는 TXT는 빈 파일로 처리한다")
    void readTextRejectsWhitespaceOnlyText() {
        assertThatThrownBy(() -> textDocumentReader.readText(textFile("blank.txt", " \n\t")))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_FILE_EMPTY));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("원본 파일명이 없는 multipart 파일은 지원 형식을 추정하지 않고 거절한다")
    void readTextRejectsMissingOriginalFilename(String originalFilename) {
        MockMultipartFile sourceFile = new MockMultipartFile(
                "episodeFiles",
                originalFilename,
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                new byte[]{(byte) 0xFF, 0x00, 0x01}
        );

        assertThatThrownBy(() -> textDocumentReader.readText(sourceFile))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode())
                                .isEqualTo(UploadErrorCode.UPLOAD_FILE_TYPE_NOT_SUPPORTED));
    }

    @Test
    @DisplayName("DOCX 문단과 줄바꿈을 텍스트로 변환한다")
    void readTextReadsDocxParagraphs() throws IOException {
        MockMultipartFile docx = docxFile("episode.docx", """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>첫 문단</w:t></w:r></w:p>
                    <w:p><w:r><w:t>둘째 문단</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """);

        assertThat(textDocumentReader.readText(docx)).isEqualTo("첫 문단\n둘째 문단");
    }

    @Test
    @DisplayName("압축 해제된 document.xml이 10MB를 초과하는 DOCX를 거부한다")
    void readTextRejectsOversizedDocxDocumentXml() throws IOException {
        MockMultipartFile docx = docxFile("oversized.docx", """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
                </w:document>
                """.formatted("a".repeat(10 * 1024 * 1024)));

        assertThat(docx.getSize()).isLessThan(10L * 1024 * 1024);
        assertThatThrownBy(() -> textDocumentReader.readText(docx))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("document.xml 앞 엔트리까지 누적 압축 해제량이 20MB를 초과하는 DOCX를 거부한다")
    void readTextRejectsOversizedDocxEntryBeforeDocumentXml() throws IOException {
        MockMultipartFile docx = docxFile(
                "oversized-leading-entry.docx",
                """
                        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                          <w:body><w:p><w:r><w:t>정상 본문</w:t></w:r></w:p></w:body>
                        </w:document>
                        """,
                1,
                20 * 1024 * 1024 + 1
        );

        assertThat(docx.getSize()).isLessThan(10L * 1024 * 1024);
        assertThatThrownBy(() -> textDocumentReader.readText(docx))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("document.xml까지 ZIP 엔트리가 256개를 초과하는 DOCX를 거부한다")
    void readTextRejectsTooManyDocxEntriesBeforeDocumentXml() throws IOException {
        MockMultipartFile docx = docxFile(
                "too-many-entries.docx",
                """
                        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                          <w:body><w:p><w:r><w:t>정상 본문</w:t></w:r></w:p></w:body>
                        </w:document>
                        """,
                256,
                0
        );

        assertThatThrownBy(() -> textDocumentReader.readText(docx))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_FILE_TOO_LARGE));
    }

    private List<DetectedEpisodeFile> parseEpisodeFiles(
            EpisodeUploadType uploadType,
            List<MultipartFile> sourceEpisodeFiles
    ) {
        return episodeFileParser.parseEpisodeFiles(uploadType, null, null, sourceEpisodeFiles);
    }

    private MockMultipartFile textFile(String filename, String content) {
        return new MockMultipartFile(
                "episodeFiles",
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile docxFile(String filename, String documentXml) throws IOException {
        return docxFile(filename, documentXml, 1, 8);
    }

    private MockMultipartFile docxFile(
            String filename,
            String documentXml,
            int leadingEntryCount,
            int leadingEntrySize
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] chunk = "a".repeat(8 * 1024).getBytes(StandardCharsets.UTF_8);
            for (int index = 0; index < leadingEntryCount; index++) {
                String entryName = index == 0
                        ? "[Content_Types].xml"
                        : "customXml/item" + index + ".xml";
                zip.putNextEntry(new ZipEntry(entryName));
                int remaining = leadingEntrySize;
                while (remaining > 0) {
                    int writeSize = Math.min(remaining, chunk.length);
                    zip.write(chunk, 0, writeSize);
                    remaining -= writeSize;
                }
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile(
                "episodeFiles",
                filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                output.toByteArray()
        );
    }
}
