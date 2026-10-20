package org.monitoring.catchholebackend.global.config.monitoring;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration(proxyBeanMethods = false)
public class SecurityRejectionMetricsConfig {

    @Bean
    FilterRegistrationBean<SecurityRejectionMetricsFilter> securityRejectionMetricsFilter(
            SecurityFilterProperties securityFilterProperties,
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        var registration = new FilterRegistrationBean<>(new SecurityRejectionMetricsFilter(handlerMapping));
        // Observation 안쪽, Security와 요청 로그 바깥에서 응답 완료 후 패턴을 보완한다.
        registration.setOrder(securityFilterProperties.getOrder() - 2);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }
}
