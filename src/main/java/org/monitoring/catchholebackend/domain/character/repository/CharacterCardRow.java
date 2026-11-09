package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;

/** 카드에 필요한 scalar만 읽는다. 현재 설정 JSON·Fact·후보 본문은 투영하지 않는다. */
public record CharacterCardRow(UUID id, String name, String roleLabel, Integer currentAge,
        Integer currentLevel, Integer firstAppearanceEpisodeNo, long recordCount) {}
