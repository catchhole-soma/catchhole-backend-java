package org.monitoring.catchholebackend.domain.upload.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;

/** HWP 5 공개 규격의 본문 레코드만 읽는다. 그림/스크립트/서식 객체는 생성하지 않는다. */
final class HwpTextReader {
    private static final int PARA_HEADER = 66;
    private static final int PARA_TEXT = 67;
    private static final int CTRL_HEADER = 71;
    private static final int LIST_HEADER = 72;
    private static final int TABLE = 77;
    private static final Set<String> OMITTED_CONTROLS = Set.of("secd", "cold", "head", "foot", "fn  ", "en  ", "tcmt");

    String read(byte[] bytes) throws IOException {
        if (new String(bytes, 0, Math.min(bytes.length, 30), StandardCharsets.US_ASCII).startsWith("HWP Document File V3")) {
            throw unsupported();
        }
        try (POIFSFileSystem file = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            DirectoryEntry root = file.getRoot();
            validateEntries(root, 0, new long[2]);
            DocumentReadLimits limits = new DocumentReadLimits();
            byte[] header = readStream(root, "FileHeader", false, limits);
            if (header.length != 256 || !new String(header, 0, 17, StandardCharsets.US_ASCII).equals("HWP Document File")) {
                throw DocumentReadLimits.invalid();
            }
            if (header[35] != 5) {
                throw unsupported();
            }
            int flags = littleEndian(header).getInt(36);
            if ((flags & 2) != 0) {
                throw new AppException(UploadErrorCode.UPLOAD_DOCUMENT_PASSWORD_PROTECTED);
            }
            // 배포용/DRM 문서는 별도 복호화가 필요하다. 일반 문서로 저장한 원본을 안내한다.
            if ((flags & (4 | 16 | 256 | 1024)) != 0) {
                throw unsupported();
            }
            boolean compressed = (flags & 1) != 0;
            List<Record> info = records(readStream(root, "DocInfo", compressed, limits));
            if (info.isEmpty() || info.getFirst().tag != 16 || info.getFirst().data.remaining() < 2) {
                throw DocumentReadLimits.invalid();
            }
            int sectionCount = Short.toUnsignedInt(info.getFirst().data.getShort(0));
            if (sectionCount == 0 || sectionCount > DocumentReadLimits.MAX_ENTRIES) {
                throw DocumentReadLimits.invalid();
            }
            Entry bodyEntry = root.getEntry("BodyText");
            if (!(bodyEntry instanceof DirectoryEntry body) || body.getEntryCount() != sectionCount) {
                throw DocumentReadLimits.invalid();
            }
            StringBuilder output = new StringBuilder();
            for (int index = 0; index < sectionCount; index++) {
                List<Record> section = records(readStream(body, "Section" + index, compressed, limits));
                if (section.isEmpty() || section.getFirst().tag != PARA_HEADER) {
                    throw DocumentReadLimits.invalid();
                }
                // 본문 문단 목록 뒤의 LIST_HEADER/MEMO_LIST는 바탕쪽·메모이며 본문이 아니다.
                for (Record paragraph : section) {
                    if (paragraph.tag != PARA_HEADER) {
                        break;
                    }
                    if (paragraph.data.remaining() < 4) {
                        throw DocumentReadLimits.invalid();
                    }
                    render(List.of(paragraph), output);
                    if ((paragraph.data.getInt(0) & 0x80000000) != 0) {
                        break;
                    }
                }
            }
            return DocumentReadLimits.withoutFinalNewline(output.toString());
        } catch (AppException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AppException(UploadErrorCode.UPLOAD_DOCUMENT_INVALID, exception);
        }
    }

    private void validateEntries(DirectoryEntry directory, int depth, long[] counts) {
        if (depth > DocumentReadLimits.MAX_DEPTH) {
            throw DocumentReadLimits.tooLarge();
        }
        for (Entry entry : directory) {
            if (++counts[0] > DocumentReadLimits.MAX_ENTRIES) {
                throw DocumentReadLimits.tooLarge();
            }
            if (entry instanceof DirectoryEntry child) {
                validateEntries(child, depth + 1, counts);
            } else if (entry instanceof DocumentEntry document) {
                counts[1] += document.getSize();
                if (document.getSize() < 0 || counts[1] > DocumentReadLimits.MAX_TOTAL_BYTES) {
                    throw DocumentReadLimits.tooLarge();
                }
            }
        }
    }

    private byte[] readStream(DirectoryEntry directory, String name, boolean compressed, DocumentReadLimits limits)
            throws IOException {
        if (!(directory.getEntry(name) instanceof DocumentEntry document)) {
            throw DocumentReadLimits.invalid();
        }
        try (DocumentInputStream input = new DocumentInputStream(document)) {
            if (!compressed) {
                return limits.read(input);
            }
            Inflater inflater = new Inflater(true);
            try (InflaterInputStream inflated = new InflaterInputStream(input, inflater)) {
                byte[] result = limits.read(inflated);
                if (!inflater.finished()) {
                    throw DocumentReadLimits.invalid();
                }
                return result;
            } finally {
                inflater.end();
            }
        }
    }

