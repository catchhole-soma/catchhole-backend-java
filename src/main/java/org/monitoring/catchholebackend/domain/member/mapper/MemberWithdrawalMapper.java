package org.monitoring.catchholebackend.domain.member.mapper;

import org.monitoring.catchholebackend.domain.member.dto.response.MemberWithdrawalResponse;
import org.monitoring.catchholebackend.domain.member.entity.MemberWithdrawalRequest;
import org.springframework.stereotype.Component;

@Component
public class MemberWithdrawalMapper {

    public MemberWithdrawalResponse toResponse(MemberWithdrawalRequest request) {
        return new MemberWithdrawalResponse(
                request.getId(),
                request.getStatus(),
                request.getRequestedAt()
        );
    }
}
