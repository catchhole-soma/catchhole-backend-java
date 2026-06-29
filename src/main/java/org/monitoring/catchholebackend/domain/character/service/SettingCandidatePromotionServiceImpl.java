package org.monitoring.catchholebackend.domain.character.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.SettingCandidatePromotionMapper;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingCandidatePromotionServiceImpl implements SettingCandidatePromotionService {

    private final WorkCharacterRepository workCharacterRepository;
    private final CharacterFactRepository characterFactRepository;
    private final EpisodeRepository episodeRepository;
    private final SettingCandidatePromotionMapper settingCandidatePromotionMapper;

    @Override
    @Transactional
    public void promote(SettingCandidate candidate) {
        // 지원하지 않는 속성은 캐릭터 생성 전에 거절해 부수효과를 남기지 않는다.
        String factKey = settingCandidatePromotionMapper.toFactKey(candidate);
        CharacterFactType factType = settingCandidatePromotionMapper.toFactType(factKey);
        WorkCharacter character = getOrCreateCharacter(candidate);
        updateFirstAppearance(character, candidate.getEpisode());

        CharacterFact newFact = characterFactRepository.saveAndFlush(
                settingCandidatePromotionMapper.toCharacterFact(candidate, character, factType, factKey)
        );

        // confirm 순서와 회차 순서가 다를 수 있으므로 같은 key의 fact 전체를 다시 평가한다.
        List<CharacterFact> facts = characterFactRepository
                .findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                        character.getId(),
                        factType,
                        factKey
                );
        CharacterFact currentFact = selectCurrentFact(facts, newFact);
        facts.forEach(fact -> updateCurrentState(fact, currentFact));
        character.applyCurrentFact(currentFact);
    }

    private WorkCharacter getOrCreateCharacter(SettingCandidate candidate) {
        String characterName = settingCandidatePromotionMapper.toCharacterName(candidate);
        return workCharacterRepository.findByWorkIdAndName(candidate.getWork().getId(), characterName)
                .orElseGet(() -> workCharacterRepository.save(
                        settingCandidatePromotionMapper.toWorkCharacter(candidate)
                ));
    }

    private void updateFirstAppearance(WorkCharacter character, Episode sourceEpisode) {
        // 첫 등장은 확정 순서가 아니라 가장 이른 업로드 회차 기준으로 유지한다.
        if (sourceEpisode == null) {
            return;
        }
        UUID currentFirstAppearanceId = character.getFirstAppearanceEpisodeId();
        if (currentFirstAppearanceId == null) {
            character.updateFirstAppearanceEpisodeId(sourceEpisode.getId());
            return;
        }
        episodeRepository.findByIdAndWorkId(currentFirstAppearanceId, character.getWork().getId())
                .filter(currentFirstAppearance -> sourceEpisode.getEpisodeNo() < currentFirstAppearance.getEpisodeNo())
                .ifPresent(currentFirstAppearance -> character.updateFirstAppearanceEpisodeId(sourceEpisode.getId()));
    }

    private CharacterFact selectCurrentFact(List<CharacterFact> facts, CharacterFact newFact) {
        return facts.stream()
                .reduce((current, candidate) -> isMoreRecent(candidate, current, newFact) ? candidate : current)
                .orElse(newFact);
    }

    private boolean isMoreRecent(CharacterFact candidate, CharacterFact current, CharacterFact newFact) {
        // null episodeNo는 가장 오래된 값으로 보고, 같은 회차는 생성 시각과 방금 저장한 fact로 tie-break 한다.
        int episodeComparison = compareNullableInteger(
                candidate.getEffectiveFromEpisodeNo(),
                current.getEffectiveFromEpisodeNo()
        );
        if (episodeComparison != 0) {
            return episodeComparison > 0;
        }

        int createdAtComparison = compareNullableDateTime(candidate.getCreatedAt(), current.getCreatedAt());
        if (createdAtComparison != 0) {
            return createdAtComparison > 0;
        }

        return candidate.getId().equals(newFact.getId()) && !current.getId().equals(newFact.getId());
    }

    private int compareNullableInteger(Integer left, Integer right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private int compareNullableDateTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private void updateCurrentState(CharacterFact fact, CharacterFact currentFact) {
        if (fact.getId().equals(currentFact.getId())) {
            fact.markCurrent();
            return;
        }
        fact.markHistorical();
    }
}
