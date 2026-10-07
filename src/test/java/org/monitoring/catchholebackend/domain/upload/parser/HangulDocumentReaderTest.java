package org.monitoring.catchholebackend.domain.upload.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.episode.parser.EpisodeFileParser;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("한글 문서 본문 추출 및 회차 연결")
class HangulDocumentReaderTest {
    private final TextDocumentReader reader = new TextDocumentReader();
    private final EpisodeFileParser parser = new EpisodeFileParser(reader);
    private static final String LEGACY_HWPML_NS = "http://www.hancom.co.kr/hwpml/2011";
    private static final String OPF = "http://www.idpf.org/2007/opf/";

    @ParameterizedTest
    @ValueSource(strings = {"hwp", "hwpx"})
    @DisplayName("별도 라이브러리로 만든 원고의 한글 본문을 읽고 단일 회차를 감지한다")
    void readsIndependentlyWrittenManuscripts(String extension) throws IOException {
        var file = fixture("episode-1." + extension);
        assertThat(reader.readText(file)).isEqualTo("제 1화 새벽의 편지\n서윤은 성문 앞에서 편지를 읽었다.");
        var detected = parser.parseEpisodeFiles(EpisodeUploadType.SINGLE_EPISODE, null, null, List.of(file));
        assertThat(detected.getFirst().detectedEpisodes().getFirst().episodeNo()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"two-sections.hwp", "two-episodes.hwpx"})
    @DisplayName("한 파일의 두 회차를 경계에 맞게 분리하며 원본 파일 연결을 유지한다")
    void detectsTwoEpisodes(String filename) throws IOException {
        var file = fixture(filename);
        var detected = parser.parseEpisodeFiles(EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE, null, null, List.of(file));
        assertThat(detected).hasSize(1);
        assertThat(detected.getFirst().sourceFile()).isSameAs(file);
        assertThat(detected.getFirst().detectedEpisodes()).extracting(episode -> episode.episodeNo()).containsExactly(1, 2);
        assertThat(detected.getFirst().detectedEpisodes()).extracting(episode -> episode.content())
                .containsExactly("서윤은 성문 앞에서 편지를 읽었다.", "도윤은 오래된 약속을 떠올렸다.");
    }

