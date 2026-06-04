package org.monitoring.catchholebackend.global.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.ErrorResponse;
import org.monitoring.catchholebackend.global.common.response.FieldErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<CommonResponse<Void>> handleAppException(AppException exception) {
        return buildErrorResponse(exception.getResultCode(), exception.getMessage(), List.of());
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
        ErrorResponse error = ErrorResponse.of(
                resultCode.getCode(),
                resultCode.getStatus().value(),
                details
        );
        CommonResponse<Void> response = CommonResponse.failure(message, error);

        return ResponseEntity.status(resultCode.getStatus()).body(response);
    }
}
