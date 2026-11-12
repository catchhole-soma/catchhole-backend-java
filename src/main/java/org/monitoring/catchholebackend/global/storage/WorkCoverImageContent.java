package org.monitoring.catchholebackend.global.storage;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.web.multipart.MultipartFile;

/** 확장자·Content-Type을 신뢰하지 않고 디코딩한 픽셀만 PNG로 다시 저장한다. */
public final class WorkCoverImageContent {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096;
    private WorkCoverImageContent() {}

    public static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid();
        try (var stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    if (!Set.of("jpeg", "png", "webp").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw invalid();
                    reader.setInput(input, true, true);
                    reader.addIIOReadWarningListener((source, warning) -> { throw invalid(); });
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) throw invalid();
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null) throw invalid();
                    double scale = Math.min(1, 1200d / Math.max(width, height));
                    var normalized = new BufferedImage(Math.max(1, (int) Math.round(width * scale)),
                            Math.max(1, (int) Math.round(height * scale)), BufferedImage.TYPE_INT_ARGB);
                    var graphics = normalized.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(decoded, 0, 0, normalized.getWidth(), normalized.getHeight(), null);
                    } finally { graphics.dispose(); }
                    var output = new ByteArrayOutputStream();
                    if (!ImageIO.write(normalized, "png", output) || output.size() > MAX_BYTES) throw invalid();
                    return output.toByteArray();
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static AppException invalid() { return new AppException(WorkErrorCode.WORK_COVER_INVALID); }
}
