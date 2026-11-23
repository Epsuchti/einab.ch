package ch.it4user.foodsharing.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Minimal reader for .xlsx workbooks (Office Open XML): returns every sheet as rows of cell texts, using the cached
 * value of formula cells. Numbers come back as written in the file (dates are serial day numbers). Only the parts the
 * import needs are read, the size of every part is capped and the XML parser resolves no external entities.
 */
final class PastEinAbXlsxReader {

    /** A sheet as rows of cells; {@code rowNumbers} holds the 1-based row number in the workbook of each row. */
    record Sheet(String name, List<List<String>> rows, List<Integer> rowNumbers) {
    }

    private static final long MAX_PART_BYTES = 60L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    private static final int MAX_COLUMNS = 200;
    private static final int MAX_ROWS = 50_000;

    private PastEinAbXlsxReader() {
    }

    static List<Sheet> read(byte[] xlsx) throws IOException {
        Map<String, byte[]> parts = unzip(xlsx);
        byte[] workbook = parts.get("xl/workbook.xml");
        if (workbook == null) {
            throw new IOException("not an xlsx workbook");
        }
        try {
            List<String> sharedStrings = parts.containsKey("xl/sharedStrings.xml")
                    ? readSharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            Map<String, String> relations = parts.containsKey("xl/_rels/workbook.xml.rels")
                    ? readRelations(parts.get("xl/_rels/workbook.xml.rels")) : Map.of();
            List<Sheet> sheets = new ArrayList<>();
            for (Map.Entry<String, String> sheet : readSheetList(workbook).entrySet()) {
                String target = relations.get(sheet.getValue());
                if (target == null) {
                    continue;
                }
                String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                byte[] data = parts.get(path);
                if (data != null) {
                    sheets.add(readSheet(sheet.getKey(), data, sharedStrings));
                }
            }
            return sheets;
        } catch (XMLStreamException ex) {
            throw new IOException("unreadable xlsx workbook", ex);
        }
    }

    private static Map<String, byte[]> unzip(byte[] xlsx) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                boolean needed = name.equals("xl/workbook.xml") || name.equals("xl/sharedStrings.xml")
                        || name.equals("xl/_rels/workbook.xml.rels")
                        || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"));
                if (entry.isDirectory() || !needed) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                long size = 0;
                while ((read = zip.read(buffer)) > 0) {
                    size += read;
                    total += read;
                    if (size > MAX_PART_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new IOException("xlsx part too large");
                    }
                    out.write(buffer, 0, read);
                }
                parts.put(name, out.toByteArray());
            }
        }
        return parts;
    }

    private static XMLStreamReader parser(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        InputStream input = new ByteArrayInputStream(xml);
        return factory.createXMLStreamReader(input);
    }

    /** Sheet name to relation id, in workbook order. */
    private static Map<String, String> readSheetList(byte[] xml) throws XMLStreamException {
        Map<String, String> sheets = new LinkedHashMap<>();
        XMLStreamReader reader = parser(xml);
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("sheet")) {
                String name = null;
                String relationId = null;
                for (int i = 0; i < reader.getAttributeCount(); i++) {
                    if (reader.getAttributeLocalName(i).equals("name")) {
                        name = reader.getAttributeValue(i);
                    } else if (reader.getAttributeLocalName(i).equals("id")) {
                        relationId = reader.getAttributeValue(i);
                    }
                }
                if (name != null && relationId != null) {
                    sheets.put(name, relationId);
                }
            }
        }
        return sheets;
    }

    private static Map<String, String> readRelations(byte[] xml) throws XMLStreamException {
        Map<String, String> relations = new HashMap<>();
        XMLStreamReader reader = parser(xml);
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("Relationship")) {
                relations.put(reader.getAttributeValue(null, "Id"), reader.getAttributeValue(null, "Target"));
            }
        }
        return relations;
    }

    /** Each {@code <si>} is one string; rich text runs ({@code <r><t>}) are joined, phonetic hints are skipped. */
    private static List<String> readSharedStrings(byte[] xml) throws XMLStreamException {
        List<String> strings = new ArrayList<>();
        XMLStreamReader reader = parser(xml);
        StringBuilder current = null;
        boolean inText = false;
        int phonetic = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "si" -> current = new StringBuilder();
                    case "rPh" -> phonetic++;
                    case "t" -> inText = phonetic == 0;
                    default -> { }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "si" -> {
                        strings.add(current == null ? "" : current.toString());
                        current = null;
                    }
                    case "rPh" -> phonetic--;
                    case "t" -> inText = false;
                    default -> { }
                }
            } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && inText
                    && current != null) {
                current.append(reader.getText());
            }
        }
        return strings;
    }

    private static Sheet readSheet(String name, byte[] xml, List<String> sharedStrings) throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        List<Integer> rowNumbers = new ArrayList<>();
        XMLStreamReader reader = parser(xml);
        List<String> row = null;
        int rowNumber = 0;
        int column = -1;
        String type = null;
        StringBuilder value = null;
        boolean inValue = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "row" -> {
                        String r = reader.getAttributeValue(null, "r");
                        rowNumber = r == null ? rowNumber + 1 : Integer.parseInt(r);
                        row = new ArrayList<>();
                        column = -1;
                    }
                    case "c" -> {
                        String reference = reader.getAttributeValue(null, "r");
                        column = reference == null ? column + 1 : columnIndex(reference);
                        type = reader.getAttributeValue(null, "t");
                        value = new StringBuilder();
                    }
                    case "v", "t" -> inValue = value != null;
                    default -> { }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "v", "t" -> inValue = false;
                    case "c" -> {
                        if (row != null && value != null && column >= 0 && column < MAX_COLUMNS) {
                            String text = cellText(type, value.toString(), sharedStrings);
                            while (row.size() <= column) {
                                row.add("");
                            }
                            row.set(column, text);
                        }
                        value = null;
                    }
                    case "row" -> {
                        if (row != null && row.stream().anyMatch(cell -> !cell.isBlank())) {
                            if (rows.size() >= MAX_ROWS) {
                                throw new XMLStreamException("too many rows");
                            }
                            rows.add(row);
                            rowNumbers.add(rowNumber);
                        }
                        row = null;
                    }
                    default -> { }
                }
            } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && inValue) {
                value.append(reader.getText());
            }
        }
        return new Sheet(name, rows, rowNumbers);
    }

    private static String cellText(String type, String raw, List<String> sharedStrings) {
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw.trim());
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
            } catch (NumberFormatException ex) {
                return "";
            }
        }
        if ("b".equals(type)) {
            return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        }
        if ("e".equals(type)) {
            return "";
        }
        return raw;
    }

    /** "AB12" to 27. */
    static int columnIndex(String reference) {
        int index = 0;
        for (char c : reference.toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                index = index * 26 + (c - 'A' + 1);
            } else {
                break;
            }
        }
        return index - 1;
    }
}
