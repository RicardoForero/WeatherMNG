package model;

/**
 * Serializa SensorData a JSON para el protocolo de red.
 * Separado del modelo para respetar Single Responsibility.
 */
public class SensorSerializer {

    private SensorSerializer() {}

    public static String toJson(SensorData s) {
        return String.format(
            "{\"id\":\"%s\",\"ip\":\"%s\",\"name\":\"%s\"," +
            "\"temp\":%.1f,\"hum\":%.1f,\"heatIndex\":%.1f," +
            "\"rssi\":%d,\"msgCount\":%d,\"online\":%b,\"uptime\":%d," +
            "\"histTemp\":%s,\"histHum\":%s}",
            s.id, s.ip, s.getName(),
            orZero(s.getTemp()), orZero(s.getHum()), orZero(s.getHeatIndex()),
            s.getRssi(), s.getMsgCount(), s.isOnline(), s.uptimeSeconds(),
            floatListJson(s.getHistTemp()),
            floatListJson(s.getHistHum()));
    }

    private static float orZero(float v) { return Float.isNaN(v) ? 0f : v; }

    private static String floatListJson(java.util.List<Float> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        return sb.append("]").toString();
    }
}
