package model;

import java.util.*;
import java.util.function.Consumer;

/**
 * Entidad de dominio pura: representa el estado de un sensor ESP32.
 * Sin dependencias de red ni de presentación.
 */
public class SensorData {

    public final String id;
    public final String ip;
    public final long   connectedAt = System.currentTimeMillis();

    private volatile String  name;
    private volatile float   temp      = Float.NaN;
    private volatile float   hum       = Float.NaN;
    private volatile float   heatIndex = Float.NaN;
    private volatile int     rssi      = 0;
    private volatile int     msgCount  = 0;
    private volatile boolean online    = true;
    private volatile long    lastSeen  = System.currentTimeMillis();

    private final List<Float> histTemp = Collections.synchronizedList(new ArrayList<>());
    private final List<Float> histHum  = Collections.synchronizedList(new ArrayList<>());

    /** Hasta 4 slots de planta que este ESP32 puede manejar (ver {@link PlantSlot#MAX_SLOTS}). */
    private final PlantSlot[] plants = new PlantSlot[PlantSlot.MAX_SLOTS];

    /**
     * Canal para enviar comandos (JSON) de vuelta al ESP32/Simulador físico
     * dueño de esta conexión. Lo registra el {@code SensorHandler} apenas se
     * identifica el socket; puede ser null brevemente durante el arranque.
     */
    private volatile Consumer<String> commandSink;

    private static final int MAX_HISTORY = 80;

    public SensorData(String id, String ip) {
        this.id = id;
        this.ip = ip;
        String tail = ip.replace(".", "");
        this.name = "ESP32-" + tail.substring(Math.max(0, tail.length() - 4));
        for (int i = 0; i < plants.length; i++) plants[i] = new PlantSlot(i);
    }

    /** Registra una nueva lectura de temperatura y humedad. */
    public void pushReading(float temp, float hum) {
        this.temp     = temp;
        this.hum      = hum;
        this.lastSeen = System.currentTimeMillis();
        this.msgCount++;
        appendHistory(histTemp, temp);
        appendHistory(histHum, hum);
    }

    private void appendHistory(List<Float> list, float value) {
        synchronized (list) {
            if (list.size() >= MAX_HISTORY) list.remove(0);
            list.add(value);
        }
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - connectedAt) / 1000;
    }

    public boolean isStale(long nowMs, long timeoutMs) {
        return (nowMs - lastSeen) >= timeoutMs;
    }

    // ── Getters ──────────────────────────────────────────────

    public String  getName()      { return name; }
    public float   getTemp()      { return temp; }
    public float   getHum()       { return hum; }
    public float   getHeatIndex() { return heatIndex; }
    public int     getRssi()      { return rssi; }
    public int     getMsgCount()  { return msgCount; }
    public boolean isOnline()     { return online; }
    public long    getLastSeen()  { return lastSeen; }

    public List<Float> getHistTemp() {
        synchronized (histTemp) { return new ArrayList<>(histTemp); }
    }

    public List<Float> getHistHum() {
        synchronized (histHum) { return new ArrayList<>(histHum); }
    }

    // ── Setters controlados ──────────────────────────────────

    public void setName(String name)           { if (name != null && !name.isBlank()) this.name = name; }
    public void setHeatIndex(float heatIndex)  { this.heatIndex = heatIndex; }
    public void setRssi(int rssi)              { this.rssi = rssi; }
    public void setOnline(boolean online)      { this.online = online; }

    // ── Plantas (hasta 4 slots por ESP32) ────────────────────

    /** @return los 4 slots de planta (algunos pueden estar vacíos). Nunca null. */
    public PlantSlot[] getPlants() { return plants; }

    public PlantSlot getPlant(int slot) {
        if (slot < 0 || slot >= plants.length) return null;
        return plants[slot];
    }

    /** Aplica una lectura de humedad reportada por el ESP32 para un slot dado. */
    public void pushPlantReading(int slot, String plantKey, float humidity) {
        PlantSlot p = getPlant(slot);
        if (p == null) return;
        if (plantKey != null) p.setPlantKey(plantKey);
        p.setHumidity(humidity);
    }

    // ── Canal de comandos hacia el ESP32 físico/simulado ─────

    public void setCommandSink(Consumer<String> sink) { this.commandSink = sink; }

    /** Envía un comando JSON al ESP32 dueño de esta conexión. No-op si está desconectado. */
    public void sendCommand(String json) {
        Consumer<String> sink = this.commandSink;
        if (sink != null) sink.accept(json);
    }
}
