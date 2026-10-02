import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Simulador del firmware ESP32_PlantMonitor.ino v4 (sin dependencias, Java 17+).
 * Habla el mismo protocolo TCP que el ESP32 real contra Weather_Server, así que
 * sirve para probar el panel web sin hardware.
 *
 * Uso:
 *   java ESP32Simulator.java --demo
 *   java ESP32Simulator.java --host 192.168.1.20 --count 3 --demo -v
 *
 * Opciones:
 *   --host H         IP del Weather_Server              (127.0.0.1)
 *   --port P         puerto TCP                          (2361)
 *   --count N        cantidad de ESP32 simulados          (1)
 *   --demo           asigna 3 plantas de ejemplo en el primer arranque
 *   --speed X        acelera/frena el secado del sustrato (1.0)
 *   --cooldown S     segundos entre riegos automáticos    (21600 = 6 h, como el firmware)
 *   --max-backoff S  espera máxima de reconexión          (60)
 *   --fresh          ignora el estado guardado (equivale a borrar la NVS)
 *   -v               imprime cada lectura enviada
 *
 * Comandos de consola (con --count > 1, antepón el número de dispositivo: "2 dry 0"):
 *   status | dry <slot> [%] | wet <slot> [%] | water <slot> | temp <°C|auto> |
 *   hum <%|auto> | drop | help | quit
 *
 * Lo que replica del .ino: envío cada 2 s, JSON idéntico, comandos rename /
 * assignPlant / water, relé de 3 s, riego automático local (gracia de 10 s y
 * cooldown), persistencia del nombre y las plantas (NVS -> carpeta sim-state/),
 * y reconexión con backoff exponencial.
 */
public class ESP32Simulator {

    // ── Constantes del firmware ──────────────────────────────
    static final int  MAX_SLOTS = 4;
    static final long SEND_INTERVAL_MS = 2000, RECONNECT_MIN_MS = 2000,
                      WATER_RELAY_MS = 3000, AUTO_WATER_BOOT_GRACE_MS = 10_000;
    static long   reconnectMaxMs = 60_000, cooldownMs = 6L * 3600_000;

    // ── Opciones de simulación ───────────────────────────────
    static double  speed = 1.0;
    static boolean verbose = false, demo = false, fresh = false;
    static String  host = "127.0.0.1";
    static int     port = 2361;

    static final long T0 = System.nanoTime();
    static long millis() { return (System.nanoTime() - T0) / 1_000_000L; }

    // Plantas de demostración (existen en data/plantas.xlsx): clave, mín, máx, óptimo
    static final Object[][] DEMO = {
        {"Rosa", 40f, 60f, 50f}, {"Girasol", 35f, 55f, 45f}, {"Tulipán", 40f, 55f, 48f}
    };

    static class Slot {
        String plantKey = "";
        float humMin = 0, humMax = 100, humOpt = 50, humidity;
        long lastWatered = 0;
        boolean waterPending;
    }

    // ════════════════════════════════════════════════════════
    //  Un ESP32 simulado
    // ════════════════════════════════════════════════════════
    static class Device implements Runnable {
        final int idx;
        String deviceName;
        final Slot[] plants = new Slot[MAX_SLOTS];
        final boolean[] relayActive = new boolean[MAX_SLOTS];
        final long[] relayOnSince = new long[MAX_SLOTS];
        final double[] dryFactor = new double[MAX_SLOTS];
        final Random rnd = new Random();
        final Path nvs;
        final Queue<String> rx = new ConcurrentLinkedQueue<>();

        int msgCount;
        long bootMs = millis(), lastSendMs, lastTick = millis(), nextReconnectMs;
        long reconnectDelayMs = RECONNECT_MIN_MS;
        double temp = 22, hum = 55;
        Double tempOverride, humOverride;
        int rssi = -55;
        volatile boolean tcpConnected, running = true;
        volatile Socket sock;
        Writer out;

        Device(int idx, String name) {
            this.idx = idx;
            this.deviceName = name;
            this.nvs = Path.of("sim-state", "esp32-" + idx + ".properties");
            for (int i = 0; i < MAX_SLOTS; i++) {
                plants[i] = new Slot();
                plants[i].humidity = 40 + rnd.nextInt(26);
                dryFactor[i] = 0.8 + rnd.nextDouble() * 0.4;
            }
            boolean hadState = !fresh && Files.exists(nvs);
            if (hadState) loadFromNVS();
            if (demo && !hadState) applyDemo();
            log("Arranque: nombre='" + deviceName + "'" + (hadState ? " (estado restaurado de NVS)" : ""));
        }

