package org.monitoring.catchholebackend.domain.work.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCoverUpdateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;
import org.springframework.web.multipart.MultipartFile;

public interface WorkCoverService {
    WorkResponse updateWorkCover(Long memberId, UUID workId, WorkCoverUpdateRequest request, MultipartFile image);
    byte[] getWorkCover(Long memberId, UUID workId, UUID imageId);
    void retryPendingCleanup();
}
