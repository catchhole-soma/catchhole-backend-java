package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingIdentityUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingDetailResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSort;

public interface WorldSettingService {

    WorldSettingListResponse getWorldSettings(
            Long memberId,
            UUID workId,
            String query,
            WorldSettingCategory category,
            WorldSettingSort sort,
            int page,
            int size
    );

    WorldSettingDetailResponse getWorldSetting(Long memberId, UUID workId, UUID worldSettingId);

    WorldSettingDetailResponse createWorldSetting(Long memberId, UUID workId, WorldSettingCreateRequest request);

    WorldSettingDetailResponse updateWorldSettingIdentity(
            Long memberId,
            UUID workId,
            UUID worldSettingId,
            WorldSettingIdentityUpdateRequest request
    );

    WorldSettingDetailResponse addWorldSettingProperty(
            Long memberId,
            UUID workId,
            UUID worldSettingId,
            WorldSettingPropertyCreateRequest request
    );

    WorldSettingDetailResponse updateWorldSettingProperty(
            Long memberId,
            UUID workId,
            UUID worldSettingId,
            WorldSettingPropertyUpdateRequest request
    );
}
