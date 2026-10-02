package model.persistance;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lector ligero de archivos .xlsx (formato Office Open XML).
 *
 * No utiliza Apache POI ni ninguna librería externa: un .xlsx es un .zip que
 * contiene XML estándar, así que basta con {@link java.util.zip.ZipFile} y el
 * parser DOM incluido en el JDK (javax.xml.parsers).
 *
 * Soporta:
 *  - Celdas con texto en línea (t="inlineStr"), como las que genera openpyxl.
 *  - Celdas con texto referenciado en xl/sharedStrings.xml (t="s"), como las
 *    que genera Microsoft Excel o LibreOffice al guardar.
 *  - Celdas numéricas (t="n" o sin atributo "t").
 *
 * Limitaciones (suficientes para un catálogo tabular simple):
 *  - Solo lee la primera hoja del workbook (xl/worksheets/sheet1.xml).
 *  - No interpreta fórmulas (se ignoran, solo se lee el valor cacheado <v> si existe).
 */
public final class XlsxReader {

    private XlsxReader() {}

    /**
     * Lee la primera hoja de un .xlsx y devuelve las filas como listas de Strings.
     * Las celdas vacías intermedias se representan como cadena vacía.
     *
     * @param path ruta al archivo .xlsx
     * @return lista de filas, cada una lista de valores de celda en orden de columna
     */
    public static List<List<String>> readFirstSheet(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<String> sharedStrings = readSharedStrings(zip);
            String sheetEntryName = resolveFirstSheetEntry(zip);
            Document doc = parseXml(zip, sheetEntryName);
            return parseRows(doc, sharedStrings);
        }
    }

    /* ── sharedStrings.xml ────────────────────────────────────── */

    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return Collections.emptyList();

        Document doc = parseXml(zip, entry);
        List<String> result = new ArrayList<>();
        NodeList siNodes = doc.getElementsByTagName("si");
        for (int i = 0; i < siNodes.getLength(); i++) {
            result.add(extractText(siNodes.item(i)));
        }
        return result;
    }

    /** Concatena todos los nodos de texto <t> dentro de un <si> (soporta runs <r>). */
    private static String extractText(Node siNode) {
        StringBuilder sb = new StringBuilder();
        NodeList tNodes = ((Element) siNode).getElementsByTagName("t");
        for (int i = 0; i < tNodes.getLength(); i++) {
            sb.append(tNodes.item(i).getTextContent());
        }
        return sb.toString();
    }

    /* ── Resolución de la hoja a leer ─────────────────────────── */

    private static String resolveFirstSheetEntry(ZipFile zip) {
        // En la gran mayoría de archivos generados por herramientas estándar
        // (Excel, LibreOffice, openpyxl) la primera hoja es xl/worksheets/sheet1.xml.
        if (zip.getEntry("xl/worksheets/sheet1.xml") != null) {
            return "xl/worksheets/sheet1.xml";
        }
        // Fallback: primera entrada que matchee el patrón, por si la numeración difiere.
        Enumeration<? extends ZipEntry> entries = zip.entries();
        String fallback = null;
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
                if (fallback == null || name.compareTo(fallback) < 0) fallback = name;
            }
        }
        if (fallback == null) throw new IllegalStateException("No se encontró ninguna hoja en el .xlsx");
        return fallback;
    }

    /* ── Parseo de filas/celdas ───────────────────────────────── */

    private static List<List<String>> parseRows(Document doc, List<String> sharedStrings) {
        List<List<String>> rows = new ArrayList<>();
        NodeList rowNodes = doc.getElementsByTagName("row");

        for (int r = 0; r < rowNodes.getLength(); r++) {
            Element rowEl = (Element) rowNodes.item(r);
            NodeList cellNodes = rowEl.getElementsByTagName("c");

            // Mapa columna(0-indexed) -> valor, para respetar huecos por celdas vacías omitidas.
            TreeMap<Integer, String> cells = new TreeMap<>();
            int maxCol = -1;

            for (int c = 0; c < cellNodes.getLength(); c++) {
                Element cellEl = (Element) cellNodes.item(c);
                String ref = cellEl.getAttribute("r");   // e.g. "C5"
                int colIdx = ref.isEmpty() ? (c) : columnIndexFromRef(ref);
                String type = cellEl.getAttribute("t");  // "s", "inlineStr", "n", "" ...
                String value = readCellValue(cellEl, type, sharedStrings);
                cells.put(colIdx, value);
                maxCol = Math.max(maxCol, colIdx);
            }

            List<String> row = new ArrayList<>();
            for (int i = 0; i <= maxCol; i++) {
                row.add(cells.getOrDefault(i, ""));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String readCellValue(Element cellEl, String type, List<String> sharedStrings) {
        if ("inlineStr".equals(type)) {
            NodeList isNodes = cellEl.getElementsByTagName("is");
            if (isNodes.getLength() > 0) return extractText(isNodes.item(0));
            return "";
        }
        if ("str".equals(type)) {
            // Resultado cacheado de una fórmula que devuelve texto.
            return firstChildText(cellEl, "v");
        }
        if ("s".equals(type)) {
            String idxStr = firstChildText(cellEl, "v");
            if (idxStr.isEmpty()) return "";
            int idx = Integer.parseInt(idxStr);
            return idx >= 0 && idx < sharedStrings.size() ? sharedStrings.get(idx) : "";
        }
        // Numérico, booleano u otro: devolver el valor crudo de <v>.
        return firstChildText(cellEl, "v");
    }

    private static String firstChildText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    /** Convierte una referencia de celda tipo "C5" al índice de columna 0-indexed (C -> 2). */
    private static int columnIndexFromRef(String ref) {
        int col = 0;
        for (char ch : ref.toCharArray()) {
            if (Character.isLetter(ch)) {
                col = col * 26 + (Character.toUpperCase(ch) - 'A' + 1);
            } else {
                break;
            }
        }
        return col - 1;
    }

    /* ── XML parsing helper ───────────────────────────────────── */

    private static Document parseXml(ZipFile zip, String entryName) throws Exception {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) throw new IllegalStateException("Entrada no encontrada en xlsx: " + entryName);
        return parseXml(zip, entry);
    }

    private static Document parseXml(ZipFile zip, ZipEntry entry) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = zip.getInputStream(entry)) {
            return builder.parse(in);
        }
    }
}
