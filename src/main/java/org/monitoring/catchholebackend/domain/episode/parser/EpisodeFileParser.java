package org.monitoring.catchholebackend.domain.episode.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class EpisodeFileParser {

    /*
     * 단일 txt 파일 안에서 회차 구분선으로 쓰는 줄 전체를 찾는다.
     * 지원 예: "12화 제목", "제 12 장 - 제목", "EP 12: 제목", "Episode_12 제목"
     * 한글 표기에서는 회차 번호와 제목을 앞쪽 괄호 쌍으로, EP/Episode 표기에서는 뒤쪽 괄호 쌍으로 잡는다.
     */
    private static final Pattern EPISODE_HEADING_PATTERN = Pattern.compile(
            "(?im)^\\h*(?:제\\h*)?(\\d+)\\h*(?:화|회|편|장)\\h*[:：\\-.\\)]?\\h*(.*)$"
                    + "|^\\h*(?:EP|Episode)\\h*[._\\h-]?(\\d+)\\h*[:：\\-.\\)]?\\h*(.*)$"
    );

    /*
     * 파일명 또는 본문 일부에서 회차 번호만 뽑을 때 사용한다.
     * 한글 표기와 EP/Episode 표기 모두에서 숫자 회차만 추출한다.
     */
    private static final Pattern EPISODE_NO_PATTERN = Pattern.compile(
            "(?i)(?:제\\s*)?(\\d+)\\s*(?:화|회|편|장)|(?:EP|Episode)\\s*[._\\s-]?(\\d+)"
    );

    /**
     * 업로드 타입에 따라 회차 파일을 ParsedEpisodeFile 목록으로 변환한다. 단일 회차, 다중 파일, 단일 파일 내 다중 회차를 서로 다른 규칙으로 파싱한다.
     */
    public List<ParsedEpisodeFile> parse(EpisodeUploadRequest request, List<MultipartFile> episodeFiles) {
        validateEpisodeFiles(episodeFiles);
        return switch (request.uploadType()) {
            case SINGLE_EPISODE -> parseSingleEpisode(request, episodeFiles);
            case MULTI_EPISODE_MULTI_FILE -> parseMultiEpisodeMultiFile(episodeFiles);
            case MULTI_EPISODE_SINGLE_FILE -> parseMultiEpisodeSingleFile(episodeFiles);
            case INITIAL_IMPORT -> throw new AppException(UploadErrorCode.UPLOAD_TYPE_NOT_SUPPORTED);
        };
    }

    private List<ParsedEpisodeFile> parseSingleEpisode(
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles
    ) {
        if (episodeFiles.size() != 1) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }
        if (request.episodeNo() == null) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_REQUIRED);
        }

        MultipartFile episodeFile = episodeFiles.get(0);
        ParsedEpisode episode = new ParsedEpisode(
                request.episodeNo(),
                resolveTitle(request.title(), episodeFile.getOriginalFilename(), request.episodeNo()),
                readText(episodeFile)
        );
        return List.of(new ParsedEpisodeFile(episodeFile, List.of(episode)));
    }

    private List<ParsedEpisodeFile> parseMultiEpisodeMultiFile(List<MultipartFile> episodeFiles) {
        List<ParsedEpisodeFile> parsedEpisodeFiles = new ArrayList<>();
        for (MultipartFile episodeFile : episodeFiles) {
            int episodeNo = detectEpisodeNo(episodeFile);
            ParsedEpisode episode = new ParsedEpisode(
                    episodeNo,
                    resolveTitle(null, episodeFile.getOriginalFilename(), episodeNo),
                    readText(episodeFile)
            );
            parsedEpisodeFiles.add(new ParsedEpisodeFile(episodeFile, List.of(episode)));
        }
        return parsedEpisodeFiles;
    }

    /**
     * 하나의 원고 파일 안에서 회차 heading을 찾아 여러 회차 본문으로 분리한다. 각 heading의 끝부터 다음 heading의 시작 직전까지를 해당 회차 본문으로 본다.
     */
    private List<ParsedEpisodeFile> parseMultiEpisodeSingleFile(List<MultipartFile> episodeFiles) {
        if (episodeFiles.size() != 1) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }

        MultipartFile episodeFile = episodeFiles.get(0);
        String episodeText = readText(episodeFile);
        List<EpisodeHeading> headings = findEpisodeHeadings(episodeText);
        if (headings.isEmpty()) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_DETECTION_FAILED);
        }
        log.info(
                "Detected {} episode headings from upload file. filename={}, textLength={}",
                headings.size(),
                resolveOriginalFilename(episodeFile),
                episodeText.length()
        );

        List<ParsedEpisode> episodes = new ArrayList<>();
        for (int index = 0; index < headings.size(); index++) {
            EpisodeHeading heading = headings.get(index);
            int episodeContentEndOffset = index + 1 < headings.size()
                    ? headings.get(index + 1).start()
                    : episodeText.length();
            String episodeContent = episodeText.substring(heading.end(), episodeContentEndOffset).trim();
            if (!StringUtils.hasText(episodeContent)) {
                throw new AppException(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED);
            }
            String title = resolveHeadingTitle(heading);
            log.info(
                    "Parsed episode from upload file. filename={}, episodeNo={}, title={}, headingLine={}, headingOffset={}..{}, contentOffset={}..{}, contentCharCount={}",
                    resolveOriginalFilename(episodeFile),
                    heading.episodeNo(),
                    title,
                    lineNumberOf(episodeText, heading.start()),
                    heading.start(),
                    heading.end(),
                    heading.end(),
                    episodeContentEndOffset,
                    episodeContent.length()
            );
            episodes.add(new ParsedEpisode(
                    heading.episodeNo(),
                    title,
                    episodeContent
            ));
        }

        return List.of(new ParsedEpisodeFile(episodeFile, episodes));
    }

    private void validateEpisodeFiles(List<MultipartFile> episodeFiles) {
        if (episodeFiles == null || episodeFiles.isEmpty()) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }
        if (episodeFiles.stream().anyMatch(MultipartFile::isEmpty)) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_EMPTY);
        }
    }

    /**
     * 회차 번호는 파일명에서 먼저 찾고, 찾지 못하면 파일 본문에서 다시 탐지한다.
     */
    private int detectEpisodeNo(MultipartFile episodeFile) {
        return detectEpisodeNo(episodeFile.getOriginalFilename())
                .or(() -> detectEpisodeNo(readText(episodeFile)))
                .orElseThrow(() -> new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_DETECTION_FAILED));
    }

    private Optional<Integer> detectEpisodeNo(String episodeText) {
        if (!StringUtils.hasText(episodeText)) {
            return Optional.empty();
        }
        Matcher matcher = EPISODE_NO_PATTERN.matcher(episodeText);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String episodeNoText = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Optional.of(Integer.parseInt(episodeNoText));
    }

    /**
     * 단일 파일 다중 회차 업로드에서 본문을 나눌 기준 heading의 위치와 회차 번호를 찾는다.
     */
    private List<EpisodeHeading> findEpisodeHeadings(String episodeText) {
        Matcher matcher = EPISODE_HEADING_PATTERN.matcher(episodeText);
        List<EpisodeHeading> headings = new ArrayList<>();
        while (matcher.find()) {
            String episodeNoText = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String title = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            headings.add(new EpisodeHeading(
                    matcher.start(),
                    matcher.end(),
                    Integer.parseInt(episodeNoText),
                    title == null ? null : title.trim()
            ));
        }
        return headings;
    }

    private String readText(MultipartFile episodeFile) {
        try {
            return new String(episodeFile.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_READ_FAILED, exception);
        }
    }

    private int lineNumberOf(String episodeText, int offset) {
        int lineNumber = 1;
        int endOffset = Math.min(offset, episodeText.length());
        for (int index = 0; index < endOffset; index++) {
            if (episodeText.charAt(index) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    private String resolveOriginalFilename(MultipartFile episodeFile) {
        return StringUtils.hasText(episodeFile.getOriginalFilename()) ? episodeFile.getOriginalFilename() : "untitled.txt";
    }

    private String resolveTitle(String requestedTitle, String originalFilename, int episodeNo) {
        if (StringUtils.hasText(requestedTitle)) {
            return requestedTitle;
        }
        String filename = StringUtils.hasText(originalFilename) ? originalFilename : null;
        if (!StringUtils.hasText(filename)) {
            return "제 " + episodeNo + "화";
        }
        int extensionIndex = filename.lastIndexOf('.');
        return extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
    }

    private String resolveHeadingTitle(EpisodeHeading heading) {
        if (StringUtils.hasText(heading.title())) {
            return heading.title();
        }
        return "제 " + heading.episodeNo() + "화";
    }

    private record EpisodeHeading(
            int start,
            int end,
            int episodeNo,
            String title
    ) {
    }
}
