package presenter;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import model.log.IServerView;
import model.JsonParser;
import model.PlantSlot;
import model.PlantSpec;
import model.SensorData;
import model.SensorReading;
import model.SensorSerializer;
import model.persistance.FileManager;
import model.persistance.PlantCatalog;

/**
 * Presenter en MVP: orquesta la lógica de aplicación.
 *
 * Responsabilidades:
 *  - Gestionar el ciclo de vida de sensores y admins.
 *  - Aplicar las reglas de negocio (límite de sensores, timeout, etc.).
 *  - Mantener el catálogo de plantas (leído de data/plantas.xlsx) y aplicar
 *    las lecturas/comandos relacionados con los hasta 4 slots de planta
 *    que maneja cada ESP32.
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
    private final PlantCatalog catalog;

    // ── Estado ───────────────────────────────────────────────
    private final ConcurrentHashMap<String, SensorData> sensors      = new ConcurrentHashMap<>();
    private final Set<PrintWriter>                       adminWriters = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger totalMessages = new AtomicInteger(0);
    private final AtomicInteger adminCount    = new AtomicInteger(0);

    public ServerPresenter(IServerView view) {
        this.view = view;
        fm = new FileManager();
        this.catalog = new PlantCatalog();
        for (String warning : catalog.lastWarnings()) {
            view.onError("Catálogo de plantas", warning);
        }
        view.log("Catálogo de plantas cargado: " + catalog.size() + " especie(s) desde " + catalog.getXlsxPath());
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
        fm.addSensorData(sensor,this);
        try {
            SensorReading r = JsonParser.parse(rawJson);
            sensor.pushReading(r.temp(), r.hum());
            sensor.setHeatIndex(r.heatIndex());
            sensor.setRssi(r.rssi());
            sensor.setOnline(true);
            sensor.setName(r.deviceName());
            applyPlantReadings(sensor, rawJson);
            totalMessages.incrementAndGet();
            view.onReadingReceived(sensor);
            broadcastSensorUpdate(sensor);
        } catch (Exception e) {
            view.onError(sensor.ip, "JSON inválido: " + rawJson.substring(0, Math.min(40, rawJson.length())));
        }
    }

    /** Aplica el array "plantas" (si está presente) de una lectura del ESP32/Simulador. */
    private void applyPlantReadings(SensorData sensor, String rawJson) {
        List<JsonParser.PlantReading> readings = JsonParser.parsePlants(rawJson);
        for (JsonParser.PlantReading r : readings) {
            sensor.pushPlantReading(r.slot(), r.plantKey(), r.humidity());
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
        safeSend(writer, catalog.toCatalogJson());
        sendSnapshotTo(writer);
    }

    public void onAdminDisconnected(PrintWriter writer, String ip) {
        adminWriters.remove(writer);
        adminCount.decrementAndGet();
        view.onAdminDisconnected(ip, adminCount.get());
    }

    /**
     * Procesa una línea de comando enviada por un Admin.
     * Formatos soportados:
     *   {"cmd":"rename","id":"ip:port","name":"NuevoNombre"}
     *   {"cmd":"assignPlant","id":"ip:port","slot":0,"plantKey":"Potos"}
     *   {"cmd":"water","id":"ip:port","slot":0}
     *   {"cmd":"reloadCatalog"}
     */
    public void onAdminCommand(String rawJson) {
        try {
            String cmd = JsonParser.parseStr(rawJson, "cmd");
            switch (cmd) {
                case "rename"       -> handleRename(rawJson);
                case "assignPlant"  -> handleAssignPlant(rawJson);
                case "water"        -> handleWater(rawJson);
                case "reloadCatalog"-> handleReloadCatalog();
                default -> view.onError("Comando admin", "Comando desconocido: " + cmd);
            }
        } catch (Exception e) {
            view.onError("Comando admin", "Comando inválido: " + rawJson + " (" + e.getMessage() + ")");
        }
    }

    private void handleRename(String rawJson) {
        SensorData sensor = findSensor(rawJson);
        if (sensor == null) return;
        String newName = JsonParser.parseStr(rawJson, "name");
        if (newName == null || "N/A".equals(newName) || newName.isBlank()) return;

        sensor.setName(newName);
        sensor.sendCommand(String.format(java.util.Locale.ROOT, "{\"cmd\":\"rename\",\"name\":\"%s\"}", escape(newName)));
        view.log("Admin renombró " + sensor.id + " -> " + newName);
        broadcastSensorUpdate(sensor);
    }

    private void handleAssignPlant(String rawJson) {
        SensorData sensor = findSensor(rawJson);
        if (sensor == null) return;
        int slot = JsonParser.parseInt(rawJson, "slot");
        if (slot < 0 || slot >= PlantSlot.MAX_SLOTS) {
            view.onError("Comando admin", "Slot fuera de rango: " + slot);
            return;
        }
        String plantKey = JsonParser.parseStr(rawJson, "plantKey");
        if ("N/A".equals(plantKey)) plantKey = "";

        PlantSpec spec = plantKey.isBlank() ? null : catalog.get(plantKey);
        if (!plantKey.isBlank() && spec == null) {
            view.onError("Comando admin", "Planta no encontrada en catálogo: " + plantKey);
            return;
        }

        PlantSlot ps = sensor.getPlant(slot);
        if (ps == null) return;
        ps.setPlantKey(plantKey);
        if (plantKey.isBlank()) ps.setHumidity(0f);

        String cmdJson = spec == null
            ? String.format(java.util.Locale.ROOT, "{\"cmd\":\"assignPlant\",\"slot\":%d,\"plantKey\":\"\"}", slot)
            : String.format(java.util.Locale.ROOT, "{\"cmd\":\"assignPlant\",\"slot\":%d,\"plantKey\":\"%s\",\"humMin\":%.0f,\"humMax\":%.0f,\"humOpt\":%.0f}",
                slot, escape(spec.key()), spec.humMin(), spec.humMax(), spec.humOptimal());
        sensor.sendCommand(cmdJson);

        view.log("Admin asignó planta '" + plantKey + "' al slot " + slot + " de " + sensor.id);
        broadcastSensorUpdate(sensor);
    }

    private void handleWater(String rawJson) {
        SensorData sensor = findSensor(rawJson);
        if (sensor == null) return;
        int slot = JsonParser.parseInt(rawJson, "slot");
        PlantSlot ps = sensor.getPlant(slot);
        if (ps == null || ps.isEmpty()) {
            view.onError("Comando admin", "No hay planta asignada en el slot " + slot + " de " + sensor.id);
            return;
        }

        ps.setLastWatered(System.currentTimeMillis());
        sensor.sendCommand(String.format(java.util.Locale.ROOT, "{\"cmd\":\"water\",\"slot\":%d}", slot));
        view.log("Admin regó manualmente el slot " + slot + " (" + ps.getPlantKey() + ") de " + sensor.id);
        broadcastSensorUpdate(sensor);
    }

    private void handleReloadCatalog() {
        List<String> warnings = catalog.reload();
        for (String w : warnings) view.onError("Catálogo de plantas", w);
        view.log("Catálogo de plantas recargado: " + catalog.size() + " especie(s)");
        broadcastRaw(catalog.toCatalogJson());
    }

    private SensorData findSensor(String rawJson) {
        String id = JsonParser.parseStr(rawJson, "id");
        SensorData sensor = sensors.get(id);
        if (sensor == null) view.onError("Comando admin", "Sensor no encontrado: " + id);
        return sensor;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    public void onErrorLog(String context, String message){
        view.onError(context, message);
    }

}
