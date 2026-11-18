package org.monitoring.catchholebackend.global.config.paymentreview;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentReviewProperties.class)
public class PaymentReviewConfig {
}
