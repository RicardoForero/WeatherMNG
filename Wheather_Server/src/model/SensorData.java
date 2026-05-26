package model;

import java.util.*;

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

    private static final int MAX_HISTORY = 80;

    public SensorData(String id, String ip) {
        this.id = id;
        this.ip = ip;
        String tail = ip.replace(".", "");
        this.name = "ESP32-" + tail.substring(Math.max(0, tail.length() - 4));
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
}
