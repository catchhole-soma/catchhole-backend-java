package org.monitoring.catchholebackend.global.storage;

public record StoredTextObject(
        String key,
        String versionId,
        String contentHash,
        int charCount
) {
}
