# WeatherMNG: Distributed Environmental Monitoring System

WeatherMNG is a high-performance, concurrent **Client-Server architecture** implemented in Java, designed to simulate and manage a network of distributed environmental sensors. The system leverages a custom TCP/IP communication protocol for real-time data ingestion, telemetry processing, and administrative visualization.

## 🏗 System Architecture

The ecosystem consists of three decoupled components:

1.  **WeatherServer (Central Hub):**
    - Multi-threaded TCP server utilizing `ExecutorService` for high-throughput socket management.
    - Implements an identification protocol to differentiate between **Sensor Nodes** (Data Producers) and **Admin Dashboards** (Data Consumers).
    - Features thread-safe state management for up to 20 concurrent sensors and broadcast mechanisms for real-time telemetry distribution.

2.  **ESP32Simulator (Edge Node Emulator):**
    - Emulates the behavior of an ESP32 microcontroller with integrated DHT11 sensors.
    - Features a high-fidelity GUI for interactive data manipulation (Knob controls and XY coordinate mapping).
    - Periodically transmits JSON-serialized telemetry packets containing Temperature, Humidity, Heat Index, and RSSI (Signal Strength).

3.  **AdminDashboard (Management Console):**
    - A dedicated administrative client that establishes an `ADMIN_v1` handshake with the server.
    - Dynamically renders incoming data streams using a real-time responsive UI.
    - Provides a holistic view of the network topology and sensor health metrics.

## 🛠 Technical Specifications

- **Networking:** Synchronous and Asynchronous TCP Sockets.
- **Data Interchange:** Raw JSON string parsing and serialization.
- **Concurrency:** Atomic variables (`AtomicInteger`), `CopyOnWriteArrayList`, and thread-pooling to prevent race conditions.
- **GUI Engine:** Java Swing with custom rendering and `FlowLayout` extensions (WrapLayout).

## 🚀 Installation & Deployment

### Prerequisites
- **Java Development Kit (JDK) 8 or higher.**
- **Terminal/Command Prompt access.**

### Step 1: Clone the Repository
```bash
git clone [https://github.com/RicardoForero/WeatherMNG.git](https://github.com/RicardoForero/WeatherMNG.git)
cd WeatherMNG