package org.monitoring.catchholebackend.global.config.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.monitoring.catchholebackend.domain.auth.security.JwtMemberAuthenticationConverter;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.config.auth.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            JwtMemberAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SecurityConstant.PUBLIC_URLS).permitAll()
                        .requestMatchers(SecurityConstant.ADMIN_URLS).hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtEncoder jwtEncoder(AuthProperties authProperties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey(authProperties)));
    }

    @Bean
    public JwtDecoder jwtDecoder(AuthProperties authProperties) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey(authProperties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtMemberAuthenticationConverter jwtMemberAuthenticationConverter(MemberRepository memberRepository) {
        return new JwtMemberAuthenticationConverter(memberRepository);
    }

    private SecretKey jwtSecretKey(AuthProperties authProperties) {
        String secret = authProperties.jwt().secret();
        if (!StringUtils.hasText(secret) || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("auth.jwt.secret은 최소 32바이트 이상이어야 합니다.");
        }
        return new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), HMAC_SHA_256);
    }
}
