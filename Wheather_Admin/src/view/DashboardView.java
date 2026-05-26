package view;

import model.SensorData;

/**
 * Contrato que la Vista expone al Presenter.
 * Todas las llamadas se ejecutan en el EDT (Swing Thread).
 */
public interface DashboardView {

    /* ── Conexión ─────────────────────────────────────── */
    void showConnected(String host, int port);
    void showDisconnected();
    void showConnectionError(String message);

    /* ── Sensores ─────────────────────────────────────── */
    void addSensorCard(SensorData sd);
    void removeSensorCard(String id);
    void clearAllCards();
    void renameSensorCard(String id, String newName);

    /* ── Estadísticas header ──────────────────────────── */
    void updateStats(long onlineCount, int totalMessages);

    /* ── Barra de estado ──────────────────────────────── */
    void setStatus(String message);

    /* ── Log de eventos ───────────────────────────────── */
    void logEvent(String entry);
}
