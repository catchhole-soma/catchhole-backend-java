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
     * 회차 원문을 작품/회차/저장본 UUID 기반의 고유 key로 저장한다.
     * 사람이 S3에서 회차를 식별할 수 있도록 마지막 파일명은 회차 번호를 유지한다.
     */
    public StoredTextObject putEpisodeContent(UUID workId, int episodeNo, String content) {
        StoredObject storedEpisodeContent = objectStorage.putText(buildEpisodeContentKey(workId, episodeNo), content);
        return new StoredTextObject(
                storedEpisodeContent.key(),
                storedEpisodeContent.versionId(),
                sha256(content),
                countNonWhitespaceCharacters(content)
        );
    }

    public StoredTextObject putEpisodeReplacementContent(UUID workId, int episodeNo, String content) {
        return putEpisodeContent(workId, episodeNo, content);
    }

    /**
     * 회차 원문을 새 고유 key에 저장하고 이전 객체는 분석 이력 확인을 위해 보존한다.
     * 호출자는 반환된 key/version/hash/글자 수로 Episode 메타데이터를 갱신한다.
     */
    public StoredTextObject replaceEpisodeContent(UUID workId, int episodeNo, String content) {
        return putEpisodeContent(workId, episodeNo, content);
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

    public byte[] getBytesFromStorageUrl(String storageUrl) {
        if (storageUrl == null || !storageUrl.startsWith("s3://")) {
            throw new IllegalArgumentException("지원하지 않는 저장소 URL입니다.");
        }
        return objectStorage.getBytes(storageUrl.substring("s3://".length()));
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

    private String buildEpisodeContentKey(UUID workId, int episodeNo) {
        return "works/" + workId + "/episodes/" + episodeNo + "/"
                + UUID.randomUUID() + "/" + episodeNo + ".txt";
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

    private int countNonWhitespaceCharacters(String content) {
        return Math.toIntExact(content.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .count());
    }
}
