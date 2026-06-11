package org.monitoring.catchholebackend.global.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.ErrorResponse;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.objectMapper = objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
    }

    public void write(HttpServletResponse response, ResultCode resultCode) throws IOException {
        ErrorResponse error = ErrorResponse.of(
                resultCode.getCode(),
                resultCode.getStatus().value(),
                List.of()
        );
        CommonResponse<Void> body = CommonResponse.failure(resultCode.getMessage(), error);

        response.setStatus(resultCode.getStatus().value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
