package org.monitoring.catchholebackend.global.monitoring;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ProductUsageSnapshot(LocalDateTime asOf, LocalDateTime startedAt, LocalDateTime collectedAt,
        List<Day> days, List<Month> months) {
    public ProductUsageSnapshot { days = List.copyOf(days); months = List.copyOf(months); }
    public record Day(LocalDate date, long members1d, long members7d, long members30d,
            long completedRequests, long repeatRequests, long unknownRequests, long worksCreated) {
        public static Day empty(LocalDate date) { return new Day(date, 0, 0, 0, 0, 0, 0, 0); }
    }
    public record Month(LocalDate month, long analysisMembers,
            long completedRequests, long repeatRequests, long unknownRequests, long worksCreated) {
        public static Month empty(LocalDate month) { return new Month(month, 0, 0, 0, 0, 0); }
    }
}
