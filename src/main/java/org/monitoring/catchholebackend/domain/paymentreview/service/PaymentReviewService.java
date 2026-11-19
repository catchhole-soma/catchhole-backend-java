package org.monitoring.catchholebackend.domain.paymentreview.service;

import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.paymentreview.dto.response.PaymentReviewPageResponse;

public interface PaymentReviewService {
    boolean isAllowed(MemberPrincipal member);

    PaymentReviewPageResponse getPage(MemberPrincipal member);
}
