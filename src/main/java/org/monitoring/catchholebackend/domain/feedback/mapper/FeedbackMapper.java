package org.monitoring.catchholebackend.domain.feedback.mapper;

import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenFeedbackRewardResult;
import org.monitoring.catchholebackend.domain.feedback.dto.response.FeedbackCreateResponse;
import org.monitoring.catchholebackend.domain.feedback.entity.Feedback;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.springframework.stereotype.Component;

@Component
public class FeedbackMapper {

    public Feedback toEntity(
            Member member,
            String content,
            String pagePath,
            AiTokenFeedbackRewardResult rewardResult
    ) {
        return Feedback.create(member, content, pagePath, rewardResult.requestId());
    }

    public FeedbackCreateResponse toResponse(
            Feedback feedback,
            AiTokenFeedbackRewardResult rewardResult
    ) {
        return new FeedbackCreateResponse(
                feedback.getId(),
                rewardResult.outcome(),
                rewardResult.requestId(),
                rewardResult.requestStatus(),
                feedback.getCreatedAt()
        );
    }
}
