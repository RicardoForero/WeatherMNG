package view;

import model.SensorData;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Vista concreta: salida por consola (stdout).
 * Implementa IServerView; no contiene lógica de negocio.
 */
public class ConsoleView implements IServerView {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void log(String message) {
        System.out.println("[" + LocalTime.now().format(FMT) + "] " + message);
    }

    @Override
    public void onSensorConnected(SensorData s, int total) {
        log("✓ Sensor conectado: " + s.ip + " | Total sensores: " + total);
    }

    @Override
    public void onSensorDisconnected(SensorData s, int active) {
        log("✗ Sensor desconectado: " + s.ip + " | Activos: " + active);
    }

    @Override
    public void onReadingReceived(SensorData s) {
        log(String.format("  ← %s  T=%.1f°  H=%.0f%%  HI=%.1f°  RSSI=%d",
                s.getName(), s.getTemp(), s.getHum(), s.getHeatIndex(), s.getRssi()));
    }

    @Override
    public void onSensorStale(SensorData s) {
        log("! Sensor sin respuesta: " + s.getName() + " (" + s.ip + ")");
    }

    @Override
    public void onAdminConnected(String ip, int total) {
        log("★ Admin conectado: " + ip + " | Total admins: " + total);
    }

    @Override
    public void onAdminDisconnected(String ip, int remaining) {
        log("★ Admin desconectado: " + ip + " | Admins restantes: " + remaining);
    }

    @Override
    public void onError(String context, String message) {
        log("! " + context + ": " + message);
    }

    @Override
    public void onServerStarted(int port) {
        log("✓ Servidor activo en :" + port);
        log("  Protocolo: primera línea = \"ADMIN_v1\" → Admin | JSON → Sensor");
    }
}
