package org.monitoring.catchholebackend.global.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 날짜/월만 제한된 범위로 노출한다. scrape는 DB가 아닌 한 immutable cache를 읽는다. */
@Component
public class ProductUsageMetrics {
    private static final String PREFIX = "catchhole.product.";
    private static final String SUCCESS = PREFIX + "usage.snapshot.success";
    private static final String COLLECTED = PREFIX + "usage.snapshot.last.success.timestamp";
    private static final String STARTED = PREFIX + "usage.collection.started.timestamp";
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<Key, Gauge> registered = new HashMap<>();
    private final Set<Key> fixed = new HashSet<>();
    private final AtomicReference<Map<Key, Double>> values = new AtomicReference<>(Map.of(
            new Key(SUCCESS, Map.of()), 0d, new Key(COLLECTED, Map.of()), 0d, new Key(STARTED, Map.of()), 0d));

    @Autowired public ProductUsageMetrics(MeterRegistry registry) { this(registry, Clock.systemDefaultZone()); }
    ProductUsageMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        for (String period : List.of("1d", "7d", "30d", "month")) {
            fixed.add(key("analysis.members", "period", period));
            fixed.add(key("period.complete", "period", period));
        }
        for (String period : List.of("1d", "month")) {
            for (String metric : List.of("completed.requests", "repeat.requests", "unknown.requests", "works.created")) {
                fixed.add(key(metric, "period", period));
            }
        }
        fixed.add(new Key(SUCCESS, Map.of())); fixed.add(new Key(COLLECTED, Map.of())); fixed.add(new Key(STARTED, Map.of()));
        fixed.forEach(this::register);
    }

    public synchronized void updateSnapshot(ProductUsageSnapshot snapshot) {
        Map<Key, Double> next = new HashMap<>();
        boolean ready = snapshot.startedAt() != null && snapshot.collectedAt() != null
                && !snapshot.collectedAt().isAfter(snapshot.asOf());
        next.put(new Key(SUCCESS, Map.of()), ready ? 1d : 0d);
        next.put(new Key(COLLECTED, Map.of()), epoch(snapshot.collectedAt()));
        next.put(new Key(STARTED, Map.of()), epoch(snapshot.startedAt()));
        if (ready) addData(next, snapshot);
        Set<Key> desired = new HashSet<>(fixed);
        desired.addAll(next.keySet());
        desired.forEach(this::register);
        values.set(Map.copyOf(next));
        // 기간이 지난 label은 registry에서도 제거하여 무한히 시계열이 늘어나지 않게 한다.
        var iterator = registered.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!desired.contains(entry.getKey())) { registry.remove(entry.getValue()); iterator.remove(); }
        }
    }

    public void snapshotFailed() {
        values.updateAndGet(previous -> {
            Map<Key, Double> failed = new HashMap<>(previous);
            failed.put(new Key(SUCCESS, Map.of()), 0d);
            return Map.copyOf(failed);
        });
    }

    private void addData(Map<Key, Double> data, ProductUsageSnapshot snapshot) {
        LocalDate today = snapshot.asOf().toLocalDate();
        LocalDate month = today.withDayOfMonth(1);
        var day = snapshot.days().stream().filter(d -> d.date().equals(today)).findFirst().orElse(ProductUsageSnapshot.Day.empty(today));
        var currentMonth = snapshot.months().stream().filter(m -> m.month().equals(month)).findFirst().orElse(ProductUsageSnapshot.Month.empty(month));
        put(data, "analysis.members", "period", "1d", day.members1d());
        put(data, "analysis.members", "period", "7d", day.members7d());
        put(data, "analysis.members", "period", "30d", day.members30d());
        put(data, "analysis.members", "period", "month", currentMonth.analysisMembers());
        counts(data, "", "period", "1d", day.completedRequests(), day.repeatRequests(), day.unknownRequests(), day.worksCreated());
        counts(data, "", "period", "month", currentMonth.completedRequests(), currentMonth.repeatRequests(), currentMonth.unknownRequests(), currentMonth.worksCreated());
        for (var entry : Map.of("1d", today, "7d", today.minusDays(6), "30d", today.minusDays(29), "month", month).entrySet()) {
            put(data, "period.complete", "period", entry.getKey(), covered(snapshot, entry.getValue()));
        }
        for (var row : snapshot.days()) {
            if (row.date().isBefore(today.minusDays(59)) || row.date().isAfter(today)
                    || row.date().isBefore(snapshot.startedAt().toLocalDate())) continue;
            String date = row.date().toString();
            put(data, "daily.analysis.members", "date", date, row.members1d());
            put(data, "daily.active.members.7d", "date", date, row.members7d());
            put(data, "daily.active.members.30d", "date", date, row.members30d());
            put(data, "daily.coverage.complete", "date", date, covered(snapshot, row.date()));
            counts(data, "daily.", "date", date, row.completedRequests(), row.repeatRequests(), row.unknownRequests(), row.worksCreated());
        }
        for (var row : snapshot.months()) {
            if (row.month().isBefore(month.minusMonths(11)) || row.month().isAfter(month)
                    || row.month().isBefore(snapshot.startedAt().toLocalDate().withDayOfMonth(1))) continue;
            String label = row.month().toString().substring(0, 7);
            put(data, "monthly.analysis.members", "month", label, row.analysisMembers());
            put(data, "monthly.coverage.complete", "month", label, covered(snapshot, row.month()));
            counts(data, "monthly.", "month", label, row.completedRequests(), row.repeatRequests(), row.unknownRequests(), row.worksCreated());
        }
    }

    private void counts(Map<Key, Double> data, String prefix, String tag, String label, long completed, long repeated, long unknown, long works) {
        put(data, prefix + "completed.requests", tag, label, completed);
        put(data, prefix + "repeat.requests", tag, label, repeated);
        put(data, prefix + "unknown.requests", tag, label, unknown);
        put(data, prefix + "works.created", tag, label, works);
    }
    private long covered(ProductUsageSnapshot snapshot, LocalDate from) {
        return snapshot.startedAt().isAfter(from.atStartOfDay()) ? 0 : 1;
    }
    private double epoch(LocalDateTime time) { return time == null ? 0 : time.atZone(clock.getZone()).toEpochSecond(); }
    private Key key(String name, String tag, String value) { return new Key(PREFIX + name, Map.of(tag, value)); }
    private void put(Map<Key, Double> data, String name, String tag, String label, long value) { data.put(key(name, tag, label), (double) value); }
    private void register(Key key) {
        if (registered.containsKey(key)) return;
        Tags tags = Tags.empty();
        for (var tag : key.tags().entrySet()) tags = tags.and(tag.getKey(), tag.getValue());
        var builder = Gauge.builder(key.name(), this, ignored -> values.get().getOrDefault(key, Double.NaN)).tags(tags);
        if (key.name().endsWith(".timestamp")) builder.baseUnit("seconds");
        registered.put(key, builder.register(registry));
    }
    private record Key(String name, Map<String, String> tags) {
        private Key { tags = Map.copyOf(tags); }
    }
}
