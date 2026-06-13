package org.monitoring.catchholebackend.global.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ObjectStorageService {

    private final ObjectStorage objectStorage;

    public StoredTextObject putEpisodeContent(UUID workId, int episodeNo, String content) {
        StoredObject storedObject = objectStorage.putText(buildEpisodeContentKey(workId, episodeNo), content);
        return new StoredTextObject(
                storedObject.key(),
                storedObject.versionId(),
                sha256(content),
                content.length()
        );
    }

    public StoredTextObject replaceEpisodeContent(
            UUID workId,
            int episodeNo,
            String oldContentKey,
            String content
    ) {
        StoredTextObject storedTextObject = putEpisodeContent(workId, episodeNo, content);
        if (oldContentKey != null && !oldContentKey.equals(storedTextObject.key())) {
            objectStorage.delete(oldContentKey);
        }
        return storedTextObject;
    }

    public StoredObject putUploadFile(UUID batchId, String originalFilename, byte[] bytes, String contentType) {
        return objectStorage.putBytes(buildUploadFileKey(batchId, originalFilename), bytes, contentType);
    }

    public String getText(String key) {
        return objectStorage.getText(key);
    }

    public void delete(String key) {
        objectStorage.delete(key);
    }

    public String toStorageUrl(String key) {
        return "s3://" + key;
    }

    private String buildUploadFileKey(UUID batchId, String originalFilename) {
        return "upload-batches/" + batchId + "/" + UUID.randomUUID() + "-" + originalFilename;
    }

    private String buildEpisodeContentKey(UUID workId, int episodeNo) {
        return "works/" + workId + "/episodes/" + episodeNo + ".txt";
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", exception);
        }
    }
}
