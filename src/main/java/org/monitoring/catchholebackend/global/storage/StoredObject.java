package org.monitoring.catchholebackend.global.storage;

public record StoredObject(
        String key,
        String versionId
) {
}
