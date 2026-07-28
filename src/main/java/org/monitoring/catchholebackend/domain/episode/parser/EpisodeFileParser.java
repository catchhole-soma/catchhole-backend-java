package org.monitoring.catchholebackend.domain.episode.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.domain.upload.parser.TextDocumentReader;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class EpisodeFileParser {

    private static final Pattern EPISODE_HEADING_PATTERN = Pattern.compile(
            "(?im)^\\h*(?:제\\h*)?(\\d+)\\h*(?:화|회|편|장)\\h*[:：\\-.\\)]?\\h*(.*)$"
                    + "|^\\h*(?:EP|Episode|Chapter)\\h*[._\\h-]?(\\d+)\\h*[:：\\-.\\)]?\\h*(.*)$"
    );

    private static final Pattern EPISODE_NO_PATTERN = Pattern.compile(
            "(?i)(?:제\\s*)?(\\d+)\\s*(?:화|회|편|장)|(?:EP|Episode|Chapter)\\s*[._\\s-]?(\\d+)"
    );

    private final TextDocumentReader textDocumentReader;

    /**
     * 업로드 타입에 따라 원고의 회차 번호, 제목과 본문 경계를 감지한다.
     */
    public List<DetectedEpisodeFile> parseEpisodeFiles(
            EpisodeUploadType uploadType,
            Integer singleEpisodeNo,
            String singleEpisodeTitle,
            List<MultipartFile> sourceEpisodeFiles
    ) {
        validateModeSpecificMetadata(uploadType, singleEpisodeNo, singleEpisodeTitle);
        validateSourceFileCount(uploadType, sourceEpisodeFiles);
        if (uploadType == EpisodeUploadType.MULTI_EPISODE_MULTI_FILE) {
            validateOneEpisodePerFileFormats(sourceEpisodeFiles);
        }
        return switch (uploadType) {
            case SINGLE_EPISODE -> parseSingleEpisodeFile(
                    singleEpisodeNo,
                    singleEpisodeTitle,
                    sourceEpisodeFiles
            );
            case MULTI_EPISODE_MULTI_FILE -> parseOneEpisodePerFile(sourceEpisodeFiles);
            case MULTI_EPISODE_SINGLE_FILE -> parseMultipleEpisodesFromSingleFile(sourceEpisodeFiles);
        };
    }

    private List<DetectedEpisodeFile> parseSingleEpisodeFile(
            Integer singleEpisodeNo,
            String singleEpisodeTitle,
            List<MultipartFile> sourceEpisodeFiles
    ) {
        MultipartFile sourceFile = sourceEpisodeFiles.getFirst();
        String content = textDocumentReader.readText(sourceFile);
        int episodeNo = singleEpisodeNo == null
                ? resolveDetectedEpisodeNo(sourceFile, content)
                : singleEpisodeNo;
        DetectedEpisode detectedEpisode = new DetectedEpisode(
                episodeNo,
                resolveEpisodeTitle(singleEpisodeTitle, content, episodeNo),
                content
        );
        return List.of(new DetectedEpisodeFile(sourceFile, List.of(detectedEpisode)));
    }

    private List<DetectedEpisodeFile> parseOneEpisodePerFile(List<MultipartFile> sourceEpisodeFiles) {
        List<DetectedEpisodeFile> detectedEpisodeFiles = new ArrayList<>();
        for (MultipartFile sourceFile : sourceEpisodeFiles) {
            String content = textDocumentReader.readText(sourceFile);
            validateOneEpisodePerFileContent(content);
            int episodeNo = resolveDetectedEpisodeNo(sourceFile, content);
            DetectedEpisode detectedEpisode = new DetectedEpisode(
                    episodeNo,
                    resolveEpisodeTitle(null, content, episodeNo),
                    content
            );
            detectedEpisodeFiles.add(new DetectedEpisodeFile(sourceFile, List.of(detectedEpisode)));
        }
        return detectedEpisodeFiles;
    }

    private List<DetectedEpisodeFile> parseMultipleEpisodesFromSingleFile(
            List<MultipartFile> sourceEpisodeFiles
    ) {
        MultipartFile sourceFile = sourceEpisodeFiles.getFirst();
        String episodeText = textDocumentReader.readText(sourceFile);
        List<EpisodeHeading> headings = findEpisodeHeadings(episodeText);
        if (headings.isEmpty()) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_DETECTION_FAILED);
        }
        if (headings.size() == 1) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_COUNT_INVALID);
        }
        if (StringUtils.hasText(episodeText.substring(0, headings.getFirst().startOffset()))) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED);
        }
        validateDetectedHeadingOrder(headings);
        log.info(
                "Detected {} episode headings from upload file. filename={}, textLength={}",
                    headings.size(),
                    resolveOriginalFilename(sourceFile),
                episodeText.length()
        );

        List<DetectedEpisode> detectedEpisodes = new ArrayList<>();
        int headingLineNumber = 1;
        int headingLineScanOffset = 0;
        for (int index = 0; index < headings.size(); index++) {
            EpisodeHeading heading = headings.get(index);
            while (headingLineScanOffset < heading.startOffset()) {
                if (episodeText.charAt(headingLineScanOffset) == '\n') {
                    headingLineNumber++;
                }
                headingLineScanOffset++;
            }
            int episodeContentEndOffset = index + 1 < headings.size()
                    ? headings.get(index + 1).startOffset()
                    : episodeText.length();
            String episodeContent = episodeText.substring(heading.endOffset(), episodeContentEndOffset).trim();
            if (!StringUtils.hasText(episodeContent)) {
                throw new AppException(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED);
            }
            String title = normalizeTitle(heading.title());
            String sourceHeading = episodeText.substring(heading.startOffset(), heading.endOffset());
            log.info(
                    "Parsed episode from upload file. filename={}, episodeNo={}, title={}, headingLine={}, contentCharCount={}",
                    resolveOriginalFilename(sourceFile),
                    heading.episodeNo(),
                    title,
                    headingLineNumber,
                    episodeContent.length()
            );
            detectedEpisodes.add(new DetectedEpisode(
                    heading.episodeNo(),
                    title,
                    sourceHeading,
                    episodeContent
            ));
        }

        return List.of(new DetectedEpisodeFile(sourceFile, detectedEpisodes));
    }

    private void validateSourceFileCount(
            EpisodeUploadType uploadType,
            List<MultipartFile> sourceEpisodeFiles
    ) {
        if (sourceEpisodeFiles == null || sourceEpisodeFiles.isEmpty()) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }
        if (uploadType == EpisodeUploadType.MULTI_EPISODE_MULTI_FILE && sourceEpisodeFiles.size() < 2) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_COUNT_INVALID);
        }
        if (uploadType != EpisodeUploadType.MULTI_EPISODE_MULTI_FILE && sourceEpisodeFiles.size() != 1) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_REQUIRED);
        }
    }

    private void validateModeSpecificMetadata(
            EpisodeUploadType uploadType,
            Integer singleEpisodeNo,
            String singleEpisodeTitle
    ) {
        if (uploadType != EpisodeUploadType.SINGLE_EPISODE
                && (singleEpisodeNo != null || singleEpisodeTitle != null)) {
            throw new AppException(UploadErrorCode.UPLOAD_SINGLE_EPISODE_METADATA_NOT_ALLOWED);
        }
    }

    private void validateOneEpisodePerFileFormats(List<MultipartFile> sourceEpisodeFiles) {
        if (sourceEpisodeFiles.stream()
                .map(this::resolveOriginalFilename)
                .map(filename -> filename.toLowerCase(Locale.ROOT))
                .anyMatch(filename -> !filename.endsWith(".txt"))) {
            throw new AppException(UploadErrorCode.UPLOAD_MULTI_FILE_TYPE_NOT_SUPPORTED);
        }
    }

    private void validateOneEpisodePerFileContent(String content) {
        if (findEpisodeHeadings(content).size() > 1) {
            throw new AppException(UploadErrorCode.UPLOAD_MULTI_FILE_EPISODE_COUNT_INVALID);
        }
    }

    private int resolveDetectedEpisodeNo(MultipartFile sourceFile, String content) {
        Optional<Integer> filenameEpisodeNo = findEpisodeNoInFilename(sourceFile.getOriginalFilename());
        Optional<Integer> contentEpisodeNo = findFirstHeadingEpisodeNo(content);
        if (filenameEpisodeNo.isPresent()
                && contentEpisodeNo.isPresent()
                && !filenameEpisodeNo.get().equals(contentEpisodeNo.get())) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_CONFLICT);
        }
        return filenameEpisodeNo.or(() -> contentEpisodeNo)
                .orElseThrow(() -> new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_DETECTION_FAILED));
    }

    private Optional<Integer> findFirstHeadingEpisodeNo(String content) {
        List<EpisodeHeading> headings = findEpisodeHeadings(content);
        return headings.isEmpty() ? Optional.empty() : Optional.of(headings.getFirst().episodeNo());
    }

    private Optional<Integer> findEpisodeNoInFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return Optional.empty();
        }
        Matcher matcher = EPISODE_NO_PATTERN.matcher(filename);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String episodeNoText = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Optional.of(parseDetectedEpisodeNo(episodeNoText));
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
                    parseDetectedEpisodeNo(episodeNoText),
                    normalizeTitle(title)
            ));
        }
        return headings;
    }

    private int parseDetectedEpisodeNo(String episodeNoText) {
        try {
            int episodeNo = Integer.parseInt(episodeNoText);
            if (episodeNo < 1) {
                throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_INVALID);
            }
            return episodeNo;
        } catch (NumberFormatException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_EPISODE_NO_INVALID, exception);
        }
    }

    private String resolveEpisodeTitle(String requestedTitle, String content, int episodeNo) {
        if (StringUtils.hasText(requestedTitle)) {
            return requestedTitle.trim();
        }
        return findEpisodeHeadings(content).stream()
                .filter(heading -> heading.episodeNo() == episodeNo)
                .map(EpisodeHeading::title)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String normalizeTitle(String title) {
        return StringUtils.hasText(title) ? title.trim() : null;
    }

    private void validateDetectedHeadingOrder(List<EpisodeHeading> headings) {
        int previousEpisodeNo = 0;
        for (EpisodeHeading heading : headings) {
            if (heading.episodeNo() <= previousEpisodeNo) {
                throw new AppException(UploadErrorCode.UPLOAD_EPISODE_ORDER_INVALID);
            }
            previousEpisodeNo = heading.episodeNo();
        }
    }

    private String resolveOriginalFilename(MultipartFile sourceFile) {
        return textDocumentReader.requireOriginalFilename(sourceFile);
    }

    private record EpisodeHeading(int startOffset, int endOffset, int episodeNo, String title) {
    }
}
