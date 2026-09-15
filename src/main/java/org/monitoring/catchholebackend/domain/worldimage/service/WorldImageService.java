package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.WorldSettingImageUpdateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageCatalogResponse;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

public interface WorldImageService {
    PageResponse<WorldImageCatalogResponse> getImageCatalog(WorldSettingCategory category, String query, int page, int size);
    Map<UUID, WorldSettingImageResponse> getSettingImages(Collection<WorldSetting> settings);
    WorldSettingImageResponse updateSettingImage(Long memberId, UUID workId, UUID settingId, WorldSettingImageUpdateRequest request);
    void clearMismatchedImage(WorldSetting setting);
    byte[] getPublishedImage(String sha);
}
