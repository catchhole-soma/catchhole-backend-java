package org.monitoring.catchholebackend.domain.episode.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("회차 파일 파서 정규식 테스트")
class EpisodeFileParserTest {

    private final EpisodeFileParser episodeFileParser = new EpisodeFileParser();

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

        List<ParsedEpisodeFile> parsedFiles = episodeFileParser.parse(
                request(UploadType.MULTI_EPISODE_SINGLE_FILE),
                List.of(episodeFile)
        );

        assertThat(parsedFiles).hasSize(1);
        assertThat(parsedFiles.get(0).episodes())
                .extracting(ParsedEpisode::episodeNo)
                .containsExactly(12, 13, 14);
        assertThat(parsedFiles.get(0).episodes())
                .extracting(ParsedEpisode::title)
                .containsExactly("재회", "각성", "마무리");
        assertThat(parsedFiles.get(0).episodes())
                .extracting(ParsedEpisode::content)
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

        List<ParsedEpisodeFile> parsedFiles = episodeFileParser.parse(
                request(UploadType.MULTI_EPISODE_SINGLE_FILE),
                List.of(episodeFile)
        );

        assertThat(parsedFiles.get(0).episodes())
                .extracting(ParsedEpisode::episodeNo)
                .containsExactly(15, 16, 17, 18);
        assertThat(parsedFiles.get(0).episodes())
                .extracting(ParsedEpisode::title)
                .containsExactly("제 15화", "닫는 괄호 제목", "마침표 제목", "콜론 제목");
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
                        """
        );

        List<ParsedEpisodeFile> parsedFiles = episodeFileParser.parse(
                request(UploadType.MULTI_EPISODE_SINGLE_FILE),
                List.of(episodeFile)
        );

        assertThat(parsedFiles.get(0).episodes()).hasSize(1);
        assertThat(parsedFiles.get(0).episodes().get(0).episodeNo()).isEqualTo(1);
        assertThat(parsedFiles.get(0).episodes().get(0).content()).contains("제 99화");
    }

    @Test
    @DisplayName("여러 파일 업로드에서 파일명에 포함된 회차 번호를 감지한다")
    void parseMultiEpisodeMultiFileDetectsEpisodeNoFromFilenames() {
        List<ParsedEpisodeFile> parsedFiles = episodeFileParser.parse(request(UploadType.MULTI_EPISODE_MULTI_FILE), List.of(
                textFile("제 7화.txt", "일곱 번째 본문입니다."),
                textFile("Episode_8.txt", "여덟 번째 본문입니다."),
                textFile("EP-9.txt", "아홉 번째 본문입니다."),
                textFile("ep.10.txt", "열 번째 본문입니다.")
        ));

        assertThat(parsedFiles)
                .flatExtracting(ParsedEpisodeFile::episodes)
                .extracting(ParsedEpisode::episodeNo)
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

        List<ParsedEpisodeFile> parsedFiles = episodeFileParser.parse(
                request(UploadType.MULTI_EPISODE_MULTI_FILE),
                List.of(episodeFile)
        );

        assertThat(parsedFiles).hasSize(1);
        assertThat(parsedFiles.get(0).episodes()).hasSize(1);
        assertThat(parsedFiles.get(0).episodes().get(0).episodeNo()).isEqualTo(42);
    }

    @Test
    @DisplayName("파일명과 본문에 회차 번호가 모두 있으면 파일명을 우선한다")
    void parseMultiEpisodeMultiFilePrefersFilenameEpisodeNoOverContentEpisodeNo() {
        MockMultipartFile episodeFile = textFile(
                "episode-5.txt",
                """
                        제 99화 본문 속 회차
                        파일명 회차 번호가 우선되어야 합니다.
                        """
        );

        List<ParsedEpisodeFile> parsedFiles = episodeFileParser.parse(
                request(UploadType.MULTI_EPISODE_MULTI_FILE),
                List.of(episodeFile)
        );

        assertThat(parsedFiles.get(0).episodes().get(0).episodeNo()).isEqualTo(5);
    }

    @Test
    @DisplayName("단일 파일 다중 회차 업로드에서 heading이 없으면 회차 번호 감지 실패로 처리한다")
    void parseMultiEpisodeSingleFileFailsWhenNoHeadingExists() {
        MockMultipartFile episodeFile = textFile("episodes.txt", "회차 heading이 없는 본문입니다.");

        assertThatThrownBy(() -> episodeFileParser.parse(
                request(UploadType.MULTI_EPISODE_SINGLE_FILE),
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

        assertThatThrownBy(() -> episodeFileParser.parse(
                request(UploadType.MULTI_EPISODE_SINGLE_FILE),
                List.of(episodeFile)
        ))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED));
    }

    private EpisodeUploadRequest request(UploadType uploadType) {
        return new EpisodeUploadRequest(uploadType, null, null);
    }

    private MockMultipartFile textFile(String filename, String content) {
        return new MockMultipartFile(
                "episodeFiles",
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
