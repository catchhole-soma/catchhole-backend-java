package org.monitoring.catchholebackend.domain.paymentreview.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
import org.monitoring.catchholebackend.domain.paymentreview.dto.response.PaymentReviewPageResponse;
import org.monitoring.catchholebackend.domain.paymentreview.mapper.PaymentReviewMapper;
import org.monitoring.catchholebackend.global.config.paymentreview.PaymentReviewProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class PaymentReviewService {
    private final PaymentReviewProperties properties;
    private final PaymentReviewMapper mapper;
    private final String conditions;
    private final String terms;
    private final String privacy;

    public PaymentReviewService(PaymentReviewProperties properties, PaymentReviewMapper mapper) throws IOException {
        this.properties = properties;
        this.mapper = mapper;
        conditions = readDocument("paid-usage-conditions.draft.ko.md");
        terms = readDocument("terms-of-service.paid.draft.ko.md");
        privacy = readDocument("privacy-policy.paid.draft.ko.md");
    }

    public boolean isAllowed(MemberPrincipal member) {
        return member != null && member.status() == MemberStatus.ACTIVE && properties.isEnabled()
                && properties.getMemberIds().contains(member.memberId());
    }

    public PaymentReviewPageResponse getPage(MemberPrincipal member) {
        if (!isAllowed(member)) throw new AppException(CommonErrorCode.AUTH_FORBIDDEN);
        return mapper.toPageResponse(properties, conditions, terms, privacy);
    }

    private String readDocument(String name) throws IOException {
        return new ClassPathResource("payment-review/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
