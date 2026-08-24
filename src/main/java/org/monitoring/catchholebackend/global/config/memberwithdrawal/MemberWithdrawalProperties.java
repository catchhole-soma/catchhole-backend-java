package org.monitoring.catchholebackend.global.config.memberwithdrawal;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "member.withdrawal")
public class MemberWithdrawalProperties {

    private boolean schedulingEnabled = true;

    private long fixedDelayMs = 10_000L;

    private Duration retryDelay = Duration.ofSeconds(10);

    private Duration auditRetention = Duration.ofDays(365);

    private int batchSize = 10;
}
