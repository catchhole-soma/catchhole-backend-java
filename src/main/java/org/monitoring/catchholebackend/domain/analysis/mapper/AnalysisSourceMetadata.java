package org.monitoring.catchholebackend.domain.analysis.mapper;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

public final class AnalysisSourceMetadata {
    private AnalysisSourceMetadata() {}
    public static void put(ObjectNode node, AnalysisJob job) {
        if (job == null) return;
        node.put("sourceKind", job.getSourceKind().name());
        node.put("sourceHash", job.getSourceContentHash());
        node.put("sourceEpisodeNo", job.getSourceEpisodeNo());
        if (job.isSettingBookSource()) {
            node.put("sourceSettingBookId", job.getSourceSettingBook().getId().toString());
            node.put("sourceSettingBookName", job.getSourceSettingBook().getOriginalFilename());
            node.put("sourceSettingBookRevisionId", job.getSourceSettingBookRevision().getId().toString());
            node.put("sourceSettingBookRevisionNo", job.getSourceSettingBookRevision().getRevisionNo());
            node.put("sourceReferenceTimeKind", job.getSourceSettingBookRevision().getReferenceTimeKind().name());
            node.put("sourceReferenceEpisodeNo", job.getSourceSettingBookRevision().getReferenceEpisodeNo());
        } else if (job.getEpisode() != null) node.put("sourceEpisodeId", job.getEpisode().getId().toString());
    }
}
