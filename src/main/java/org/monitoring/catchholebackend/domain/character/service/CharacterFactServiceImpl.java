package org.monitoring.catchholebackend.domain.character.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactEvidenceResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactSearchResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactMapper;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSearchScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSearchType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CharacterFactServiceImpl implements CharacterFactService {

    private final WorkRepository workRepository;
    private final CharacterFactRepository characterFactRepository;
    private final CharacterSnapshotSourceRepository characterSnapshotSourceRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final CharacterFactMapper characterFactMapper;
    private final CharacterFactEvidenceMapper characterFactEvidenceMapper;
    private final ObjectStorageService objectStorageService;

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

        String normalizedQuery = normalizeQuery(query);
        List<CharacterFactType> factTypes = factType.toFactTypes();
        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(workId);
        Page<CharacterFact> factPage = characterFactRepository.search(
                workId,
                CharacterStatus.ACTIVE,
                factTypes,
                escapeLikePattern(normalizedQuery),
                escapeLikePattern(normalizeFactKeyQuery(normalizedQuery)),
                findDisplayNameSchemaKeys(normalizedQuery, factTypes, schemas),
                scope == CharacterFactSearchScope.ALL,
                scope == CharacterFactSearchScope.CURRENT,
                PageRequest.of(page, size)
        );
        List<UUID> factIds = factPage.getContent().stream().map(CharacterFact::getId).toList();
        Set<UUID> snapshotSourceFactIds = factIds.isEmpty()
                ? Set.of()
                : characterSnapshotSourceRepository.findAllBySourceFactIdIn(factIds).stream()
                        .map(source -> source.getSourceFact().getId())
                        .collect(Collectors.toSet());
        List<CharacterFactSearchResponse> content = factPage.getContent().stream()
                .map(fact -> characterFactMapper.toSearchResponse(
                        fact,
                        schemas,
                        snapshotSourceFactIds.contains(fact.getId())
                ))
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

        List<CharacterSettingSchema> schemas =
                characterSettingSchemaRepository.findAllActiveForWork(workId);
        return characterFactMapper.toDetailResponse(
                fact,
                schemas,
                characterSnapshotSourceRepository.existsBySourceFactId(fact.getId())
        );
    }

    @Override
    public CharacterFactEvidenceResponse getEvidence(
            Long memberId,
            UUID workId,
            UUID characterFactId
    ) {
        workRepository.getOwnedWork(workId, memberId);
        CharacterFact fact = characterFactRepository
                .findActiveByIdAndWorkId(characterFactId, workId, CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(CharacterErrorCode.CHARACTER_FACT_NOT_FOUND));
        validateEvidenceBelongsToWork(fact, workId);

        String content = loadAnalysisSource(fact);
        return characterFactEvidenceMapper.toResponse(fact, content);
    }

    /**
     * 느슨한 이력 참조가 잘못 연결돼도 다른 작품의 후보·회차 원문을 노출하지 않는다.
     */
    private void validateEvidenceBelongsToWork(CharacterFact fact, UUID workId) {
        SettingCandidate candidate = fact.getSettingCandidate();
        if (candidate != null && (!candidate.getWork().getId().equals(workId)
                || candidate.getEpisode() != null
                && !candidate.getEpisode().getWork().getId().equals(workId))) {
            throw new AppException(CharacterErrorCode.CHARACTER_FACT_NOT_FOUND);
        }
        if (fact.getSourceEpisode() != null
                && !fact.getSourceEpisode().getWork().getId().equals(workId)) {
            throw new AppException(CharacterErrorCode.CHARACTER_FACT_NOT_FOUND);
        }
    }

    /**
     * 신규 후보는 분석 당시 key를 사용하고, 컬럼 추가 전 후보만 현재 회차 key로 fallback한다.
     * 저장소 장애는 인용문까지 숨기지 않도록 원문만 null로 응답한다.
     */
    private String loadAnalysisSource(CharacterFact fact) {
        SettingCandidate candidate = fact.getSettingCandidate();
        if (candidate == null) {
            return null;
        }

        String sourceKey = candidate.getSourceContentS3Key();
        if ((sourceKey == null || sourceKey.isBlank()) && candidate.getEpisode() != null) {
            sourceKey = candidate.getEpisode().getContentS3Key();
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            return null;
        }

        try {
            return objectStorageService.getText(sourceKey);
        } catch (AppException exception) {
            log.warn(
                    "Character Fact evidence source read failed. factId={}, candidateId={}",
                    fact.getId(),
                    candidate.getId()
            );
            return null;
        }
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }

    /**
     * 화면 표시명과 동적 factKey의 공백·underscore를 제거해 반복 구분자도 같은 이름으로 검색한다.
     */
    private String normalizeFactKeyQuery(String query) {
        return query.replaceAll("[\\s_]+", "");
    }

    /**
     * exact schema의 한글 표시명을 내부 factKey로 역매핑해 페이지네이션 전에 검색한다.
     */
    private List<String> findDisplayNameSchemaKeys(
            String query,
            List<CharacterFactType> factTypes,
            List<CharacterSettingSchema> schemas
    ) {
        String normalizedQuery = normalizeDisplayName(query);
        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        return schemas.stream()
                .filter(schema -> factTypes.contains(schema.getFactType()))
                .filter(schema -> normalizeDisplayName(schema.getDisplayName()).contains(normalizedQuery))
                .map(schema -> schema.getSchemaKey().trim())
                .distinct()
                .toList();
    }

    private String normalizeDisplayName(String value) {
        return value.replaceAll("[\\s_]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String escapeLikePattern(String query) {
        return query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
