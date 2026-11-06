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
    private static final Pattern ROUTE = Pattern.compile("/api/v1/works/([^/]+)/episodes(/detect)?");
    private static final Pattern FIELD = Pattern.compile(
            "uploadType|singleEpisodeNo|singleEpisodeTitle|episodeConfirmations(?:\\[\\d{1,6}\\])?(?:\\.(?:detectionOrder|episodeNo|title))?");
    private static final int MAX_ITEMS = 8;

    private UploadFailureLogger() {
    }

    public static void captureMetadata(HttpServletRequest request, Enum<?> uploadType, List<MultipartFile> files) {
        List<FileInfo> summaries = new ArrayList<>();
        for (int i = 0; i < Math.min(files.size(), MAX_ITEMS); i++) {
            MultipartFile file = files.get(i);
            summaries.add(new FileInfo(i, format(file.getOriginalFilename()), file.getSize()));
        }
        request.setAttribute(METADATA, new Metadata(uploadType.name(), files.size(), List.copyOf(summaries)));
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
        log.warn("회차 업로드 요청 실패: operation={}, workId={}, errorCode={}, status={}, uploadType={}, fileCount={}, requestBytes={}, files={}, omittedFiles={}, exceptionType={}, causeType={}, invalidFields={}",
                route.group(2) == null ? "UPLOAD" : "DETECT", workId(route.group(1)), code.getCode(),
                code.getStatus().value(), metadata.uploadType(), metadata.fileCount(), request.getContentLengthLong(),
                metadata.files(), Math.max(0, metadata.fileCount() - metadata.files().size()),
                failure.exceptionType(), failure.causeType(), fields);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
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

    private record FileInfo(int index, String format, long bytes) {
    }

    private record Metadata(String uploadType, int fileCount, List<FileInfo> files) {
    }

    private record Failure(String exceptionType, String causeType) {
    }
}
