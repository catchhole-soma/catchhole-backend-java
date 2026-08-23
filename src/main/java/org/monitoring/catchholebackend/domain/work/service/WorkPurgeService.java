package org.monitoring.catchholebackend.domain.work.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkPurgeCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkPurgeResponse;

public interface WorkPurgeService {

    WorkPurgeResponse requestPurge(Long memberId, UUID workId, WorkPurgeCreateRequest request);

    WorkPurgeResponse getPurgeRequest(Long memberId, UUID requestId);

    WorkPurgeResponse getPurgeRequestByWork(Long memberId, UUID workId);

    WorkPurgeResponse retryPurge(Long memberId, UUID requestId);
}
