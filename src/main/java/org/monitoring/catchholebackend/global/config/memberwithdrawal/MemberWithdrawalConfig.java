package org.monitoring.catchholebackend.global.config.memberwithdrawal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MemberWithdrawalProperties.class)
public class MemberWithdrawalConfig {
}
