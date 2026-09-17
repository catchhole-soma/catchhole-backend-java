package org.monitoring.catchholebackend.domain.worldimage.entity;

import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.domain.worldimage.type.PrivateWorldImageStatus;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "private_world_images")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrivateWorldImage extends BaseEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false)
    private Work work;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vault_id", nullable = false)
    private PrivateImageVault vault;
    // 파일명·MIME 등 표시 메타데이터도 브라우저만 복호화한다.
    @Column(name = "encrypted_metadata", nullable = false, length = 4096)
    private String encryptedMetadata;
    @Column(name = "image_bytes", nullable = false) private long imageBytes;
    @Column(name = "thumbnail_bytes", nullable = false) private long thumbnailBytes;
    @Version private Long version;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PrivateWorldImageStatus status = PrivateWorldImageStatus.READY;
    @Column(name = "storage_attempt_id")
    private UUID storageAttemptId;
    @Column(name = "cleanup_attempted_at")
    private LocalDateTime cleanupAttemptedAt;

    public void reserveUpload(UUID attemptId) {
        storageAttemptId = attemptId;
        status = PrivateWorldImageStatus.UPLOADING;
    }
    public void completeUpload() {
        if (status != PrivateWorldImageStatus.UPLOADING) {
            throw new AppException(
                    WorldImageErrorCode.PRIVATE_IMAGE_CONFLICT);
        }
        status = PrivateWorldImageStatus.READY;
    }
    public void requestDeletion() { status = PrivateWorldImageStatus.DELETING; }

    public void startCleanup(LocalDateTime attemptedAt) {
        requestDeletion();
        cleanupAttemptedAt = attemptedAt;
    }


    public static PrivateWorldImage create(UUID id, Work work, PrivateImageVault vault,
            String encryptedMetadata, long imageBytes, long thumbnailBytes) {
        PrivateWorldImage image = new PrivateWorldImage();
        image.id = id;
        image.work = work;
        image.vault = vault;
        image.encryptedMetadata = encryptedMetadata;
        image.imageBytes = imageBytes;
        image.thumbnailBytes = thumbnailBytes;
        return image;
    }
}
