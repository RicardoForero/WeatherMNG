package presenter;

import model.SensorMath;
import model.SensorModel;
import view.ISensorView;
import view.SensorView;

import javax.swing.*;
import java.io.IOException;

import static model.AppColors.*;
import static model.SensorModel.DEFAULT_PORT;
import static model.SensorModel.SEND_INTERVAL_MS;

/**
 * PRESENTER — Orquesta Model y View.
 * No depende de Swing directamente (salvo SwingUtilities para el render loop).
 */
public class SensorPresenter {

    private final SensorModel model;
    private final ISensorView view;

    public SensorPresenter(SensorModel model, ISensorView view) {
        this.model = model;
        this.view  = view;
        wireCallbacks();
        startRenderLoop();
    }

    // ── Conectar callbacks Model → View y View → Presenter ──

    private void wireCallbacks() {
        // Model notifica al Presenter cuando envía un mensaje
        model.setOnMessageSent(count -> {
            view.setMessageCount(count);
        });

        // Model notifica log
        model.setOnLogMessage(msg -> view.appendLog(msg, TEXT));

        // Model notifica desconexión inesperada
        model.setOnDisconnected(() -> {
            view.showDisconnected();
            view.appendLog("— Desconectado", MUTED);
            view.setStatusText("Desconectado. Presiona CONECTAR para enviar datos.");
        });

        // View delega acción de conexión al Presenter
        if (view instanceof SensorView sv) {
            sv.setOnConnectToggle(this::toggleConnection);
            sv.setOnTempChanged(this::onTemperatureChanged);
            sv.setOnHumChanged(this::onHumidityChanged);
        }
    }

    // ── Acciones del usuario ─────────────────────────────────

    private void toggleConnection() {
        if (model.isConnected()) disconnect();
        else                     connect();
    }

    private void connect() {
        String host = view.getHostInput();
        String portStr = view.getPortInput();
        int port;
        try { port = Integer.parseInt(portStr); }
        catch (NumberFormatException e) { port = DEFAULT_PORT; }

        String name = view.getNameInput();
        if (name.isEmpty()) name = "ESP32-SIM-01";

        try {
            model.connect(host, port, name);
            view.showConnected(host, port);
            view.appendLog("✓ Conectado a " + host + ":" + port, ACCENT);
            view.setStatusText("Conectado · Enviando cada " + SEND_INTERVAL_MS / 1000 + "s");
        } catch (IOException ex) {
            view.appendLog("✗ Error: " + ex.getMessage(), HOT);
            view.setStatusText("Error: " + ex.getMessage());
        }
    }

    private void disconnect() {
        model.disconnect();
        view.showDisconnected();
        view.appendLog("— Desconectado", MUTED);
        view.setStatusText("Desconectado. Presiona CONECTAR para enviar datos.");
    }

    private void onTemperatureChanged(float value) {
        model.setTemperature(value);
        view.syncControls(model.getTemperature(), model.getHumidity());
    }

    private void onHumidityChanged(float value) {
        model.setHumidity(value);
        view.syncControls(model.getTemperature(), model.getHumidity());
    }

    // ── Render loop (actualiza etiquetas y widgets ~30 fps) ──

    private void startRenderLoop() {
        new Timer(33, e -> refreshView()).start();
    }

    private void refreshView() {
        float t  = model.getTemperature();
        float h  = model.getHumidity();
        float hi = SensorMath.computeHeatIndex(t, h);

        view.setTemperatureDisplay(t, SensorMath.tempColor(t));
        view.setHumidityDisplay(h);
        view.setHeatIndexDisplay(hi, SensorMath.tempColor(hi));
        view.setMessageCount(model.getMessagesSent());
        view.setStatusText(String.format("T=%.1f°C  H=%.1f%%  HI=%.1f°C  |  %s",
            t, h, hi,
            model.isConnected()
                ? "Enviando a " + model.getHost() + ":" + model.getPort()
                : "Desconectado"));
        view.syncControls(t, h);
    }
}
