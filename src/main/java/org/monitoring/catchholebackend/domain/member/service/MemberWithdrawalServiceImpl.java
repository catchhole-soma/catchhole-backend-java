package org.monitoring.catchholebackend.domain.member.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.repository.RefreshTokenRepository;
import org.monitoring.catchholebackend.domain.member.dto.request.MemberWithdrawalCreateRequest;
import org.monitoring.catchholebackend.domain.member.dto.response.MemberWithdrawalResponse;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.entity.MemberWithdrawalRequest;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.mapper.MemberWithdrawalMapper;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalRequestRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberWithdrawalServiceImpl implements MemberWithdrawalService {

    private final MemberRepository memberRepository;
    private final MemberWithdrawalRequestRepository withdrawalRequestRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MemberWithdrawalMapper withdrawalMapper;

    @Override
    @Transactional
    public MemberWithdrawalResponse requestWithdrawal(
            Long memberId,
            MemberWithdrawalCreateRequest request
    ) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new AppException(MemberErrorCode.MEMBER_NOT_FOUND));

        boolean alreadyPurging = member.isPurging();
        if (!alreadyPurging) {
            member.validateActive();
        }
        if (!passwordEncoder.matches(request.currentPassword(), member.getPasswordHash())) {
            throw new AppException(MemberErrorCode.MEMBER_WITHDRAWAL_PASSWORD_MISMATCH);
        }
        if (alreadyPurging) {
            return withdrawalMapper.toResponse(withdrawalRequestRepository.findByMemberId(memberId)
                    .orElseThrow(() -> new IllegalStateException("회원 탈퇴 요청 상태가 사라졌습니다.")));
        }

        LocalDateTime requestedAt = LocalDateTime.now();
        member.startPurging();
        MemberWithdrawalRequest withdrawalRequest = withdrawalRequestRepository.save(
                MemberWithdrawalRequest.request(memberId, requestedAt)
        );
        refreshTokenRepository.revokeAllByMemberId(memberId, requestedAt);
        return withdrawalMapper.toResponse(withdrawalRequest);
    }
}
