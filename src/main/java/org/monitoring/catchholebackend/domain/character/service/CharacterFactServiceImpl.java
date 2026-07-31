package org.monitoring.catchholebackend.domain.character.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactSearchResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactMapper;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSearchScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSearchType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CharacterFactServiceImpl implements CharacterFactService {

    private final WorkRepository workRepository;
    private final CharacterFactRepository characterFactRepository;
    private final CharacterFactMapper characterFactMapper;

    @Override
    public PageResponse<CharacterFactSearchResponse> searchCharacterFacts(
            Long memberId,
            UUID workId,
            String query,
            CharacterFactSearchType factType,
            CharacterFactSearchScope scope,
            int page,
            int size
    ) {
        workRepository.getOwnedWork(workId, memberId);

        Page<CharacterFact> factPage = characterFactRepository.search(
                workId,
                CharacterStatus.ACTIVE,
                factType.toFactTypes(),
                escapeLikePattern(normalizeQuery(query)),
                scope == CharacterFactSearchScope.ALL,
                scope == CharacterFactSearchScope.CURRENT,
                PageRequest.of(page, size)
        );
        List<CharacterFactSearchResponse> content = factPage.getContent().stream()
                .map(characterFactMapper::toSearchResponse)
                .toList();

        return PageResponse.from(factPage, content);
    }

    @Override
    public CharacterFactDetailResponse getCharacterFact(
            Long memberId,
            UUID workId,
            UUID characterFactId
    ) {
        workRepository.getOwnedWork(workId, memberId);

        CharacterFact fact = characterFactRepository
                .findActiveByIdAndWorkId(characterFactId, workId, CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(CharacterErrorCode.CHARACTER_FACT_NOT_FOUND));

        return characterFactMapper.toDetailResponse(fact);
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }

    private String escapeLikePattern(String query) {
        return query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
