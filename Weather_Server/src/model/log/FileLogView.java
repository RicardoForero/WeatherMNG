package model.log;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import model.SensorData;

/**
 * Vista concreta que escribe los eventos del servidor en archivos de log.
 *
 * Ubicación: <directorio_del_jar>/logs/weather-YYYY-MM-DD.log
 *
 * Características:
 *  - Rotación diaria automática: al cambiar de día se abre un nuevo archivo.
 *  - Escritura con BufferedWriter para minimizar syscalls.
 *  - Thread-safe mediante sincronización en el writer.
 *  - Cierre limpio registrado como shutdown hook.
 *  - Puede combinarse con ConsoleView en un CompositeView.
 */
public class FileLogView implements IServerView {

    // ── Formatos ──────────────────────────────────────────────
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ── Estado interno ────────────────────────────────────────
    private final Path     logsDir;
    private BufferedWriter writer;
    private LocalDate      currentDay;

    // ── Constructor ───────────────────────────────────────────

    /**
     * Crea el FileLogView resolviendo la carpeta {@code logs/} junto al JAR/clase principal.
     * Si la resolución falla, usa el directorio de trabajo actual como fallback.
     */
    public FileLogView() {
        this(resolveLogsDir());
    }

    /**
     * Constructor explícito para tests o despliegues con ruta personalizada.
     *
     * @param logsDir ruta absoluta de la carpeta de logs
     */
    public FileLogView(Path logsDir) {
        this.logsDir = logsDir;
        openWriter();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "log-closer"));
    }

    // ── IServerView ───────────────────────────────────────────

    @Override
    public void log(String message) {
        write("INFO ", message);
    }

    @Override
    public void onSensorConnected(SensorData s, int total) {
        write("CONN ", String.format("Sensor conectado: %s (%s) | Total: %d", s.getName(), s.ip, total));
    }

    @Override
    public void onSensorDisconnected(SensorData s, int active) {
        write("DISC ", String.format("Sensor desconectado: %s (%s) | Activos: %d", s.getName(), s.ip, active));
    }

    @Override
    public void onReadingReceived(SensorData s) {
        /*write("DATA ", String.format("%-15s  T=%5.1f°C  H=%4.0f%%  HI=%5.1f°C  RSSI=%d",
           s.getName(), s.getTemp(), s.getHum(), s.getHeatIndex(), s.getRssi()));
     */    
     }

    @Override
    public void onSensorStale(SensorData s) {
        write("WARN ", "Sin respuesta: " + s.getName() + " (" + s.ip + ")");
    }

    @Override
    public void onAdminConnected(String ip, int total) {
        write("ADMN ", "Admin conectado: " + ip + " | Total admins: " + total);
    }

    @Override
    public void onAdminDisconnected(String ip, int remaining) {
        write("ADMN ", "Admin desconectado: " + ip + " | Restantes: " + remaining);
    }

    @Override
    public void onError(String context, String message) {
        write("ERR  ", "[" + context + "] " + message);
    }

    @Override
    public void onServerStarted(int port) {
        write("BOOT ", "Servidor iniciado en puerto " + port);
        write("BOOT ", "Logs en: " + logsDir.toAbsolutePath());
    }

    // ── Escritura con rotación diaria ─────────────────────────

    /**
     * Escribe una línea formateada con nivel y timestamp.
     * Rota el archivo si cambió el día desde la última escritura.
     */
    private synchronized void write(String level, String message) {
        try {
            rotatIfNeeded();
            writer.write(buildLine(level, message));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("[FileLogView] Error al escribir log: " + e.getMessage());
        }
    }

    private String buildLine(String level, String message) {
        return LocalTime.now().format(TIME_FMT) + " " + level + " " + message;
    }

    /** Abre un nuevo archivo si el día actual difiere del que se abrió. */
    private void rotatIfNeeded() throws IOException {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            closeQuietly();
            currentDay = today;
            openWriterForDay(today);
        }
    }

    // ── Gestión del writer ────────────────────────────────────

    private void openWriter() {
        try {
            Files.createDirectories(logsDir);
            currentDay = LocalDate.now();
            openWriterForDay(currentDay);
        } catch (IOException e) {
            System.err.println("[FileLogView] No se pudo crear la carpeta de logs: " + e.getMessage());
        }
    }

    private void openWriterForDay(LocalDate day) throws IOException {
        String filename = "weather-" + day.format(DATE_FMT) + ".log";
        Path   logFile  = logsDir.resolve(filename);

        // append=true para no borrar logs de sesiones anteriores del mismo día
        writer = Files.newBufferedWriter(logFile,
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);

        writer.write("──────────────────────────────────────────");
        writer.newLine();
        writer.write("  Sesión iniciada: " + LocalTime.now().format(TIME_FMT));
        writer.newLine();
        writer.write("──────────────────────────────────────────");
        writer.newLine();
        writer.flush();
    }

    private synchronized void close() {
        write("BOOT ", "Servidor detenido.");
        closeQuietly();
    }

    private void closeQuietly() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    // ── Resolución de la carpeta logs/ ────────────────────────

    /**
     * Resuelve la carpeta {@code logs/} en el mismo directorio que el JAR ejecutable.
     * Si no se puede determinar (e.g. ejecutando desde IDE), usa el directorio de trabajo.
     */
    private static Path resolveLogsDir() {
        try {
            Path jarPath = Path.of(
                FileLogView.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
            );
            // Si es un .jar, sube al directorio que lo contiene
            Path base = Files.isRegularFile(jarPath) ? jarPath.getParent() : jarPath;
            return base.resolve("logs");
        } catch (URISyntaxException | NullPointerException e) {
            // Fallback: directorio de trabajo
            return Path.of(System.getProperty("user.dir"), "logs");
        }
    }
}
