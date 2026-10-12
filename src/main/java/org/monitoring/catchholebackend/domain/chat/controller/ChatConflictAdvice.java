package org.monitoring.catchholebackend.domain.chat.controller;

import org.monitoring.catchholebackend.domain.chat.exception.ChatErrorCode;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A simultaneous callId collision across different works still has a safe conflict response. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ChatController.class, InternalChatController.class})
public class ChatConflictAdvice {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<CommonResponse<Void>> conflict(DataIntegrityViolationException ignored) {
        var code = ChatErrorCode.CHAT_CONFLICT;
        return ResponseEntity.status(code.getStatus())
                .body(
                        CommonResponse.failure(
                                code.getMessage(), ErrorResponse.of(code.getCode(), 409)));
    }
}
