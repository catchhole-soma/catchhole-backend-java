package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldimage.entity.*;
import org.monitoring.catchholebackend.domain.worldimage.processor.*;
import org.monitoring.catchholebackend.domain.worldimage.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AutomaticImageServiceImpl implements AutomaticImageService {
    private final CharacterImageRepository characters;
    private final WorldSettingImageRepository settings;
    private final WorldImageCatalogRepository catalogs;
    private final CharacterRaceImageMatcher characterMatcher;
    private final WorldSettingImageMatcher settingMatcher;

    @Override
    public void refreshCharacterImages(Collection<WorkCharacter> subjects) {
        if (subjects.isEmpty()) return;
        var unique = subjects.stream().collect(Collectors.toMap(WorkCharacter::getId, Function.identity(), (a, b) -> a));
        var selected = characters.findSelections(unique.keySet()).stream().collect(Collectors.toMap(CharacterImage::getCharacterId, Function.identity()));
        var automatic = unique.values().stream().filter(c -> !selected.containsKey(c.getId()) || selected.get(c.getId()).isAutomatic()).toList();
        if (automatic.isEmpty()) return;
        var races = catalogs.findRaceImagesWithAliases();
        for (var subject : automatic) {
            var selection = selected.getOrDefault(subject.getId(), CharacterImage.create(subject));
            if (selection.applyAutomaticImage(characterMatcher.matchRace(subject, races))) characters.save(selection);
        }
    }

    @Override
    public void refreshWorldSettingImages(Collection<WorldSetting> subjects) {
        if (subjects.isEmpty()) return;
        var unique = subjects.stream().collect(Collectors.toMap(WorldSetting::getId, Function.identity(), (a, b) -> a));
        var selected = settings.findSelections(unique.keySet()).stream().collect(Collectors.toMap(WorldSettingImage::getWorldSettingId, Function.identity()));
        Map<String, List<WorldImageCatalog>> imagesByTheme = new HashMap<>();
        for (var subject : unique.values()) {
            var selection = selected.getOrDefault(subject.getId(), WorldSettingImage.create(subject));
            if (!selection.isAutomatic()) continue;
            String theme = WorldImageThemes.forGenre(subject.getWork().getGenre());
            var images = imagesByTheme.computeIfAbsent(theme, catalogs::findAutomaticWorldImagesWithAliases);
            if (selection.applyAutomaticImage(settingMatcher.match(subject, images))) settings.save(selection);
        }
    }
}
