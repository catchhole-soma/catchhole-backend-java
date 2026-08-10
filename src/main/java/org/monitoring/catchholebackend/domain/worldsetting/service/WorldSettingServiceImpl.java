package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingIdentityUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingDetailResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingListItemResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSort;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldSettingServiceImpl implements WorldSettingService {

    private final WorkRepository workRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final WorldSettingMapper worldSettingMapper;

    @Override
    public WorldSettingListResponse getWorldSettings(
            Long memberId,
            UUID workId,
            String query,
            WorldSettingCategory category,
            WorldSettingSort sort,
            int page,
            int size
    ) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        String normalizedQuery = WorldSettingNameNormalizer.displayName(query);
        normalizedQuery = normalizedQuery == null ? "" : normalizedQuery;
        PageRequest pageable = PageRequest.of(page, size);
        Page<WorldSetting> worldSettingPage = sort == WorldSettingSort.UPDATED_DESC
                ? worldSettingRepository.searchUpdatedDesc(
                        work.getId(),
                        normalizedQuery,
                        category == null ? null : category.name(),
                        pageable
                )
                : worldSettingRepository.searchCategorySubjectAsc(
                        work.getId(),
                        normalizedQuery,
                        category == null ? null : category.name(),
                        pageable
                );
        String mappingQuery = normalizedQuery;
        List<WorldSettingListItemResponse> items = worldSettingPage.getContent().stream()
                .map(worldSetting -> worldSettingMapper.toListItemResponse(worldSetting, mappingQuery))
                .toList();
        return new WorldSettingListResponse(
                worldSettingRepository.countByWorkId(work.getId()),
                PageResponse.from(worldSettingPage, items)
        );
    }

    @Override
    public WorldSettingDetailResponse getWorldSetting(Long memberId, UUID workId, UUID worldSettingId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        return toDetail(getWorldSetting(worldSettingId, work.getId()));
    }

    @Override
    @Transactional
    public WorldSettingDetailResponse createWorldSetting(
            Long memberId,
            UUID workId,
            WorldSettingCreateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        String normalizedSubjectName = WorldSettingNameNormalizer.duplicateKey(request.subjectName());
        if (worldSettingRepository.findByWorkIdAndCategoryAndNormalizedSubjectName(
                work.getId(),
                request.category(),
                normalizedSubjectName
        ).isPresent()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_SUBJECT_DUPLICATED);
        }

        WorldSetting worldSetting = worldSettingMapper.toEntity(work, request);
        return toDetail(saveNewWorldSetting(worldSetting));
    }

    @Override
    @Transactional
    public WorldSettingDetailResponse updateWorldSettingIdentity(
            Long memberId,
            UUID workId,
            UUID worldSettingId,
            WorldSettingIdentityUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSetting worldSetting = getWorldSettingForUpdate(worldSettingId, work.getId());
        worldSetting.validateVersion(request.version());
        String normalizedSubjectName = WorldSettingNameNormalizer.duplicateKey(request.subjectName());
        if (worldSettingRepository.existsByWorkIdAndCategoryAndNormalizedSubjectNameAndIdNot(
                work.getId(),
                request.category(),
                normalizedSubjectName,
                worldSetting.getId()
        )) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_SUBJECT_DUPLICATED);
        }
        worldSetting.changeIdentity(request.category(), request.subjectName());
        flushIdentityChange(worldSetting);
        return toDetail(worldSetting);
    }

    @Override
    @Transactional
    public WorldSettingDetailResponse addWorldSettingProperty(
            Long memberId,
            UUID workId,
            UUID worldSettingId,
            WorldSettingPropertyCreateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSetting worldSetting = getWorldSettingForUpdate(worldSettingId, work.getId());
        worldSetting.validateVersion(request.version());
        worldSetting.addProperty(request.scopeName(), request.settingName(), request.settingValue());
        worldSettingRepository.flush();
        return toDetail(worldSetting);
    }

    @Override
    @Transactional
    public WorldSettingDetailResponse updateWorldSettingProperty(
            Long memberId,
            UUID workId,
            UUID worldSettingId,
            WorldSettingPropertyUpdateRequest request
    ) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        WorldSetting worldSetting = getWorldSettingForUpdate(worldSettingId, work.getId());
        worldSetting.validateVersion(request.version());
        worldSetting.updateProperty(
                request.currentScopeName(),
                request.currentSettingName(),
                request.scopeName(),
                request.settingName(),
                request.settingValue()
        );
        worldSettingRepository.flush();
        return toDetail(worldSetting);
    }

    private WorldSetting saveNewWorldSetting(WorldSetting worldSetting) {
        try {
            return worldSettingRepository.saveAndFlush(worldSetting);
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_SUBJECT_DUPLICATED, exception);
        }
    }

    private void flushIdentityChange(WorldSetting worldSetting) {
        try {
            worldSettingRepository.saveAndFlush(worldSetting);
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_SUBJECT_DUPLICATED, exception);
        }
    }

    private WorldSetting getWorldSetting(UUID worldSettingId, UUID workId) {
        return worldSettingRepository.findByIdAndWorkId(worldSettingId, workId)
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_NOT_FOUND));
    }

    private WorldSetting getWorldSettingForUpdate(UUID worldSettingId, UUID workId) {
        return worldSettingRepository.findByIdAndWorkIdForUpdate(worldSettingId, workId)
                .orElseThrow(() -> new AppException(WorldSettingErrorCode.WORLD_SETTING_NOT_FOUND));
    }

    private WorldSettingDetailResponse toDetail(WorldSetting worldSetting) {
        List<WorldSettingCandidate> confirmedCandidates = worldSettingCandidateRepository
                .findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(
                        worldSetting.getId(),
                        WorldSettingReviewStatus.CONFIRMED
                );
        return worldSettingMapper.toDetailResponse(worldSetting, confirmedCandidates);
    }
}
