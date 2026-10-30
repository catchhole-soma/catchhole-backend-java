package org.monitoring.catchholebackend.global.config.validation;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
@Configuration @EnableConfigurationProperties(ValidationExecutionProperties.class)
public class ValidationExecutionConfig {}
