package org.monitoring.catchholebackend.global.config.logging;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.global.common.response.FieldErrorResponse;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UrlPathHelper;

@Slf4j
public final class UploadFailureLogger {

    private static final String METADATA = UploadFailureLogger.class.getName() + ".metadata";
    private static final String FAILURE = UploadFailureLogger.class.getName() + ".failure";
    private static final String STAGE = UploadFailureLogger.class.getName() + ".stage";
    private static final Pattern ROUTE = Pattern.compile("/api/v1/works/([^/]+)/episodes(/detect)?");
    private static final Pattern FIELD = Pattern.compile(
            "uploadType|singleEpisodeNo|singleEpisodeTitle|episodeConfirmations(?:\\[\\d{1,6}\\])?(?:\\.(?:detectionOrder|episodeNo|title))?");
    private static final int MAX_ITEMS = 8;
    private static final int MAX_NAME_CODE_POINTS = 120;

    /** 요청이 어느 단계까지 진행했는지 나타내는 고정 코드. */
    public enum Stage {
        REQUEST_BINDING,
        ADMISSION,
        FILE_READ,
        CHARACTER_LIMIT,
        EPISODE_NO_DETECTION,
        EPISODE_STRUCTURE,
        CONFIRMATION,
        NUMBER_AVAILABILITY,
        STORAGE
    }

    public record UnresolvedFile(int index, MultipartFile file, String reason) {
    }

    private UploadFailureLogger() {
    }

    public static void captureMetadata(HttpServletRequest request, Enum<?> uploadType, List<MultipartFile> files) {
        List<FileInfo> summaries = new ArrayList<>();
        for (int i = 0; i < Math.min(files.size(), MAX_ITEMS); i++) {
            MultipartFile file = files.get(i);
            summaries.add(fileInfo(i, file));
        }
        request.setAttribute(METADATA, new Metadata(uploadType.name(), files.size(), List.copyOf(summaries)));
        request.setAttribute(STAGE, new StageMark(Stage.ADMISSION, null));
    }

    /** 특정 파일과 무관한 단계에 들어간다. 이후 실패의 failedFile은 NONE이다. */
    public static void markStage(Stage stage) {
        setStageMark(new StageMark(stage, null));
    }

    /** 파일 하나를 처리하는 단계에 들어간다. 목록 상한과 무관하게 실패 시 이 파일을 기록한다. */
    public static void markStage(Stage stage, int index, MultipartFile file) {
        setStageMark(new StageMark(stage, fileInfo(index, file)));
    }

    /** 요청은 성공하므로 실패 로그가 남지 않는 번호 미확정 파일을 기록한다. */
    public static void logUnresolvedEpisodeNumbers(UUID workId, int fileCount, List<UnresolvedFile> unresolvedFiles) {
        if (unresolvedFiles.isEmpty()) return;
        List<UnresolvedFileInfo> summaries = unresolvedFiles.stream().limit(MAX_ITEMS)
                .map(unresolved -> new UnresolvedFileInfo(
                        fileInfo(unresolved.index(), unresolved.file()), unresolved.reason()))
                .toList();
        log.info("회차 번호 미인식 파일: operation=DETECT, workId={}, fileCount={}, unresolvedCount={}, unresolvedFiles={}, omittedFiles={}",
                workId, fileCount, unresolvedFiles.size(), summaries, unresolvedFiles.size() - summaries.size());
    }

    public static void captureException(Throwable exception) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            request.setAttribute(FAILURE, new Failure(exception.getClass().getSimpleName(),
                    exception.getCause() == null ? "NONE" : exception.getCause().getClass().getSimpleName()));
        }
    }

    public static void logFailure(ResultCode code, List<FieldErrorResponse> details) {
        HttpServletRequest request = currentRequest();
        if (request == null || !"POST".equals(request.getMethod())) return;
        String path;
        try {
            path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (IllegalArgumentException exception) {
            return;
        }
        var route = ROUTE.matcher(path);
        if (!route.matches()) return;

        Metadata metadata = request.getAttribute(METADATA) instanceof Metadata value
                ? value : new Metadata("UNKNOWN", -1, List.of());
        Failure failure = request.getAttribute(FAILURE) instanceof Failure value
                ? value : new Failure("UNKNOWN", "UNKNOWN");
        List<String> fields = details.stream().limit(MAX_ITEMS)
                .map(detail -> detail.field() != null && FIELD.matcher(detail.field()).matches()
                        ? detail.field() : "UNKNOWN")
                .toList();
        StageMark stage = request.getAttribute(STAGE) instanceof StageMark value
                ? value : new StageMark(Stage.REQUEST_BINDING, null);
        log.warn("회차 업로드 요청 실패: operation={}, workId={}, errorCode={}, status={}, uploadType={}, fileCount={}, requestBytes={}, files={}, omittedFiles={}, failureStage={}, failedFile={}, exceptionType={}, causeType={}, invalidFields={}",
                route.group(2) == null ? "UPLOAD" : "DETECT", workId(route.group(1)), code.getCode(),
                code.getStatus().value(), metadata.uploadType(), metadata.fileCount(), request.getContentLengthLong(),
                metadata.files(), Math.max(0, metadata.fileCount() - metadata.files().size()),
                stage.stage(), stage.file() == null ? "NONE" : stage.file(),
                failure.exceptionType(), failure.causeType(), fields);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    private static void setStageMark(StageMark mark) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            request.setAttribute(STAGE, mark);
        }
    }

    private static FileInfo fileInfo(int index, MultipartFile file) {
        String filename = file.getOriginalFilename();
        return new FileInfo(index, safeName(filename), format(filename), file.getSize());
    }

    /** 사용자 파일명으로 로그 줄을 위조하지 못하게 경로·제어 문자를 지우고 길이를 제한한다. */
    private static String safeName(String filename) {
        if (filename == null) return "UNKNOWN";
        String basename = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        if (basename.isBlank()) return "UNKNOWN";
        StringBuilder safe = new StringBuilder();
        basename.codePoints().limit(MAX_NAME_CODE_POINTS)
                .forEach(codePoint -> safe.appendCodePoint(isLogUnsafe(codePoint) ? '?' : codePoint));
        return safe.toString();
    }

    private static boolean isLogUnsafe(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }

    private static String format(String filename) {
        if (filename == null) return "UNKNOWN";
        String normalized = filename.toLowerCase(Locale.ROOT);
        for (String extension : List.of("txt", "docx", "hwp", "hwpx")) {
            if (normalized.endsWith("." + extension)) return extension.toUpperCase(Locale.ROOT);
        }
        return "OTHER";
    }

    private static String workId(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            return "UNKNOWN";
        }
    }

    private record FileInfo(int index, String name, String format, long bytes) {
    }

    private record UnresolvedFileInfo(FileInfo file, String reason) {
    }

    private record StageMark(Stage stage, FileInfo file) {
    }

    private record Metadata(String uploadType, int fileCount, List<FileInfo> files) {
    }

    private record Failure(String exceptionType, String causeType) {
    }
}
