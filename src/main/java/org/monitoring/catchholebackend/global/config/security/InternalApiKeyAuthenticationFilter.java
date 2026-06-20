package org.monitoring.catchholebackend.global.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class InternalApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PRINCIPAL = "internal-worker";
    private static final List<SimpleGrantedAuthority> INTERNAL_AUTHORITIES = List.of(
            new SimpleGrantedAuthority("ROLE_INTERNAL")
    );

    private final InternalApiProperties internalApiProperties;
    private final SecurityErrorResponseWriter responseWriter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String apiKey = request.getHeader(SecurityConstant.INTERNAL_API_KEY_HEADER);
        if (!isValidApiKey(apiKey)) {
            responseWriter.write(response, CommonErrorCode.AUTH_UNAUTHORIZED);
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                INTERNAL_PRINCIPAL,
                null,
                INTERNAL_AUTHORITIES
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isValidApiKey(String apiKey) {
        return StringUtils.hasText(apiKey) && apiKey.equals(internalApiProperties.apiKey());
    }
}
