package model;

import java.io.*;
import java.net.Socket;
import presenter.ServerPresenter;

/**
 * Handler de red para clientes Sensor (ESP32).
 * Lee líneas TCP y las entrega al Presenter; no interpreta JSON.
 */
public class SensorHandler implements Runnable {

    private final Socket          socket;
    private final BufferedReader  reader;
    private final String          ip;
    private final String          firstLine;
    private final ServerPresenter presenter;

    public SensorHandler(Socket socket, BufferedReader reader,
                         String firstLine, String ip,
                         ServerPresenter presenter) {
        this.socket    = socket;
        this.reader    = reader;
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
            presenter.onSensorDisconnected(sensor);
            closeQuietly();
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
