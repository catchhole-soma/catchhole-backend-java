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
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class EpisodeUploadParser {

    private static final Pattern EPISODE_HEADING_PATTERN = Pattern.compile(
            "(?im)^\\s*(?:제\\s*)?(\\d+)\\s*(?:화|회|편|장)\\s*[:：\\-.\\)]?\\s*(.*)$"
                    + "|^\\s*(?:EP|Episode)\\s*[._\\s-]?(\\d+)\\s*[:：\\-.\\)]?\\s*(.*)$"
    );

    private static final Pattern EPISODE_NO_PATTERN = Pattern.compile(
            "(?i)(?:제\\s*)?(\\d+)\\s*(?:화|회|편|장)|(?:EP|Episode)\\s*[._\\s-]?(\\d+)"
    );

    public List<ParsedUploadFile> parse(EpisodeUploadRequest request, List<MultipartFile> episodeFiles) {
        validateEpisodeFiles(episodeFiles);
        return switch (request.uploadType()) {
            case SINGLE_EPISODE -> parseSingleEpisode(request, episodeFiles);
            case MULTI_EPISODE_MULTI_FILE -> parseMultiEpisodeMultiFile(episodeFiles);
            case MULTI_EPISODE_SINGLE_FILE -> parseMultiEpisodeSingleFile(episodeFiles);
            case INITIAL_IMPORT -> throw new AppException(UploadErrorCode.UPLOAD_TYPE_NOT_SUPPORTED);
        };
    }

    private List<ParsedUploadFile> parseSingleEpisode(
            EpisodeUploadRequest request,
            List<MultipartFile> episodeFiles
    ) {
        if (episodeFiles.size() != 1) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }
        if (request.episodeNo() == null) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_REQUIRED);
        }

        MultipartFile file = episodeFiles.get(0);
        ParsedEpisode episode = new ParsedEpisode(
                request.episodeNo(),
                resolveTitle(request.title(), file.getOriginalFilename(), request.episodeNo()),
                readText(file)
        );
        return List.of(new ParsedUploadFile(file, List.of(episode)));
    }

    private List<ParsedUploadFile> parseMultiEpisodeMultiFile(List<MultipartFile> episodeFiles) {
        List<ParsedUploadFile> parsedUploadFiles = new ArrayList<>();
        for (MultipartFile file : episodeFiles) {
            int episodeNo = detectEpisodeNo(file);
            ParsedEpisode episode = new ParsedEpisode(
                    episodeNo,
                    resolveTitle(null, file.getOriginalFilename(), episodeNo),
                    readText(file)
            );
            parsedUploadFiles.add(new ParsedUploadFile(file, List.of(episode)));
        }
        return parsedUploadFiles;
    }

    private List<ParsedUploadFile> parseMultiEpisodeSingleFile(List<MultipartFile> episodeFiles) {
        if (episodeFiles.size() != 1) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }

        MultipartFile file = episodeFiles.get(0);
        String text = readText(file);
        List<EpisodeHeading> headings = findEpisodeHeadings(text);
        if (headings.isEmpty()) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_DETECTION_FAILED);
        }
        log.info(
                "Detected {} episode headings from upload file. filename={}, textLength={}",
                headings.size(),
                resolveOriginalFilename(file),
                text.length()
        );

        List<ParsedEpisode> episodes = new ArrayList<>();
        for (int index = 0; index < headings.size(); index++) {
            EpisodeHeading heading = headings.get(index);
            int contentEnd = index + 1 < headings.size() ? headings.get(index + 1).start() : text.length();
            String content = text.substring(heading.end(), contentEnd).trim();
            if (!StringUtils.hasText(content)) {
                throw new AppException(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED);
            }
            String title = resolveHeadingTitle(heading);
            log.info(
                    "Parsed episode from upload file. filename={}, episodeNo={}, title={}, headingLine={}, headingOffset={}..{}, contentOffset={}..{}, contentCharCount={}",
                    resolveOriginalFilename(file),
                    heading.episodeNo(),
                    title,
                    lineNumberOf(text, heading.start()),
                    heading.start(),
                    heading.end(),
                    heading.end(),
                    contentEnd,
                    content.length()
            );
            episodes.add(new ParsedEpisode(
                    heading.episodeNo(),
                    title,
                    content
            ));
        }

        return List.of(new ParsedUploadFile(file, episodes));
    }

    private void validateEpisodeFiles(List<MultipartFile> episodeFiles) {
        if (episodeFiles == null || episodeFiles.isEmpty()) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }
        if (episodeFiles.stream().anyMatch(MultipartFile::isEmpty)) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_EMPTY);
        }
    }

    private int detectEpisodeNo(MultipartFile file) {
        return detectEpisodeNo(file.getOriginalFilename())
                .or(() -> detectEpisodeNo(readText(file)))
                .orElseThrow(() -> new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_DETECTION_FAILED));
    }

    private Optional<Integer> detectEpisodeNo(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Matcher matcher = EPISODE_NO_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String episodeNo = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Optional.of(Integer.parseInt(episodeNo));
    }

    private List<EpisodeHeading> findEpisodeHeadings(String text) {
        Matcher matcher = EPISODE_HEADING_PATTERN.matcher(text);
        List<EpisodeHeading> headings = new ArrayList<>();
        while (matcher.find()) {
            String episodeNo = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String title = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            headings.add(new EpisodeHeading(
                    matcher.start(),
                    matcher.end(),
                    Integer.parseInt(episodeNo),
                    title == null ? null : title.trim()
            ));
        }
        return headings;
    }

    private String readText(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_READ_FAILED, exception);
        }
    }

    private int lineNumberOf(String text, int offset) {
        int lineNumber = 1;
        int end = Math.min(offset, text.length());
        for (int index = 0; index < end; index++) {
            if (text.charAt(index) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    private String resolveOriginalFilename(MultipartFile file) {
        return StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "untitled.txt";
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
