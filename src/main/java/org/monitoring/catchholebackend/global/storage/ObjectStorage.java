package org.monitoring.catchholebackend.global.storage;

import java.util.Collection;

public interface ObjectStorage {

    StoredObject putText(String key, String content);

    StoredObject putBytes(String key, byte[] bytes, String contentType);

    String getText(String key);

    byte[] getBytes(String key);

    void delete(String key);

    ObjectStoragePurgeResult purgePrefixes(Collection<String> prefixes);

    ObjectStoragePurgeResult purgePrefixesExcluding(
            Collection<String> prefixes,
            Collection<String> retainedKeys
    );
}
