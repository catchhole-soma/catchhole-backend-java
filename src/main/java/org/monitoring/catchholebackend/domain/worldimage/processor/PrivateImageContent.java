package org.monitoring.catchholebackend.domain.worldimage.processor;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.monitoring.catchholebackend.domain.worldimage.exception.WorldImageErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.web.multipart.MultipartFile;

/** 일반 업로드의 PNG를 검증하고 저장 크기로 축소해 다시 인코딩한다. */
public final class PrivateImageContent {
    private PrivateImageContent() {}

    public static byte[] readPng(MultipartFile file, boolean thumbnail) {
        long limit = thumbnail ? 512 * 1024 : 5 * 1024 * 1024;
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
                int maxEdge = thumbnail ? 350 : 768;
                int longestEdge = Math.max(width, height);
                int sampling = Math.max(1, longestEdge / maxEdge);
                var parameters = reader.getDefaultReadParam();
                // 큰 PNG도 축소용 디코딩 버퍼의 크기를 제한한다.
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                var decoded = reader.read(0, parameters);
                if (longestEdge > maxEdge) {
                    int targetWidth = Math.max(1, (int) Math.round((double) width * maxEdge / longestEdge));
                    int targetHeight = Math.max(1, (int) Math.round((double) height * maxEdge / longestEdge));
                    decoded = resize(decoded, targetWidth, targetHeight);
                }
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(decoded, "png", output) || output.size() > limit) throw invalid();
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        var resized = new BufferedImage(width, height, type);
        var graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private static AppException invalid() { return new AppException(WorldImageErrorCode.PRIVATE_IMAGE_INVALID); }
}
