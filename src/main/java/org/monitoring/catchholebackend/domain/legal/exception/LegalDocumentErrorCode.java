package org.monitoring.catchholebackend.domain.legal.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum LegalDocumentErrorCode implements ResultCode {

    LEGAL_DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "법률 문서를 찾을 수 없습니다."),
    LEGAL_DOCUMENTS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 법률 문서를 불러올 수 없습니다."),
    LEGAL_DOCUMENT_NOT_CURRENT(HttpStatus.CONFLICT, "법률 문서가 변경되었습니다. 최신 문서를 다시 확인해주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
