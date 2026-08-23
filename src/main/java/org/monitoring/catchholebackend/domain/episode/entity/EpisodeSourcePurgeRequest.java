package org.monitoring.catchholebackend.domain.episode.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeSourcePurgeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "episode_source_purge_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EpisodeSourcePurgeRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "episode_id",
            nullable = false,
            updatable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_episode_source_purge_episode")
    )
    private Episode episode;

    @Column(name = "work_id", nullable = false, updatable = false)
    private UUID workId;

    @Column(name = "previous_source_file_id", updatable = false)
    private UUID previousSourceFileId;

    @Column(name = "previous_episode_no", nullable = false, updatable = false)
    private int previousEpisodeNo;

    @Column(name = "previous_content_key", length = 512, updatable = false)
    private String previousContentKey;

    @Column(name = "previous_source_storage_url", length = 512, updatable = false)
    private String previousSourceStorageUrl;

    @Column(name = "retained_content_key", nullable = false, length = 512, updatable = false)
    private String retainedContentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EpisodeSourcePurgeStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    private EpisodeSourcePurgeRequest(
            Episode episode,
            UploadFile previousSourceFile,
            String retainedContentKey
    ) {
        this.episode = episode;
        this.workId = episode.getWork().getId();
        this.previousSourceFileId = previousSourceFile == null ? null : previousSourceFile.getId();
        this.previousEpisodeNo = episode.getEpisodeNo();
        this.previousContentKey = episode.getContentS3Key();
        this.previousSourceStorageUrl = previousSourceFile == null ? null : previousSourceFile.getStorageUrl();
        this.retainedContentKey = retainedContentKey;
        this.status = EpisodeSourcePurgeStatus.REQUESTED;
        this.requestedAt = LocalDateTime.now();
    }

    public static EpisodeSourcePurgeRequest request(
            Episode episode,
            UploadFile previousSourceFile,
            String retainedContentKey
    ) {
        return new EpisodeSourcePurgeRequest(episode, previousSourceFile, retainedContentKey);
    }

    public void startProcessing() {
        this.status = EpisodeSourcePurgeStatus.PROCESSING;
        this.processingStartedAt = LocalDateTime.now();
        this.attemptCount++;
        this.lastErrorCode = null;
    }

    public void retry(String errorCode) {
        this.status = EpisodeSourcePurgeStatus.REQUESTED;
        this.processingStartedAt = null;
        this.lastErrorCode = errorCode;
    }

    public void recoverStaleProcessing(String errorCode) {
        if (status == EpisodeSourcePurgeStatus.PROCESSING) {
            retry(errorCode);
        }
    }
}
