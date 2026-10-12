package org.monitoring.catchholebackend.domain.chat.service;

import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolQuery;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse;

import java.util.UUID;

public interface ChatToolAccess {
    record Access(Long memberId, UUID workId, SettingToolQuery query) {}

    Access begin(UUID id, String token, UUID runner, SettingToolQuery query);

    void complete(UUID id, String token, UUID runner, SettingToolResponse response);
}
