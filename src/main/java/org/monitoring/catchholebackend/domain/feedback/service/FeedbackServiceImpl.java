package org.monitoring.catchholebackend.domain.feedback.service;

import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenFeedbackRewardResult;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.feedback.dto.request.FeedbackCreateRequest;
import org.monitoring.catchholebackend.domain.feedback.dto.response.FeedbackCreateResponse;
import org.monitoring.catchholebackend.domain.feedback.entity.Feedback;
import org.monitoring.catchholebackend.domain.feedback.exception.FeedbackErrorCode;
import org.monitoring.catchholebackend.domain.feedback.mapper.FeedbackMapper;
import org.monitoring.catchholebackend.domain.feedback.repository.FeedbackRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackServiceImpl implements FeedbackService {

    private static final int MIN_CONTENT_LENGTH = 35;
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int MAX_PAGE_PATH_LENGTH = 255;

    private final FeedbackRepository feedbackRepository;
    private final MemberRepository memberRepository;
    private final AiTokenService aiTokenService;
    private final FeedbackMapper feedbackMapper;

    @Override
    @Transactional
    public FeedbackCreateResponse createFeedback(Long memberId, FeedbackCreateRequest request) {
        String content = normalizeContent(request.content());
        String pagePath = normalizePagePath(request.pagePath());
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(MemberErrorCode.MEMBER_NOT_FOUND));
        member.validateActive();

        AiTokenFeedbackRewardResult rewardResult = aiTokenService
                .createGeneralFeedbackRewardRequest(memberId, content);
        Feedback saved = feedbackRepository.save(
                feedbackMapper.toEntity(member, content, pagePath, rewardResult)
        );
        return feedbackMapper.toResponse(saved, rewardResult);
    }

    private String normalizeContent(String content) {
        String normalized = content == null ? "" : content.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_CONTENT_LENGTH || length > MAX_CONTENT_LENGTH) {
            throw new AppException(FeedbackErrorCode.FEEDBACK_CONTENT_INVALID);
        }
        return normalized;
    }

    private String normalizePagePath(String pagePath) {
        if (pagePath == null || pagePath.isBlank()) {
            return null;
        }
        String normalized = pagePath.strip();
        if (normalized.length() > MAX_PAGE_PATH_LENGTH
                || !normalized.startsWith("/")
                || normalized.contains("?")
                || normalized.contains("#")) {
            throw new AppException(FeedbackErrorCode.FEEDBACK_PAGE_PATH_INVALID);
        }
        return normalized;
    }
}
