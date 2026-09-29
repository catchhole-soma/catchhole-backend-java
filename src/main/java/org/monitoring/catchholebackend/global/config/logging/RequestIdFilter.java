package org.monitoring.catchholebackend.global.config.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Slf4j
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = (String) request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        }
        response.setHeader("X-Request-ID", requestId);
        MDC.put("requestId", requestId);
        long startedAt = System.nanoTime();
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            try {
                // ponytail: 동기 API만 지원한다. Servlet 비동기 도입 시 완료 기록을 AsyncListener로 옮긴다.
                if (request.getDispatcherType() != DispatcherType.ERROR && shouldLog(request, status)) {
                    Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    log.info("HTTP 요청 완료: method={}, path={}, status={}, durationMs={}",
                            request.getMethod(), route != null ? route : request.getRequestURI(),
                            status, (System.nanoTime() - startedAt) / 1_000_000);
                }
            } finally {
                MDC.remove("requestId");
            }
        }
    }

    private boolean shouldLog(HttpServletRequest request, int status) {
        String path = request.getRequestURI();
        return status >= 400 || !(path.equals("/healthz")
                || path.equals("/actuator/health") || path.equals("/actuator/prometheus"));
    }
}
