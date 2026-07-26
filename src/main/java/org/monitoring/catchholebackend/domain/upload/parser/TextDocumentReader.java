package org.monitoring.catchholebackend.domain.upload.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Component
public class TextDocumentReader {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_DOCX_DOCUMENT_XML_SIZE = 10 * 1024 * 1024;
    private static final String DOCX_DOCUMENT_ENTRY = "word/document.xml";

    public String readText(MultipartFile sourceFile) {
        validateTextDocument(sourceFile);
        try {
            return readText(requireOriginalFilename(sourceFile), sourceFile.getBytes());
        } catch (IOException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_READ_FAILED, exception);
        }
    }

    public String readText(String originalFilename, byte[] fileBytes) {
        validateTextDocument(originalFilename, fileBytes);
        try {
            String content = originalFilename.toLowerCase(Locale.ROOT).endsWith(".docx")
                    ? readDocxText(fileBytes)
                    : stripUtf8Bom(new String(fileBytes, StandardCharsets.UTF_8));
            if (!StringUtils.hasText(content)) {
                throw new AppException(UploadErrorCode.UPLOAD_FILE_EMPTY);
            }
            return content;
        } catch (XMLStreamException | IOException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_READ_FAILED, exception);
        }
    }

    private void validateTextDocument(MultipartFile sourceFile) {
        if (sourceFile == null || sourceFile.isEmpty()) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_EMPTY);
        }
        validateTextDocument(requireOriginalFilename(sourceFile), sourceFile.getSize());
    }

    private void validateTextDocument(String originalFilename, byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_EMPTY);
        }
        validateTextDocument(originalFilename, fileBytes.length);
    }

    private void validateTextDocument(String originalFilename, long fileSize) {
        if (fileSize > MAX_FILE_SIZE) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_TOO_LARGE);
        }
        String normalizedFilename = originalFilename.toLowerCase(Locale.ROOT);
        if (!normalizedFilename.endsWith(".txt") && !normalizedFilename.endsWith(".docx")) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_TYPE_NOT_SUPPORTED);
        }
    }

    private String readDocxText(byte[] fileBytes) throws IOException, XMLStreamException {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (!DOCX_DOCUMENT_ENTRY.equals(entry.getName())) {
                    continue;
                }
                XMLInputFactory factory = XMLInputFactory.newFactory();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
                byte[] documentXml = zipInputStream.readNBytes(MAX_DOCX_DOCUMENT_XML_SIZE + 1);
                if (documentXml.length > MAX_DOCX_DOCUMENT_XML_SIZE) {
                    throw new AppException(UploadErrorCode.UPLOAD_FILE_TOO_LARGE);
                }
                XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(documentXml));
                StringBuilder text = new StringBuilder();
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String name = reader.getLocalName();
                        if ("t".equals(name)) {
                            text.append(reader.getElementText());
                        } else if ("tab".equals(name)) {
                            text.append('\t');
                        } else if ("br".equals(name)) {
                            text.append('\n');
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && "p".equals(reader.getLocalName())) {
                        text.append('\n');
                    }
                }
                reader.close();
                String result = text.toString().trim();
                if (!StringUtils.hasText(result)) {
                    throw new AppException(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED);
                }
                return result;
            }
        }
        throw new AppException(UploadErrorCode.UPLOAD_FILE_PARSE_FAILED);
    }

    private String stripUtf8Bom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    public String requireOriginalFilename(MultipartFile sourceFile) {
        if (sourceFile == null || !StringUtils.hasText(sourceFile.getOriginalFilename())) {
            throw new AppException(UploadErrorCode.UPLOAD_FILE_TYPE_NOT_SUPPORTED);
        }
        return sourceFile.getOriginalFilename();
    }
}
