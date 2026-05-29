package model;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * MODEL — Estado del sensor y comunicación TCP.
 * No conoce ningún componente Swing.
 */
public class SensorModel {

    public static final String DEFAULT_HOST     = "127.0.0.1";
    public static final int    DEFAULT_PORT     = 2361;
    public static final int    SEND_INTERVAL_MS = 2000;

    private volatile float   temperature = 25.0f;
    private volatile float   humidity    = 60.0f;
    private volatile boolean connected   = false;
    private volatile int     messagesSent = 0;

    private Socket                    socket;
    private PrintWriter               writer;
    private String                    host = DEFAULT_HOST;
    private int                       port = DEFAULT_PORT;
    private ScheduledExecutorService  scheduler;

    // ── Callbacks hacia el Presenter ────────────────────────
    private Consumer<String>  onLogMessage;
    private Consumer<Integer> onMessageSent;
    private Runnable          onDisconnected;

    // ── Getters ─────────────────────────────────────────────
    public float   getTemperature()  { return temperature;  }
    public float   getHumidity()     { return humidity;     }
    public boolean isConnected()     { return connected;    }
    public int     getMessagesSent() { return messagesSent; }
    public String  getHost()         { return host;         }
    public int     getPort()         { return port;         }

    // ── Setters ─────────────────────────────────────────────
    public void setTemperature(float t) { temperature = SensorMath.clamp(t, -10, 50); }
    public void setHumidity(float h)    { humidity    = SensorMath.clamp(h,  0, 100); }

    // ── Callbacks ────────────────────────────────────────────
    public void setOnLogMessage(Consumer<String> cb)  { onLogMessage  = cb; }
    public void setOnMessageSent(Consumer<Integer> cb){ onMessageSent = cb; }
    public void setOnDisconnected(Runnable cb)        { onDisconnected = cb; }

    // ── Conexión TCP ─────────────────────────────────────────
    public void connect(String newHost, int newPort, String deviceName) throws IOException {
        this.host = newHost;
        this.port = newPort;
        socket = new Socket(host, port);
        writer = new PrintWriter(
            new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
        connected    = true;
        messagesSent = 0;

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
            () -> sendData(deviceName), 0, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS);
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
                "\"red\":{\"rssi\":%d,\"ip\":\"%s\"}}",
                messagesSent, deviceName, System.currentTimeMillis(),
                temperature, humidity, hi, rssi, ip);

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
}
