package org.monitoring.catchholebackend.domain.worldimage.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldSettingImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorldSettingImageRepository extends JpaRepository<WorldSettingImage, UUID> {
    @Query("select i from WorldSettingImage i left join fetch i.catalog left join fetch i.privateImage p left join fetch p.vault where i.worldSettingId in :ids")
    List<WorldSettingImage> findSelections(Collection<UUID> ids);
    boolean existsByPrivateImageId(UUID privateImageId);
}
