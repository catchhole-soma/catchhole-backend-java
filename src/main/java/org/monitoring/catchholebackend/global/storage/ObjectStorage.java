package org.monitoring.catchholebackend.global.storage;

public interface ObjectStorage {

    StoredObject putText(String key, String content);

    StoredObject putBytes(String key, byte[] bytes, String contentType);

    String getText(String key);

    void delete(String key);
}
