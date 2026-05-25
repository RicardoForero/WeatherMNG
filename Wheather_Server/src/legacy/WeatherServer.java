package legacy;

import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║   WeatherServer — Servidor TCP sin interfaz gráfica     ║
 * ║                                                         ║
 * ║   Protocolo de identificación (primera línea):          ║
 * ║   · "ADMIN_v1"  → cliente Admin  (recibe broadcasts)   ║
 * ║   · JSON {...}  → cliente Sensor (envía lecturas)       ║
 * ╠══════════════════════════════════════════════════════════╣
 * ║  Compilar : javac WeatherServer.java                    ║
 * ║  Ejecutar : java WeatherServer                          ║
 * ╚══════════════════════════════════════════════════════════╝
 */
public class WeatherServer {

    /* ── Configuración ───────────────────────────────────── */
    static final int    PORT          = 2361;
    static final int    MAX_SENSORS   = 20;
    static final String ADMIN_HELLO   = "ADMIN_v1";

    /* ── Modelo de un sensor conectado ───────────────────── */
    static class SensorData {
        final String id, ip;
        volatile String  name;
        volatile float   temp      = Float.NaN;
        volatile float   hum       = Float.NaN;
        volatile float   heatIndex = Float.NaN;
        volatile int     rssi      = 0;
        volatile int     msgCount  = 0;
        volatile boolean online    = true;
        volatile long    lastSeen  = System.currentTimeMillis();
        final long connectedAt = System.currentTimeMillis();
        final List<Float> histTemp = Collections.synchronizedList(new ArrayList<>());
        final List<Float> histHum  = Collections.synchronizedList(new ArrayList<>());

        SensorData(String id, String ip) {
            this.id = id; this.ip = ip;
            String tail = ip.replace(".", "");
            this.name = "ESP32-" + tail.substring(Math.max(0, tail.length() - 4));
        }

        void push(float t, float h) {
            temp = t; hum = h;
            lastSeen = System.currentTimeMillis();
            msgCount++;
            synchronized (histTemp) { if (histTemp.size() >= 80) histTemp.remove(0); histTemp.add(t); }
            synchronized (histHum)  { if (histHum.size()  >= 80) histHum.remove(0);  histHum.add(h); }
        }

        long uptimeSeconds() { return (System.currentTimeMillis() - connectedAt) / 1000; }

        /** Serializa el estado completo como JSON para los admins. */
        String toJson() {
            List<Float> snapT, snapH;
            synchronized (histTemp) { snapT = new ArrayList<>(histTemp); }
            synchronized (histHum)  { snapH = new ArrayList<>(histHum); }

            StringBuilder histTJson = new StringBuilder("[");
            for (int i = 0; i < snapT.size(); i++) {
                if (i > 0) histTJson.append(",");
                histTJson.append( String.valueOf(snapT.get(i)));
            }
            histTJson.append("]");

            StringBuilder histHJson = new StringBuilder("[");
            for (int i = 0; i < snapH.size(); i++) {
                if (i > 0) histHJson.append(",");
                histHJson.append( String.valueOf(snapH.get(i)));
            }
            histHJson.append("]");

            return String.format(
                "{\"id\":\"%s\",\"ip\":\"%s\",\"name\":\"%s\"," +
                "\"temp\":%.1f,\"hum\":%.1f,\"heatIndex\":%.1f," +
                "\"rssi\":%d,\"msgCount\":%d,\"online\":%b,\"uptime\":%d," +
                "\"histTemp\":%s,\"histHum\":%s}",
                id, ip, name,
                Float.isNaN(temp) ? 0f : temp,
                Float.isNaN(hum)  ? 0f : hum,
                Float.isNaN(heatIndex) ? 0f : heatIndex,
                rssi, msgCount, online, uptimeSeconds(),
                histTJson, histHJson);
        }
    }

    /* ── Estado global ───────────────────────────────────── */
    final ConcurrentHashMap<String, SensorData> sensors = new ConcurrentHashMap<>();
    final Set<PrintWriter> adminWriters = Collections.newSetFromMap(new ConcurrentHashMap<>());
    final AtomicInteger totalMessages = new AtomicInteger(0);
    final AtomicInteger adminCount    = new AtomicInteger(0);

    final ExecutorService pool = Executors.newCachedThreadPool();
    ServerSocket serverSocket;

    /* ── Main ────────────────────────────────────────────── */
    public static void main(String[] args) throws Exception {
        new WeatherServer().run();
    }

    void run() {
        log("WeatherServer arrancando en puerto " + PORT + "...");
        startHeartbeat();
        startAcceptLoop();
    }

    /* ── Heartbeat: detecta sensores offline cada 5s ─────── */
    void startHeartbeat() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (SensorData s : sensors.values()) {
                boolean was = s.online;
                s.online = (now - s.lastSeen) < 12_000;
                if (was && !s.online) {
                    log("! Sensor sin respuesta: " + s.name + " (" + s.ip + ")");
                    broadcast("{\"type\":\"update\",\"sensor\":" + s.toJson() + "}");
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /* ── Bucle principal de aceptación ──────────────────── */
    void startAcceptLoop() {
        try {
            serverSocket = new ServerSocket(PORT);
            log("✓ Servidor activo en :" + PORT);
            log("  Protocolo: primera línea = \"" + ADMIN_HELLO + "\" → Admin | JSON → Sensor");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                pool.shutdownNow();
                try { serverSocket.close(); } catch (IOException ignored) {}
                log("Servidor detenido.");
            }));

            while (!serverSocket.isClosed()) {
                try {
                    Socket sock = serverSocket.accept();
                    pool.submit(() -> identify(sock));
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) log("! Accept: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log("! Error fatal: " + e.getMessage());
        }
    }

    /* ── Identificación del cliente ──────────────────────── */
    void identify(Socket sock) {
        String ip = sock.getInetAddress().getHostAddress();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            PrintWriter    writer = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(sock.getOutputStream())), true);

