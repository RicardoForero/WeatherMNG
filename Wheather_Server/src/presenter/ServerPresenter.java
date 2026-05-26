package presenter;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import model.JsonParser;
import model.SensorData;
import model.SensorReading;
import model.SensorSerializer;
import model.persistance.FileManager;
import view.IServerView;

/**
 * Presenter en MVP: orquesta la lógica de aplicación.
 *
 * Responsabilidades:
 *  - Gestionar el ciclo de vida de sensores y admins.
 *  - Aplicar las reglas de negocio (límite de sensores, timeout, etc.).
 *  - Delegar feedback a la Vista y broadcasting a los admins.
 *  - NO conoce detalles de red (ServerSocket vive en la Infrastructure).
 */
public class ServerPresenter {

    // ── Configuración ────────────────────────────────────────
    public static final int    PORT           = 2361;
    public static final int    MAX_SENSORS    = 20;
    public static final String ADMIN_HELLO    = "ADMIN_v1";
    public static final long   SENSOR_TIMEOUT = 12_000L;

    // ── Colaboradores ────────────────────────────────────────
    private final IServerView view;
    private FileManager fm;
    // ── Estado ───────────────────────────────────────────────
    private final ConcurrentHashMap<String, SensorData> sensors      = new ConcurrentHashMap<>();
    private final Set<PrintWriter>                       adminWriters = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger totalMessages = new AtomicInteger(0);
    private final AtomicInteger adminCount    = new AtomicInteger(0);

    public ServerPresenter(IServerView view) {
        this.view = view;
        fm = new FileManager();
    }

    // ── Ciclo de vida del servidor ───────────────────────────

    public void onServerStarted(int port) {
        view.onServerStarted(port);
    }

    public void onServerError(String message) {
        view.onError("Error fatal", message);
    }

    // ── Heartbeat ────────────────────────────────────────────

    /**
     * Llamado periódicamente por la Infrastructure para detectar sensores caídos.
     */
    public void checkStaleSensors() {
        long now = System.currentTimeMillis();
        for (SensorData s : sensors.values()) {
            boolean wasOnline = s.isOnline();
            boolean stale     = s.isStale(now, SENSOR_TIMEOUT);
            if (stale) s.setOnline(false);
            if (wasOnline && stale) {
                view.onSensorStale(s);
                broadcastSensorUpdate(s);
            }
        }
    }

    // ── Gestión de sensores ──────────────────────────────────

    /**
     * @return SensorData registrado, o null si se rechazó (límite alcanzado).
     */
    public SensorData onSensorConnected(String ip, int port) {
        if (sensors.size() >= MAX_SENSORS) {
            view.onError("Límite sensores", "Rechazando " + ip + " (máx " + MAX_SENSORS + ")");
            return null;
        }
        String id     = ip + ":" + port;
        SensorData sd = new SensorData(id, ip);
        sensors.put(id, sd);
        view.onSensorConnected(sd, sensors.size());
        return sd;
    }

    public void onSensorReading(SensorData sensor, String rawJson) {
        System.out.println(fm.buildString(sensor).toString());
        fm.addSensorData(sensor);
        try {
            SensorReading r = JsonParser.parse(rawJson);
            sensor.pushReading(r.temp(), r.hum());
            sensor.setHeatIndex(r.heatIndex());
            sensor.setRssi(r.rssi());
            sensor.setOnline(true);
            sensor.setName(r.deviceName());
            totalMessages.incrementAndGet();
            view.onReadingReceived(sensor);
            broadcastSensorUpdate(sensor);
        } catch (Exception e) {
            view.onError(sensor.ip, "JSON inválido: " + rawJson.substring(0, Math.min(40, rawJson.length())));
        }
    }

    public void onSensorDisconnected(SensorData sensor) {
        sensor.setOnline(false);
        sensors.remove(sensor.id);
        view.onSensorDisconnected(sensor, sensors.size());
        broadcastRaw("{\"type\":\"remove\",\"id\":\"" + sensor.id + "\"}");
    }

    // ── Gestión de admins ─────────────────────────────────────

    public void onAdminConnected(PrintWriter writer, String ip) {
        adminWriters.add(writer);
        adminCount.incrementAndGet();
        view.onAdminConnected(ip, adminCount.get());
        sendSnapshotTo(writer);
    }

    public void onAdminDisconnected(PrintWriter writer, String ip) {
        adminWriters.remove(writer);
        adminCount.decrementAndGet();
        view.onAdminDisconnected(ip, adminCount.get());
    }

    // ── Broadcasting ─────────────────────────────────────────

    private void broadcastSensorUpdate(SensorData s) {
        broadcastRaw("{\"type\":\"update\",\"sensor\":" + SensorSerializer.toJson(s) + "}");
    }

    private void broadcastRaw(String msg) {
        for (PrintWriter w : adminWriters) safeSend(w, msg);
    }

    private void sendSnapshotTo(PrintWriter writer) {
        StringBuilder sb = new StringBuilder("{\"type\":\"snapshot\",\"sensors\":[");
        boolean first = true;
        for (SensorData s : sensors.values()) {
            if (!first) sb.append(",");
            sb.append(SensorSerializer.toJson(s));
            first = false;
        }
        sb.append("],\"totalMessages\":").append(totalMessages.get()).append("}");
        safeSend(writer, sb.toString());
    }

    private void safeSend(PrintWriter w, String msg) {
        try { w.println(msg); } catch (Exception ignored) {}
    }

    // ── Protocolo: identificación ─────────────────────────────

    public boolean isAdminHandshake(String firstLine) {
        return ADMIN_HELLO.equals(firstLine);
    }
}
