package org.monitoring.catchholebackend.domain.upload.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.stream.XMLInputFactory;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;

/** 문서가 선언한 크기 대신 실제 읽은 바이트와 구조 깊이를 제한한다. 요청마다 새로 사용한다. */
final class DocumentReadLimits {
    static final int MAX_PART_BYTES = 10 * 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;
    static final int MAX_ENTRIES = 256;
    static final int MAX_DEPTH = 64;
    static final int MAX_NODES = 200_000;
    private long totalBytes;

    byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            totalBytes += count;
            if (totalBytes > MAX_TOTAL_BYTES || output.size() + count > MAX_PART_BYTES) {
                throw tooLarge();
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static XMLInputFactory xmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new javax.xml.stream.XMLStreamException("External XML resources are disabled");
        });
        return factory;
    }

    static AppException tooLarge() {
        return new AppException(UploadErrorCode.UPLOAD_DOCUMENT_LIMIT_EXCEEDED);
    }

    static AppException invalid() {
        return new AppException(UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
    }

    static String withoutFinalNewline(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }
}
