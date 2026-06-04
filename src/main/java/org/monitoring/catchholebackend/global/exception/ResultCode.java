package org.monitoring.catchholebackend.global.exception;

import org.springframework.http.HttpStatus;

public interface ResultCode {

    String getCode();
    HttpStatus getStatus();
    String getMessage();
}
