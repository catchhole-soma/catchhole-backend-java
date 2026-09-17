package org.monitoring.catchholebackend.domain.worldimage.service;

import org.monitoring.catchholebackend.domain.worldimage.type.PrivateWorldImageStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.CharacterImageUpdateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;
import org.monitoring.catchholebackend.domain.worldimage.entity.CharacterImage;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageCatalog;
import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.domain.worldimage.mapper.WorldImageMapper;
import org.monitoring.catchholebackend.domain.worldimage.processor.CharacterRaceImageMatcher;
import org.monitoring.catchholebackend.domain.worldimage.repository.*;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CharacterImageServiceImpl implements CharacterImageService {
    private final CharacterImageRepository selections;
    private final WorldImageCatalogRepository catalogs;
    private final PrivateWorldImageRepository privateImages;
    private final WorkCharacterRepository characters;
    private final WorkRepository works;
    private final CharacterRaceImageMatcher matcher;
    private final WorldImageMapper mapper;

    @Override
    public Map<UUID, WorldSettingImageResponse> getCharacterImages(Collection<WorkCharacter> subjects) {
        if (subjects.isEmpty()) return Map.of();
        var selected = selections.findSelections(subjects.stream().map(WorkCharacter::getId).toList()).stream()
                .collect(Collectors.toMap(CharacterImage::getCharacterId, Function.identity()));
        Map<UUID, WorldSettingImageResponse> result = new HashMap<>();
        for (var character : subjects) {
            var selection = selected.get(character.getId());
            long version = selection == null ? 0 : selection.getVersion();
            if (selection != null && selection.getPrivateImage() != null) {
                result.put(character.getId(), mapper.toPrivateSelectionResponse(selection.getPrivateImage(), version));
                continue;
            }
            WorldImageCatalog catalog = null;
            String source = selection == null ? null : selection.getSelectionSource();
            if ("MANUAL".equals(source) || "AUTO".equals(source)) {
                var chosen = selection.getCatalog();
                if (chosen != null && chosen.isActive() && chosen.getCategory() == WorldSettingCategory.RACE) catalog = chosen;
            }
            result.put(character.getId(), mapper.toSelectionResponse(catalog,
                    "MANUAL".equals(source) ? "MANUAL" : "DEFAULT".equals(source) ? "DEFAULT" : "AUTO", version));
        }
        return result;
    }

    @Override
    @Transactional
    public WorldSettingImageResponse updateCharacterImage(Long memberId, UUID workId, UUID characterId, CharacterImageUpdateRequest request) {
        works.getOwnedWorkForUpdate(workId, memberId);
        var character = characters.findByIdAndWorkIdAndStatusForUpdate(characterId, workId, CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(CharacterErrorCode.CHARACTER_NOT_FOUND));
        var selection = selections.findById(characterId).orElseGet(() -> CharacterImage.create(character));
        selection.validateVersion(request.version());
        boolean useDefault = Boolean.TRUE.equals(request.useDefault());
        int choices = (request.catalogId() != null ? 1 : 0) + (request.privateImageId() != null ? 1 : 0) + (useDefault ? 1 : 0);
        if (choices > 1) throw new AppException(WorldImageErrorCode.WORLD_IMAGE_SELECTION_CONFLICT);
        if (choices == 0) {
            var matched = matcher.matchRace(character, catalogs.findRaceImagesWithAliases());
            if (selection.selectAutomaticImage(matched)) selections.saveAndFlush(selection);
            return getCharacterImages(List.of(character)).get(characterId);
        }
        var privateImage = request.privateImageId() == null ? null : privateImages.findByIdAndWorkIdAndStatus(request.privateImageId(), workId, PrivateWorldImageStatus.READY)
                .orElseThrow(() -> new AppException(WorldImageErrorCode.WORLD_IMAGE_NOT_FOUND));
        var catalog = request.catalogId() == null ? null : catalogs.findById(request.catalogId()).filter(WorldImageCatalog::isActive)
                .orElseThrow(() -> new AppException(WorldImageErrorCode.WORLD_IMAGE_NOT_FOUND));
        if (catalog != null && (catalog.getCategory() != WorldSettingCategory.RACE || catalog.isDefaultImage())) {
            throw new AppException(WorldImageErrorCode.WORLD_IMAGE_CATEGORY_MISMATCH);
        }
        if (selection.selectImage(catalog, privateImage, useDefault)) selections.saveAndFlush(selection);
        return getCharacterImages(List.of(character)).get(characterId);
    }
}
