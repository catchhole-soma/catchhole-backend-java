package org.monitoring.catchholebackend.domain.upload.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.monitoring.catchholebackend.domain.upload.exception.UploadErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;

final class HwpxTextReader {
    private static final Set<String> PARAGRAPH_NAMESPACES = Set.of(
            "http://www.hancom.co.kr/hwpml/2011/paragraph",
            "http://www.owpml.org/owpml/2021/paragraph",
            "http://www.owpml.org/owpml/2024/paragraph"
    );
    private static final Set<String> SECTION_NAMESPACES = Set.of(
            "http://www.hancom.co.kr/hwpml/2011/section",
            "http://www.owpml.org/owpml/2021/section",
            "http://www.owpml.org/owpml/2024/section"
    );
    private static final String OPF_NS = "http://www.idpf.org/2007/opf/";
    private static final Set<String> OMITTED = Set.of("secPr", "header", "footer", "footNote", "endNote", "hiddenComment");

    String read(byte[] bytes) throws IOException, XMLStreamException {
        Map<String, byte[]> parts = readArchive(bytes);
        if (!"application/hwp+zip".equals(new String(required(parts, "mimetype"), StandardCharsets.US_ASCII).trim())) {
            throw DocumentReadLimits.invalid();
        }
        if (parts.containsKey("META-INF/manifest.xml")) {
            Node manifest = xml(parts.get("META-INF/manifest.xml"));
            if (hasElement(manifest, "encryption-data")) {
                throw new AppException(UploadErrorCode.UPLOAD_DOCUMENT_PASSWORD_PROTECTED);
            }
        }
        Node packageXml = xml(required(parts, "Contents/content.hpf"));
        if (!packageXml.is(OPF_NS, "package")) {
            throw DocumentReadLimits.invalid();
        }
        Map<String, String> manifest = new HashMap<>();
        for (Node item : descendants(packageXml, OPF_NS, "item")) {
            String href = item.attributes.get("href");
            if (href != null && href.matches("(?:Contents/)?section\\d+\\.xml")) {
                String path = href.startsWith("Contents/") ? href : "Contents/" + href;
                if (manifest.put(item.attributes.get("id"), path) != null) {
                    throw DocumentReadLimits.invalid();
                }
            }
        }
        StringBuilder output = new StringBuilder();
        Set<String> readSections = new HashSet<>();
        for (Node reference : descendants(packageXml, OPF_NS, "itemref")) {
            String path = manifest.get(reference.attributes.get("idref"));
            if (path == null) {
                continue; // header/settings도 spine에 등장한다.
            }
            if (!readSections.add(path)) {
                throw DocumentReadLimits.invalid();
            }
            Node section = xml(required(parts, path));
            if (!section.is(SECTION_NAMESPACES, "sec")) {
                throw DocumentReadLimits.invalid();
            }
            render(section, output);
        }
        long storedSectionCount = parts.keySet().stream().filter(name -> name.matches("Contents/section\\d+\\.xml")).count();
        if (readSections.isEmpty() || readSections.size() != storedSectionCount || readSections.size() != manifest.size()) {
            throw DocumentReadLimits.invalid();
        }
        return DocumentReadLimits.withoutFinalNewline(output.toString());
    }

