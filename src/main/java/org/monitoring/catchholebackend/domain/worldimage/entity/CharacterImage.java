package org.monitoring.catchholebackend.domain.worldimage.entity;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Persistable;

@Getter
@Entity
@Table(name = "character_images")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterImage extends BaseEntity implements Persistable<UUID> {
    @Transient private boolean newEntity = true;
    @Id @Column(name = "character_id") private UUID characterId;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private WorkCharacter character;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "catalog_id")
    private WorldImageCatalog catalog;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "private_image_id")
    private PrivateWorldImage privateImage;
    // AUTO는 저장된 자동 매칭 결과(미일치도 포함), null은 아직 보정하지 않은 이전 자동 상태다.
    @Column(name = "selection_source", length = 20) private String selectionSource;
    @Column(nullable = false) private long version;

    @Override public UUID getId() { return characterId; }
    @Override public boolean isNew() { return newEntity; }
    @PostLoad @PostPersist void markPersisted() { newEntity = false; }

    public static CharacterImage create(WorkCharacter character) {
        var selection = new CharacterImage();
        selection.characterId = character.getId();
        selection.character = character;
        return selection;
    }

    public void validateVersion(long expected) {
        if (version != expected) throw new AppException(WorldImageErrorCode.WORLD_IMAGE_VERSION_CONFLICT);
    }

    public boolean isAutomatic() { return selectionSource == null || "AUTO".equals(selectionSource); }

    public boolean applyAutomaticImage(WorldImageCatalog image) {
        if (!isAutomatic()) return false;
        if ("AUTO".equals(selectionSource) && Objects.equals(catalog == null ? null : catalog.getId(), image == null ? null : image.getId())) return false;
        catalog = image;
        privateImage = null;
        selectionSource = "AUTO";
        if (!newEntity) version++;
        return true;
    }

    public boolean selectAutomaticImage(WorldImageCatalog image) {
        if (!isAutomatic()) selectionSource = null;
        return applyAutomaticImage(image);
    }

    public boolean selectImage(WorldImageCatalog catalog, PrivateWorldImage image, boolean useDefault) {
        String source = image != null ? "PRIVATE" : catalog != null ? "MANUAL" : useDefault ? "DEFAULT" : null;
        if (Objects.equals(selectionSource, source)
                && Objects.equals(this.catalog == null ? null : this.catalog.getId(), catalog == null ? null : catalog.getId())
                && Objects.equals(privateImage == null ? null : privateImage.getId(), image == null ? null : image.getId())) return false;
        this.catalog = catalog;
        this.privateImage = image;
        this.selectionSource = source;
        version++;
        return true;
    }
}
