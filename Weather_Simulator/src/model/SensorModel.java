package model;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MODEL — Estado del sensor y comunicación TCP.
 * No conoce ningún componente Swing.
 *
 * Desde la versión multi-planta, este ESP32 simulado también maneja hasta
 * {@link PlantSlot#MAX_SLOTS} slots de planta y es full-duplex: además de
 * enviar lecturas periódicas, escucha comandos del Server (reenviados desde
 * el Admin) para renombrarse, asignar una planta a un slot, o regar.
 */
public class SensorModel {

    public static final String DEFAULT_HOST     = "127.0.0.1";
    public static final int    DEFAULT_PORT     = 2361;
    public static final int    SEND_INTERVAL_MS = 2000;
    /** Cada cuánto se aplica el secado natural de las plantas (independiente del envío). */
    private static final int  DRY_TICK_MS = 1000;

    private volatile float   temperature = 25.0f;
    private volatile float   humidity    = 60.0f;
    private volatile boolean connected   = false;
    private volatile int     messagesSent = 0;
    private volatile String  deviceName  = "ESP32-SIM-01";

    private final PlantSlot[] plants = new PlantSlot[PlantSlot.MAX_SLOTS];

    private Socket                    socket;
    private PrintWriter               writer;
    private BufferedReader            reader;
    private String                    host = DEFAULT_HOST;
    private int                       port = DEFAULT_PORT;
    private ScheduledExecutorService  scheduler;
    private Thread                    commandListenerThread;

    // ── Callbacks hacia el Presenter ────────────────────────
    private Consumer<String>  onLogMessage;
    private Consumer<Integer> onMessageSent;
    private Runnable          onDisconnected;
    private Runnable          onPlantsChanged;
    private BiConsumer<String, String> onRenamedByServer; // (oldName, newName)

    public SensorModel() {
        for (int i = 0; i < plants.length; i++) plants[i] = new PlantSlot(i);
    }

    // ── Getters ─────────────────────────────────────────────
    public float   getTemperature()  { return temperature;  }
    public float   getHumidity()     { return humidity;     }
    public boolean isConnected()     { return connected;    }
    public int     getMessagesSent() { return messagesSent; }
    public String  getHost()         { return host;         }
    public int     getPort()         { return port;         }
    public String  getDeviceName()   { return deviceName;   }
    public PlantSlot[] getPlants()   { return plants;        }

    // ── Setters ─────────────────────────────────────────────
    public void setTemperature(float t) { temperature = SensorMath.clamp(t, -10, 50); }
    public void setHumidity(float h)    { humidity    = SensorMath.clamp(h,  0, 100); }

    /** Permite forzar manualmente (desde la UI) la humedad de un slot. */
    public void setPlantHumidityManual(int slot, float value) {
        if (slot < 0 || slot >= plants.length) return;
        plants[slot].setHumidityManual(value);
        if (onPlantsChanged != null) onPlantsChanged.run();
    }

    // ── Callbacks ────────────────────────────────────────────
    public void setOnLogMessage(Consumer<String> cb)   { onLogMessage  = cb; }
    public void setOnMessageSent(Consumer<Integer> cb) { onMessageSent = cb; }
    public void setOnDisconnected(Runnable cb)         { onDisconnected = cb; }
    public void setOnPlantsChanged(Runnable cb)        { onPlantsChanged = cb; }
    public void setOnRenamedByServer(BiConsumer<String, String> cb) { onRenamedByServer = cb; }

    // ── Conexión TCP ─────────────────────────────────────────
    public void connect(String newHost, int newPort, String deviceName) throws IOException {
        this.host = newHost;
        this.port = newPort;
        this.deviceName = deviceName;
        socket = new Socket(host, port);
        writer = new PrintWriter(
            new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        connected    = true;
        messagesSent = 0;

        scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleAtFixedRate(
            () -> sendData(this.deviceName), 0, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(
            this::applyNaturalDrying, DRY_TICK_MS, DRY_TICK_MS, TimeUnit.MILLISECONDS);

        commandListenerThread = new Thread(this::listenForCommands, "server-cmd-listener");
        commandListenerThread.setDaemon(true);
        commandListenerThread.start();
    }

    public void disconnect() {
        connected = false;
        if (scheduler != null) scheduler.shutdownNow();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        writer = null;
        if (onDisconnected != null) onDisconnected.run();
    }

    // ── Envío de datos ───────────────────────────────────────
    private void sendData(String deviceName) {
        if (!connected || writer == null) return;
        try {
            float  hi   = SensorMath.computeHeatIndex(temperature, humidity);
            int    rssi = -50 - (int)(Math.random() * 30);
            String ip   = socket.getLocalAddress().getHostAddress();
            messagesSent++;

            String json = String.format(
                "{\"id\":%d,\"dispositivo\":\"%s\",\"timestamp\":%d," +
                "\"sensores\":{\"temperatura\":%.1f,\"humedad\":%.1f,\"indice_calor\":%.1f}," +
                "\"red\":{\"rssi\":%d,\"ip\":\"%s\"}," +
                "\"plantas\":%s}",
                messagesSent, deviceName, System.currentTimeMillis(),
                temperature, humidity, hi, rssi, ip,
                plantsJson());

            writer.println(json);
            if (writer.checkError()) throw new IOException("Broken pipe");

            if (onMessageSent != null) onMessageSent.accept(messagesSent);
            if (onLogMessage  != null)
                onLogMessage.accept(String.format(
                    "→ #%d  T=%.1f°  H=%.0f%%  HI=%.1f°",
                    messagesSent, temperature, humidity, hi));

        } catch (IOException ex) {
            if (onLogMessage  != null) onLogMessage.accept("✗ Fallo: " + ex.getMessage());
            disconnect();
        }
    }

    private String plantsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < plants.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(plants[i].toJson());
        }
        return sb.append("]").toString();
    }

    // ── Secado natural de plantas ─────────────────────────────
    private long lastDryTick = System.currentTimeMillis();

    private void applyNaturalDrying() {
        long now = System.currentTimeMillis();
        long delta = now - lastDryTick;
        lastDryTick = now;
        for (PlantSlot p : plants) p.applyNaturalDrying(delta);
        if (onPlantsChanged != null) onPlantsChanged.run();
    }

    // ── Hilo lector de comandos del Server ────────────────────
    private void listenForCommands() {
        try {
            String line;
            while (connected && reader != null && (line = reader.readLine()) != null) {
                final String cmdLine = line.trim();
                if (!cmdLine.isEmpty()) handleCommand(cmdLine);
            }
        } catch (IOException ignored) {
            // El socket se cerró (desconexión normal o error ya manejado por sendData).
        }
    }

    private void handleCommand(String json) {
        String cmd = JsonParser.parseStr(json, "cmd");
        switch (cmd) {
            case "rename" -> {
                String newName = JsonParser.parseStr(json, "name");
                if (!"N/A".equals(newName) && !newName.isBlank()) {
                    String old = this.deviceName;
                    this.deviceName = newName;
                    if (onRenamedByServer != null) onRenamedByServer.accept(old, newName);
                    if (onLogMessage != null) onLogMessage.accept("⟲ Renombrado por Admin: " + newName);
                }
            }
            case "assignPlant" -> {
                int slot = JsonParser.parseInt(json, "slot");
                if (slot < 0 || slot >= plants.length) return;
                String plantKey = JsonParser.parseStr(json, "plantKey");
                if ("N/A".equals(plantKey)) plantKey = "";
                float min = JsonParser.parseFloat(json, "humMin");
                float max = JsonParser.parseFloat(json, "humMax");
                float opt = JsonParser.parseFloat(json, "humOpt");
                plants[slot].assign(plantKey,
                    Float.isNaN(min) ? 0f   : min,
                    Float.isNaN(max) ? 100f : max,
                    Float.isNaN(opt) ? 50f  : opt);
                if (onPlantsChanged != null) onPlantsChanged.run();
                if (onLogMessage != null) {
                    onLogMessage.accept(plantKey.isBlank()
                        ? "⟲ Slot " + slot + " vaciado por Admin"
                        : "⟲ Slot " + slot + " ← " + plantKey + " (Admin)");
                }
            }
            case "water" -> {
                int slot = JsonParser.parseInt(json, "slot");
                if (slot < 0 || slot >= plants.length) return;
                plants[slot].water();
                if (onPlantsChanged != null) onPlantsChanged.run();
                if (onLogMessage != null) onLogMessage.accept("💧 Riego remoto en slot " + slot);
            }
            default -> { /* comando desconocido: ignorar */ }
        }
    }
}
