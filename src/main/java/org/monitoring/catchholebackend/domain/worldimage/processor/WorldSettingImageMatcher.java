package org.monitoring.catchholebackend.domain.worldimage.processor;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;
import org.monitoring.catchholebackend.domain.worldimage.entity.WorldImageCatalog;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.springframework.stereotype.Component;

@Component
public class WorldSettingImageMatcher {
    // 붙여 쓴 고유 지명도 허용하는 명확한 지형어. 일반 별칭은 단어 경계가 있어야 한다.
    private static final Set<String> LOCATION_SUFFIXES = Set.of("숲", "동굴", "미궁", "산맥", "사막", "호수", "계곡", "평원", "해안", "해변", "늪지", "화산", "유적", "요새", "성채");

    public WorldImageCatalog match(WorldSetting setting, List<WorldImageCatalog> images) {
        String name = WorldImageSearch.normalize(setting.getSubjectName());
        var candidates = images.stream().filter(i -> i.getCategory() == setting.getCategory()).toList();
        var exact = candidates.stream().filter(i -> terms(i).anyMatch(t -> WorldImageSearch.normalize(t).equals(name))).toList();
        if (!exact.isEmpty()) return exact.size() == 1 ? exact.getFirst() : null;
        String display = Normalizer.normalize(setting.getSubjectName(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT).trim();
        int longest = 0;
        Set<WorldImageCatalog> matches = new HashSet<>();
        for (var image : candidates) {
            for (String term : terms(image).toList()) {
                String normalized = WorldImageSearch.normalize(term);
                if (normalized.isBlank() || !name.endsWith(normalized)) continue;
                String suffix = Normalizer.normalize(term, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).trim();
                boolean boundary = normalized.length() >= 2 && (display.endsWith(" " + suffix) || display.endsWith("의" + suffix));
                boolean terrain = setting.getCategory() == WorldSettingCategory.LOCATION && LOCATION_SUFFIXES.contains(normalized);
                if (!boundary && !terrain) continue;
                if (normalized.length() > longest) { longest = normalized.length(); matches.clear(); }
                if (normalized.length() == longest) matches.add(image);
            }
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private Stream<String> terms(WorldImageCatalog image) {
        return Stream.concat(Stream.of(image.getName()), image.getAliases().stream());
    }
}
