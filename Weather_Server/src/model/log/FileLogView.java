package model.log;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import model.PlantSlot;
import model.SensorData;

/**
 * Vista concreta que escribe los eventos del servidor tanto en archivos de log diarios
 * como en la consola estándar (System.out / System.err) para máxima observabilidad.
 *
 * Ubicación del archivo: logs/weather-YYYY-MM-DD.log (resuelto inteligentemente).
 *
 * Características:
 *  - Rotación diaria automática de archivos.
 *  - Salida simultánea a archivo y consola con timestamps formateados.
 *  - Thread-safe mediante sincronización en el writer.
 *  - Cierre limpio registrado como shutdown hook.
 */
public class FileLogView implements IServerView {

    // ── Formatos ──────────────────────────────────────────────
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ── Estado interno ────────────────────────────────────────
    private final Path     logsDir;
    private BufferedWriter writer;
    private LocalDate      currentDay;

    // ── Constructores ─────────────────────────────────────────

    public FileLogView() {
        this(resolveLogsDir());
    }

    public FileLogView(Path logsDir) {
        this.logsDir = logsDir;
        openWriter();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "log-closer"));
    }

    // ── IServerView ───────────────────────────────────────────

    @Override
    public void log(String message) {
        write("INFO ", message, false);
    }

    @Override
    public void onSensorConnected(SensorData s, int total) {
        write("CONN ", String.format(Locale.ROOT, "Sensor conectado: %s (%s) | Total activos: %d", s.getName(), s.ip, total), false);
    }

    @Override
    public void onSensorDisconnected(SensorData s, int active) {
        write("DISC ", String.format(Locale.ROOT, "Sensor desconectado: %s (%s) | Restantes activos: %d", s.getName(), s.ip, active), false);
    }

    @Override
    public void onReadingReceived(SensorData s) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-16s T=%5.1f°C  H=%4.0f%%  HI=%5.1f°C  RSSI=%4d dBm",
                s.getName(), s.getTemp(), s.getHum(), s.getHeatIndex(), s.getRssi()));
        sb.append(" | Slots: [");
        PlantSlot[] plants = s.getPlants();
        for (int i = 0; i < plants.length; i++) {
            if (i > 0) sb.append(", ");
            PlantSlot ps = plants[i];
            if (ps.isEmpty()) {
                sb.append("S").append(i).append(": libre");
            } else {
                sb.append("S").append(i).append(": ").append(ps.getPlantKey())
                  .append(String.format(Locale.ROOT, "(%.0f%%)", ps.getHumidity()));
            }
        }
        sb.append("]");
        write("DATA ", sb.toString(), false);
    }

    @Override
    public void onSensorStale(SensorData s) {
        write("WARN ", "Sensor sin respuesta (stale): " + s.getName() + " (" + s.ip + ")", true);
    }

    @Override
    public void onAdminConnected(String ip, int total) {
        write("ADMN ", "Admin conectado: " + ip + " | Total admins: " + total, false);
    }

    @Override
    public void onAdminDisconnected(String ip, int remaining) {
        write("ADMN ", "Admin desconectado: " + ip + " | Restantes: " + remaining, false);
    }

    @Override
    public void onError(String context, String message) {
        write("ERR  ", "[" + context + "] " + message, true);
    }

    @Override
    public void onServerStarted(int port) {
        write("BOOT ", "Servidor Weather_Server iniciado exitosamente en puerto TCP " + port, false);
        write("BOOT ", "Directorio de logs activos: " + logsDir.toAbsolutePath(), false);
    }

    // ── Escritura simultánea (Archivo + Consola) ───────────────

    /**
     * Escribe una línea formateada con nivel y timestamp tanto en el log diario como en consola.
     */
    private synchronized void write(String level, String message, boolean isError) {
        String line = buildLine(level, message);

        // 1. Salida a consola interactiva
        if (isError) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }

        // 2. Salida al archivo de log diario
        try {
            rotateIfNeeded();
            if (writer != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            System.err.println("[FileLogView] Error al escribir archivo de log: " + e.getMessage());
        }
    }

    private String buildLine(String level, String message) {
        return LocalTime.now().format(TIME_FMT) + " " + level + " " + message;
    }

    /** Abre un nuevo archivo si el día actual difiere del que se abrió. */
    private void rotateIfNeeded() throws IOException {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            closeQuietly();
            currentDay = today;
            openWriterForDay(today);
        }
    }

    // ── Gestión del BufferedWriter ────────────────────────────

    private void openWriter() {
        try {
            Files.createDirectories(logsDir);
            currentDay = LocalDate.now();
            openWriterForDay(currentDay);
        } catch (IOException e) {
            System.err.println("[FileLogView] No se pudo inicializar la carpeta de logs: " + e.getMessage());
        }
    }

    private void openWriterForDay(LocalDate day) throws IOException {
        String filename = "weather-" + day.format(DATE_FMT) + ".log";
        Path logFile = logsDir.resolve(filename);

        // append=true para preservar logs previos del mismo día
        writer = Files.newBufferedWriter(logFile,
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);

        writer.write("──────────────────────────────────────────────────────────────────────");
        writer.newLine();
        writer.write("  Sesión iniciada: " + LocalDate.now() + " " + LocalTime.now().format(TIME_FMT));
        writer.newLine();
        writer.write("──────────────────────────────────────────────────────────────────────");
        writer.newLine();
        writer.flush();
    }

    private synchronized void close() {
        write("BOOT ", "Servidor detenido.", false);
        closeQuietly();
    }

    private void closeQuietly() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    // ── Resolución inteligente de la carpeta logs/ ────────────

    public static Path resolveLogsDir() {
        // 1. Variable de entorno
        String envLogs = System.getenv("LOGS_DIR");
        if (envLogs != null && !envLogs.isBlank()) {
            Path p = Path.of(envLogs);
            try { Files.createDirectories(p); return p.toAbsolutePath().normalize(); } catch (IOException ignored) {}
        }

        // 2. Comprobar si existe la carpeta Weather_Server/logs
        Path pWeatherServer = Path.of("Weather_Server", "logs");
        if (Files.exists(pWeatherServer)) return pWeatherServer.toAbsolutePath().normalize();

        // 3. Comprobar si existe logs/ local
        Path pLocal = Path.of("logs");
        if (Files.exists(pLocal)) return pLocal.toAbsolutePath().normalize();

        // 4. Ubicación junto al CodeSource (JAR o bin)
        try {
            Path jarPath = Path.of(FileLogView.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path base = Files.isRegularFile(jarPath) ? jarPath.getParent() : jarPath;
            Path candidate = base.resolve("logs");
            if (Files.exists(candidate)) return candidate.toAbsolutePath().normalize();

            if (base.getParent() != null) {
                Path cand2 = base.getParent().resolve("logs");
                if (Files.exists(cand2)) return cand2.toAbsolutePath().normalize();

                Path cand3 = base.getParent().resolve("Weather_Server").resolve("logs");
                if (Files.exists(cand3)) return cand3.toAbsolutePath().normalize();
            }
        } catch (URISyntaxException | NullPointerException ignored) {}

        // 5. Fallback por defecto preferente
        Path fallback = Files.exists(Path.of("Weather_Server"))
                ? Path.of("Weather_Server", "logs")
                : Path.of("logs");
        try { Files.createDirectories(fallback); } catch (IOException ignored) {}
        return fallback.toAbsolutePath().normalize();
    }
}
