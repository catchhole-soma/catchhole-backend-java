package org.monitoring.catchholebackend.global.config.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("chat")
public record ChatProperties(
        boolean enabled,
        String pythonUrl,
        String capabilitySecret,
        int maxBodyBytes,
        int dispatchThreads,
        int dispatchQueueCapacity,
        int maxQuestionCharacters,
        int maxActivePerMember,
        int maxCalls,
        int maxToolCalls,
        long attemptTokenBudget,
        Duration executionTimeout,
        Duration usageGrace,
        String retrievalEmbeddingVersion) {
    public ChatProperties {
        if (retrievalEmbeddingVersion == null) retrievalEmbeddingVersion = "v1";
        if (maxBodyBytes == 0) maxBodyBytes = 131072;
        if (dispatchThreads == 0) dispatchThreads = 2;
        if (dispatchQueueCapacity == 0) dispatchQueueCapacity = 32;
        if (maxQuestionCharacters == 0) maxQuestionCharacters = 4000;
        if (maxActivePerMember == 0) maxActivePerMember = 3;
        if (maxCalls == 0) maxCalls = 8;
        if (maxToolCalls == 0) maxToolCalls = 20;
        if (attemptTokenBudget == 0) attemptTokenBudget = 240000;
        if (executionTimeout == null) executionTimeout = Duration.ofSeconds(120);
        if (usageGrace == null) usageGrace = Duration.ofMinutes(10);
        if (maxBodyBytes < 512
                || maxBodyBytes > 131072
                || dispatchThreads < 1
                || dispatchThreads > 64
                || dispatchQueueCapacity < 1
                || dispatchQueueCapacity > 2048
                || maxQuestionCharacters < 1
                || maxQuestionCharacters > 4000
                || maxActivePerMember < 1
                || maxCalls < 1
                || maxToolCalls < 1
                || attemptTokenBudget < 1
                || executionTimeout.isNegative()
                || executionTimeout.isZero()
                || usageGrace.isNegative()
                || usageGrace.isZero()
                || retrievalEmbeddingVersion.isBlank()
                || retrievalEmbeddingVersion.length() > 50) throw new IllegalArgumentException("채팅 제한 설정이 올바르지 않습니다.");
        if (enabled
                && (capabilitySecret == null
                        || capabilitySecret.length() < 32
                        || pythonUrl == null
                        || !(pythonUrl.startsWith("http://") || pythonUrl.startsWith("https://"))))
            throw new IllegalArgumentException("채팅 주소와 별도 32자 이상의 실행 인증 비밀값이 필요합니다.");
    }
}
