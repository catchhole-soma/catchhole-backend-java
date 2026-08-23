package org.monitoring.catchholebackend.global.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public class LocalFileObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalFileObjectStorage(
            @Value("${storage.local.root}") String root
    ) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredObject putText(String key, String content) {
        return putBytes(key, content.getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
    }

    @Override
    public StoredObject putBytes(String key, byte[] bytes, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return new StoredObject(key, null);
        } catch (IOException exception) {
            throw storageException("로컬 E2E 파일 저장에 실패했습니다.", exception);
        }
    }

    @Override
    public String getText(String key) {
        return new String(getBytes(key), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getBytes(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException exception) {
            throw storageException("로컬 E2E 파일 조회에 실패했습니다.", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw storageException("로컬 E2E 파일 삭제에 실패했습니다.", exception);
        }
    }

    @Override
    public ObjectStoragePurgeResult purgePrefixes(Collection<String> prefixes) {
        return purgePrefixesExcluding(prefixes, List.of());
    }

    @Override
    public ObjectStoragePurgeResult purgePrefixesExcluding(
            Collection<String> prefixes,
            Collection<String> retainedKeys
    ) {
        int targetCount = 0;
        int deletedCount = 0;
        int failedCount = 0;
        Set<Path> retainedPaths = new HashSet<>();
        retainedKeys.forEach(key -> retainedPaths.add(resolve(key)));
        for (String prefix : prefixes) {
            Path prefixPath = resolve(prefix);
            if (!Files.exists(prefixPath)) {
                continue;
            }
            List<Path> paths = listPathsInReverseOrder(prefixPath);
            for (Path path : paths) {
                boolean regularFile = Files.isRegularFile(path);
                if (regularFile && retainedPaths.contains(path)) {
                    continue;
                }
                if (regularFile) {
                    targetCount++;
                }
                try {
                    Files.deleteIfExists(path);
                    if (regularFile) {
                        deletedCount++;
                    }
                } catch (IOException exception) {
                    if (regularFile) {
                        failedCount++;
                    }
                }
            }
        }
        return new ObjectStoragePurgeResult(targetCount, deletedCount, failedCount);
    }

    private List<Path> listPathsInReverseOrder(Path prefixPath) {
        try (var paths = Files.walk(prefixPath)) {
            return paths.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException exception) {
            throw storageException("로컬 E2E 영구 삭제 대상을 조회하지 못했습니다.", exception);
        }
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("저장소 경로를 벗어난 key입니다.");
        }
        return target;
    }

    private AppException storageException(String message, Exception cause) {
        return new AppException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR, message, cause);
    }
}
