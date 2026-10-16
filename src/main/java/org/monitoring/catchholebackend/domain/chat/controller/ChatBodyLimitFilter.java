package org.monitoring.catchholebackend.domain.chat.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;

@lombok.RequiredArgsConstructor
public class ChatBodyLimitFilter extends OncePerRequestFilter {
    private final org.monitoring.catchholebackend.global.config.chat.ChatProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/internal/v1/chat-runs")
                && !path.matches("/api/v1/works/[^/]+/(chat-questions|chat-sessions).*");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        byte[] body = request.getInputStream().readNBytes(properties.maxBodyBytes() + 1);
        if (body.length > properties.maxBodyBytes()) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter()
                    .write(
                            new tools.jackson.databind.ObjectMapper()
                                    .writeValueAsString(
                                            org.monitoring.catchholebackend.global.common.response
                                                    .CommonResponse.failure(
                                                    "채팅 요청 크기를 초과했습니다.",
                                                    org.monitoring.catchholebackend.global.common
                                                            .response.ErrorResponse.of(
                                                            "REQUEST_BODY_TOO_LARGE", 413))));
            return;
        }
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        var bytes = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            public int read() {
                                return bytes.read();
                            }

                            public boolean isFinished() {
                                return bytes.available() == 0;
                            }

                            public boolean isReady() {
                                return true;
                            }

                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(
                                new InputStreamReader(
                                        getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                },
                response);
    }
}
