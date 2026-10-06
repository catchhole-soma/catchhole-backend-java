package org.monitoring.catchholebackend.domain.upload.parser;

import java.util.Arrays;
import java.util.Locale;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;

public enum DocumentFormat {
    TXT(".txt", "text/plain; charset=UTF-8"),
    DOCX(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    HWP(".hwp", "application/x-hwp"),
    HWPX(".hwpx", "application/hwp+zip");

    private final String extension;
    private final String mimeType;

    DocumentFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String mimeType() {
        return mimeType;
    }

    public static DocumentFormat fromFilename(String filename) {
        if (filename == null) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_TYPE_NOT_SUPPORTED);
        }
        String normalized = filename.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> normalized.endsWith(format.extension)).findFirst()
                .orElseThrow(() -> new AppException(UploadErrorCode.UPLOAD_FILE_TYPE_NOT_SUPPORTED));
    }
}
