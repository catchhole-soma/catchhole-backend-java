package org.monitoring.catchholebackend.domain.work.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.type.WorkCoverImageStatus;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(name = "work_cover_images")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkCoverImage extends BaseEntity {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false)
    private Work work;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkCoverImageStatus status;
    private LocalDateTime cleanupAttemptedAt;

    public static WorkCoverImage reserve(Work work) {
        var image = new WorkCoverImage();
        image.id = UUID.randomUUID();
        image.work = work;
        image.status = WorkCoverImageStatus.UPLOADING;
        return image;
    }

    public void completeUpload() {
        if (status != WorkCoverImageStatus.UPLOADING) throw new AppException(WorkErrorCode.WORK_COVER_CONFLICT);
        status = WorkCoverImageStatus.READY;
    }

    public void requestDeletion() { status = WorkCoverImageStatus.DELETING; }

    public void startCleanup() {
        requestDeletion();
        cleanupAttemptedAt = LocalDateTime.now();
    }
}
