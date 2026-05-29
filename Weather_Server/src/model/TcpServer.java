package model;

import presenter.ServerPresenter;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * Capa de Infrastructure: gestiona el ServerSocket TCP.
 * No contiene lógica de negocio ni de presentación.
 *
 * Responsabilidades:
 *  - Abrir el puerto y aceptar conexiones.
 *  - Leer la primera línea para identificar el tipo de cliente.
 *  - Delegar al handler correcto (SensorHandler / AdminHandler).
 *  - Gestionar el pool de hilos y el shutdown hook.
 */
public class TcpServer {

    private final ServerPresenter presenter;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;

    public TcpServer(ServerPresenter presenter) {
        this.presenter = presenter;
    }

    public void start(int port) {
        presenter.onServerStarted(port);
        startHeartbeat();
        registerShutdownHook();
        acceptLoop(port);
    }

    // ── Heartbeat ────────────────────────────────────────────

    private void startHeartbeat() {
        Executors.newSingleThreadScheduledExecutor()
                 .scheduleAtFixedRate(presenter::checkStaleSensors, 5, 5, TimeUnit.SECONDS);
    }

    // ── Bucle de aceptación ──────────────────────────────────

    private void acceptLoop(int port) {
        try {
            serverSocket = new ServerSocket(port);
            while (!serverSocket.isClosed()) {
                try {
                    Socket sock = serverSocket.accept();
                    pool.submit(() -> identify(sock));
                } catch (IOException e) {
                    if (!serverSocket.isClosed())
                        presenter.onServerError("Accept: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            presenter.onServerError(e.getMessage());
        }
    }

    // ── Identificación del cliente ───────────────────────────

    private void identify(Socket sock) {
        String ip = sock.getInetAddress().getHostAddress();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(sock.getInputStream()));
            PrintWriter writer = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(sock.getOutputStream())), true);

            String firstLine = reader.readLine();
            if (firstLine == null) { sock.close(); return; }
            firstLine = firstLine.trim();

            Runnable handler = presenter.isAdminHandshake(firstLine)
                    ? new AdminHandler(sock, reader, writer, ip, presenter)
                    : new SensorHandler(sock, reader, firstLine, ip, presenter);

            pool.submit(handler);

        } catch (IOException e) {
            presenter.onServerError("Identify " + ip + ": " + e.getMessage());
        }
    }

    // ── Shutdown ──────────────────────────────────────────────

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pool.shutdownNow();
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        }));
    }
}