        // ── Persistencia (NVS) ───────────────────────────────
        void saveToNVS() {
            Properties p = new Properties();
            p.setProperty("devName", deviceName);
            for (int i = 0; i < MAX_SLOTS; i++) {
                p.setProperty("pk" + i, plants[i].plantKey);
                p.setProperty("mn" + i, String.valueOf(plants[i].humMin));
                p.setProperty("mx" + i, String.valueOf(plants[i].humMax));
                p.setProperty("op" + i, String.valueOf(plants[i].humOpt));
                p.setProperty("lw" + i, String.valueOf(plants[i].lastWatered));
            }
            try {
                Files.createDirectories(nvs.getParent());
                try (Writer w = Files.newBufferedWriter(nvs, StandardCharsets.UTF_8)) { p.store(w, "NVS simulada"); }
            } catch (IOException e) { log("[NVS] No se pudo guardar: " + e.getMessage()); }
        }

        void loadFromNVS() {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(nvs, StandardCharsets.UTF_8)) { p.load(r); }
            catch (IOException e) { return; }
            deviceName = p.getProperty("devName", deviceName);
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (!p.containsKey("pk" + i)) continue;
                plants[i].plantKey = p.getProperty("pk" + i, "");
                plants[i].humMin = Float.parseFloat(p.getProperty("mn" + i, "0"));
                plants[i].humMax = Float.parseFloat(p.getProperty("mx" + i, "100"));
                plants[i].humOpt = Float.parseFloat(p.getProperty("op" + i, "50"));
                plants[i].lastWatered = Long.parseLong(p.getProperty("lw" + i, "0"));
            }
        }

        void applyDemo() {
            for (int i = 0; i < DEMO.length; i++) {
                Slot s = plants[i];
                s.plantKey = (String) DEMO[i][0];
                s.humMin = (Float) DEMO[i][1]; s.humMax = (Float) DEMO[i][2]; s.humOpt = (Float) DEMO[i][3];
            }
            plants[0].humidity = plants[0].humOpt;          // óptima
            plants[1].humidity = plants[1].humMin + 4;      // pronto quedará seca y se regará sola
            plants[2].humidity = plants[2].humMax + 12;     // en exceso
            saveToNVS();
        }

        // ── Bucle principal (equivale a loop() del .ino) ─────
        @Override public void run() {
            while (running) {
                synchronized (this) { tick(); }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        }

        void tick() {
            long now = millis();
            physics((now - lastTick) / 1000.0);
            lastTick = now;

            if (!tcpConnected) attemptTcpReconnect(now);
            else processCommands();

            if (now - lastSendMs >= SEND_INTERVAL_MS) {
                lastSendMs = now;
                readSensors();
                checkAutoWater(now);
                if (tcpConnected) sendReading(); else logOffline();
            }
            handleRelays(now);
            handlePendingWater(now);
        }

        // ── Física del sustrato ──────────────────────────────
        void physics(double dt) {
            for (int i = 0; i < MAX_SLOTS; i++) {
                Slot s = plants[i];
                if (relayActive[i]) s.humidity += 4.0 * dt;                          // bomba: +4 %/s
                else s.humidity -= 0.05 * (0.6 + temp / 30.0) * speed * dryFactor[i] * dt; // secado
                s.humidity = Math.max(0f, Math.min(100f, s.humidity));
            }
        }

        void readSensors() {
            double t = 22 + 4 * Math.sin(2 * Math.PI * (millis() / 1000.0 * speed) / 600.0) + rnd.nextGaussian() * 0.15;
            temp = tempOverride != null ? tempOverride : t;
            hum = humOverride != null ? humOverride
                : Math.max(20, Math.min(95, 60 - 1.5 * (temp - 22) + rnd.nextGaussian() * 0.8));
            rssi = Math.max(-75, Math.min(-40, rssi + rnd.nextInt(5) - 2));
        }

        // Fórmula tal cual está en el .ino
        static float computeHeatIndex(float t, float h) {
            float c1 = -8.78469475556f, c2 = 1.61139411f, c3 = 2.33854883889f, c4 = -0.14611605f,
                  c5 = -0.01230809851f, c6 = -0.01642482777f, c7 = 0.00221732f, c8 = 0.00072546f,
                  c9 = -0.00000358583f;
            return c1 + c2*t + c3*h + c4*t*h + c5*t*t + c6*h*h + c7*t*t*h + c8*t*h*h + c9*t*t*h*h;
        }

        // ── Red ──────────────────────────────────────────────
        void attemptTcpReconnect(long now) {
            if (now < nextReconnectMs) return;
            log("[TCP] Conectando a " + host + ":" + port + " ...");
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setTcpNoDelay(true);
                sock = s;
                out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                rx.clear();
                reconnectDelayMs = RECONNECT_MIN_MS;
                tcpConnected = true;
                startReader(s);
                log("[TCP] Conectado al servidor.");
            } catch (IOException e) {
                tcpConnected = false;
                reconnectDelayMs = Math.min(reconnectMaxMs, reconnectDelayMs * 2);
                nextReconnectMs = now + reconnectDelayMs;
                log(String.format(Locale.ROOT, "[TCP] Fallo (%s). Reintento en %.1fs", e.getMessage(), reconnectDelayMs / 1000.0));
            }
        }

        void startReader(Socket s) {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) { line = line.trim(); if (!line.isEmpty()) rx.add(line); }
                } catch (IOException ignored) { }
                if (sock == s && tcpConnected) { tcpConnected = false; log("[TCP] Servidor desconectado."); }
                try { s.close(); } catch (IOException ignored) { }
            }, "rx-" + idx);
            t.setDaemon(true);
            t.start();
        }

        synchronized void disconnect() {
            tcpConnected = false;
            try { if (sock != null) sock.close(); } catch (IOException ignored) { }
            log("[TCP] Conexión cerrada manualmente.");
        }

        void sendReading() {
            msgCount++;
            float t = (float) temp, h = (float) hum, hi = computeHeatIndex(t, h);
            String ip = sock != null ? sock.getLocalAddress().getHostAddress() : "0.0.0.0";
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":").append(msgCount)
              .append(",\"dispositivo\":\"").append(esc(deviceName)).append('"')
              .append(",\"timestamp\":").append(millis())
              .append(",\"sensores\":{\"temperatura\":").append(f1(t))
              .append(",\"humedad\":").append(f1(h))
              .append(",\"indice_calor\":").append(f1(hi)).append('}')
              .append(",\"red\":{\"rssi\":").append(rssi).append(",\"ip\":\"").append(ip).append("\"}")
              .append(",\"plantas\":[");
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (i > 0) sb.append(',');
                float v = (float) (plants[i].humidity + rnd.nextGaussian() * 0.2);
                v = Math.max(0, Math.min(100, v));
                sb.append("{\"slot\":").append(i)
                  .append(",\"plantKey\":\"").append(esc(plants[i].plantKey)).append('"')
                  .append(",\"humedad\":").append(f1(v))
                  .append(",\"regadoEn\":").append(plants[i].lastWatered).append('}');
            }
            sb.append("]}");
            try {
                out.write(sb.toString());
                out.write("\r\n");
                out.flush();
            } catch (IOException e) {
                log("[TCP] Error de escritura. Desconectando...");
                tcpConnected = false;
                try { sock.close(); } catch (IOException ignored) { }
                return;
            }
            if (verbose || msgCount % 15 == 0)
                log(String.format(Locale.ROOT, "[TX #%d] T=%.1fC H=%.1f%% RSSI=%d dBm | %s", msgCount, t, h, rssi, slotsSummary()));
        }

        void logOffline() {
            if (msgCount % 5 == 0 || verbose) log("[OFFLINE] " + slotsSummary());
            msgCount++;
        }

        // ── Comandos del servidor ────────────────────────────
        void processCommands() {
            String line;
            while ((line = rx.poll()) != null) handleCommand(line);
        }

        void handleCommand(String json) {
            log("[CMD] " + json);
            String cmd = jstr(json, "cmd");
            if (cmd == null) return;
            switch (cmd) {
                case "rename" -> {
                    String n = jstr(json, "name");
                    if (n == null || n.isEmpty()) return;
                    deviceName = n.length() > 47 ? n.substring(0, 47) : n;
                    log("[CMD] Renombrado a: " + deviceName);
                    saveToNVS();
                }
                case "assignPlant" -> {
                    int slot = jint(json, "slot", -1);
                    if (slot < 0 || slot >= MAX_SLOTS) { log("[CMD] Slot inválido: " + slot); return; }
                    String key = jstr(json, "plantKey");
                    if (key == null) key = "";
                    Slot s = plants[slot];
                    s.plantKey = key.length() > 47 ? key.substring(0, 47) : key;
                    if (s.plantKey.isEmpty()) {
                        s.humMin = 0; s.humMax = 100; s.humOpt = 50; s.lastWatered = 0;
                        log("[CMD] Slot " + slot + " vaciado.");
                    } else {
                        s.humMin = (float) jnum(json, "humMin", 0);
                        s.humMax = (float) jnum(json, "humMax", 100);
                        s.humOpt = (float) jnum(json, "humOpt", 50);
                        log(String.format(Locale.ROOT, "[CMD] Slot %d <- '%s' (%.0f-%.0f%%, opt=%.0f%%)",
                                slot, s.plantKey, s.humMin, s.humMax, s.humOpt));
                    }
                    saveToNVS();
                }
                case "water" -> requestWater(jint(json, "slot", -1));
                default -> log("[CMD] Comando desconocido: " + cmd);
            }
        }

        void requestWater(int slot) {
            if (slot < 0 || slot >= MAX_SLOTS) { log("[CMD] Riego: slot inválido " + slot); return; }
            if (plants[slot].plantKey.isEmpty()) { log("[CMD] Riego: slot " + slot + " sin planta asignada. Ignorando."); return; }
            plants[slot].waterPending = true;
            log("[CMD] Riego pendiente para slot " + slot + " ('" + plants[slot].plantKey + "').");
        }

        // ── Riego ────────────────────────────────────────────
        void checkAutoWater(long now) {
            if (now - bootMs < AUTO_WATER_BOOT_GRACE_MS) return;
            for (int i = 0; i < MAX_SLOTS; i++) {
                Slot s = plants[i];
                if (s.plantKey.isEmpty()) continue;
                if (relayActive[i] || s.waterPending) continue;
                if (s.humidity >= s.humMin) continue;
                if (s.lastWatered != 0 && (now - s.lastWatered) < cooldownMs) continue;
                s.waterPending = true;
                log(String.format(Locale.ROOT, "[AUTO-RIEGO] Slot %d ('%s'): humedad %.1f%% < minimo %.1f%%. Riego automatico local disparado%s.",
                        i, s.plantKey, s.humidity, s.humMin, tcpConnected ? "" : " (servidor desconectado)"));
            }
        }

        void handlePendingWater(long now) {
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (!plants[i].waterPending) continue;
                plants[i].waterPending = false;
                relayActive[i] = true;
                relayOnSince[i] = now;
                plants[i].lastWatered = now;
                log("[AGUA] Regando slot " + i + " ('" + plants[i].plantKey + "') por " + WATER_RELAY_MS + "ms.");
                saveToNVS();
            }
        }

        void handleRelays(long now) {
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (relayActive[i] && now - relayOnSince[i] >= WATER_RELAY_MS) {
                    relayActive[i] = false;
                    log("[AGUA] Riego slot " + i + " completado.");
                }
            }
        }

        // ── Utilidades ───────────────────────────────────────
        String slotsSummary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (plants[i].plantKey.isEmpty()) continue;
                if (sb.length() > 0) sb.append("  ");
                sb.append(String.format(Locale.ROOT, "S%d[%s]=%.1f%%%s", i, plants[i].plantKey, plants[i].humidity, relayActive[i] ? " [ON]" : ""));
            }
            return sb.length() == 0 ? "(sin plantas asignadas)" : sb.toString();
        }

        synchronized String status() {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%s | %s | T=%.1fC H=%.0f%% | enviados=%d%n", deviceName, tcpConnected ? "conectado" : "SIN SERVIDOR", temp, hum, msgCount));
            for (int i = 0; i < MAX_SLOTS; i++) {
                Slot s = plants[i];
                sb.append(String.format(Locale.ROOT, "   slot %d: %-16s hum=%5.1f%%  rango %.0f-%.0f%%  %s%n", i,
                    s.plantKey.isEmpty() ? "(vacío)" : s.plantKey, s.humidity, s.humMin, s.humMax, relayActive[i] ? "BOMBA ON" : ""));
            }
            return sb.toString();
        }

        void log(String m) {
            String t = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
            synchronized (System.out) { System.out.println(t + " [" + deviceName + "] " + m); }
        }
    }

    // ── Helpers JSON (comandos planos del servidor) ──────────
    static String f1(float v) { return String.format(Locale.ROOT, "%.1f", v); }
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static String jstr(String j, String k) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(j);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }
    static double jnum(String j, String k, double def) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*(-?[0-9.]+)").matcher(j);
        return m.find() ? Double.parseDouble(m.group(1)) : def;
    }
    static int jint(String j, String k, int def) { return (int) jnum(j, k, def); }

    // ════════════════════════════════════════════════════════
    //  main + consola
    // ════════════════════════════════════════════════════════
    public static void main(String[] a) throws Exception {
        int count = 1;
        String baseName = "ESP32-SIM";
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--host" -> host = a[++i];
                case "--port" -> port = Integer.parseInt(a[++i]);
                case "--count" -> count = Math.max(1, Math.min(20, Integer.parseInt(a[++i])));
                case "--speed" -> speed = Double.parseDouble(a[++i]);
                case "--cooldown" -> cooldownMs = (long) (Double.parseDouble(a[++i]) * 1000);
                case "--max-backoff" -> reconnectMaxMs = (long) (Double.parseDouble(a[++i]) * 1000);
                case "--demo" -> demo = true;
                case "--fresh" -> fresh = true;
                case "-v" -> verbose = true;
                case "--help", "-h" -> { System.out.println("Ver el comentario al inicio de ESP32Simulator.java"); return; }
                default -> { System.err.println("Opción desconocida: " + a[i]); return; }
            }
        }
        List<Device> devs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Device d = new Device(i, String.format("%s-%02d", baseName, i));
            devs.add(d);
            Thread t = new Thread(d, "esp32-" + i);
            t.setDaemon(true);
            t.start();
        }
        System.out.println("Simulando " + count + " ESP32 -> " + host + ":" + port + "   (escribe 'help' para los comandos)");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 0 || p[0].isEmpty()) continue;
            int di = 0, o = 0;
            if (p[0].matches("\\d+") && p.length > 1) { di = Integer.parseInt(p[0]) - 1; o = 1; }
            if (di < 0 || di >= devs.size()) { System.out.println("Dispositivo inexistente."); continue; }
            Device d = devs.get(di);
            try {
                switch (p[o]) {
                    case "status" -> { for (Device x : (o == 1 ? List.of(d) : devs)) System.out.print(x.status()); }
                    case "dry" -> { int s = Integer.parseInt(p[o + 1]); synchronized (d) { d.plants[s].humidity = p.length > o + 2 ? Float.parseFloat(p[o + 2]) : 10; } }
                    case "wet" -> { int s = Integer.parseInt(p[o + 1]); synchronized (d) { d.plants[s].humidity = p.length > o + 2 ? Float.parseFloat(p[o + 2]) : 80; } }
                    case "water" -> { synchronized (d) { d.requestWater(Integer.parseInt(p[o + 1])); } }
                    case "temp" -> { synchronized (d) { d.tempOverride = p[o + 1].equals("auto") ? null : Double.valueOf(p[o + 1]); } }
                    case "hum" -> { synchronized (d) { d.humOverride = p[o + 1].equals("auto") ? null : Double.valueOf(p[o + 1]); } }
                    case "drop" -> d.disconnect();
                    case "quit", "exit" -> { System.exit(0); }
                    default -> System.out.println("""
                        status | dry <slot> [%] | wet <slot> [%] | water <slot> | temp <°C|auto> | hum <%|auto> | drop | quit
                        (con varios dispositivos: "2 dry 0 10")""");
                }
            } catch (RuntimeException e) {
                System.out.println("Comando inválido (" + e.getClass().getSimpleName() + "). Escribe 'help'.");
            }
        }
        // stdin cerrado (ejecución sin terminal): seguir simulando
        Thread.currentThread().join();
    }
}
