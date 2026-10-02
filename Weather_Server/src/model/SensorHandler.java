package model;

import java.io.*;
import java.net.Socket;
import presenter.ServerPresenter;

/**
 * Handler de red para clientes Sensor (ESP32).
 * Lee líneas TCP y las entrega al Presenter; no interpreta JSON.
 *
 * Desde la versión multi-planta, también es full-duplex: registra un
 * "command sink" en el {@link SensorData} para que el Presenter pueda
 * reenviar comandos del Admin (renombrar, asignar planta, regar) de vuelta
 * al ESP32/Simulador dueño de esta conexión.
 */
public class SensorHandler implements Runnable {

    private final Socket          socket;
    private final BufferedReader  reader;
    private final PrintWriter     writer;
    private final String          firstLine;
    private final String          ip;
    private final ServerPresenter presenter;

    public SensorHandler(Socket socket, BufferedReader reader, PrintWriter writer,
                         String firstLine, String ip,
                         ServerPresenter presenter) {
        this.socket    = socket;
        this.reader    = reader;
        this.writer    = writer;
        this.firstLine = firstLine;
        this.ip        = ip;
        this.presenter = presenter;
    }

    @Override
    public void run() {
        SensorData sensor = (SensorData) presenter.onSensorConnected(ip, socket.getPort());
        if (sensor == null) {
            closeQuietly(); // rechazado por límite
            return;
        }

        // Habilita que el Presenter envíe comandos (riego, asignar planta, renombrar)
        // de vuelta a este ESP32/Simulador a través del mismo socket.
        sensor.setCommandSink(this::sendCommand);

        // Procesar la primera línea ya leída durante identificación
        presenter.onSensorReading(sensor, firstLine);

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) presenter.onSensorReading(sensor, trimmed);
            }
        } catch (IOException e) {
            presenter_onError("IO", e.getMessage());
        } finally {
            sensor.setCommandSink(null);
            presenter.onSensorDisconnected(sensor);
            closeQuietly();
        }
    }

    /** Envía una línea de comando JSON al ESP32/Simulador. Seguro ante fallos de E/S. */
    private void sendCommand(String json) {
        try {
            writer.println(json);
        } catch (Exception ignored) {
            // El socket pudo haberse cerrado entre tanto; el hilo de lectura lo detectará.
        }
    }

    // Método puente para errores de red no críticos
    private void presenter_onError(String context,String msg) {
       presenter.onErrorLog(context, msg);
    }

    private void closeQuietly() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
