package org.monitoring.catchholebackend.global.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

    /**
     * 교체 원문을 새 고유 key에 먼저 저장한다. 호출자는 이전 원문을 파기할 때 반환된 key를
     * 제외 대상으로 전달한 뒤 Episode 메타데이터를 갱신한다.
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

    /**
     * 설정집에서 추출한 편집용 텍스트를 작품/설정집/원본 파일명 기반의 고정 key에 저장한다.
     * 같은 설정집을 다시 수정하면 동일 key를 PUT해 새 객체 key가 누적되지 않게 한다.
     */
    public StoredObject putSettingBookContent(
            UUID workId,
            UUID settingBookId,
            String originalFilename,
            String content
    ) {
        return objectStorage.putText(
                buildSettingBookContentKey(workId, settingBookId, originalFilename),
                content
        );
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

    public ObjectStoragePurgeResult purgeWork(UUID workId, Collection<UUID> uploadBatchIds) {
        List<String> prefixes = new ArrayList<>();
        prefixes.add("works/" + workId + "/");
        uploadBatchIds.stream()
                .map(batchId -> "upload-batches/" + batchId + "/")
                .forEach(prefixes::add);
        return objectStorage.purgePrefixes(prefixes);
    }

    /**
     * 회차 번호 아래에 누적된 모든 원문 version과 현재 업로드 원본을 완전히 파기한다.
     * 파일 교체에서는 먼저 저장한 새 원문 key만 제외해 새 원문까지 함께 지우지 않는다.
     */
    public ObjectStoragePurgeResult purgeEpisodeSource(
            UUID workId,
            int episodeNo,
            String currentContentKey,
            String sourceStorageUrl,
            String retainedContentKey
    ) {
        String episodePrefix = "works/" + workId + "/episodes/" + episodeNo + "/";
        Set<String> prefixes = new LinkedHashSet<>();
        prefixes.add(episodePrefix);
        if (StringUtils.hasText(currentContentKey) && !currentContentKey.startsWith(episodePrefix)) {
            prefixes.add(currentContentKey);
        }
        String sourceKey = storageKeyOrNull(sourceStorageUrl);
        if (sourceKey != null) {
            prefixes.add(sourceKey);
        }
        List<String> retainedKeys = StringUtils.hasText(retainedContentKey)
                ? List.of(retainedContentKey)
                : List.of();
        return objectStorage.purgePrefixesExcluding(prefixes, retainedKeys);
    }

    public String toStorageUrl(String key) {
        return "s3://" + key;
    }

    private String storageKeyOrNull(String storageUrl) {
        return StringUtils.hasText(storageUrl) && storageUrl.startsWith("s3://")
                ? storageUrl.substring("s3://".length())
                : null;
    }

    /**
     * 업로드 파일 key는 원본 파일명을 보존하되 UUID를 포함해 같은 파일명 업로드 간 충돌을 막는다.
     */
    private String buildUploadFileKey(UUID batchId, String originalFilename) {
        return "upload-batches/" + batchId + "/" + UUID.randomUUID() + "-" + originalFilename;
    }

    private String buildSettingBookContentKey(
            UUID workId,
            UUID settingBookId,
            String originalFilename
    ) {
        String normalizedFilename =
                Normalizer.normalize(originalFilename, Normalizer.Form.NFC).replace('\\', '/');
        String filename = normalizedFilename.substring(normalizedFilename.lastIndexOf('/') + 1);
        String baseFilename = filename.substring(0, filename.lastIndexOf('.'));
        return "works/" + workId + "/setting-books/" + settingBookId + "/"
                + baseFilename + ".txt";
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
