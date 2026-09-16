package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.Collection;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageThemeResponse;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.WorldSettingImageUpdateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageCatalogResponse;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

public interface WorldImageService {
    PageResponse<WorldImageCatalogResponse> getImageCatalog(Long memberId, UUID workId, boolean recommended, WorldSettingCategory category, String query, int page, int size);
    WorldImageThemeResponse getImageTheme(Long memberId, UUID workId);
    Map<UUID, WorldSettingImageResponse> getSettingImages(Collection<WorldSetting> settings);
    WorldSettingImageResponse updateSettingImage(Long memberId, UUID workId, UUID settingId, WorldSettingImageUpdateRequest request);
    void clearMismatchedImage(WorldSetting setting);
    byte[] getPublishedImage(String sha);
}
