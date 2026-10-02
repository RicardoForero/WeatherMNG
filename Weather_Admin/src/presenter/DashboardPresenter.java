package presenter;

import model.DashboardModel;
import model.PlantCatalog;
import model.PlantSlot;
import model.PlantSpec;
import model.SensorData;
import view.DashboardView;
import model.AppConfig;
import model.JsonParser;

import javax.swing.SwingUtilities;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presenter del patrón MVP.
 *
 * Responsabilidades:
 *  • Gestionar la conexión TCP al servidor.
 *  • Parsear los mensajes JSON entrantes (snapshot, update, remove, catalog).
 *  • Actualizar el Model.
 *  • Enviar comandos al servidor (rename, assignPlant, water, reloadCatalog).
 *  • Ordenar a la View que refleje los cambios.
 */
public class DashboardPresenter {

    private final DashboardModel model;
    private final DashboardView  view;
    private final PlantCatalog   catalog = new PlantCatalog();

    private Socket           socket;
    private PrintWriter      netWriter;
    private BufferedReader   netReader;
    private volatile boolean connected = false;

    private final ExecutorService netPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "net-reader");
        t.setDaemon(true);
        return t;
    });

    public DashboardPresenter(DashboardModel model, DashboardView view) {
        this.model = model;
        this.view  = view;
    }

    /* ── API pública ──────────────────────────────────────── */

    public boolean isConnected() { return connected; }

    public PlantCatalog getCatalog() { return catalog; }

    /** Llamado por la Vista cuando el usuario pulsa "CONECTAR / DESCONECTAR". */
    public void onToggleConnection(String host, String portText) {
        if (connected) {
            disconnect();
        } else {
            int port;
            try { port = Integer.parseInt(portText.trim()); }
            catch (NumberFormatException e) { port = AppConfig.DEFAULT_PORT; }
            connect(host.trim(), port);
        }
    }

    /* ── Comandos Admin → Server ─────────────────────────── */

    /** Renombra un ESP32 desde el Admin. */
    public void sendRename(String sensorId, String newName) {
        if (newName == null || newName.isBlank()) return;
        String escaped = newName.replace("\\", "\\\\").replace("\"", "\\\"");
        sendRaw(String.format("{\"cmd\":\"rename\",\"id\":\"%s\",\"name\":\"%s\"}",
            sensorId, escaped));
    }

    /** Asigna (o vacía, si plantKey="") una planta a un slot del ESP32. */
    public void sendAssignPlant(String sensorId, int slot, String plantKey) {
        String key = (plantKey == null) ? "" : plantKey;
        String escaped = key.replace("\\", "\\\\").replace("\"", "\\\"");
        sendRaw(String.format("{\"cmd\":\"assignPlant\",\"id\":\"%s\",\"slot\":%d,\"plantKey\":\"%s\"}",
            sensorId, slot, escaped));
    }

    /** Solicita riego manual de un slot. */
    public void sendWater(String sensorId, int slot) {
        sendRaw(String.format("{\"cmd\":\"water\",\"id\":\"%s\",\"slot\":%d}", sensorId, slot));
    }

    /** Solicita al Server que recargue el catálogo de plantas desde disco. */
    public void sendReloadCatalog() {
        sendRaw("{\"cmd\":\"reloadCatalog\"}");
    }

    private synchronized void sendRaw(String json) {
        if (netWriter != null && connected) {
            try { netWriter.println(json); }
            catch (Exception ignored) {}
        }
    }

    /* ── Conexión ─────────────────────────────────────────── */

    private void connect(String host, int port) {
        final int finalPort = port;
        netPool.submit(() -> {
            try {
                socket    = new Socket(host, finalPort);
                netWriter = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
                netReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                netWriter.println(AppConfig.ADMIN_HELLO);   // handshake admin

                connected = true;
                SwingUtilities.invokeLater(() -> view.showConnected(host, finalPort));

                String line;
                while ((line = netReader.readLine()) != null) {
                    final String msg = line.trim();
                    if (!msg.isEmpty()) {
                        SwingUtilities.invokeLater(() -> handleMessage(msg));
                    }
                }
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> view.showConnectionError(ex.getMessage()));
            } finally {
                connected = false;
                netWriter = null;
                SwingUtilities.invokeLater(() -> view.showDisconnected());
            }
        });
    }

    private void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        model.clear();
        view.clearAllCards();
        view.showDisconnected();
        view.logEvent("— Desconectado del servidor");
    }

    /* ── Protocolo de mensajes entrantes ─────────────────── */

    private void handleMessage(String json) {
        String type = JsonParser.parseStr(json, "type");
        switch (type) {
            case "snapshot" -> handleSnapshot(json);
            case "update"   -> handleUpdate(json);
            case "remove"   -> handleRemove(JsonParser.parseStr(json, "id"));
            case "catalog"  -> handleCatalog(json);
        }
    }

    private void handleCatalog(String json) {
        List<String> plantObjs = JsonParser.parseObjectArray(json, "plants");
        List<PlantSpec> specs  = new ArrayList<>();
        for (String obj : plantObjs) {
            String key  = JsonParser.parseStr(obj, "key");
            String sci  = JsonParser.parseStr(obj, "sci");
            String alt  = JsonParser.parseStr(obj, "alt");
            float  min  = JsonParser.parseFloat(obj, "humMin");
            float  max  = JsonParser.parseFloat(obj, "humMax");
            float  opt  = JsonParser.parseFloat(obj, "humOpt");
            if (!"N/A".equals(key) && !key.isBlank()) {
                specs.add(new PlantSpec(key,
                    "N/A".equals(sci) ? "" : sci,
                    "N/A".equals(alt) ? "" : alt,
                    Float.isNaN(min) ? 0f  : min,
                    Float.isNaN(max) ? 100f: max,
                    Float.isNaN(opt) ? 50f : opt));
            }
        }
        catalog.replaceAll(specs);
        view.onCatalogUpdated(catalog);
        view.logEvent("▶ Catálogo recibido: " + catalog.size() + " planta(s)");
    }

    private void handleSnapshot(String json) {
        List<String> sensorJsons = JsonParser.parseObjectArray(json, "sensors");
        for (String sObj : sensorJsons) parseSensor(sObj);

        int tot = JsonParser.parseInt(json, "totalMessages");
        if (tot > 0) model.setTotalMessages(tot);

        view.logEvent("★ Snapshot recibido · " + sensorJsons.size() + " sensores");
        view.setStatus("Conectado · " + model.sensorCount() + " sensores activos");
        view.updateStats(model.onlineCount(), model.getTotalMessages());
    }

    private void handleUpdate(String json) {
        // El sensor JSON comienza en {"id":... pero está dentro de "sensor":{...}
        int si = json.indexOf("{\"id\":");
        if (si < 0) return;
        // Encontrar el cierre correcto del objeto sensor (sin lastIndexOf, contamos llaves)
        String sensorJson = extractTopObject(json, si);
        SensorData sd = parseSensor(sensorJson);
        if (sd != null) {
            model.incrementMessages();
            view.setStatus(String.format("Actualización: %s  T=%.1f°C  H=%.0f%%",
                sd.name, sd.temp, sd.hum));
            view.updateStats(model.onlineCount(), model.getTotalMessages());
        }
    }

    /** Extrae el objeto JSON completo (respeta anidamiento de llaves) desde startIdx. */
    private String extractTopObject(String json, int startIdx) {
        int depth = 0;
        for (int i = startIdx; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return json.substring(startIdx, i + 1);
            }
        }
        return json.substring(startIdx); // sin cierre = devolver el resto
    }

    private void handleRemove(String id) {
        model.remove(id);
        view.removeSensorCard(id);
        view.logEvent("✗ Sensor eliminado: " + id.split(":")[0]);
        view.updateStats(model.onlineCount(), model.getTotalMessages());
    }

    /* ── Parseo de un objeto sensor ───────────────────────── */

    private SensorData parseSensor(String json) {
        try {
            String id   = JsonParser.parseStr(json, "id");
            String ip   = JsonParser.parseStr(json, "ip");
            String name = JsonParser.parseStr(json, "name");
            if ("N/A".equals(id)) return null;

            SensorData sd = model.getOrCreate(id, ip, name);
            sd.name      = name;
            sd.temp      = JsonParser.parseFloat(json, "temp");
            sd.hum       = JsonParser.parseFloat(json, "hum");
            sd.heatIndex = JsonParser.parseFloat(json, "heatIndex");
            sd.rssi      = JsonParser.parseInt(json, "rssi");
            sd.msgCount  = JsonParser.parseInt(json, "msgCount");
            sd.online    = JsonParser.parseBool(json, "online");
            sd.uptime    = JsonParser.parseLong(json, "uptime");

            List<Float> hTemp = JsonParser.parseFloatArray(json, "histTemp");
            if (!hTemp.isEmpty()) {
                synchronized (sd.histTemp) { sd.histTemp.clear(); sd.histTemp.addAll(hTemp); }
            }
            List<Float> hHum = JsonParser.parseFloatArray(json, "histHum");
            if (!hHum.isEmpty()) {
                synchronized (sd.histHum) { sd.histHum.clear(); sd.histHum.addAll(hHum); }
            }

            // Parsear slots de planta
            List<String> plantObjs = JsonParser.parseObjectArray(json, "plants");
            for (String pObj : plantObjs) {
                int slot = JsonParser.parseInt(pObj, "slot");
                if (slot >= 0 && slot < PlantSlot.MAX_SLOTS) {
                    PlantSlot ps = sd.plants[slot];
                    String key = JsonParser.parseStr(pObj, "plantKey");
                    ps.plantKey    = "N/A".equals(key) ? "" : key;
                    ps.humidity    = JsonParser.parseFloat(pObj, "hum");
                    ps.lastWatered = JsonParser.parseLong(pObj, "lastWatered");
                    if (Float.isNaN(ps.humidity)) ps.humidity = 0f;
                }
            }

            // Notificar a la vista (idempotente: addSensorCard ignora duplicados)
            view.addSensorCard(sd);
            return sd;
        } catch (Exception e) {
            return null;
        }
    }
}
