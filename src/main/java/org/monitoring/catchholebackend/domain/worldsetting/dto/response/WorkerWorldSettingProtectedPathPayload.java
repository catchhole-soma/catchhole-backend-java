package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record WorkerWorldSettingProtectedPathPayload(@Schema(nullable = true) String scopeName, String settingName) {}
