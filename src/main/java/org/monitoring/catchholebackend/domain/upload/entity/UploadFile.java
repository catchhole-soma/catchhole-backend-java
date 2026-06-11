package org.monitoring.catchholebackend.domain.upload.entity;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "upload_files")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadFile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_upload_files_batch")
    )
    private UploadBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_role", nullable = false, length = 30)
    private UploadFileRole fileRole;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "storage_url", length = 512)
    private String storageUrl;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "detected_episode_start_no")
    private Integer detectedEpisodeStartNo;

    @Column(name = "detected_episode_end_no")
    private Integer detectedEpisodeEndNo;

    @Column(name = "detected_episode_count")
    private Integer detectedEpisodeCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private UploadFileParseStatus parseStatus;

    private UploadFile(
            UploadBatch batch,
            UploadFileRole fileRole,
            String originalFilename,
            String mimeType,
            String storageUrl,
            long fileSize
    ) {
        this.batch = batch;
        this.fileRole = fileRole;
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.storageUrl = storageUrl;
        this.fileSize = fileSize;
        this.parseStatus = UploadFileParseStatus.PENDING;
    }

    public static UploadFile create(
            UploadBatch batch,
            UploadFileRole fileRole,
            String originalFilename,
            String mimeType,
            String storageUrl,
            long fileSize
    ) {
        return new UploadFile(batch, fileRole, originalFilename, mimeType, storageUrl, fileSize);
    }

    public void updateStorage(String storageUrl) {
        this.storageUrl = storageUrl;
    }

    public void markParsed(
            Integer detectedEpisodeStartNo,
            Integer detectedEpisodeEndNo,
            Integer detectedEpisodeCount
    ) {
        this.detectedEpisodeStartNo = detectedEpisodeStartNo;
        this.detectedEpisodeEndNo = detectedEpisodeEndNo;
        this.detectedEpisodeCount = detectedEpisodeCount;
        this.parseStatus = UploadFileParseStatus.PARSED;
    }

    public void markFailed() {
        this.parseStatus = UploadFileParseStatus.FAILED;
    }
}
