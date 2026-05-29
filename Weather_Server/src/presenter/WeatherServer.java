package presenter;

import model.log.FileLogView;
import model.log.IServerView;
import model.TcpServer;


/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║   WeatherServer — Punto de entrada (MVP refactorizado)  ║
 * ╠══════════════════════════════════════════════════════════╣
 * ║  Compilar:                                              ║
 * ║    javac -d out $(find . -name "*.java")                ║
 * ║  Ejecutar:                                              ║
 * ║    java -cp out legacy.WeatherServer                    ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * Arquitectura MVP:
 *
 *   ┌─────────────────────────────────────────────────┐
 *   │  Infrastructure (TcpServer)                     │
 *   │  · ServerSocket, accept loop, pool de hilos     │
 *   │  · SensorHandler  ─ lee TCP, entrega al Pres.  │
 *   │  · AdminHandler   ─ mantiene conexión abierta  │
 *   └────────────────────┬────────────────────────────┘
 *                        │ llama a
 *   ┌────────────────────▼────────────────────────────┐
 *   │  Presenter (ServerPresenter)                    │
 *   │  · Lógica de negocio: límites, heartbeat, etc.  │
 *   │  · Gestiona sensores y admins                   │
 *   │  · Delega feedback → IServerView                │
 *   └──────────┬──────────────────────┬───────────────┘
 *              │ notifica             │ usa
 *   ┌──────────▼──────────┐  ┌───────▼──────────────┐
 *   │  View (IServerView) │  │  Model / Protocol    │
 *   │  ConsoleView        │  │  SensorData          │
 *   │  (sustituible por   │  │  SensorReading       │
 *   │   GUI, web, etc.)   │  │  JsonParser          │
 *   └─────────────────────┘  │  SensorSerializer    │
 *                             └──────────────────────┘
 */
public class WeatherServer {
 
      public static void main(String[] args) {
        IServerView view = new FileLogView();
        ServerPresenter presenter = new ServerPresenter(view);
        TcpServer       server    = new TcpServer(presenter);
 
        server.start(ServerPresenter.PORT);
    }
}
