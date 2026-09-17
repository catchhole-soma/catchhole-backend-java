package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.Collection;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;

public interface AutomaticImageService {
    void refreshCharacterImages(Collection<WorkCharacter> characters);
    void refreshWorldSettingImages(Collection<WorldSetting> settings);
}
