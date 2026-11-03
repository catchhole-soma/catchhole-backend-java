package org.monitoring.catchholebackend.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;

/** Keeps unit fixtures mutable while exercising the same scalar projection default methods as JPA. */
public final class AnalysisJobSourceViewTestSupport {
    private AnalysisJobSourceViewTestSupport() {}

    public static AnalysisJobSourceView view(AnalysisJob job) {
        Map<String,Object> values = new HashMap<>();
        var bean = new BeanWrapperImpl(job);
        copy(values, bean, "id", "id");
        copy(values, bean, "workId", "work.id");
        copy(values, bean, "jobType", "jobType");
        copy(values, bean, "status", "status");
        copy(values, bean, "analysisMode", "analysisMode");
        copy(values, bean, "journalStatus", "journalStatus");
        copy(values, bean, "reviewMode", "reviewMode");
        copy(values, bean, "automaticAppliedAt", "automaticAppliedAt");
        copy(values, bean, "reviewApplicationHeldAt", "reviewApplicationHeldAt");
        copy(values, bean, "analysisRunId", "analysisRunId");
        copy(values, bean, "runGeneration", "runGeneration");
        copy(values, bean, "runSequence", "runSequence");
        copy(values, bean, "predecessorJobId", "predecessorJobId");
        copy(values, bean, "sourceKind", "sourceKind");
        copy(values, bean, "sourceEpisodeNo", "sourceEpisodeNo");
        copy(values, bean, "sourceContentHash", "sourceContentHash");
        copy(values, bean, "sourceContentS3Key", "sourceContentS3Key");
        copy(values, bean, "sourceContentS3Version", "sourceContentS3Version");
        copy(values, bean, "episodeId", "episode.id");
        copy(values, bean, "episodeWorkId", "episode.work.id");
        copy(values, bean, "episodeNo", "episode.episodeNo");
        copy(values, bean, "episodeStatus", "episode.status");
        copy(values, bean, "episodeContentHash", "episode.contentHash");
        copy(values, bean, "episodeContentS3Key", "episode.contentS3Key");
        copy(values, bean, "episodeContentS3Version", "episode.contentS3Version");
        copy(values, bean, "sourceSettingBookId", "sourceSettingBook.id");
        copy(values, bean, "sourceSettingBookName", "sourceSettingBook.originalFilename");
        copy(values, bean, "sourceSettingBookWorkId", "sourceSettingBook.batch.work.id");
        copy(values, bean, "settingBookArchivedAt", "sourceSettingBook.archivedAt");
        copy(values, bean, "currentSettingBookRevisionId", "sourceSettingBook.currentSettingBookRevision.id");
        copy(values, bean, "sourceSettingBookRevisionId", "sourceSettingBookRevision.id");
        copy(values, bean, "revisionSettingBookId", "sourceSettingBookRevision.settingBook.id");
        copy(values, bean, "sourceSettingBookRevisionNo", "sourceSettingBookRevision.revisionNo");
        copy(values, bean, "sourceReferenceTimeKind", "sourceSettingBookRevision.referenceTimeKind");
        copy(values, bean, "sourceReferenceEpisodeNo", "sourceSettingBookRevision.referenceEpisodeNo");
        copy(values, bean, "revisionContentHash", "sourceSettingBookRevision.contentHash");
        copy(values, bean, "revisionContentS3Key", "sourceSettingBookRevision.contentS3Key");
        copy(values, bean, "revisionContentS3Version", "sourceSettingBookRevision.contentS3Version");
        if (values.get("analysisMode") == null) values.put("analysisMode", job.isOrderedProvisional() ? AnalysisMode.ORDERED_PROVISIONAL : AnalysisMode.CONFIRMED_ONLY);
        if (values.get("reviewMode") == null) values.put("reviewMode", job.isAutomaticReview() ? AnalysisReviewMode.AUTOMATIC : AnalysisReviewMode.MANUAL);
        return new SpelAwareProxyProjectionFactory().createProjection(AnalysisJobSourceView.class, values);
    }

    private static void copy(Map<String,Object> values, BeanWrapperImpl bean, String key, String path) {
        try { values.put(key, bean.getPropertyValue(path)); }
        catch (org.springframework.beans.InvalidPropertyException error) { values.put(key, null); }
    }

    public static AnalysisJobSourceRepository repositoryFor(AnalysisJob... jobs) {
        var repository = org.mockito.Mockito.mock(AnalysisJobSourceRepository.class);
        org.mockito.Mockito.when(repository.findSourceById(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> List.of(jobs).stream().filter(job -> job.getId().equals(call.getArgument(0))).findFirst().map(AnalysisJobSourceViewTestSupport::view));
        org.mockito.Mockito.when(repository.findExtractionSourcesForWork(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> List.of(jobs).stream().filter(job -> job.getWork().getId().equals(call.getArgument(0))).map(AnalysisJobSourceViewTestSupport::view).toList());
        return repository;
    }
}
