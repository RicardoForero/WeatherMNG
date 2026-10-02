package model;

import java.util.ArrayList;
import java.util.List;

/**
 * Parseo JSON mínimo sin dependencias externas.
 * Responsabilidad única: texto de red → SensorReading.
 */
public class JsonParser {

    private JsonParser() {}

    /** Lectura cruda de un slot de planta, extraída del array "plantas". */
    public record PlantReading(int slot, String plantKey, float humidity) {}

    public static SensorReading parse(String json) {
        float  temp      = parseFloat(json, "temperatura");
        float  hum       = parseFloat(json, "humedad");
        float  heatIndex = parseFloat(json, "indice_calor");
        int    rssi      = parseInt(json,  "rssi");
        String devName   = parseStr(json,  "dispositivo");
        return new SensorReading(temp, hum, heatIndex, rssi, devName);
    }

    /**
     * Extrae el array "plantas":[{...},{...}] de una lectura del simulador/ESP32.
     * Si la clave no existe (firmware antiguo o sin plantas configuradas),
     * devuelve una lista vacía.
     */
    public static List<PlantReading> parsePlants(String json) {
        List<PlantReading> result = new ArrayList<>();
        int arrStart = indexOfArrayStart(json, "plantas");
        if (arrStart < 0) return result;

        int depth = 0, objStart = -1;
        for (int i = arrStart; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '[') { depth++; continue; }
            if (ch == ']') { depth--; if (depth == 0) break; continue; }
            if (ch == '{') { if (depth == 1 && objStart < 0) objStart = i; depth++; }
            else if (ch == '}') {
                depth--;
                if (depth == 1 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    result.add(parsePlantObject(obj));
                    objStart = -1;
                }
            }
        }
        return result;
    }

    private static PlantReading parsePlantObject(String obj) {
        int slot = parseInt(obj, "slot");
        String key = parseStr(obj, "plantKey");
        if ("N/A".equals(key)) key = "";
        float hum = parseFloat(obj, "humedad");
        if (Float.isNaN(hum)) hum = 0f;
        return new PlantReading(slot, key, hum);
    }

    /** Devuelve el índice del '[' que abre el array para la clave dada, o -1 si no existe. */
    private static int indexOfArrayStart(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return -1;
        int c = json.indexOf(':', i);
        if (c < 0) return -1;
        int s = c + 1;
        while (s < json.length() && json.charAt(s) == ' ') s++;
        return (s < json.length() && json.charAt(s) == '[') ? s : -1;
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
