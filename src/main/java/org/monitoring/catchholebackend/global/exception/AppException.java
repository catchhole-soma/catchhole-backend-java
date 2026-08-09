package org.monitoring.catchholebackend.global.exception;

import java.util.Map;
import lombok.Getter;

@Getter
public class AppException extends RuntimeException {

    private final ResultCode resultCode;
    private final Long retryAfterSeconds;
    private final Map<String, Object> errorContext;

    public AppException(ResultCode resultCode) {
        super(resolveMessage(resultCode, null));
        this.resultCode = resultCode;
        this.retryAfterSeconds = null;
        this.errorContext = Map.of();
    }

    public AppException(ResultCode resultCode, String message) {
        super(resolveMessage(resultCode, message));
        this.resultCode = resultCode;
        this.retryAfterSeconds = null;
        this.errorContext = Map.of();
    }

    public AppException(ResultCode resultCode, Throwable cause) {
        super(resolveMessage(resultCode, null), cause);
        this.resultCode = resultCode;
        this.retryAfterSeconds = null;
        this.errorContext = Map.of();
    }

    public AppException(ResultCode resultCode, String message, Throwable cause) {
        super(resolveMessage(resultCode, message), cause);
        this.resultCode = resultCode;
        this.retryAfterSeconds = null;
        this.errorContext = Map.of();
    }

    public AppException(ResultCode resultCode, long retryAfterSeconds) {
        super(resolveMessage(resultCode, null));
        this.resultCode = resultCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.errorContext = Map.of();
    }

    public AppException(ResultCode resultCode, Map<String, Object> errorContext) {
        super(resolveMessage(resultCode, null));
        this.resultCode = resultCode;
        this.retryAfterSeconds = null;
        this.errorContext = Map.copyOf(errorContext);
    }

    private static String resolveMessage(ResultCode resultCode, String message) {
        return message != null ? message + " " + resultCode.getMessage() : resultCode.getMessage();
    }
}
