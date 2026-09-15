package org.monitoring.catchholebackend.domain.worldimage.mapper;

import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageCatalogResponse;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageCatalog;
import org.monitoring.catchholebackend.global.storage.WorldImageAssetPaths;
import org.springframework.stereotype.Component;

@Component
public class WorldImageMapper {
    public WorldImageCatalogResponse toResponse(WorldImageCatalog catalog) {
        return new WorldImageCatalogResponse(catalog.getId(), catalog.getCategory(), catalog.getName(),
                catalog.getAliases().stream().sorted().toList(),
                WorldImageAssetPaths.publicPath(catalog.getThumbnailSha()),
                WorldImageAssetPaths.publicPath(catalog.getImageSha()));
    }

    public WorldSettingImageResponse toSelectionResponse(WorldImageCatalog catalog, boolean manual, long version) {
        return new WorldSettingImageResponse(catalog == null ? null : catalog.getId(),
                catalog == null ? null : catalog.getName(),
                catalog == null ? null : WorldImageAssetPaths.publicPath(catalog.getThumbnailSha()),
                catalog == null ? null : WorldImageAssetPaths.publicPath(catalog.getImageSha()),
                manual ? "MANUAL" : "DEFAULT", version);
    }
}
