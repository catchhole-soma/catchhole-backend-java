package org.monitoring.catchholebackend.domain.work.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkUpdateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;

public interface WorkService {

    WorkResponse createWork(Long memberId, WorkCreateRequest request);

    List<WorkResponse> getMyWorks(Long memberId);

    WorkResponse getWork(Long memberId, UUID workId);

    WorkResponse updateWork(Long memberId, UUID workId, WorkUpdateRequest request);

    void deleteWork(Long memberId, UUID workId);
}
