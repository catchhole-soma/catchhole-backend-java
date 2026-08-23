package org.monitoring.catchholebackend.global.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.ErrorResponse;
import org.monitoring.catchholebackend.global.common.response.FieldErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<CommonResponse<Void>> handleAppException(AppException exception) {
        ResponseEntity<CommonResponse<Void>> response =
                buildErrorResponse(
                        exception.getResultCode(),
                        exception.getMessage(),
                        List.of(),
                        exception.getErrorContext()
                );
        if (exception.getRetryAfterSeconds() == null) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(response.getBody());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        List<FieldErrorResponse> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldErrorResponse(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldErrorResponse::field))
                .toList();

        return buildErrorResponse(CommonErrorCode.REQUEST_VALIDATION_FAILED, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CommonResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException exception
    ) {
        List<FieldErrorResponse> details = exception.getConstraintViolations()
                .stream()
                .map(violation -> new FieldErrorResponse(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()
                ))
                .sorted(Comparator.comparing(FieldErrorResponse::field))
                .toList();

        return buildErrorResponse(CommonErrorCode.REQUEST_VALIDATION_FAILED, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResponse<Void>> handleHttpMessageNotReadableException() {
        return buildErrorResponse(CommonErrorCode.REQUEST_INVALID_ARGUMENT, List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CommonResponse<Void>> handleMaxUploadSizeExceededException() {
        return buildErrorResponse(UploadErrorCode.UPLOAD_SIZE_LIMIT_EXCEEDED, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CommonResponse<Void>> handleMethodArgumentTypeMismatchException() {
        return buildErrorResponse(CommonErrorCode.REQUEST_INVALID_ARGUMENT, List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<CommonResponse<Void>> handleMissingServletRequestParameterException() {
        return buildErrorResponse(CommonErrorCode.REQUEST_INVALID_ARGUMENT, List.of());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<CommonResponse<Void>> handleMissingServletRequestPartException() {
        return buildErrorResponse(CommonErrorCode.REQUEST_INVALID_ARGUMENT, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CommonResponse<Void>> handleNoResourceFoundException() {
        return buildErrorResponse(CommonErrorCode.RESOURCE_NOT_FOUND, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResponse<Void>> handleException() {
        return buildErrorResponse(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR, List.of());
    }

    private ResponseEntity<CommonResponse<Void>> buildErrorResponse(
            ResultCode resultCode,
            List<FieldErrorResponse> details
    ) {
        return buildErrorResponse(resultCode, resultCode.getMessage(), details);
    }

    private ResponseEntity<CommonResponse<Void>> buildErrorResponse(
            ResultCode resultCode,
            String message,
            List<FieldErrorResponse> details
    ) {
        return buildErrorResponse(resultCode, message, details, Map.of());
    }

    private ResponseEntity<CommonResponse<Void>> buildErrorResponse(
            ResultCode resultCode,
            String message,
            List<FieldErrorResponse> details,
            Map<String, Object> context
    ) {
        ErrorResponse error = ErrorResponse.of(
                resultCode.getCode(),
                resultCode.getStatus().value(),
                details,
                context
        );
        CommonResponse<Void> response = CommonResponse.failure(message, error);

        return ResponseEntity.status(resultCode.getStatus()).body(response);
    }
}
