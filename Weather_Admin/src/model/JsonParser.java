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
        int i = json.indexOf("\"" + key + "\"");
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
     * Extrae el array JSON de primer nivel identificado por {@code arrayKey}
     * y devuelve cada objeto {@code {...}} como String.
     */
    public static List<String> parseObjectArray(String json, String arrayKey) {
        List<String> result = new ArrayList<>();
        int arrStart = json.indexOf("\"" + arrayKey + "\":[");
        if (arrStart < 0) return result;
        int start = json.indexOf('[', arrStart);
        int end   = json.lastIndexOf(']');
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
     * Extrae un array de floats en línea, p. ej. {@code "histTemp":[1.0,2.5,…]}.
     */
    public static List<Float> parseFloatArray(String json, String arrayKey) {
        List<Float> result = new ArrayList<>();
        int idx = json.indexOf("\"" + arrayKey + "\":[");
        if (idx < 0) return result;
        int s = json.indexOf('[', idx) + 1;
        int e = json.indexOf(']', s);
        if (e <= s) return result;
        for (String v : json.substring(s, e).split(",")) {
            try { result.add(Float.parseFloat(v.trim())); } catch (Exception ignored) {}
        }
        return result;
    }
}