    private Map<String, byte[]> readArchive(byte[] bytes) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        DocumentReadLimits limits = new DocumentReadLimits();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (names.size() >= DocumentReadLimits.MAX_ENTRIES) {
                    throw DocumentReadLimits.tooLarge();
                }
                if (!names.add(entry.getName())) {
                    throw DocumentReadLimits.invalid();
                }
                byte[] content = limits.read(input);
                String name = entry.getName();
                if (name.equals("mimetype") || name.equals("Contents/content.hpf")
                        || name.equals("META-INF/manifest.xml") || name.matches("Contents/section\\d+\\.xml")) {
                    parts.put(name, content);
                }
            }
        } catch (ZipException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("encrypted")) {
                throw new AppException(UploadErrorCode.UPLOAD_DOCUMENT_PASSWORD_PROTECTED, exception);
            }
            throw new AppException(UploadErrorCode.UPLOAD_DOCUMENT_INVALID, exception);
        }
        return parts;
    }

    private byte[] required(Map<String, byte[]> parts, String path) {
        byte[] content = parts.get(path);
        if (content == null) {
            throw DocumentReadLimits.invalid();
        }
        return content;
    }

    private Node xml(byte[] bytes) throws XMLStreamException {
        XMLStreamReader reader = DocumentReadLimits.xmlFactory().createXMLStreamReader(new ByteArrayInputStream(bytes));
        try {
            List<Node> stack = new ArrayList<>();
            Node root = null;
            int count = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw DocumentReadLimits.invalid();
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++count > DocumentReadLimits.MAX_NODES || stack.size() >= DocumentReadLimits.MAX_DEPTH) {
                        throw DocumentReadLimits.tooLarge();
                    }
                    Node node = new Node(reader.getNamespaceURI(), reader.getLocalName(), null);
                    for (String attribute : List.of("id", "href", "idref")) {
                        String value = reader.getAttributeValue(null, attribute);
                        if (value != null) {
                            node.attributes.put(attribute, value);
                        }
                    }
                    if (stack.isEmpty()) {
                        if (root != null) {
                            throw DocumentReadLimits.invalid();
                        }
                        root = node;
                    } else {
                        stack.getLast().children.add(node);
                    }
                    stack.add(node);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    stack.removeLast();
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && !stack.isEmpty()) {
                    if (++count > DocumentReadLimits.MAX_NODES) {
                        throw DocumentReadLimits.tooLarge();
                    }
                    stack.getLast().children.add(new Node("", "#text", reader.getText()));
                }
            }
            if (root == null || !stack.isEmpty()) {
                throw DocumentReadLimits.invalid();
            }
            return root;
        } finally {
            reader.close();
        }
    }

    private void render(Node node, StringBuilder output) {
        if (omitted(node)) {
            return;
        }
        if (node.is(PARAGRAPH_NAMESPACES, "p")) {
            List<Node> blocks = new ArrayList<>();
            inline(node, output, blocks, false);
            output.append('\n');
            for (Node block : blocks) {
                render(block, output);
            }
        } else if (node.is(PARAGRAPH_NAMESPACES, "tbl")) {
            for (Node child : node.children) {
                if (child.is(PARAGRAPH_NAMESPACES, "caption")) {
                    render(child, output);
                }
            }
            for (Node row : node.children) {
                if (!row.is(PARAGRAPH_NAMESPACES, "tr")) {
                    continue;
                }
                boolean first = true;
                for (Node cell : row.children) {
                    if (!cell.is(PARAGRAPH_NAMESPACES, "tc")) {
                        continue;
                    }
                    if (!first) {
                        output.append('\t');
                    }
                    first = false;
                    StringBuilder content = new StringBuilder();
                    render(cell, content);
                    output.append(DocumentReadLimits.withoutFinalNewline(content.toString()));
                }
                output.append('\n');
            }
        } else {
            for (Node child : node.children) {
                render(child, output);
            }
        }
        if (output.length() > DocumentReadLimits.MAX_PART_BYTES) {
            throw DocumentReadLimits.tooLarge();
        }
    }

    private void inline(Node node, StringBuilder output, List<Node> blocks, boolean inText) {
        if (omitted(node)) {
            return;
        }
        if (node.is(PARAGRAPH_NAMESPACES, "tbl") || node.is(PARAGRAPH_NAMESPACES, "subList")) {
            blocks.add(node);
            return;
        }
        if (inText && node.text != null) {
            output.append(node.text);
        } else if (node.is(PARAGRAPH_NAMESPACES, "lineBreak")) {
            output.append('\n');
        } else if (node.is(PARAGRAPH_NAMESPACES, "tab")) {
            output.append('\t');
        } else if (node.is(PARAGRAPH_NAMESPACES, "hyphen")) {
            output.append('-');
        } else if (node.is(PARAGRAPH_NAMESPACES, "nbSpace") || node.is(PARAGRAPH_NAMESPACES, "fwSpace")) {
            output.append(' ');
        } else {
            for (Node child : node.children) {
                inline(child, output, blocks, inText || node.is(PARAGRAPH_NAMESPACES, "t"));
            }
        }
    }

    private boolean omitted(Node node) {
        return node.namespace != null && PARAGRAPH_NAMESPACES.contains(node.namespace) && OMITTED.contains(node.name);
    }

    private boolean hasElement(Node node, String name) {
        return name.equals(node.name) || node.children.stream().anyMatch(child -> hasElement(child, name));
    }

    private List<Node> descendants(Node node, String namespace, String name) {
        List<Node> found = new ArrayList<>();
        if (node.is(namespace, name)) {
            found.add(node);
        }
        for (Node child : node.children) {
            found.addAll(descendants(child, namespace, name));
        }
        return found;
    }

    private static final class Node {
        final String namespace;
        final String name;
        final String text;
        final Map<String, String> attributes = new HashMap<>();
        final List<Node> children = new ArrayList<>();

        Node(String namespace, String name, String text) {
            this.namespace = namespace;
            this.name = name;
            this.text = text;
        }

        boolean is(String namespace, String name) {
            return namespace.equals(this.namespace) && name.equals(this.name);
        }

        boolean is(Set<String> namespaces, String name) {
            return namespace != null && namespaces.contains(namespace) && name.equals(this.name);
        }
    }
}
