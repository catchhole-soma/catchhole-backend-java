package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.Collection;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageThemeResponse;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageThemeAsset;
import org.monitoring.catchholebackend.domain.worldimage.repository.WorldImageThemeAssetRepository;
import org.monitoring.catchholebackend.domain.worldimage.processor.WorldImageThemes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.WorldSettingImageUpdateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageCatalogResponse;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageCatalog;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldSettingImage;
import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.domain.worldimage.mapper.WorldImageMapper;
import org.monitoring.catchholebackend.domain.worldimage.processor.WorldImageSearch;
import org.monitoring.catchholebackend.domain.worldimage.repository.WorldImageCatalogRepository;
import org.monitoring.catchholebackend.domain.worldimage.repository.WorldSettingImageRepository;
import org.monitoring.catchholebackend.domain.worldimage.repository.PrivateWorldImageRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.WorldImageAssetPaths;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldImageServiceImpl implements WorldImageService {
    private final WorldImageCatalogRepository catalogRepository;
    private final WorldImageThemeAssetRepository themeRepository;
    private final WorldSettingImageRepository selectionRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final WorkRepository workRepository;
    private final WorldImageMapper mapper;
    private final ObjectStorage objectStorage;
    private final PrivateWorldImageRepository privateImageRepository;

    @Override
    public PageResponse<WorldImageCatalogResponse> getImageCatalog(Long memberId, UUID workId, boolean recommended, WorldSettingCategory category, String query, int page, int size) {
        String theme = workId == null ? null : WorldImageThemes.forGenre(workRepository.getOwnedWork(workId, memberId).getGenre());
        if (recommended && theme == null) throw new AppException(WorldImageErrorCode.WORLD_IMAGE_WORK_REQUIRED);
        var result = recommended
                ? catalogRepository.searchRecommendedCatalog(category, WorldImageSearch.containsQuery(query), theme, PageRequest.of(page, size))
                : catalogRepository.searchCatalog(category, WorldImageSearch.containsQuery(query), PageRequest.of(page, size));
        return PageResponse.from(result, result.getContent().stream().map(mapper::toResponse).toList());
    }

    @Override
    public WorldImageThemeResponse getImageTheme(Long memberId, UUID workId) {
        String theme = WorldImageThemes.forGenre(workRepository.getOwnedWork(workId, memberId).getGenre());
        return mapper.toThemeResponse(theme, themeRepository.findAllByThemeIn(List.of(theme)));
    }

    @Override
    public Map<UUID, WorldSettingImageResponse> getSettingImages(Collection<WorldSetting> settings) {
        if (settings.isEmpty()) return Map.of();
        Map<WorldSettingCategory, WorldImageCatalog> defaults = catalogRepository.findAllByDefaultImageTrueAndActiveTrue()
                .stream().collect(Collectors.toMap(WorldImageCatalog::getCategory, Function.identity()));
        var themes = settings.stream().map(setting -> WorldImageThemes.forGenre(setting.getWork().getGenre())).distinct().toList();
        Map<String, WorldImageThemeAsset> themeDefaults = themeRepository.findAllByThemeIn(themes).stream()
                .filter(asset -> asset.getPurpose().equals("DEFAULT"))
                .collect(Collectors.toMap(asset -> asset.getTheme() + ":" + asset.getSlot(), Function.identity()));
        Map<UUID, WorldSettingImage> selections = selectionRepository.findSelections(settings.stream().map(WorldSetting::getId).toList())
                .stream().collect(Collectors.toMap(WorldSettingImage::getWorldSettingId, Function.identity()));
        Map<UUID, WorldSettingImageResponse> result = new HashMap<>();
        for (WorldSetting setting : settings) {
            WorldSettingImage selection = selections.get(setting.getId());
            if (selection != null && selection.getPrivateImage() != null) {
                result.put(setting.getId(), mapper.toPrivateSelectionResponse(selection.getPrivateImage(), selection.getVersion()));
                continue;
            }
            WorldImageCatalog chosen = selection == null ? null : selection.getCatalog();
            boolean manual = chosen != null && chosen.isActive() && chosen.getCategory() == setting.getCategory();
            long version = selection == null ? 0 : selection.getVersion();
            var themeDefault = themeDefaults.get(WorldImageThemes.forGenre(setting.getWork().getGenre()) + ":" + setting.getCategory().name());
            result.put(setting.getId(), !manual && themeDefault != null
                    ? mapper.toThemeAssetResponse(themeDefault, version)
                    : mapper.toSelectionResponse(manual ? chosen : defaults.get(setting.getCategory()), manual, version));
        }
        return result;
    }

    @Override
    @Transactional
    public WorldSettingImageResponse updateSettingImage(Long memberId, UUID workId, UUID settingId, WorldSettingImageUpdateRequest request) {
        workRepository.getOwnedWorkForUpdate(workId, memberId);
        // 최초 선택도 대상 행을 잠가 직렬화한다. 대상 Entity의 내용·version은 변경하지 않는다.
        WorldSetting setting = worldSettingRepository.findByIdAndWorkIdForUpdate(settingId, workId)
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_NOT_FOUND));
        WorldSettingImage selection = selectionRepository.findById(settingId).orElseGet(() -> WorldSettingImage.create(setting));
        selection.validateVersion(request.version());
        if (request.privateImageId() != null) {
            if (request.catalogId() != null) throw new AppException(WorldImageErrorCode.PRIVATE_IMAGE_INVALID);
            var image = privateImageRepository.findByIdAndWorkId(request.privateImageId(), workId)
                    .orElseThrow(() -> new AppException(WorldImageErrorCode.WORLD_IMAGE_NOT_FOUND));
            if (selection.selectPrivateImage(image)) selectionRepository.saveAndFlush(selection);
            return getSettingImages(List.of(setting)).get(settingId);
        }
        WorldImageCatalog catalog = request.catalogId() == null ? null : catalogRepository.findById(request.catalogId())
                .filter(WorldImageCatalog::isActive)
                .orElseThrow(() -> new AppException(WorldImageErrorCode.WORLD_IMAGE_NOT_FOUND));
        if (catalog != null && catalog.getCategory() != setting.getCategory()) {
            throw new AppException(WorldImageErrorCode.WORLD_IMAGE_CATEGORY_MISMATCH);
        }
        if (catalog != null && catalog.isDefaultImage()) catalog = null;
        if (selection.selectImage(catalog)) selectionRepository.saveAndFlush(selection);
        return getSettingImages(List.of(setting)).get(settingId);
    }

    @Override
    @Transactional
    public void clearMismatchedImage(WorldSetting setting) {
        selectionRepository.findById(setting.getId()).ifPresent(selection -> {
            if (selection.getCatalog() != null && selection.getCatalog().getCategory() != setting.getCategory()) {
                selection.selectImage(null);
            }
        });
    }

    @Override
    public byte[] getPublishedImage(String sha) {
        if (catalogRepository.findPublishedAsset(sha, PageRequest.of(0, 1)).isEmpty()
                && !themeRepository.existsByThumbnailShaOrImageSha(sha, sha)) {
            throw new AppException(WorldImageErrorCode.WORLD_IMAGE_NOT_FOUND);
        }
        return objectStorage.getBytes(WorldImageAssetPaths.storageKey(sha));
    }
}
