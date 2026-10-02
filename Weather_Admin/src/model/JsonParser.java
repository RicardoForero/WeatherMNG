package model;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser JSON ligero para los mensajes del servidor.
 * No requiere dependencias externas.
 */
public final class JsonParser {

    private JsonParser() {}

    public static float  parseFloat(String json, String key) {
        String s = parseStr(json, key);
        return "N/A".equals(s) ? Float.NaN : Float.parseFloat(s);
    }

    public static int    parseInt(String json, String key) {
        String s = parseStr(json, key);
        return "N/A".equals(s) ? 0 : (int) Float.parseFloat(s);
    }

    public static long   parseLong(String json, String key) {
        String s = parseStr(json, key);
        return "N/A".equals(s) ? 0L : (long) Double.parseDouble(s);
    }

    public static boolean parseBool(String json, String key) {
        return "true".equals(parseStr(json, key));
    }

    public static String parseStr(String json, String key) {
        int i = findTopLevelKey(json, key);
        if (i < 0) return "N/A";
        int c = json.indexOf(':', i);
        if (c < 0) return "N/A";
        int s = c + 1;
        while (s < json.length() && json.charAt(s) == ' ') s++;
        if (s >= json.length()) return "N/A";
        if (json.charAt(s) == '"') {
            int e = json.indexOf('"', s + 1);
            return e < 0 ? "N/A" : json.substring(s + 1, e);
        }
        int e = s;
        while (e < json.length() && (Character.isLetterOrDigit(json.charAt(e))
                || json.charAt(e) == '.' || json.charAt(e) == '-')) e++;
        return json.substring(s, e);
    }

    /**
     * Busca la clave indicada en el JSON, pero solo si está en el primer nivel
     * del objeto (profundidad = 1). Devuelve el índice del '"' que abre la
     * clave, o -1 si no se encuentra.
     */
    private static int findTopLevelKey(String json, String key) {
        int depth = 0; // '{' incrementa; para el objeto raíz queremos depth==1
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') {
                // comprobar si la comilla está escapada
                int j = i - 1;
                boolean escaped = false;
                while (j >= 0 && json.charAt(j) == '\\') { escaped = !escaped; j--; }
                if (!escaped) {
                    int end = json.indexOf('"', i + 1);
                    if (end < 0) return -1;
                    String content = json.substring(i + 1, end);
                    if (content.equals(key) && depth == 1) return i;
                    i = end; // saltar al cierre
                    continue;
                }
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            }
        }
        return -1;
    }

    /**
     * Extrae el array JSON de primer nivel identificado por {@code arrayKey}
     * y devuelve cada objeto {@code {...}} como String.
     */
    public static List<String> parseObjectArray(String json, String arrayKey) {
        List<String> result = new ArrayList<>();
        int keyIdx = findTopLevelKey(json, arrayKey);
        if (keyIdx < 0) return result;
        int start = json.indexOf('[', keyIdx);
        int end   = matchingBracket(json, start);
        if (start < 0 || end < 0 || end <= start) return result;

        String content = json.substring(start + 1, end).trim();
        if (content.isEmpty()) return result;

        int depth = 0, objStart = -1;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '{') { if (depth == 0) objStart = i; depth++; }
            else if (ch == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    result.add(content.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return result;
    }

    /**
     * Dado el índice de un '[' de apertura, devuelve el índice de su ']' de
     * cierre correspondiente, respetando anidamiento. -1 si no hay match.
     */
    private static int matchingBracket(String json, int openIdx) {
        if (openIdx < 0 || openIdx >= json.length() || json.charAt(openIdx) != '[') return -1;
        int depth = 0;
        for (int i = openIdx; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '[') depth++;
            else if (ch == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Extrae un array de floats en línea, p. ej. {@code "histTemp":[1.0,2.5,…]}.
     */
    public static List<Float> parseFloatArray(String json, String arrayKey) {
        List<Float> result = new ArrayList<>();
        int keyIdx = findTopLevelKey(json, arrayKey);
        if (keyIdx < 0) return result;
        int s = json.indexOf('[', keyIdx) + 1;
        int e = json.indexOf(']', s);
        if (e <= s) return result;
        for (String v : json.substring(s, e).split(",")) {
            try { result.add(Float.parseFloat(v.trim())); } catch (Exception ignored) {}
        }
        return result;
    }
}
