package model;

/**
 * Estado simulado de uno de los hasta 4 slots de planta de este ESP32.
 *
 * La humedad de sustrato decae gradualmente con el tiempo (simulando
 * evapotranspiración) y sube cuando se recibe un comando de riego desde el
 * Admin (o si el usuario fuerza un valor manualmente desde la UI del
 * simulador). No representa hardware real: es una simulación lógica pura,
 * tal como se acordó (sin relé/actuador físico).
 */
public class PlantSlot {

    public static final int MAX_SLOTS = 4;

    /** Tasa de secado natural, en puntos porcentuales de humedad por segundo. */
    private static final float DRY_RATE_PER_SEC = 0.03f;

    private final int slot;
    private volatile String plantKey = ""; // "" = vacío
    private volatile float  humidity = 0f;
    private volatile float  humMin   = 0f;
    private volatile float  humMax   = 100f;
    private volatile float  humOpt   = 50f;
    private volatile long   lastWatered = 0L;

    public PlantSlot(int slot) {
        this.slot = slot;
    }

    public int getSlot() { return slot; }

    public String getPlantKey() { return plantKey; }
    public boolean isEmpty() { return plantKey == null || plantKey.isBlank(); }

    public float getHumidity() { return humidity; }
    public float getHumMin()   { return humMin; }
    public float getHumMax()   { return humMax; }
    public float getHumOpt()   { return humOpt; }
    public long  getLastWatered() { return lastWatered; }

    /** Asigna (o vacía) la planta de este slot, con sus rangos de humedad recomendados. */
    public synchronized void assign(String plantKey, float humMin, float humMax, float humOpt) {
        this.plantKey = plantKey == null ? "" : plantKey;
        if (this.plantKey.isBlank()) {
            this.humidity = 0f;
            this.humMin = 0f; this.humMax = 100f; this.humOpt = 50f;
        } else {
            this.humMin = humMin;
            this.humMax = humMax;
            this.humOpt = humOpt;
            // Al asignar una planta nueva, arrancamos cerca del óptimo para que
            // la tarjeta no muestre un estado de estrés hídrico desde el inicio.
            this.humidity = humOpt;
        }
    }

    /** Riega: sube la humedad hacia (o un poco por encima de) el óptimo. */
    public synchronized void water() {
        if (isEmpty()) return;
        float target = Math.min(100f, humOpt + (humMax - humOpt) * 0.6f);
        this.humidity = Math.max(humidity, target);
        this.lastWatered = System.currentTimeMillis();
    }

    /** Permite forzar manualmente la humedad desde la UI del simulador (slider). */
    public synchronized void setHumidityManual(float value) {
        this.humidity = SensorMath.clamp(value, 0, 100);
    }

    /** Aplica el secado natural transcurrido un intervalo de {@code deltaMs} milisegundos. */
    public synchronized void applyNaturalDrying(long deltaMs) {
        if (isEmpty() || humidity <= 0f) return;
        float drop = DRY_RATE_PER_SEC * (deltaMs / 1000f);
        humidity = Math.max(0f, humidity - drop);
    }

    /** Fragmento JSON de este slot para incluir en el array "plantas" del reporte. */
    public synchronized String toJson() {
        return String.format(
            "{\"slot\":%d,\"plantKey\":\"%s\",\"humedad\":%.1f,\"regadoEn\":%d}",
            slot, escape(plantKey), humidity, lastWatered);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
