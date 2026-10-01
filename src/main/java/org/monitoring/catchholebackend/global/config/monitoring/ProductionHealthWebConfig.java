package org.monitoring.catchholebackend.global.config.monitoring;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionHealthWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 관리 포트 분리 뒤에도 기존 배포·외부 health 확인은 실제 API 포트를 검사한다.
        // 상태와 HTTP 응답 코드는 Actuator의 전체 health group이 결정한다.
        registry.addViewController("/actuator/health").setViewName("forward:/healthz");
    }
}
