package org.monitoring.catchholebackend.domain.paymentreview.mapper;

import org.monitoring.catchholebackend.domain.paymentreview.dto.response.PaymentReviewAccessResponse;
import org.monitoring.catchholebackend.domain.paymentreview.dto.response.PaymentReviewPageResponse;
import org.monitoring.catchholebackend.global.config.paymentreview.PaymentReviewProperties;
import org.springframework.stereotype.Component;

@Component
public class PaymentReviewMapper {
    public PaymentReviewAccessResponse toAccessResponse(boolean allowed) {
        return new PaymentReviewAccessResponse(allowed);
    }

    public PaymentReviewPageResponse toPageResponse(PaymentReviewProperties properties,
            String conditions, String terms, String privacy) {
        String storeId = properties.getStoreId().trim();
        String channelKey = properties.getChannelKey().trim();
        return new PaymentReviewPageResponse(storeId, channelKey,
                storeId.startsWith("store-") && channelKey.startsWith("channel-key-"),
                conditions, terms, privacy);
    }
}
