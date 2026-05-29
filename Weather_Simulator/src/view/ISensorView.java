package view;

import java.awt.Color;

/**
 * CONTRATO DE VISTA (interfaz ).
 * El Presenter solo interactúa con esta interfaz; nunca con clases Swing directamente.
 */
public interface ISensorView {

    // ── Valores del sensor ───────────────────────────────────
    void setTemperatureDisplay(float value, Color color);
    void setHumidityDisplay(float value);
    void setHeatIndexDisplay(float value, Color color);
    void setMessageCount(int count);
    void setStatusText(String text);

    // ── Estado de conexión ───────────────────────────────────
    void showConnected(String host, int port);
    void showDisconnected();

    // ── Sincronización de controles ──────────────────────────
    void syncControls(float temperature, float humidity);

    // ── Log ─────────────────────────────────────────────────
    void appendLog(String message, Color color);

    // ── Lectura de campos de conexión ────────────────────────
    String getHostInput();
    String getPortInput();
    String getNameInput();
}
