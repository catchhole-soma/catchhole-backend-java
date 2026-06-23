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

    private static final Pattern EPISODE_HEADING_PATTERN = Pattern.compile(
            "(?im)^\\s*(?:제\\s*)?(\\d+)\\s*(?:화|회|편|장)\\s*[:：\\-.\\)]?\\s*(.*)$"
                    + "|^\\s*(?:EP|Episode)\\s*[._\\s-]?(\\d+)\\s*[:：\\-.\\)]?\\s*(.*)$"
    );

    private static final Pattern EPISODE_NO_PATTERN = Pattern.compile(
            "(?i)(?:제\\s*)?(\\d+)\\s*(?:화|회|편|장)|(?:EP|Episode)\\s*[._\\s-]?(\\d+)"
    );

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
