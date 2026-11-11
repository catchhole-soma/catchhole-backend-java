package org.monitoring.catchholebackend.domain.productusage.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.productusage.dto.ProductUsageCompletion;
import org.monitoring.catchholebackend.domain.productusage.entity.ProductUsageMember;
import org.monitoring.catchholebackend.domain.productusage.repository.ProductUsageRepository;
import org.monitoring.catchholebackend.domain.productusage.type.ProductUsageFirstKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class ProductUsageService {
    private final ProductUsageRepository repository;

    public void initialize(LocalDateTime start) { repository.lockState().initialize(start); }

    public void accept(ProductUsageCompletion event) {
        LocalDateTime start = repository.lockState().getStartedAt();
        if (start == null) throw new IllegalStateException("제품 사용 집계가 아직 시작되지 않았습니다.");
        if (repository.recorded(event.requestId())) return;
        boolean eligible = !event.requestedAt().isBefore(start);
        ProductUsageMember member = repository.member(event.memberId(), event.memberCreatedAt().isBefore(start));
        boolean first = member.isEarlier(event);
        boolean unknown = first && eligible && member.isPriorHistoryUnknown();
        if (first) {
            reclassifyPreviousFirst(member);
            member.observeFirst(event, !eligible ? ProductUsageFirstKind.BEFORE_START
                    : unknown ? ProductUsageFirstKind.UNKNOWN : ProductUsageFirstKind.FIRST);
        }
        if (eligible) {
            LocalDate date = event.completedAt().toLocalDate();
            repository.daily(date).complete(!first, unknown);
            repository.monthly(date).complete(!first, unknown);
            recordActivity(event.memberId(), date);
        }
        repository.record(event);
    }

    private void reclassifyPreviousFirst(ProductUsageMember member) {
        if (member.getFirstKind() == null || member.getFirstKind() == ProductUsageFirstKind.BEFORE_START) return;
        LocalDate previous = member.getFirstCompletedAt().toLocalDate();
        boolean unknown = member.getFirstKind() == ProductUsageFirstKind.UNKNOWN;
        repository.daily(previous).reclassifyFirstAsRepeat(unknown);
        repository.monthly(previous).reclassifyFirstAsRepeat(unknown);
    }

    private void recordActivity(Long memberId, LocalDate day) {
        List<LocalDate> existing = repository.activityDates(memberId, day);
        if (existing.contains(day)) return;
        for (int offset = 0; offset < 30; offset++) {
            LocalDate reference = day.plusDays(offset);
            for (int window : new int[]{1, 7, 30}) {
                if (offset >= window) continue;
                LocalDate from = reference.minusDays(window - 1);
                if (existing.stream().noneMatch(d -> !d.isBefore(from) && !d.isAfter(reference))) {
                    repository.daily(reference).addMember(window);
                }
            }
        }
        if (existing.stream().noneMatch(d -> d.withDayOfMonth(1).equals(day.withDayOfMonth(1)))) {
            repository.monthly(day).addMember();
        }
        repository.recordActivity(memberId, day);
    }

    public void purgeWork(UUID workId) { repository.lockState(); repository.purgeWork(workId); }
    public void purgeMember(Long memberId) { repository.lockState(); repository.purgeMember(memberId); }
}
