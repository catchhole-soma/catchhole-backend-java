package org.monitoring.catchholebackend.domain.episode.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.processor.FinalizedEpisode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeAnalysisStatus;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.springframework.stereotype.Component;

@Component
public class EpisodeMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Episode toEntity(
            Work work,
            UploadFile sourceFile,
            FinalizedEpisode finalizedEpisode,
            StoredTextObject storedEpisodeContent
    ) {
        return Episode.create(
                work,
                sourceFile.getId(),
                finalizedEpisode.episodeNo(),
                finalizedEpisode.title(),
                storedEpisodeContent.key(),
                storedEpisodeContent.versionId(),
                storedEpisodeContent.contentHash(),
                storedEpisodeContent.charCount()
        );
    }

    public EpisodeResponse toResponse(Episode episode, String content) {
        return toResponse(episode, content, null);
    }

    public EpisodeResponse toResponse(Episode episode, String content, UploadFile sourceFile) {
        return new EpisodeResponse(
                episode.getId(),
                episode.getWork().getId(),
                episode.getSourceFileId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                content,
                sourceFile == null ? null : sourceFile.getOriginalFilename(),
                episode.getContentUpdatedAt(),
                episode.getContentS3Key(),
                episode.getContentS3Version(),
                episode.getContentHash(),
                episode.getCharCount(),
                episode.getStatus(),
                episode.getCreatedAt(),
                episode.getUpdatedAt()
        );
    }

    public EpisodeSummaryResponse toSummaryResponse(Episode episode) {
        return toSummaryResponse(episode, null, null);
    }

    public EpisodeSummaryResponse toSummaryResponse(
            Episode episode,
            UploadFile uploadFile,
            AnalysisJob latestAnalysisJob
    ) {
        EpisodeAnalysisStatus analysisStatus = resolveAnalysisStatus(episode, latestAnalysisJob);
        return new EpisodeSummaryResponse(
                episode.getId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                episode.getCharCount(),
                episode.getStatus(),
                uploadFile == null ? null : uploadFile.getOriginalFilename(),
                uploadFile == null ? null : uploadFile.getBatch().getId(),
                analysisStatus,
                resolveUnresolvedFindingCount(analysisStatus, latestAnalysisJob),
                latestAnalysisJob == null ? null : latestAnalysisJob.getId(),
                episode.getContentUpdatedAt(),
                episode.getCreatedAt(),
                episode.getUpdatedAt()
        );
    }

    private EpisodeAnalysisStatus resolveAnalysisStatus(Episode episode, AnalysisJob latestAnalysisJob) {
        if (latestAnalysisJob != null
                && (latestAnalysisJob.getStatus() == AnalysisJobStatus.PENDING
                || latestAnalysisJob.getStatus() == AnalysisJobStatus.RUNNING)) {
            return EpisodeAnalysisStatus.IN_PROGRESS;
        }
        if (episode.getStatus() == org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.FAILED
                || latestAnalysisJob != null && latestAnalysisJob.getStatus() == AnalysisJobStatus.FAILED) {
            return EpisodeAnalysisStatus.FAILED;
        }
        if (episode.getStatus() == org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ANALYZED) {
            return EpisodeAnalysisStatus.COMPLETED;
        }
        return EpisodeAnalysisStatus.REANALYSIS_REQUIRED;
    }

    private Integer resolveUnresolvedFindingCount(
            EpisodeAnalysisStatus analysisStatus,
            AnalysisJob latestAnalysisJob
    ) {
        if (analysisStatus != EpisodeAnalysisStatus.COMPLETED) {
            return null;
        }
        if (latestAnalysisJob == null || latestAnalysisJob.getSummaryJson() == null) {
            return null;
        }
        try {
            JsonNode summary = objectMapper.readTree(latestAnalysisJob.getSummaryJson());
            JsonNode explicitCount = summary.path("unresolvedFindingCount");
            if (explicitCount.isIntegralNumber()
                    && explicitCount.canConvertToInt()
                    && explicitCount.intValue() >= 0) {
                return explicitCount.intValue();
            }
            JsonNode findings = summary.path("findings");
            return findings.isArray() ? findings.size() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public List<EpisodeSummaryResponse> toSummaryResponseList(List<Episode> episodes) {
        return episodes.stream()
                .map(this::toSummaryResponse)
                .toList();
    }
}
