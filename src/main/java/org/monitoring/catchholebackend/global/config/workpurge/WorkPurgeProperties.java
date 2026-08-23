package org.monitoring.catchholebackend.global.config.workpurge;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "work.purge")
public class WorkPurgeProperties {

    private boolean schedulingEnabled = true;

    private long fixedDelayMs = 10_000L;

    private Duration workerDrain = Duration.ofSeconds(75);

    private Duration staleProcessing = Duration.ofMinutes(15);

    private Duration auditRetention = Duration.ofDays(365);

    private int batchSize = 10;
}
