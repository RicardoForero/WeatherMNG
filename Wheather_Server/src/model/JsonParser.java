package model;

/**
 * Parseo JSON mínimo sin dependencias externas.
 * Responsabilidad única: texto de red → SensorReading.
 */
public class JsonParser {

    private JsonParser() {}

    public static SensorReading parse(String json) {
        float  temp      = parseFloat(json, "temperatura");
        float  hum       = parseFloat(json, "humedad");
        float  heatIndex = parseFloat(json, "indice_calor");
        int    rssi      = parseInt(json,  "rssi");
        String devName   = parseStr(json,  "dispositivo");
        return new SensorReading(temp, hum, heatIndex, rssi, devName);
    }

    public static float parseFloat(String j, String k) {
        String s = parseStr(j, k);
        return "N/A".equals(s) ? Float.NaN : Float.parseFloat(s);
    }

    public static int parseInt(String j, String k) {
        String s = parseStr(j, k);
        return "N/A".equals(s) ? 0 : (int) Float.parseFloat(s);
    }

    public static String parseStr(String json, String key) {
        int i = json.indexOf("\"" + key + "\""); if (i < 0) return "N/A";
        int c = json.indexOf(':', i);            if (c < 0) return "N/A";
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
