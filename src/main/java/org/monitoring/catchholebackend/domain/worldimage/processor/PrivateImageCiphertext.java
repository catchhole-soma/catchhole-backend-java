package org.monitoring.catchholebackend.domain.worldimage.processor;

import java.io.IOException;
import java.util.Base64;
import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.web.multipart.MultipartFile;

/** CHI1 + 12-byte nonce + AES-GCM ciphertext/tag. 서버는 복호화나 이미지 디코딩을 하지 않는다. */
public final class PrivateImageCiphertext {
    private PrivateImageCiphertext() {}
    public static final long IMAGE_LIMIT = 5L * 1024 * 1024 + 32;
    public static final long THUMBNAIL_LIMIT = 512L * 1024;

    public static byte[] read(MultipartFile file, long limit) {
        if (file.isEmpty() || file.getSize() > limit) throw invalid();
        try {
            byte[] bytes = file.getBytes();
            validate(bytes);
            return bytes;
        } catch (IOException exception) {
            throw invalid();
        }
    }

    public static void validateBase64(String value) {
        try {
            validate(Base64.getDecoder().decode(value));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void validate(byte[] bytes) {
        if (bytes.length < 33 || bytes[0] != 'C' || bytes[1] != 'H' || bytes[2] != 'I' || bytes[3] != '1') {
            throw invalid();
        }
    }
    private static AppException invalid() { return new AppException(WorldImageErrorCode.PRIVATE_IMAGE_INVALID); }
}
