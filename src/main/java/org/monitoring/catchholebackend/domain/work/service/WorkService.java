package org.monitoring.catchholebackend.domain.work.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkUpdateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;

public interface WorkService {

    /**
     * 인증된 회원을 조회하고 해당 회원을 소유자로 연결해 새 작품을 생성한다.
     */
    WorkResponse createWork(Long memberId, WorkCreateRequest request);

    /**
     * 회원이 소유한 작품 목록을 최신 생성순으로 조회한다.
     */
    List<WorkResponse> getMyWorks(Long memberId);

    /**
     * 작품 소유권을 확인한 뒤 단건 작품 정보를 조회한다.
     */
    WorkResponse getWork(Long memberId, UUID workId);

    /**
     * 작품 소유권을 확인한 뒤 제목, 장르, 설명 같은 작품 기본 정보를 수정한다.
     */
    WorkResponse updateWork(Long memberId, UUID workId, WorkUpdateRequest request);

    /**
     * 작품 소유권을 확인한 뒤 작품을 삭제한다.
     */
    void deleteWork(Long memberId, UUID workId);
}
