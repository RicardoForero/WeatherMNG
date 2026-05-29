package presenter;

import model.DashboardModel;
import model.SensorData;
import view.DashboardView;
import model.AppConfig;
import model.JsonParser;

import javax.swing.SwingUtilities;
import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presenter del patrón MVP.
 *
 * Responsabilidades:
 *  • Gestionar la conexión TCP al servidor.
 *  • Parsear los mensajes JSON entrantes.
 *  • Actualizar el Model.
 *  • Ordenar a la View que refleje los cambios.
 *
 * La Vista nunca accede directamente al Modelo.
 */
public class DashboardPresenter {

    private final DashboardModel model;
    private final DashboardView  view;

    private Socket           socket;
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

    /* ── Conexión ─────────────────────────────────────────── */

    private void connect(String host, int port) {
        final int finalPort = port;
        netPool.submit(() -> {
            try {
                socket = new Socket(host, finalPort);
                PrintWriter writer = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
                netReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                writer.println(AppConfig.ADMIN_HELLO);   // handshake admin

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

    /* ── Protocolo de mensajes ────────────────────────────── */

    private void handleMessage(String json) {
        String type = JsonParser.parseStr(json, "type");
        switch (type) {
            case "snapshot": handleSnapshot(json); break;
            case "update":   handleUpdate(json);   break;
            case "remove":   handleRemove(JsonParser.parseStr(json, "id")); break;
        }
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
        int si = json.indexOf("{\"id\":");
        if (si < 0) return;
        String sensorJson = json.substring(si, json.lastIndexOf('}') + 1);
        SensorData sd = parseSensor(sensorJson);
        if (sd != null) {
            model.incrementMessages();
            view.setStatus(String.format("Actualización: %s  T=%.1f°C  H=%.0f%%",
                sd.name, sd.temp, sd.hum));
            view.updateStats(model.onlineCount(), model.getTotalMessages());
        }
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

            // Notificar a la vista
            if (!model.contains(id) || view == null) {
                // primera vez: la tarjeta aún no existe; addCard se encarga
            }
            boolean isNew = !hasCard(id);
            if (isNew) {
                view.addSensorCard(sd);
            } else {
                view.renameSensorCard(id, name);
            }
            return sd;
        } catch (Exception e) {
            return null;
        }
    }

    // Delega en la Vista la comprobación de si la tarjeta ya existe.
    // El Presenter no guarda estado de UI; se apoya en la Vista vía el contrato.
    private boolean hasCard(String id) {
        // La vista gestiona internamente el mapa de tarjetas.
        // Consultamos el modelo: si el sensor ya existía antes de parseSensor
        // la vista ya habrá creado su tarjeta.
        // Esta lógica se delega completamente a la Vista en addSensorCard (idempotente).
        return false; // siempre delegamos a la Vista (ella ignora duplicados)
    }
}
