import java.io.*;
import java.net.*;
import java.util.*;

public class Simulator {
    // ── Configuración de red (ajusta según tu servidor) ───────────────────
    private static final String SERVER_HOST = "localhost"; // IP del PC donde corre WeatherServer
    private static final int SERVER_PORT = 2361;
    private static final int MAX_SLOTS = 4;

    private static String deviceName = "ESP32-PLANT-01";
    private static int msgCount = 0;
    private static long startTime = System.currentTimeMillis();

    // Estructura de slot de planta
    static class PlantSlot {
        int slot;
        String plantKey;
        double humMin, humMax, humOpt;
        double humidity;
        long regadoEn;

        PlantSlot(int slot, String plantKey, double humMin, double humMax, double humOpt, double humidity) {
            this.slot = slot;
            this.plantKey = plantKey;
            this.humMin = humMin;
            this.humMax = humMax;
            this.humOpt = humOpt;
            this.humidity = humidity;
            this.regadoEn = 0;
        }
    }

    private static List<PlantSlot> plants = new ArrayList<>();

    public static void main(String[] args) {
        // Inicializar slots (Slot 0 con planta por defecto)
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (i == 0) {
                plants.add(new PlantSlot(0, "Potos", 35.0, 60.0, 45.0, 48.5));
            } else {
                plants.add(new PlantSlot(i, "", 0.0, 100.0, 50.0, 0.0));
            }
        }

        while (true) {
            try {
                System.out.println("[TCP] Conectando a " + SERVER_HOST + ":" + SERVER_PORT + "...");
                Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
                System.out.println("[TCP] ¡Conectado exitosamente al servidor!");

                // Hilo en segundo plano para escuchar comandos entrantes del servidor
                Thread readerThread = new Thread(() -> listenToServer(socket));
                readerThread.setDaemon(true);
                readerThread.start();

                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                Random random = new Random();

                while (socket.isConnected() && !socket.isClosed()) {
                    msgCount++;
                    long uptimeMs = System.currentTimeMillis() - startTime;

                    double temp = 21.0 + (26.0 - 21.0) * random.nextDouble();
                    double hum = 55.0 + (65.0 - 55.0) * random.nextDouble();
                    double indiceCalor = temp + 0.4;

                    // Simulación de reducción gradual de humedad en el sustrato
                    for (PlantSlot p : plants) {
                        if (!p.plantKey.isEmpty()) {
                            p.humidity = Math.max(0.0, p.humidity - 0.1);
                        }
                    }

                    // Construcción del JSON respetando el protocolo del firmware
                    StringBuilder json = new StringBuilder();
                    json.append("{");
                    json.append("\"id\":").append(msgCount).append(",");
                    json.append("\"dispositivo\":\"").append(deviceName).append("\",");
                    json.append("\"timestamp\":").append(uptimeMs).append(",");
                    json.append("\"sensores\":{");
                    json.append("\"temperatura\":").append(String.format(Locale.US, "%.1f", temp)).append(",");
                    json.append("\"humedad\":").append(String.format(Locale.US, "%.1f", hum)).append(",");
                    json.append("\"indice_calor\":").append(String.format(Locale.US, "%.1f", indiceCalor));
                    json.append("},");
                    json.append("\"red\":{");
                    json.append("\"rssi\":-58,");
                    json.append("\"ip\":\"192.168.1.105\"");
                    json.append("},");
                    json.append("\"plantas\":[");
                    for (int i = 0; i < plants.size(); i++) {
                        PlantSlot p = plants.get(i);
                        json.append("{");
                        json.append("\"slot\":").append(p.slot).append(",");
                        json.append("\"plantKey\":\"").append(p.plantKey).append("\",");
                        json.append("\"humedad\":").append(String.format(Locale.US, "%.1f", p.humidity)).append(",");
                        json.append("\"regadoEn\":").append(p.regadoEn);
                        json.append("}");
                        if (i < plants.size() - 1) json.append(",");
                    }
                    json.append("]}");

                    // Envío con salto de línea (newline-terminated)[cite: 5]
                    writer.println(json.toString());
                    System.out.println("[TX #" + msgCount + "] T=" + String.format(Locale.US, "%.1f", temp) + "°C H=" + String.format(Locale.US, "%.1f", hum) + "% | Slot 0 Hum=" + String.format(Locale.US, "%.1f", plants.get(0).humidity) + "%");

                    Thread.sleep(2000);
                }
            } catch (IOException | InterruptedException e) {
                System.out.println("[TCP] Conexión perdida: " + e.getMessage() + ". Reconectando en 3 segundos...");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private static void listenToServer(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    System.out.println("\n[CMD Recibido] " + line);
                    processCommand(line);
                }
            }
        } catch (IOException e) {
            System.out.println("[TCP] Error leyendo comandos del servidor: " + e.getMessage());
        }
    }

    private static void processCommand(String json) {
        if (json.contains("\"cmd\":\"rename\"")) {
            String newName = extractString(json, "\"name\":\"");
            if (newName != null) {
                deviceName = newName;
                System.out.println("-> Dispositivo renombrado a: " + deviceName);
            }
        } else if (json.contains("\"cmd\":\"assignPlant\"")) {
            int slot = extractInt(json, "\"slot\":");
            if (slot >= 0 && slot < MAX_SLOTS) {
                String plantKey = extractString(json, "\"plantKey\":\"");
                PlantSlot p = plants.get(slot);
                p.plantKey = plantKey != null ? plantKey : "";
                if (p.plantKey.isEmpty()) {
                    p.humMin = 0.0;
                    p.humMax = 100.0;
                    p.humOpt = 50.0;
                    p.regadoEn = 0;
                } else {
                    p.humMin = extractDouble(json, "\"humMin\":", 0.0);
                    p.humMax = extractDouble(json, "\"humMax\":", 100.0);
                    p.humOpt = extractDouble(json, "\"humOpt\":", 50.0);
                }
                System.out.println("-> Slot " + slot + " actualizado con planta '" + p.plantKey + "'");
            }
        } else if (json.contains("\"cmd\":\"water\"")) {
            int slot = extractInt(json, "\"slot\":");
            if (slot >= 0 && slot < MAX_SLOTS) {
                PlantSlot p = plants.get(slot);
                if (!p.plantKey.isEmpty()) {
                    p.regadoEn = System.currentTimeMillis();
                    p.humidity = Math.min(100.0, p.humidity + 35.0);
                    System.out.println("-> [AGUA] Riego ejecutado en slot " + slot + " ('" + p.plantKey + "')");
                } else {
                    System.out.println("-> [AGUA] Advertencia: Slot " + slot + " sin planta asignada.");
                }
            }
        }
    }

    private static int extractInt(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return -1;
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (Exception e) { return -1; }
    }

    private static double extractDouble(String json, String key, double defaultVal) {
        int idx = json.indexOf(key);
        if (idx == -1) return defaultVal;
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        try { return Double.parseDouble(json.substring(start, end)); } catch (Exception e) { return defaultVal; }
    }

    private static String extractString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int start = idx + key.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }
}