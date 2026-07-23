package org.monitoring.catchholebackend.global.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
            @Value("${storage.local.root:/private/tmp/catchhole-e2e-storage}") String root
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
