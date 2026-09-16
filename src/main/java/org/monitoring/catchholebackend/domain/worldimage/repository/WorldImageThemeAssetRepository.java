package org.monitoring.catchholebackend.domain.worldimage.repository;

import java.util.Collection;
import java.util.List;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageThemeAsset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorldImageThemeAssetRepository extends JpaRepository<WorldImageThemeAsset, String> {
    List<WorldImageThemeAsset> findAllByThemeIn(Collection<String> themes);
    boolean existsByThumbnailShaOrImageSha(String thumbnailSha, String imageSha);
}

