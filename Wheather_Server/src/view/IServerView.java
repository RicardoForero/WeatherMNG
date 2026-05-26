package view;

import model.SensorData;

/**
 * Contrato de Vista en MVP.
 * El Presenter conoce esta interfaz, no la implementación concreta.
 * Permite sustituir la consola por una GUI/web sin tocar el Presenter.
 */
public interface IServerView {

    /** Mensaje de log general con timestamp. */
    void log(String message);

    /** Notifica que un sensor se conectó. */
    void onSensorConnected(SensorData sensor, int totalSensors);

    /** Notifica que un sensor se desconectó. */
    void onSensorDisconnected(SensorData sensor, int activeSensors);

    /** Notifica que llegó una lectura válida. */
    void onReadingReceived(SensorData sensor);

    /** Notifica que un sensor quedó sin respuesta. */
    void onSensorStale(SensorData sensor);

    /** Notifica que un admin se conectó. */
    void onAdminConnected(String ip, int totalAdmins);

    /** Notifica que un admin se desconectó. */
    void onAdminDisconnected(String ip, int remainingAdmins);

    /** Notifica un error de red o protocolo. */
    void onError(String context, String message);

    /** Notifica el inicio exitoso del servidor. */
    void onServerStarted(int port);
}
