package org.monitoring.catchholebackend.domain.worldimage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(name = "world_setting_images")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSettingImage implements Persistable<UUID> {
    // 직접 부여한 UUID의 최초 저장은 merge 대신 persist로 처리한다.
    @Transient
    private boolean newEntity = true;

    @Override
    public UUID getId() { return worldSettingId; }

    @Override
    public boolean isNew() { return newEntity; }

    @PostLoad
    @PostPersist
    void markPersisted() { newEntity = false; }

    @Id
    @Column(name = "world_setting_id")
    private UUID worldSettingId;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "world_setting_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private WorldSetting worldSetting;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "catalog_id")
    private WorldImageCatalog catalog;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "private_image_id")
    private PrivateWorldImage privateImage;
    @Column(nullable = false)
    private long version;
    // AUTO는 저장된 자동 결과, DEFAULT는 작가가 선택한 분류 기본 이미지다.
    @Column(name = "selection_source", length = 20)
    private String selectionSource;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static WorldSettingImage create(WorldSetting setting) {
        WorldSettingImage selection = new WorldSettingImage();
        selection.worldSetting = setting;
        selection.worldSettingId = setting.getId();
        selection.updatedAt = LocalDateTime.now();
        return selection;
    }

    public void validateVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new AppException(WorldImageErrorCode.WORLD_IMAGE_VERSION_CONFLICT);
        }
    }

    public boolean isAutomatic() { return selectionSource == null || "AUTO".equals(selectionSource); }

    public boolean applyAutomaticImage(WorldImageCatalog image) {
        if (!isAutomatic()) return false;
        if ("AUTO".equals(selectionSource) && Objects.equals(catalog == null ? null : catalog.getId(), image == null ? null : image.getId())) return false;
        catalog = image;
        privateImage = null;
        selectionSource = "AUTO";
        if (!newEntity) version++;
        updatedAt = LocalDateTime.now();
        return true;
    }

    public boolean selectAutomaticImage(WorldImageCatalog image) {
        if (!isAutomatic()) selectionSource = null;
        return applyAutomaticImage(image);
    }

    public boolean selectImage(WorldImageCatalog image) {
        String source = image == null ? "DEFAULT" : "MANUAL";
        if (Objects.equals(selectionSource, source) && privateImage == null && Objects.equals(catalog == null ? null : catalog.getId(), image == null ? null : image.getId())) {
            return false;
        }
        catalog = image;
        privateImage = null;
        selectionSource = source;
        version++;
        updatedAt = LocalDateTime.now();
        return true;
    }

    public boolean selectPrivateImage(PrivateWorldImage image) {
        if (privateImage != null && privateImage.getId().equals(image.getId())) return false;
        catalog = null;
        privateImage = image;
        selectionSource = "PRIVATE";
        version++;
        updatedAt = LocalDateTime.now();
        return true;
    }
}
