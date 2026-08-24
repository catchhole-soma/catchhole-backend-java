package org.monitoring.catchholebackend.domain.work.service;

public interface MemberWorkPurgeCoordinator {

    MemberWorkPurgeProgress coordinateForWithdrawal(Long memberId);
}
