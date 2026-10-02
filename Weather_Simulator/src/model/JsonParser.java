package model;

/**
 * Parseo JSON mínimo sin dependencias externas.
 * Usado por el Simulador para interpretar los comandos que el Server le
 * reenvía (rename, assignPlant, water).
 */
public final class JsonParser {

    private JsonParser() {}

    public static float parseFloat(String json, String key) {
        String s = parseStr(json, key);
        return "N/A".equals(s) ? Float.NaN : Float.parseFloat(s);
    }

    public static int parseInt(String json, String key) {
        String s = parseStr(json, key);
        return "N/A".equals(s) ? 0 : (int) Float.parseFloat(s);
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
        while (e < json.length() &&
               (Character.isDigit(json.charAt(e)) || json.charAt(e) == '.' || json.charAt(e) == '-')) e++;
        return json.substring(s, e);
    }
}
