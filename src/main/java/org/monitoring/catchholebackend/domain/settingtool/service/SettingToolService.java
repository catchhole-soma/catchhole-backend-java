package org.monitoring.catchholebackend.domain.settingtool.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolQuery;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse;

public interface SettingToolService {
    SettingToolResponse querySettings(Long memberId, UUID workId, SettingToolQuery query);
}
