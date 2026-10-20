package org.monitoring.catchholebackend.global.config.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

final class SecurityRejectionMetricsFilter extends OncePerRequestFilter {

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

    SecurityRejectionMetricsFilter(ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(request, response);
        if (response.getStatus() != 401 && response.getStatus() != 403) {
            return;
        }
        ServerHttpObservationFilter.findObservationContext(request)
                .filter(context -> context.getPathPattern() == null)
                .ifPresent(context -> resolvePathPattern(request, context));
    }

    private void resolvePathPattern(HttpServletRequest request, ServerRequestObservationContext context) {
        boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
        try {
            if (parsedHere) {
                ServletRequestPathUtils.parseAndCache(request);
            }
            // MVC가 메서드·매핑 조건에 맞는 등록 경로를 찾아 Observation에 패턴을 설정한다.
            // 반환된 체인은 실행하지 않으므로 컨트롤러와 인터셉터는 호출하지 않는다.
            handlerMapping.getObject().getHandler(request);
        } catch (Exception ignored) {
            // 매핑 실패(405/415 등)는 원래 인증 응답을 유지하고 UNKNOWN으로 집계한다.
            context.setPathPattern(null);
        } finally {
            if (parsedHere) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }
}
