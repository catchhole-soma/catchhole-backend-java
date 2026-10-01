package org.monitoring.catchholebackend.global.config.logging;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

// API context의 component scan과 별도 관리 context의 imports에서 각각 한 번 등록한다.
@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
public class RequestLoggingConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter(SecurityFilterProperties securityFilterProperties) {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(securityFilterProperties.getOrder() - 1);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        return registration;
    }
}
