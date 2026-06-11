package org.monitoring.catchholebackend.domain.work.mapper;

import java.util.List;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Component;

@Component
public class WorkMapper {

    //Request DTO를 저장용 Entity로 바꾸기 위해
    public Work toEntity(WorkCreateRequest request, Member member) {
        return Work.create(
                member,
                request.title(),
                request.genre(),
                request.description()
        );
    }

    //Entity를 응답 DTO로 바꿈
    public WorkResponse toResponse(Work work) {
        return new WorkResponse(
                work.getId(),
                work.getTitle(),
                work.getGenre(),
                work.getDescription(),
                work.getStatus(),
                work.getLatestEpisodeNo(),
                work.getCreatedAt(),
                work.getUpdatedAt()
        );
    }

    //Entity 목록을 응답 DTO 목록으로 바꿈
    public List<WorkResponse> toResponseList(List<Work> works) {
        return works.stream()
                .map(this::toResponse)
                .toList();
    }
}
