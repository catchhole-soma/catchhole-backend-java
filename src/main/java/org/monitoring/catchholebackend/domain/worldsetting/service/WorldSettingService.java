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

    /** 검수 리포트 자신의 정정 전용 설정 수정. updateWorldSettingProperty와 같지만 그 리포트의 검토 단계 설정 잠금만 통과한다(#381). */
    WorldSettingDetailResponse correctWorldSettingProperty(
            Long memberId,
            UUID workId,
            UUID worldSettingId,
            WorldSettingPropertyUpdateRequest request,
            UUID reportId
    );
}
