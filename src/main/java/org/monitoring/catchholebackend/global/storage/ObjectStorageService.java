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

    /**
     * 회차 원문을 작품/회차 번호 기반 key로 저장하고, DB에 남길 저장소 메타데이터를 만든다.
     * 원문 본문은 반환하지 않고 S3 key, version, SHA-256 hash, 글자 수만 반환한다.
     */
    public StoredTextObject putEpisodeContent(UUID workId, int episodeNo, String content) {
        StoredObject storedObject = objectStorage.putText(buildEpisodeContentKey(workId, episodeNo), content);
        return new StoredTextObject(
                storedObject.key(),
                storedObject.versionId(),
                sha256(content),
                content.length()
        );
    }

    /**
     * 회차 원문을 새 내용으로 저장하고, 저장 key가 달라진 경우 이전 객체를 삭제한다.
     * 호출자는 반환된 key/version/hash/글자 수로 Episode 메타데이터를 갱신한다.
     */
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

    /**
     * 업로드 원본 파일을 배치 하위에 저장하고, UploadFile 엔티티가 참조할 저장소 메타데이터를 반환한다.
     * 원본 파일명은 남기되 같은 배치 안에서 이름이 겹쳐도 덮어쓰지 않도록 key를 분리한다.
     */
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

    /**
     * 업로드 파일 key는 원본 파일명을 보존하되 UUID를 포함해 같은 파일명 업로드 간 충돌을 막는다.
     */
    private String buildUploadFileKey(UUID batchId, String originalFilename) {
        return "upload-batches/" + batchId + "/" + UUID.randomUUID() + "-" + originalFilename;
    }

    /**
     * 회차 본문 key는 작품과 회차 번호로 결정해 같은 회차를 다시 저장할 때 동일 위치를 갱신한다.
     */
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
