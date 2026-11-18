package org.monitoring.catchholebackend.global.config.paymentreview;

import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "payment-review")
public class PaymentReviewProperties {
    private boolean enabled;
    private Set<Long> memberIds = new HashSet<>();
    private String storeId = "";
    private String channelKey = "";
}
