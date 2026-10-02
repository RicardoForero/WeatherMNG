package model;

/**
 * Estado de uno de los hasta 4 slots de planta que maneja un ESP32.
 * Vive dentro de {@link SensorData}. Es mutable porque su humedad y estado
 * de riego cambian con cada lectura / comando de riego.
 */
public class PlantSlot {

    public static final int MAX_SLOTS = 4;

    private final int slot;       // índice 0..3
    private String plantKey = ""; // clave del catálogo (PlantSpec.key); "" = slot vacío
    private float humidity = 0f;  // humedad de sustrato reportada/simulada (%)
    private long lastWatered = 0; // epoch millis de la última vez regado; 0 = nunca

    public PlantSlot(int slot) {
        this.slot = slot;
    }

    public int getSlot() { return slot; }

    public String getPlantKey() { return plantKey; }
    public void setPlantKey(String plantKey) { this.plantKey = plantKey == null ? "" : plantKey; }

    public boolean isEmpty() { return plantKey == null || plantKey.isBlank(); }

    public float getHumidity() { return humidity; }
    public void setHumidity(float humidity) { this.humidity = humidity; }

    public long getLastWatered() { return lastWatered; }
    public void setLastWatered(long lastWatered) { this.lastWatered = lastWatered; }

    /** Representación JSON usada dentro del array "plants" hacia el Admin. */
    public String toJson() {
        return String.format(java.util.Locale.ROOT, 
            "{\"slot\":%d,\"plantKey\":\"%s\",\"hum\":%.1f,\"lastWatered\":%d}",
            slot, escape(plantKey), humidity, lastWatered);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
        return "PlantSlot{slot=" + slot + ", plantKey='" + plantKey + "', humidity=" + humidity + "}";
    }
}
