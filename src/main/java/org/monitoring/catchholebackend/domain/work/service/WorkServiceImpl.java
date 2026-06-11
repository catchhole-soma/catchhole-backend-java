package org.monitoring.catchholebackend.domain.work.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkUpdateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.mapper.WorkMapper;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkServiceImpl implements WorkService {

    private final WorkRepository workRepository;
    private final MemberRepository memberRepository;
    private final WorkMapper workMapper;

    @Override
    @Transactional
    public WorkResponse createWork(Long memberId, WorkCreateRequest request) {
        Member member = getMember(memberId);
        Work work = workMapper.toEntity(request, member);
        return workMapper.toResponse(workRepository.save(work));
    }

    @Override
    public List<WorkResponse> getMyWorks(Long memberId) {
        return workMapper.toResponseList(workRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId));
    }

    @Override
    @Transactional
    public WorkResponse updateWork(Long memberId, UUID workId, WorkUpdateRequest request) {
        Work work = getOwnedWork(workId, memberId);
        work.updateInfo(request.title(), request.genre(), request.description());
        return workMapper.toResponse(work);
    }

    @Override
    @Transactional
    public void deleteWork(Long memberId, UUID workId) {
        Work work = getOwnedWork(workId, memberId);
        workRepository.delete(work);
    }

    private Member getMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    private Work getOwnedWork(UUID workId, Long memberId) {
        return workRepository.findByIdAndMemberId(workId, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
    }
}
