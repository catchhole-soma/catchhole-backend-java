package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.CharacterImageUpdateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;

public interface CharacterImageService {
    Map<UUID, WorldSettingImageResponse> getCharacterImages(Collection<WorkCharacter> characters);
    WorldSettingImageResponse updateCharacterImage(Long memberId, UUID workId, UUID characterId, CharacterImageUpdateRequest request);
}
