package model.persistance;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import model.PlantSlot;
import model.SensorData;
import presenter.ServerPresenter;

/**
 * Gestor de persistencia en archivos de texto para cada ESP32 o simulador.
 *
 * Cada dispositivo conectado tiene su propio archivo de registro individual
 * dentro de la carpeta {@code files/<nombre_dispositivo>.txt}.
 *
 * Registra en cada lectura recibida:
 *  - Fecha y hora exacta.
 *  - Variables ambientales globales: Temperatura (°C), Humedad relativa (%),
 *    Sensación térmica / Índice de calor (°C), Señal WiFi (RSSI en dBm) y contador de mensajes.
 *  - Telemetría detallada de cada uno de los 4 slots: Especie asignada (o "Libre"),
 *    humedad de sustrato (%) y timestamp del último riego.
 */
public class FileManager {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Object FILE_LOCK = new Object();

    private final Path filesDir;

    public FileManager() {
        this(resolveFilesDir());
    }

    public FileManager(Path filesDir) {
        this.filesDir = filesDir;
        try {
            Files.createDirectories(this.filesDir);
        } catch (IOException e) {
            System.err.println("[FileManager] Error creando directorio de archivos de sensores: " + e.getMessage());
        }
    }

    /**
     * Agrega el registro de telemetría más reciente al archivo de texto individual del dispositivo.
     * Si el archivo no existe, lo crea e inicializa con encabezado descriptivo.
     *
     * @param s          SensorData con los datos ya actualizados
     * @param presenter  Presenter para canalizar avisos de error
     */
    public void addSensorData(SensorData s, ServerPresenter presenter) {
        if (s == null) return;

        // Nombre de archivo seguro (evita caracteres ilegales en Windows/Linux)
        String rawName = (s.getName() != null && !s.getName().isBlank()) ? s.getName() : s.id;
        String safeName = rawName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        Path filePath = filesDir.resolve(safeName + ".txt");

        String entryLine = buildRecordLine(s);

        synchronized (FILE_LOCK) {
            try {
                boolean isNewFile = !Files.exists(filePath);
                try (BufferedWriter writer = Files.newBufferedWriter(filePath,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {

                    if (isNewFile) {
                        writer.write("# ==============================================================================");
                        writer.newLine();
                        writer.write(String.format("# REGISTRO DE TELEMETRÍA INDIVIDUAL — DISPOSITIVO: %s", safeName));
                        writer.newLine();
                        writer.write(String.format("# Identificador: %s | Dirección IP: %s | Conectado: %s",
                                s.id, s.ip, LocalDate.now() + " " + LocalTime.now().format(TIME_FMT)));
                        writer.newLine();
                        writer.write("# Columnas: Fecha | Hora | Ambiente Global (Temp, Hum, Sensación, RSSI, Msg) | Slots 0..3 (Planta, Humedad, Riego)");
                        writer.newLine();
                        writer.write("# ==============================================================================");
                        writer.newLine();
                    }

                    writer.write(entryLine);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                if (presenter != null) {
                    presenter.onErrorLog("Persistencia", "Error al escribir registro de " + safeName + ": " + e.getMessage());
                } else {
                    System.err.println("[FileManager] Error escribiendo en " + filePath + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Construye la línea formateada de telemetría completa para un sensor.
     */
    public static String buildRecordLine(SensorData s) {
        StringBuilder sb = new StringBuilder();

        // 1. Timestamp
        String dateStr = LocalDate.now().toString();
        String timeStr = LocalTime.now().format(TIME_FMT);
        sb.append(String.format("Date: %s | Hour: %s", dateStr, timeStr));

        // 2. Ambiente Global del Dispositivo
        float temp = Float.isNaN(s.getTemp()) ? 0f : s.getTemp();
        float hum = Float.isNaN(s.getHum()) ? 0f : s.getHum();
        float hi = Float.isNaN(s.getHeatIndex()) ? 0f : s.getHeatIndex();
        sb.append(String.format(Locale.ROOT,
                " | Ambiente: [Temp: %.1f°C, Hum: %.1f%%, Sensacion: %.1f°C, RSSI: %d dBm, MsgCount: %d]",
                temp, hum, hi, s.getRssi(), s.getMsgCount()));

        // 3. Telemetría de los 4 Slots
        sb.append(" | Slots: [");
        PlantSlot[] plants = s.getPlants();
        for (int i = 0; i < plants.length; i++) {
            if (i > 0) sb.append(" | ");
            PlantSlot ps = plants[i];
            sb.append("Slot ").append(i).append(": ");
            if (ps == null || ps.isEmpty()) {
                sb.append("Libre (Hum: 0.0%)");
            } else {
                String wateredStr = ps.getLastWatered() > 0 ? (ps.getLastWatered() + " ms") : "nunca";
                sb.append(String.format(Locale.ROOT, "%s (Hum: %.1f%%, UltRiego: %s)",
                        ps.getPlantKey(), ps.getHumidity(), wateredStr));
            }
        }
        sb.append("]");

        return sb.toString();
    }

    /**
     * Resuelve inteligentemente la ruta de la carpeta {@code files/} para guardar los logs de sensores.
     */
    public static Path resolveFilesDir() {
        // 1. Variable de entorno
        String custom = System.getenv("SENSORS_FILES_DIR");
        if (custom != null && !custom.isBlank()) {
            Path p = Path.of(custom);
            try { Files.createDirectories(p); return p.toAbsolutePath().normalize(); } catch (IOException ignored) {}
        }

        // 2. Comprobar si existe la carpeta Weather_Server/files
        Path pWeatherServer = Path.of("Weather_Server", "files");
        if (Files.exists(pWeatherServer) && Files.isDirectory(pWeatherServer)) {
            return pWeatherServer.toAbsolutePath().normalize();
        }

        // 3. Comprobar si existe files/ local
        Path pLocal = Path.of("files");
        if (Files.exists(pLocal) && Files.isDirectory(pLocal)) {
            return pLocal.toAbsolutePath().normalize();
        }

        // 4. Ubicación junto al CodeSource (JAR o bin)
        try {
            Path jarPath = Path.of(FileManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path base = Files.isRegularFile(jarPath) ? jarPath.getParent() : jarPath;
            Path candidate = base.resolve("files");
            if (Files.exists(candidate)) return candidate.toAbsolutePath().normalize();

            if (base.getParent() != null) {
                Path cand2 = base.getParent().resolve("files");
                if (Files.exists(cand2)) return cand2.toAbsolutePath().normalize();

                Path cand3 = base.getParent().resolve("Weather_Server").resolve("files");
                if (Files.exists(cand3)) return cand3.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {}

        // 5. Fallback por defecto preferente
        Path fallback = Files.exists(Path.of("Weather_Server"))
                ? Path.of("Weather_Server", "files")
                : Path.of("files");
        try { Files.createDirectories(fallback); } catch (IOException ignored) {}
        return fallback.toAbsolutePath().normalize();
    }
}
