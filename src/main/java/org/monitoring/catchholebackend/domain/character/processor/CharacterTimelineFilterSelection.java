package org.monitoring.catchholebackend.domain.character.processor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineFactFilter;
import org.monitoring.catchholebackend.global.exception.AppException;

/**
 * 단일 유형 필터와 종류별 다중 선택을 하나의 타임라인 조회 조건으로 정규화한다.
 * Repository, 요약 집계와 cursor가 같은 조건을 공유해 화면마다 필터 의미가 달라지지 않게 한다.
 */
public record CharacterTimelineFilterSelection(
        boolean all,
        CharacterTimelineFactFilter appliedFactType,
        List<CharacterFactType> factTypes,
        List<String> factKeys
) {

    public static CharacterTimelineFilterSelection from(
            CharacterTimelineFactFilter legacyFactType,
            List<CharacterTimelineFactFilter> requestedFactTypes,
            List<String> requestedFactKeys
    ) {
        boolean multiSelectionRequested = requestedFactTypes != null || requestedFactKeys != null;
        if (!multiSelectionRequested) {
            return new CharacterTimelineFilterSelection(
                    legacyFactType == CharacterTimelineFactFilter.ALL,
                    legacyFactType,
                    legacyFactType.toFactTypes(),
                    List.of()
            );
        }
        if (legacyFactType != CharacterTimelineFactFilter.ALL) {
            throw invalidFilter();
        }

        if (requestedFactKeys != null && requestedFactKeys.stream().anyMatch(String::isBlank)) {
            throw invalidFilter();
        }

        List<CharacterTimelineFactFilter> filters = requestedFactTypes == null
                ? List.of()
                : requestedFactTypes.stream().distinct().toList();
        List<String> factKeys = requestedFactKeys == null
                ? List.of()
                : requestedFactKeys.stream()
                        .map(String::trim)
                        .distinct()
                        .sorted()
                        .toList();

        if (filters.contains(CharacterTimelineFactFilter.ALL)) {
            if (filters.size() > 1 || !factKeys.isEmpty()) {
                throw invalidFilter();
            }
            return new CharacterTimelineFilterSelection(
                    true,
                    CharacterTimelineFactFilter.ALL,
                    CharacterTimelineFactFilter.supportedFactTypes(),
                    List.of()
            );
        }

        List<CharacterFactType> factTypes = filters.stream()
                .map(filter -> CharacterFactType.valueOf(filter.name()))
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .toList();
        if (factTypes.isEmpty() && factKeys.isEmpty()) {
            throw invalidFilter();
        }

        return new CharacterTimelineFilterSelection(
                false,
                CharacterTimelineFactFilter.ALL,
                factTypes,
                factKeys
        );
    }

    public String cursorFingerprint() {
        String canonicalSelection = "types=" + factTypes.stream()
                .map(Enum::name)
                .collect(Collectors.joining(","))
                + "|keys=" + String.join(",", factKeys)
                + "|all=" + all;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalSelection.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    /**
     * 종류별 보기에서 사용자가 명시적으로 고른 상위 유형만 응답에 노출한다.
     * 기존 단일 필터와 ALL 조회에 내부적으로 사용하는 유효 유형 목록은 선택값으로 오인하지 않는다.
     */
    public List<CharacterFactType> explicitFactTypes() {
        if (all || appliedFactType != CharacterTimelineFactFilter.ALL) {
            return List.of();
        }
        return factTypes;
    }

    private static AppException invalidFilter() {
        return new AppException(CharacterErrorCode.CHARACTER_TIMELINE_FILTER_INVALID);
    }
}
