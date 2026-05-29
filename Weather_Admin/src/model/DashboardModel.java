package model;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado central del dashboard.
 * Accesible sólo a través del Presenter; la Vista no lo toca directamente.
 */
public class DashboardModel {

    private final ConcurrentHashMap<String, SensorData> sensors = new ConcurrentHashMap<>();
    private volatile int totalMessages = 0;

    /* ── Sensores ─────────────────────────────────────── */

    public SensorData getOrCreate(String id, String ip, String name) {
        return sensors.computeIfAbsent(id, k -> new SensorData(id, ip, name));
    }

    public SensorData get(String id) {
        return sensors.get(id);
    }

    public boolean contains(String id) {
        return sensors.containsKey(id);
    }

    public void remove(String id) {
        sensors.remove(id);
    }

    public void clear() {
        sensors.clear();
        totalMessages = 0;
    }

    public Collection<SensorData> allSensors() {
        return sensors.values();
    }

    public long onlineCount() {
        return sensors.values().stream().filter(s -> s.online).count();
    }

    public int sensorCount() {
        return sensors.size();
    }

    /* ── Mensajes ─────────────────────────────────────── */

    public int getTotalMessages() { return totalMessages; }

    public void setTotalMessages(int n) { totalMessages = n; }

    public void incrementMessages() { totalMessages++; }
}