            String first = reader.readLine();
            if (first == null) { sock.close(); return; }
            first = first.trim();

            if (ADMIN_HELLO.equals(first)) {
                handleAdmin(sock, reader, writer, ip);
            } else {
                handleSensor(sock, reader, writer, first, ip);
            }
        } catch (IOException e) {
            log("! Identify " + ip + ": " + e.getMessage());
        }
    }

    /* ── Manejo de sensor ────────────────────────────────── */
    void handleSensor(Socket sock, BufferedReader reader, PrintWriter writer,
                      String firstLine, String ip) {
        if (sensors.size() >= MAX_SENSORS) {
            log("! Límite de sensores alcanzado (" + MAX_SENSORS + "), rechazando " + ip);
            try { sock.close(); } catch (IOException ignored) {}
            return;
        }

        String id = ip + ":" + sock.getPort();
        SensorData sensor = new SensorData(id, ip);
        sensors.put(id, sensor);

        log("✓ Sensor conectado: " + ip + " | Total sensores: " + sensors.size());

        // Procesar primera línea ya leída
        parseAndPush(sensor, firstLine);

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String l = line.trim();
                if (!l.isEmpty()) parseAndPush(sensor, l);
            }
        } catch (IOException e) {
            log("! " + ip + ": " + e.getMessage());
        } finally {
            sensor.online = false;
            sensors.remove(id);
            log("✗ Sensor desconectado: " + ip + " | Activos: " + sensors.size());
            broadcast("{\"type\":\"remove\",\"id\":\"" + id + "\"}");
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    void parseAndPush(SensorData sensor, String json) {
        try {
            float t  = parseFloat(json, "temperatura");
            float h  = parseFloat(json, "humedad");
            float hi = parseFloat(json, "indice_calor");
            int   r  = parseInt(json,  "rssi");
            String devName = parseStr(json, "dispositivo");

            sensor.push(t, h);
            sensor.heatIndex = hi;
            sensor.rssi      = r;
            sensor.online    = true;
            if (!"N/A".equals(devName) && !devName.isBlank())
                sensor.name = devName;

            totalMessages.incrementAndGet();
            log(String.format("  ← %s  T=%.1f°  H=%.0f%%  HI=%.1f°  RSSI=%d",
                    sensor.name, t, h, hi, r));

            broadcast("{\"type\":\"update\",\"sensor\":" + sensor.toJson() + "}");
        } catch (Exception e) {
            log("! JSON inválido de " + sensor.ip + ": " + json.substring(0, Math.min(40, json.length())));
        }
    }

    /* ── Manejo de admin ─────────────────────────────────── */
    void handleAdmin(Socket sock, BufferedReader reader, PrintWriter writer, String ip) {
        adminWriters.add(writer);
        adminCount.incrementAndGet();
        log("★ Admin conectado: " + ip + " | Total admins: " + adminCount.get());

        // Enviar snapshot completo al nuevo admin
        sendSnapshot(writer);

        // Mantener conexión; el admin no envía datos
        try {
            while (reader.read() != -1) { /* keep-alive */ }
        } catch (IOException ignored) {}
        finally {
            adminWriters.remove(writer);
            adminCount.decrementAndGet();
            log("★ Admin desconectado: " + ip + " | Admins restantes: " + adminCount.get());
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    void sendSnapshot(PrintWriter writer) {
        StringBuilder sb = new StringBuilder("{\"type\":\"snapshot\",\"sensors\":[");
        boolean first = true;
        for (SensorData s : sensors.values()) {
            if (!first) sb.append(",");
            sb.append(s.toJson());
            first = false;
        }
        sb.append("],\"totalMessages\":").append(totalMessages.get()).append("}");
        safeSend(writer, sb.toString());
    }

    void broadcast(String msg) {
        for (PrintWriter w : adminWriters) safeSend(w, msg);
    }

    void safeSend(PrintWriter w, String msg) {
        try { w.println(msg); } catch (Exception ignored) {}
    }

    /* ── Parseo JSON mínimo ──────────────────────────────── */
    static float  parseFloat(String j, String k) { String s=parseStr(j,k); return "N/A".equals(s)?Float.NaN:Float.parseFloat(s); }
    static int    parseInt(String j, String k)    { String s=parseStr(j,k); return "N/A".equals(s)?0:(int)Float.parseFloat(s); }
    static String parseStr(String json, String key) {
        int i = json.indexOf("\"" + key + "\""); if (i < 0) return "N/A";
        int c = json.indexOf(':', i);            if (c < 0) return "N/A";
        int s = c + 1; while (s < json.length() && json.charAt(s) == ' ') s++;
        if (s >= json.length()) return "N/A";
        if (json.charAt(s) == '"') { int e = json.indexOf('"', s+1); return e<0?"N/A":json.substring(s+1,e); }
        int e = s; while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e)=='.' || json.charAt(e)=='-')) e++;
        return json.substring(s, e);
    }

    /* ── Log por consola ─────────────────────────────────── */
    static void log(String msg) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[" + time + "] " + msg);
    }
}
