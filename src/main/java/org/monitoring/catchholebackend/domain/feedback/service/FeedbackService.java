package org.monitoring.catchholebackend.domain.feedback.service;

import org.monitoring.catchholebackend.domain.feedback.dto.request.FeedbackCreateRequest;
import org.monitoring.catchholebackend.domain.feedback.dto.response.FeedbackCreateResponse;

public interface FeedbackService {

    /** 의견은 매번 저장하고 일반 피드백 보상 요청은 회원당 한 번만 연결한다. */
    FeedbackCreateResponse createFeedback(Long memberId, FeedbackCreateRequest request);
}
