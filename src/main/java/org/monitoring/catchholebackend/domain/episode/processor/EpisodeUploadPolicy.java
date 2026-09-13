package org.monitoring.catchholebackend.domain.episode.processor;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadPolicyResponse;
import org.monitoring.catchholebackend.domain.episode.parser.EpisodeFileParser;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeUploadPolicyRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EpisodeUploadPolicy {

    private final EpisodeUploadPolicyRepository policyRepository;

    public EpisodeUploadPolicyResponse getPolicy(UUID workId) {
        long completedCount = policyRepository.countCompletedSingleEpisodes(workId);
        return new EpisodeUploadPolicyResponse(
                completedCount,
                0,
                true,
                policyRepository.countPendingCharacterCandidates(workId),
                policyRepository.countPendingWorldSettingCandidates(workId),
                EpisodeFileParser.MAX_UPLOAD_CHARACTERS
        );
    }
}
