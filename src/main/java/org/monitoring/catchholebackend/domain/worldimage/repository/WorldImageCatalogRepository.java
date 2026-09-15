package org.monitoring.catchholebackend.domain.worldimage.repository;

import java.util.List;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageCatalog;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorldImageCatalogRepository extends JpaRepository<WorldImageCatalog, String> {
    @Query("""
            select c from WorldImageCatalog c
            where c.active = true and c.defaultImage = false
              and c.category = :category and c.searchText like :query escape '!'
            order by c.name, c.id
            """)
    Page<WorldImageCatalog> searchCatalog(WorldSettingCategory category, String query, Pageable pageable);

    List<WorldImageCatalog> findAllByDefaultImageTrueAndActiveTrue();

    @Query("select c from WorldImageCatalog c where c.active = true and (c.thumbnailSha = :sha or c.imageSha = :sha)")
    List<WorldImageCatalog> findPublishedAsset(String sha, Pageable pageable);
}
