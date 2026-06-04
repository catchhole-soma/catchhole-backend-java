package org.monitoring.catchholebackend.global.exception;

import lombok.Getter;

@Getter
public class AppException extends RuntimeException {

    private final ResultCode resultCode;

    public AppException(ResultCode resultCode) {
        super(resolveMessage(resultCode, null));
        this.resultCode = resultCode;
    }

    public AppException(ResultCode resultCode, String message) {
        super(resolveMessage(resultCode, message));
        this.resultCode = resultCode;
    }

    public AppException(ResultCode resultCode, Throwable cause) {
        super(resolveMessage(resultCode, null), cause);
        this.resultCode = resultCode;
    }

    public AppException(ResultCode resultCode, String message, Throwable cause) {
        super(resolveMessage(resultCode, message), cause);
        this.resultCode = resultCode;
    }

    private static String resolveMessage(ResultCode resultCode, String message) {
        return message != null ? message + " " + resultCode.getMessage() : resultCode.getMessage();
    }
}
