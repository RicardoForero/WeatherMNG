package model;

import presenter.ServerPresenter;

import java.io.*;
import java.net.Socket;

/**
 * Handler de red para clientes Admin.
 * Solo gestiona E/S TCP; delega toda la lógica al Presenter.
 *
 * Desde la versión multi-planta, el canal es bidireccional: el Admin puede
 * enviar líneas de comando JSON ({@code {"cmd":"rename",...}},
 * {@code {"cmd":"assignPlant",...}}, {@code {"cmd":"water",...}}) que se
 * delegan al Presenter para que las aplique y, si corresponde, las reenvíe
 * al ESP32/Simulador correspondiente.
 */
public class AdminHandler implements Runnable {

    private final Socket          socket;
    private final BufferedReader  reader;
    private final PrintWriter     writer;
    private final String          ip;
    private final ServerPresenter presenter;

    public AdminHandler(Socket socket, BufferedReader reader,
                        PrintWriter writer, String ip,
                        ServerPresenter presenter) {
        this.socket    = socket;
        this.reader    = reader;
        this.writer    = writer;
        this.ip        = ip;
        this.presenter = presenter;
    }

    @Override
    public void run() {
        presenter.onAdminConnected(writer, ip);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) presenter.onAdminCommand(trimmed);
            }
        } catch (IOException ignored) {
        } finally {
            presenter.onAdminDisconnected(writer, ip);
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