    private List<Record> records(byte[] bytes) {
        ByteBuffer input = littleEndian(bytes);
        Record root = new Record(-1, -1, ByteBuffer.allocate(0));
        ArrayDeque<Record> parents = new ArrayDeque<>();
        parents.push(root);
        int count = 0;
        while (input.hasRemaining()) {
            if (input.remaining() < 4) {
                throw DocumentReadLimits.invalid();
            }
            int header = input.getInt();
            int tag = header & 0x3ff;
            int level = (header >>> 10) & 0x3ff;
            int size = header >>> 20;
            if (size == 0xfff) {
                if (input.remaining() < 4) {
                    throw DocumentReadLimits.invalid();
                }
                size = input.getInt();
            }
            if (size < 0 || size > input.remaining() || tag < 16) {
                throw DocumentReadLimits.invalid();
            }
            if (++count > DocumentReadLimits.MAX_NODES || level > DocumentReadLimits.MAX_DEPTH) {
                throw DocumentReadLimits.tooLarge();
            }
            while (parents.peek().level >= level) {
                parents.pop();
            }
            if (parents.peek().level + 1 != level) {
                throw DocumentReadLimits.invalid();
            }
            Record record = new Record(tag, level, input.slice(input.position(), size).order(ByteOrder.LITTLE_ENDIAN));
            input.position(input.position() + size);
            parents.peek().children.add(record);
            parents.push(record);
        }
        return root.children;
    }

    private void render(List<Record> records, StringBuilder output) {
        for (Record record : records) {
            if (record.tag == PARA_HEADER) {
                // 부유 개체는 화면 좌표 대신 연결된 문단 뒤에 문서 순서로 한 번 배치한다.
                for (Record child : record.children) {
                    if (child.tag == PARA_TEXT) {
                        appendText(child.data.duplicate().order(ByteOrder.LITTLE_ENDIAN), output);
                    }
                }
                output.append('\n');
                for (Record child : record.children) {
                    if (child.tag != PARA_TEXT) {
                        render(List.of(child), output);
                    }
                }
            } else if (record.tag == CTRL_HEADER) {
                String control = controlId(record.data);
                if (OMITTED_CONTROLS.contains(control)) {
                    continue;
                }
                if (control.equals("tbl ")) {
                    renderTable(record.children, output);
                } else {
                    render(record.children, output);
                }
            } else {
                render(record.children, output);
            }
            if (output.length() > DocumentReadLimits.MAX_PART_BYTES) {
                throw DocumentReadLimits.tooLarge();
            }
        }
    }

    private void renderTable(List<Record> children, StringBuilder output) {
        boolean cellsStarted = false;
        int previousRow = -1;
        for (int index = 0; index < children.size(); index++) {
            Record record = children.get(index);
            if (record.tag == TABLE) {
                cellsStarted = true;
            } else if (cellsStarted && record.tag == LIST_HEADER) {
                if (record.data.remaining() < 16) {
                    throw DocumentReadLimits.invalid();
                }
                // 셀 LIST_HEADER는 문단 수 4 + 속성 4바이트 뒤에 열(8), 행(10)이 온다.
                // hwplib 생성 표와 실제 문서로 대조했으며 8을 읽으면 열 번호가 된다.
                int row = Short.toUnsignedInt(record.data.getShort(10));
                if (previousRow != -1) {
                    output.append(previousRow == row ? '\t' : '\n');
                }
                previousRow = row;
                int end = index + 1;
                while (end < children.size() && children.get(end).tag != LIST_HEADER) {
                    end++;
                }
                StringBuilder cell = new StringBuilder();
                render(children.subList(index + 1, end), cell);
                output.append(DocumentReadLimits.withoutFinalNewline(cell.toString()));
                index = end - 1;
            } else if (!cellsStarted) {
                render(List.of(record), output);
            }
        }
        if (previousRow != -1) {
            output.append('\n');
        }
    }

    private void appendText(ByteBuffer data, StringBuilder output) {
        if (data.remaining() % 2 != 0) {
            throw DocumentReadLimits.invalid();
        }
        while (data.hasRemaining()) {
            char code = data.getChar();
            if (code >= 32) {
                output.append(code);
            } else if ((code >= 1 && code <= 9) || (code >= 11 && code <= 12) || (code >= 14 && code <= 23)) {
                if (data.remaining() < 14) {
                    throw DocumentReadLimits.invalid();
                }
                data.position(data.position() + 14);
                if (code == 9) {
                    output.append('\t');
                }
            } else if (code == 10) {
                output.append('\n');
            } else if (code == 24) {
                output.append('-');
            } else if (code == 30 || code == 31) {
                output.append(' ');
            }
        }
    }

    private String controlId(ByteBuffer data) {
        if (data.remaining() < 4) {
            throw DocumentReadLimits.invalid();
        }
        return new String(new byte[]{data.get(3), data.get(2), data.get(1), data.get(0)}, StandardCharsets.US_ASCII);
    }

    private static ByteBuffer littleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private AppException unsupported() {
        return new AppException(UploadErrorCode.UPLOAD_DOCUMENT_VERSION_NOT_SUPPORTED);
    }

    private static final class Record {
        final int tag;
        final int level;
        final ByteBuffer data;
        final List<Record> children = new ArrayList<>();

        Record(int tag, int level, ByteBuffer data) {
            this.tag = tag;
            this.level = level;
            this.data = data;
        }
    }
}
