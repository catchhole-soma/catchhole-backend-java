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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "upload_batches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadBatch extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_upload_batches_work")
    )
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "uploaded_by_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_upload_batches_member")
    )
    private Member uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_type", nullable = false, length = 40)
    private UploadType uploadType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private UploadSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UploadStatus status;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    private UploadBatch(Work work, Member uploadedBy, UploadType uploadType, UploadSourceType sourceType) {
        this.work = work;
        this.uploadedBy = uploadedBy;
        this.uploadType = uploadType;
        this.sourceType = sourceType;
        this.status = UploadStatus.PENDING;
        this.fileCount = 0;
    }

    public static UploadBatch create(
            Work work,
            Member uploadedBy,
            UploadType uploadType,
            UploadSourceType sourceType
    ) {
        return new UploadBatch(work, uploadedBy, uploadType, sourceType);
    }

    public void updateFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    public void startProcessing() {
        this.status = UploadStatus.PROCESSING;
    }

    public void complete() {
        this.status = UploadStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = UploadStatus.FAILED;
        this.completedAt = LocalDateTime.now();
    }
}