    @Test
    @DisplayName("HWPX와 HWP를 역순 선택해도 감지 순서와 각 원본 대응을 보존한다")
    void preservesMixedFileIdentity() throws IOException {
        var second = fixture("episode-2.hwpx");
        var first = fixture("episode-1.hwp");
        var detected = parser.parseEpisodeFiles(EpisodeUploadType.MULTI_EPISODE_MULTI_FILE, null, null, List.of(second, first));
        assertThat(detected).extracting(file -> file.sourceFile()).containsExactly(second, first);
        assertThat(detected).extracting(file -> file.detectedEpisodes().getFirst().episodeNo()).containsExactly(2, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("압축 여부와 무관하게 HWP의 문단·탭·표 셀·글상자를 구분하고 머리말·각주는 제외한다")
    void readsHwpStructure(boolean compressed) throws IOException {
        byte[] section = join(paragraph(0, "  첫 문단\n둘째 줄  "),
                record(71, 1, control("head")), paragraph(2, "제 99화 머리말"),
                record(71, 1, control("fn  ")), paragraph(2, "제 98화 각주"),
                record(71, 1, control("gso ")), record(76, 2, new byte[4]),
                record(72, 3, new byte[8]), paragraph(3, "글상자 내용"),
                paragraph(0, "표 앞"), record(71, 1, control("tbl ")), record(77, 2, new byte[8]),
                record(72, 2, cell(0, 0)), paragraph(2, "이름"),
                record(72, 2, cell(1, 0)), paragraph(2, "관계"),
                record(72, 2, cell(0, 1)), paragraph(2, "서윤"),
                record(72, 2, cell(1, 1)), paragraph(2, "자매"), paragraph(0, "그림 뒤"));
        var file = multipart("structure.hwp", hwp(compressed ? 1 : 0, section));
        assertThat(reader.readTextPreservingWhitespace(file))
                .isEqualTo("  첫 문단\n둘째 줄  \n글상자 내용\n표 앞\n이름\t관계\n서윤\t자매\n그림 뒤");
        byte[] tab = join(new byte[]{9, 0}, new byte[14]);
        assertThat(reader.readText("tab.hwp", hwp(0, join(record(66, 0, new byte[24]),
                record(67, 1, join("한".getBytes(StandardCharsets.UTF_16LE), tab, "글\r".getBytes(StandardCharsets.UTF_16LE)))))))
                .isEqualTo("한\t글");
    }

    @ParameterizedTest
    @ValueSource(strings = {"table-rows.hwp", "table-rows-uncompressed.hwp"})
    @DisplayName("별도 라이브러리로 쓴 HWP 표의 네 행과 병합 셀을 탭·줄바꿈으로 보존한다")
    void preservesIndependentlyWrittenTableRows(String filename) throws IOException {
        assertThat(reader.readText(fixture(filename)))
                .isEqualTo("관계표\n이름\t관계\t소속\n서윤\t자매\t북쪽\n도윤\t아군\n합류");
    }

    @Test
    @DisplayName("HWP 본문 뒤의 확장 바탕쪽·메모 문단을 분석 본문에 섞지 않는다")
    void excludesTrailingMasterPagesAndMemos() throws IOException {
        for (int tag : List.of(72, 93)) {
            byte[] section = join(paragraph(0, "제 1화 시작\n본문"), record(tag, 0, new byte[34]),
                    paragraph(0, "제 99화 부가 내용"));
            assertThat(reader.readText("auxiliary.hwp", hwp(1, section))).isEqualTo("제 1화 시작\n본문");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://www.hancom.co.kr/hwpml/2011", "http://www.owpml.org/owpml/2021", "http://www.owpml.org/owpml/2024"})
    @DisplayName("HWPX의 지원 규격마다 문단·글상자·표·탭을 보존하고 부가 텍스트는 제외한다")
    void readsHwpxStructure(String namespaceBase) throws IOException {
        String body = """
                <hp:p><hp:run><hp:t>  앞<hp:tab/>뒤<hp:lineBreak/>다음  </hp:t>
                <hp:header><hp:subList><hp:p><hp:run><hp:t>제 99화 머리말</hp:t></hp:run></hp:p></hp:subList></hp:header>
                <hp:footNote><hp:subList><hp:p><hp:run><hp:t>각주</hp:t></hp:run></hp:p></hp:subList></hp:footNote>
                <hp:footer><hp:subList><hp:p><hp:run><hp:t>꼬리말</hp:t></hp:run></hp:p></hp:subList></hp:footer>
                <hp:endNote><hp:subList><hp:p><hp:run><hp:t>미주</hp:t></hp:run></hp:p></hp:subList></hp:endNote>
                <hp:hiddenComment><hp:subList><hp:p><hp:run><hp:t>숨은 설명</hp:t></hp:run></hp:p></hp:subList></hp:hiddenComment>
                <hp:secPr><hp:subList><hp:p><hp:run><hp:t>구역 속성</hp:t></hp:run></hp:p></hp:subList></hp:secPr>
                <hp:rect><hp:drawText><hp:subList><hp:p><hp:run><hp:t>글상자</hp:t></hp:run></hp:p></hp:subList></hp:drawText></hp:rect>
                <hp:pic/><hp:t> 끝</hp:t></hp:run></hp:p>
                <hp:p><hp:run><hp:tbl><hp:tr><hp:tc><hp:subList><hp:p><hp:run><hp:t>이름</hp:t></hp:run></hp:p></hp:subList></hp:tc>
                <hp:tc><hp:subList><hp:p><hp:run><hp:t>서윤</hp:t></hp:run></hp:p></hp:subList></hp:tc></hp:tr></hp:tbl></hp:run></hp:p>
                """;
        var parts = parts(body);
        parts.put("Contents/section0.xml", section(body, namespaceBase));
        assertThat(reader.readTextPreservingWhitespace(multipart("structure.hwpx", zip(parts))))
                .isEqualTo("  앞\t뒤\n다음   끝\n글상자\n\n이름\t서윤");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://www.hancom.co.kr/hwpml/2011", "http://www.owpml.org/owpml/2021", "http://www.owpml.org/owpml/2024"})
    @DisplayName("HWPX의 명시적 하이픈을 보존하고 다른 네임스페이스의 같은 이름은 해석하지 않는다")
    void preservesHwpxInlineHyphens(String namespaceBase) throws IOException {
        String body = p("well<hp:hyphen/>known<hp:tab/>장<hp:hyphen/>미<hp:lineBreak/>원래-표기")
                + p("앞<other:hyphen xmlns:other='urn:other'/>뒤");
        var parts = parts(body);
        parts.put("Contents/section0.xml", section(body, namespaceBase));

        assertThat(reader.readText("hyphens.hwpx", zip(parts))).isEqualTo("well-known\t장-미\n원래-표기\n앞뒤");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://www.owpml.org/owpml/2099", "http://example.com/owpml/2024", "http://www.owpml.org/owpml/2024/extra"})
    @DisplayName("지원하지 않는 구역 네임스페이스는 이름이 같아도 거부한다")
    void rejectsUnknownHwpxSectionNamespace(String namespaceBase) throws IOException {
        var parts = parts(p("본문"));
        parts.put("Contents/section0.xml", section(p("본문"), namespaceBase));

        rejects("unknown.hwpx", zip(parts), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("네임스페이스가 없는 구역은 파서 내부 오류 대신 손상 문서로 거부한다")
    void rejectsHwpxSectionWithoutNamespace() throws IOException {
        var parts = parts(p("본문"));
        parts.put("Contents/section0.xml", "<sec><p><run><t>본문</t></run></p></sec>".getBytes(StandardCharsets.UTF_8));

        rejects("unqualified.hwpx", zip(parts), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("HWPX는 ZIP 엔트리 순서가 아닌 패키지 spine의 구역 순서를 따른다")
    void usesManifestOrder() throws IOException {
        Map<String, byte[]> parts = parts(p("두 번째"));
        parts.put("Contents/section1.xml", section(p("첫 번째")));
        parts.put("Contents/content.hpf", ("<package xmlns='" + OPF + "'><manifest>"
                + "<item id='a' href='Contents/section0.xml'/><item id='b' href='Contents/section1.xml'/>"
                + "</manifest><spine><itemref idref='b'/><itemref idref='a'/></spine></package>").getBytes(StandardCharsets.UTF_8));
        assertThat(reader.readText("sections.hwpx", zip(parts))).isEqualTo("첫 번째\n두 번째");
    }

    @Test
    @DisplayName("암호·배포용·구형 HWP를 손상 파일과 구분한다")
    void distinguishesUnsupportedDocuments() throws IOException {
        rejects("password.hwp", hwp(2, paragraph(0, "본문")), UploadErrorCode.UPLOAD_DOCUMENT_PASSWORD_PROTECTED);
        rejects("distribution.hwp", hwp(4, paragraph(0, "본문")), UploadErrorCode.UPLOAD_DOCUMENT_VERSION_NOT_SUPPORTED);
        rejects("legacy.hwp", "HWP Document File V3.00".getBytes(StandardCharsets.US_ASCII), UploadErrorCode.UPLOAD_DOCUMENT_VERSION_NOT_SUPPORTED);
        var parts = parts(p("본문"));
        parts.put("META-INF/manifest.xml", "<manifest><encryption-data/></manifest>".getBytes(StandardCharsets.UTF_8));
        rejects("encrypted.hwpx", zip(parts), UploadErrorCode.UPLOAD_DOCUMENT_PASSWORD_PROTECTED);
    }

    @Test
    @DisplayName("확장자 위장·잘린 레코드·잘못된 XML·본문 없는 문서를 거부한다")
    void rejectsMalformedAndEmptyDocuments() throws IOException {
        rejects("fake.hwp", "일반 텍스트".getBytes(StandardCharsets.UTF_8), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
        rejects("fake.hwpx", fixture("episode-1.hwp").getBytes(), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
        rejects("fake.docx", fixture("episode-1.hwpx").getBytes(), UploadErrorCode.UPLOAD_FILE_PARSE_FAILED);
        rejects("fake.txt", fixture("episode-1.hwp").getBytes(), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
        rejects("broken.hwp", hwp(0, new byte[]{66, 0, 0, 1}), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
        rejects("broken.hwpx", hwpx("<hp:p>"), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
        rejects("empty.hwp", hwp(0, paragraph(0, "  ")), UploadErrorCode.UPLOAD_FILE_EMPTY);
        rejects("empty.hwpx", hwpx(p("  ")), UploadErrorCode.UPLOAD_FILE_EMPTY);
        var missing = parts(p("본문"));
        missing.remove("Contents/section0.xml");
        rejects("missing.hwpx", zip(missing), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
    }

    @Test
    @DisplayName("DTD·외부 엔터티·과도한 XML 깊이·HWP 레코드 깊이를 거부한다")
    void boundsParserStructure() throws IOException {
        var parts = parts(p("본문"));
        parts.put("Contents/section0.xml", ("<!DOCTYPE sec [<!ENTITY x SYSTEM 'file:///must-not-read'>]>"
                + new String(section(p("&x;")), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        rejects("entity.hwpx", zip(parts), UploadErrorCode.UPLOAD_DOCUMENT_INVALID);
        rejects("deep.hwpx", hwpx("<hp:p>".repeat(65) + "</hp:p>".repeat(65)), UploadErrorCode.UPLOAD_DOCUMENT_LIMIT_EXCEEDED);
        rejects("deep.hwp", hwp(0, record(66, 65, new byte[24])), UploadErrorCode.UPLOAD_DOCUMENT_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("HWP·HWPX의 실제 압축 해제량 및 ZIP 엔트리 수를 제한한다")
    void boundsDecompressionAndEntries() throws IOException {
        byte[] large = new byte[10 * 1024 * 1024 + 1];
        rejects("bomb.hwp", hwp(1, large), UploadErrorCode.UPLOAD_DOCUMENT_LIMIT_EXCEEDED);
        var parts = parts(p("본문"));
        parts.put("BinData/bomb.bin", large);
        rejects("bomb.hwpx", zip(parts), UploadErrorCode.UPLOAD_DOCUMENT_LIMIT_EXCEEDED);
        parts = parts(p("본문"));
        parts.put("BinData/a", new byte[10 * 1024 * 1024]);
        parts.put("BinData/b", new byte[10 * 1024 * 1024]);
        rejects("total.hwpx", zip(parts), UploadErrorCode.UPLOAD_DOCUMENT_LIMIT_EXCEEDED);
        parts = parts(p("본문"));
        for (int i = 0; i < 256; i++) parts.put("extra/" + i, new byte[0]);
        rejects("entries.hwpx", zip(parts), UploadErrorCode.UPLOAD_DOCUMENT_LIMIT_EXCEEDED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hwp", "hwpx"})
    @DisplayName("한글 파일도 공백과 보조 평면 문자를 포함해 합계 250000자로 제한한다")
    void enforcesManuscriptCharacterLimit(String extension) throws IOException {
        String allowed = "😀" + " ".repeat(249_998) + "끝";
        for (String text : List.of(allowed, allowed + "!")) {
            byte[] bytes = extension.equals("hwp") ? hwp(1, paragraph(0, text)) : hwpx(p(text));
            var file = multipart("1화." + extension, bytes);
            if (text.equals(allowed)) {
                assertThat(parser.parseEpisodeFiles(EpisodeUploadType.SINGLE_EPISODE, 1, "제목", List.of(file)))
                        .hasSize(1);
            } else {
                assertThatThrownBy(() -> parser.parseEpisodeFiles(EpisodeUploadType.SINGLE_EPISODE, 1, "제목", List.of(file)))
                        .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getResultCode())
                                .isEqualTo(UploadErrorCode.UPLOAD_CHARACTER_LIMIT_EXCEEDED));
            }
        }
    }

    private void rejects(String name, byte[] bytes, UploadErrorCode code) {
        assertThatThrownBy(() -> reader.readText(name, bytes)).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getResultCode()).isEqualTo(code));
    }

    private MockMultipartFile fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/upload/" + name)) {
            return multipart(name, input.readAllBytes());
        }
    }

    private MockMultipartFile multipart(String name, byte[] bytes) {
        return new MockMultipartFile("episodeFiles", name, "application/octet-stream", bytes);
    }

    private static byte[] paragraph(int level, String text) throws IOException {
        return join(record(66, level, new byte[24]), record(67, level + 1, (text + "\r").getBytes(StandardCharsets.UTF_16LE)));
    }

    private static byte[] control(String id) {
        return new byte[]{(byte) id.charAt(3), (byte) id.charAt(2), (byte) id.charAt(1), (byte) id.charAt(0)};
    }

    private static byte[] cell(int col, int row) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(0)
                .putShort((short) col).putShort((short) row).putShort((short) 1).putShort((short) 1).array();
    }

    private static byte[] record(int tag, int level, byte[] body) throws IOException {
        boolean extended = body.length >= 4095;
        var header = ByteBuffer.allocate(extended ? 8 : 4).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(tag | (level << 10) | ((extended ? 4095 : body.length) << 20));
        if (extended) header.putInt(body.length);
        return join(header.array(), body);
    }

    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part);
        return output.toByteArray();
    }

    private static byte[] hwp(int flags, byte[] section) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] header = new byte[256];
            System.arraycopy("HWP Document File".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 17);
            header[35] = 5;
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(36, flags);
            fs.getRoot().createDocument("FileHeader", new ByteArrayInputStream(header));
            byte[] info = record(16, 0, new byte[]{1, 0});
            fs.getRoot().createDocument("DocInfo", new ByteArrayInputStream((flags & 1) == 0 ? info : deflate(info)));
            fs.getRoot().createDirectory("BodyText").createDocument("Section0", new ByteArrayInputStream((flags & 1) == 0 ? section : deflate(section)));
            fs.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static byte[] deflate(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(output, deflater)) {
            stream.write(bytes);
        } finally {
            deflater.end();
        }
        return output.toByteArray();
    }

    private static String p(String text) { return "<hp:p><hp:run><hp:t>" + text + "</hp:t></hp:run></hp:p>"; }
    private static byte[] section(String body) {
        return section(body, LEGACY_HWPML_NS);
    }
    private static byte[] section(String body, String namespaceBase) {
        return ("<hs:sec xmlns:hs='" + namespaceBase + "/section' xmlns:hp='" + namespaceBase + "/paragraph'>" + body + "</hs:sec>")
                .getBytes(StandardCharsets.UTF_8);
    }
    private static Map<String, byte[]> parts(String body) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("mimetype", "application/hwp+zip".getBytes(StandardCharsets.US_ASCII));
        parts.put("Contents/content.hpf", ("<package xmlns='" + OPF + "'><manifest><item id='s0' href='Contents/section0.xml'/>"
                + "</manifest><spine><itemref idref='s0'/></spine></package>").getBytes(StandardCharsets.UTF_8));
        parts.put("Contents/section0.xml", section(body));
        return parts;
    }
    private static byte[] hwpx(String body) throws IOException { return zip(parts(body)); }
    private static byte[] zip(Map<String, byte[]> parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
