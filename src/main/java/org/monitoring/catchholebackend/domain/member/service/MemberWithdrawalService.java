package org.monitoring.catchholebackend.domain.member.service;

import org.monitoring.catchholebackend.domain.member.dto.request.MemberWithdrawalCreateRequest;
import org.monitoring.catchholebackend.domain.member.dto.response.MemberWithdrawalResponse;

public interface MemberWithdrawalService {

    MemberWithdrawalResponse requestWithdrawal(Long memberId, MemberWithdrawalCreateRequest request);
}
