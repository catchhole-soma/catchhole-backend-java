package org.monitoring.catchholebackend.domain.relationship.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipHoldClosure;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipBackfillWorkRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipHoldClosureRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipHoldReviewRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads normal semantic holds only. Call closure under the owning Work lock with job creation. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationshipHoldReviewGate {
    private final RelationshipHoldClosureRepository closures;
    private final RelationshipBackfillWorkRepository backfillWorks;

    public record Entry(UUID analysisId, String observationRef, int episodeNo, String revision) {}
    public record Snapshot(UUID workId, int beforeEpisodeNo, List<Entry> entries) {
        public Snapshot { entries = List.copyOf(entries); }
        public long count() { return entries.size(); }
    }

    public Snapshot snapshot(UUID workId, int beforeEpisodeNo) {
        long generation = backfillWorks.findById(workId).map(w -> w.getPublishedGeneration()).orElse(0L);
        Map<Integer, RelationshipHoldReviewRow> latest = new TreeMap<>();
        closures.findReviewRows(workId, generation, beforeEpisodeNo)
                .forEach(row -> latest.put(row.episodeNo(), row));
        // GH367: public episodes are independent. INVALIDATED excludes that row, not every
        // later hold. Actual unavailable source remains a prefix boundary. This query only reads
        // the published generation; private backfill's stricter build boundary is unchanged.
        int unavailableFrom = latest.values().stream().filter(row -> !row.sourceAvailable())
                .mapToInt(RelationshipHoldReviewRow::episodeNo).min().orElse(Integer.MAX_VALUE);
        var visible = latest.values().stream().filter(row -> row.episodeNo() < unavailableFrom
                && row.status() == AnalysisStatus.APPLIED).toList();
        Set<String> consumed = new HashSet<>(closures.findClosedKeysByWorkId(workId));
        for (var row : visible) {
            if (row.decisions() == null || row.bindings() == null) continue;
            for (JsonNode decision : row.decisions()) {
                if ("HOLD".equals(decision.path("operation").asText())) continue;
                for (JsonNode ref : decision.path("observationRefs")) {
                    if (!ref.asText().startsWith("H")) continue;
                    JsonNode source = row.bindings().path("heldSources").path(ref.asText());
                    consumed.add(source.path("analysisId").asText() + ":" + source.path("observationRef").asText());
                }
            }
        }
        List<Entry> result = new ArrayList<>();
        for (var row : visible) {
            if (row.jobStatus() != AnalysisJobStatus.SUCCEEDED || row.decisions() == null) continue;
            Set<String> ownHolds = new java.util.TreeSet<>();
            for (JsonNode decision : row.decisions()) {
                if (!"HOLD".equals(decision.path("operation").asText())) continue;
                for (JsonNode ref : decision.path("observationRefs")) {
                    String ownRef = ref.asText();
                    if (ownRef.matches("O[1-9][0-9]*") && ownRef.length() <= 40
                            && !consumed.contains(row.analysisId() + ":" + ownRef)) ownHolds.add(ownRef);
                }
            }
            for (String ref : ownHolds) result.add(new Entry(row.analysisId(), ref, row.episodeNo(),
                    row.completionHash() + ":" + row.updatedAt()));
        }
        return new Snapshot(workId, beforeEpisodeNo, result);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void closeHolds(Snapshot snapshot, UUID requestId) {
        Set<String> closed = closures.findClosedKeysByWorkId(snapshot.workId());
        List<RelationshipHoldClosure> values = snapshot.entries().stream()
                .filter(entry -> !closed.contains(entry.analysisId() + ":" + entry.observationRef()))
                .map(entry -> RelationshipHoldClosure.close(snapshot.workId(), entry.analysisId(),
                        entry.observationRef(), requestId)).toList();
        closures.saveAll(values);
    }
}
