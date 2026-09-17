package org.monitoring.catchholebackend.domain.worldimage.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldimage.entity.CharacterImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CharacterImageRepository extends JpaRepository<CharacterImage, UUID> {
    @Query("select i from CharacterImage i left join fetch i.catalog left join fetch i.privateImage p left join fetch p.vault where i.characterId in :ids")
    List<CharacterImage> findSelections(Collection<UUID> ids);
    boolean existsByPrivateImageId(UUID privateImageId);
}
