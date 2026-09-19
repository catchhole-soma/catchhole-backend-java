package org.monitoring.catchholebackend.domain.worldimage.processor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.web.multipart.MultipartFile;

/** 새 업로드는 브라우저에서 PNG로 정규화하고 서버에서 크기·디코딩을 검증해 다시 인코딩한다. */
public final class PrivateImageContent {
    private PrivateImageContent() {}

    public static byte[] readPng(MultipartFile file, boolean thumbnail) {
        long limit = thumbnail ? 512 * 1024 : 8 * 1024 * 1024;
        if (file == null || file.isEmpty() || file.getSize() > limit) throw invalid();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) throw invalid();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                int dimensionLimit = thumbnail ? 480 : 16384;
                if (width < 1 || height < 1 || width > dimensionLimit || height > dimensionLimit
                        || (long) width * height > 32_000_000) throw invalid();
                var decoded = reader.read(0);
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(decoded, "png", output) || output.size() > limit) throw invalid();
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static AppException invalid() { return new AppException(WorldImageErrorCode.PRIVATE_IMAGE_INVALID); }
}
