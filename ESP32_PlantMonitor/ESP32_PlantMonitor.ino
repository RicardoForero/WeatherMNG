/*
 * ═══════════════════════════════════════════════════════════════════════
 *  ESP32 PLANT MONITOR — Firmware v4
 *  Compatible con WeatherServer v4 (protocolo multi-planta)
 *
 *  Funcionalidades:
 *  ─ Lectura de temperatura y humedad ambiental (DHT22/DHT11).
 *  ─ Hasta 4 sensores de humedad de sustrato (ADC analógico).
 *  ─ Envío periódico de lecturas al servidor TCP (cada SEND_INTERVAL_MS).
 *  ─ Canal full-duplex: recibe comandos del servidor (assignPlant, water,
 *    rename) y los aplica inmediatamente en memoria y NVS (Flash).
 *  ─ Reconexión automática WiFi y TCP con backoff exponencial.
 *    Si el servidor cae, el ESP32 sigue midiendo y loggea en Serial;
 *    cuando el servidor vuelve, retoma el envío automáticamente.
 *  ─ Persistencia en NVS (Preferences): nombre del dispositivo y plantas
 *    asignadas a cada slot se conservan tras un corte de luz o reboot.
 *  ─ Riego simulado (señal digital a relé/bomba, o solo log si no hay hw).
 *
 *  Pines por defecto (ajusta en config.h o en las constantes de abajo):
 *    DHT22         → GPIO 4
 *    Soil sensor 0 → GPIO 34 (ADC1, solo lectura)
 *    Soil sensor 1 → GPIO 35 (ADC1)
 *    Soil sensor 2 → GPIO 32 (ADC1)
 *    Soil sensor 3 → GPIO 33 (ADC1)
 *    Relay slot 0  → GPIO 25  (HIGH = bomba ON)
 *    Relay slot 1  → GPIO 26
 *    Relay slot 2  → GPIO 27
 *    Relay slot 3  → GPIO 14
 *    LED estado    → GPIO 2   (built-in LED en la mayoría de devboards)
 *
 *  Librerías necesarias (instalar desde Library Manager):
 *    - DHT sensor library  (Adafruit)
 *    - Adafruit Unified Sensor
 *    - ArduinoJson  v6.x  (Benoit Blanchon)
 *
 *  PROTOCOLO (ver WeatherServer/src/model/SensorHandler.java):
 *  ESP32 → Server: JSON newline-terminated, campos:
 *    {"id":N,"dispositivo":"NAME","timestamp":MS,
 *     "sensores":{"temperatura":T,"humedad":H,"indice_calor":HI},
 *     "red":{"rssi":R,"ip":"IP"},
 *     "plantas":[{"slot":0,"plantKey":"Potos","humedad":42.3,"regadoEn":0}, ...]}
 *
 *  Server → ESP32: JSON newline-terminated, campo "cmd":
 *    {"cmd":"rename",      "name":"NuevoNombre"}
 *    {"cmd":"assignPlant", "slot":0,"plantKey":"Potos","humMin":35,"humMax":60,"humOpt":45}
 *    {"cmd":"water",       "slot":0}
 * ═══════════════════════════════════════════════════════════════════════
 */

// ── Librerías ──────────────────────────────────────────────────────────
#include <WiFi.h>
#include <Preferences.h>
#include <DHT.h>
#include <ArduinoJson.h>
#include <math.h>

// ── Credenciales WiFi (modifica aquí o usa config.h) ──────────────────
#ifndef WIFI_SSID
  #define WIFI_SSID   "WIFI-UPTC"
  // Ricardo Forero
  // CLARO-B750
#endif
#ifndef WIFI_PASS
  #define WIFI_PASS   ""
  // 1002460231
  // ueUFhJk9cG
#endif

// ── Servidor ───────────────────────────────────────────────────────────
#ifndef SERVER_HOST
  #define SERVER_HOST "10.200.37.20"   // IP del PC donde corre WeatherServer
  // 192.168.1.12
  // 192.168.128.15
#endif
#ifndef SERVER_PORT
  #define SERVER_PORT 2361
#endif

// ── Pines de hardware ──────────────────────────────────────────────────
#define DHT_PIN        4
#define DHT_TYPE       DHT11          // o DHT11

#define SOIL_PINS      {34, 35, 32, 33}   // ADC analógico (solo ADC1 en ESP32)
#define RELAY_PINS     {25, 26, 27, 14}   // Relé/bomba por slot (HIGH = ON)
#define LED_PIN        2                   // LED de estado onboard

// ── Calibración de sensores de suelo ──────────────────────────────────
// Ajusta estos valores según tu sensor específico:
//   SOIL_DRY  = lectura ADC cuando el sustrato está completamente seco
//   SOIL_WET  = lectura ADC cuando el sustrato está saturado de agua
#define SOIL_DRY  3120    // típico capacitivo: ~3200 / resistivo: ~4095
#define SOIL_WET   1180    // típico capacitivo: ~800  / resistivo: ~1000

// ── Temporización ──────────────────────────────────────────────────────
#define SEND_INTERVAL_MS     2000   // cada cuánto enviar lecturas al servidor
#define RECONNECT_MIN_MS     2000   // espera mínima entre reintentos de conexión
#define RECONNECT_MAX_MS    60000   // espera máxima (backoff exponencial)
#define WIFI_TIMEOUT_MS     15000   // tiempo máximo esperando WiFi en cada intento
#define WATER_RELAY_MS       3000   // tiempo que permanece encendida la bomba

// ── Riego automático local (independiente del servidor) ────────────────
// Si la humedad de un slot cae por debajo de humMin, el propio ESP32
// dispara el riego SIN esperar ningún comando del servidor. Esto permite
// que la planta se siga regando aunque el WeatherServer esté caído o no
// haya WiFi disponible.
#define AUTO_WATER_COOLDOWN_MS   (6UL * 60UL * 60UL * 1000UL)  // 6 h entre riegos automáticos por slot
#define AUTO_WATER_BOOT_GRACE_MS (10UL * 1000UL)                // 10 s de gracia tras arrancar

// ── Slots de planta ─────────────────────────────────────────────────────
#define MAX_SLOTS  4

// ── NVS namespace ──────────────────────────────────────────────────────
#define NVS_NS   "plantmon"

// ══════════════════════════════════════════════════════════════════════
//  ESTRUCTURAS DE DATOS
// ══════════════════════════════════════════════════════════════════════

struct PlantSlot {
    char    plantKey[48];   // clave del catálogo; "" = vacío
    float   humMin;
    float   humMax;
    float   humOpt;
    float   humidity;       // humedad actual de sustrato (%)
    long    lastWatered;    // epoch-millis del último riego; 0 = nunca
    bool    waterPending;   // bandera: riego solicitado pero aún no ejecutado
};

// ══════════════════════════════════════════════════════════════════════
//  VARIABLES GLOBALES
// ══════════════════════════════════════════════════════════════════════

// Hardware
DHT           dht(DHT_PIN, DHT_TYPE);
const int     soilPins[MAX_SLOTS]  = SOIL_PINS;
const int     relayPins[MAX_SLOTS] = RELAY_PINS;

// Estado del dispositivo
char          deviceName[48]       = "ESP32-PLANT-01";
PlantSlot     plants[MAX_SLOTS];
int           msgCount             = 0;
long          bootTimeMs           = 0;

// Red
WiFiClient    tcpClient;
bool          tcpConnected         = false;
unsigned long lastSendMs           = 0;
unsigned long reconnectDelayMs     = RECONNECT_MIN_MS;
unsigned long nextReconnectMs      = 0;

// Último riego (para apagar relé después de WATER_RELAY_MS)
unsigned long relayOnSince[MAX_SLOTS];
bool          relayActive[MAX_SLOTS];

// Persistencia
Preferences   prefs;

// Buffer de recepción TCP (para mensajes multi-fragmento)
String        rxBuffer;

// ══════════════════════════════════════════════════════════════════════
//  SETUP
// ══════════════════════════════════════════════════════════════════════

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("\n\n╔══════════════════════════════════╗");
    Serial.println("║  ESP32 Plant Monitor  v4         ║");
    Serial.println("╚══════════════════════════════════╝");

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);

    for (int i = 0; i < MAX_SLOTS; i++) {
        pinMode(relayPins[i], OUTPUT);
        digitalWrite(relayPins[i], LOW);
        relayActive[i] = false;
        relayOnSince[i] = 0;
    }

    dht.begin();
    initPlantSlots();
    loadFromNVS();
    bootTimeMs = millis();

    connectWiFi();
}

// ══════════════════════════════════════════════════════════════════════
//  LOOP
// ══════════════════════════════════════════════════════════════════════

void loop() {
    // 1. Mantener WiFi
    if (WiFi.status() != WL_CONNECTED) {
        tcpConnected = false;
        tcpClient.stop();
        connectWiFi();
        return;
    }

    // 2. Mantener conexión TCP al servidor
    if (!tcpConnected || !tcpClient.connected()) {
        tcpConnected = false;
        attemptTcpReconnect();
    }

    // 3. Leer comandos entrantes del servidor
    if (tcpConnected) {
        readServerCommands();
    }

    // 4. Enviar lecturas periódicas
    unsigned long now = millis();
    if (now - lastSendMs >= SEND_INTERVAL_MS) {
        lastSendMs = now;
        readSensors();

        // Riego automático por humedad baja: se evalúa SIEMPRE, haya o no
        // conexión con el servidor. La decisión de regar es 100% local.
        checkAutoWater();

        if (tcpConnected) {
            sendReading();
        } else {
            // Servidor caído: el ESP32 sigue midiendo, regando si hace
            // falta, y loggeando localmente.
            logSensorsSerial();
        }
    }

    // 5. Gestionar relés (apagar bomba tras WATER_RELAY_MS)
    handleRelays();

    // 6. Ejecutar riegos pendientes
    handlePendingWater();

    // 7. Parpadeo LED según estado
    blinkLed();

    delay(10);
}

// ══════════════════════════════════════════════════════════════════════
//  INICIALIZACIÓN Y PERSISTENCIA (NVS)
// ══════════════════════════════════════════════════════════════════════

void initPlantSlots() {
    for (int i = 0; i < MAX_SLOTS; i++) {
        plants[i].plantKey[0] = '\0';
        plants[i].humMin      = 0.0f;
        plants[i].humMax      = 100.0f;
        plants[i].humOpt      = 50.0f;
        plants[i].humidity    = 0.0f;
        plants[i].lastWatered = 0;
        plants[i].waterPending = false;
    }
}

void saveToNVS() {
    prefs.begin(NVS_NS, false);
    prefs.putString("devName", deviceName);
    for (int i = 0; i < MAX_SLOTS; i++) {
        char k[16];
        snprintf(k, sizeof(k), "pk%d", i);  prefs.putString(k, plants[i].plantKey);
        snprintf(k, sizeof(k), "mn%d", i);  prefs.putFloat(k,  plants[i].humMin);
        snprintf(k, sizeof(k), "mx%d", i);  prefs.putFloat(k,  plants[i].humMax);
        snprintf(k, sizeof(k), "op%d", i);  prefs.putFloat(k,  plants[i].humOpt);
        snprintf(k, sizeof(k), "lw%d", i);  prefs.putLong(k,   plants[i].lastWatered);
    }
    prefs.end();
    Serial.println("[NVS] Estado guardado en Flash.");
}

void loadFromNVS() {
    prefs.begin(NVS_NS, true);   // read-only
    if (prefs.isKey("devName")) {
        String n = prefs.getString("devName", deviceName);
        n.toCharArray(deviceName, sizeof(deviceName));
    }
    for (int i = 0; i < MAX_SLOTS; i++) {
        char k[16];
        snprintf(k, sizeof(k), "pk%d", i);
        if (prefs.isKey(k)) {
            String pk = prefs.getString(k, "");
            pk.toCharArray(plants[i].plantKey, sizeof(plants[i].plantKey));
            snprintf(k, sizeof(k), "mn%d", i); plants[i].humMin = prefs.getFloat(k, 0.0f);
            snprintf(k, sizeof(k), "mx%d", i); plants[i].humMax = prefs.getFloat(k, 100.0f);
            snprintf(k, sizeof(k), "op%d", i); plants[i].humOpt = prefs.getFloat(k, 50.0f);
            snprintf(k, sizeof(k), "lw%d", i); plants[i].lastWatered = prefs.getLong(k, 0);
        }
    }
    prefs.end();
    Serial.printf("[NVS] Cargado: nombre='%s'\n", deviceName);
    for (int i = 0; i < MAX_SLOTS; i++) {
        if (strlen(plants[i].plantKey) > 0)
            Serial.printf("[NVS]   Slot %d = '%s' (%.0f-%.0f%%)\n",
                i, plants[i].plantKey, plants[i].humMin, plants[i].humMax);
    }
}

// ══════════════════════════════════════════════════════════════════════
//  WIFI — con reconexión automática
// ══════════════════════════════════════════════════════════════════════

void connectWiFi() {
    if (WiFi.status() == WL_CONNECTED) return;

    Serial.printf("[WiFi] Conectando a '%s'", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASS);

    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED) {
        if (millis() - start > WIFI_TIMEOUT_MS) {
            Serial.println("\n[WiFi] Timeout. Reintentando en 5s…");
            WiFi.disconnect();
            delay(5000);
            return;
        }
        Serial.print(".");
        delay(500);
    }
    Serial.printf("\n[WiFi] Conectado. IP: %s\n", WiFi.localIP().toString().c_str());
    reconnectDelayMs = RECONNECT_MIN_MS;   // reset backoff
    nextReconnectMs  = 0;
}

// ══════════════════════════════════════════════════════════════════════
//  TCP — reconexión con backoff exponencial
// ══════════════════════════════════════════════════════════════════════

void attemptTcpReconnect() {
    unsigned long now = millis();
    if (now < nextReconnectMs) return;   // todavía esperando el backoff

    if (tcpClient.connected()) tcpClient.stop();

    Serial.printf("[TCP] Conectando a %s:%d …\n", SERVER_HOST, SERVER_PORT);
    if (tcpClient.connect(SERVER_HOST, SERVER_PORT)) {
        tcpConnected     = true;
        reconnectDelayMs = RECONNECT_MIN_MS;   // reset backoff al éxito
        rxBuffer         = "";
        Serial.println("[TCP] Conectado al servidor.");
        digitalWrite(LED_PIN, HIGH);
    } else {
        tcpConnected = false;
        // Backoff exponencial: duplicar la espera hasta el máximo
        reconnectDelayMs = min((unsigned long)RECONNECT_MAX_MS, reconnectDelayMs * 2);
        nextReconnectMs  = now + reconnectDelayMs;
        Serial.printf("[TCP] Fallo. Reintento en %.1fs\n", reconnectDelayMs / 1000.0);
        digitalWrite(LED_PIN, LOW);
    }
}

// ══════════════════════════════════════════════════════════════════════
//  LECTURA DE SENSORES
// ══════════════════════════════════════════════════════════════════════

float g_temp = NAN;
float g_hum  = NAN;
float g_hi   = NAN;

void readSensors() {
    // DHT22 / DHT11
    float t = dht.readTemperature();
    float h = dht.readHumidity();

    if (!isnan(t)) g_temp = t;
    if (!isnan(h)) g_hum  = h;

    if (!isnan(g_temp) && !isnan(g_hum)) {
        g_hi = computeHeatIndex(g_temp, g_hum);
    }

    // Sensores de humedad de sustrato (ADC)
    for (int i = 0; i < MAX_SLOTS; i++) {
        int raw = analogRead(soilPins[i]);
        
        // Convertir a porcentaje (0% = seco, 100% = saturado)
        float pct = map(raw, SOIL_DRY, SOIL_WET, 0, 100);
        pct = constrain(pct, 0.0f, 100.0f);
        plants[i].humidity = pct;
    }
}


// Índice de calor (fórmula NOAA simplificada)
float computeHeatIndex(float t, float h) {
    float c1 = -8.78469475556f;
    float c2 =  1.61139411f;
    float c3 =  2.33854883889f;
    float c4 = -0.14611605f;
    float c5 = -0.01230809851f;
    float c6 = -0.01642482777f;
    float c7 =  0.00221732f;
    float c8 =  0.00072546f;
    float c9 = -0.00000358583f;
    return c1 + c2*t + c3*h + c4*t*h + c5*t*t + c6*h*h +
           c7*t*t*h + c8*t*h*h + c9*t*t*h*h;
}

void logSensorsSerial() {
    Serial.printf("[OFFLINE] T=%.1f°C  H=%.1f%%  HI=%.1f°C",
        isnan(g_temp) ? 0 : g_temp,
        isnan(g_hum)  ? 0 : g_hum,
        isnan(g_hi)   ? 0 : g_hi);
    for (int i = 0; i < MAX_SLOTS; i++) {
        if (strlen(plants[i].plantKey) > 0)
            Serial.printf("  Slot%d[%s]=%.1f%%", i, plants[i].plantKey, plants[i].humidity);
    }
    Serial.println();
}

// ══════════════════════════════════════════════════════════════════════
//  ENVÍO AL SERVIDOR
// ══════════════════════════════════════════════════════════════════════

void sendReading() {
    if (!tcpClient.connected()) {
        tcpConnected = false;
        return;
    }

    msgCount++;
    long uptimeMs = millis() - bootTimeMs;

    // Construir JSON con ArduinoJson
    StaticJsonDocument<1024> doc;
    doc["id"]          = msgCount;
    doc["dispositivo"] = deviceName;
    doc["timestamp"]   = (long)(millis());

    JsonObject sensores     = doc.createNestedObject("sensores");
    sensores["temperatura"] = isnan(g_temp) ? 0.0f : roundf(g_temp * 10) / 10.0f;
    sensores["humedad"]     = isnan(g_hum)  ? 0.0f : roundf(g_hum  * 10) / 10.0f;
    sensores["indice_calor"]= isnan(g_hi)   ? 0.0f : roundf(g_hi   * 10) / 10.0f;

    JsonObject red = doc.createNestedObject("red");
    red["rssi"] = WiFi.RSSI();
    red["ip"]   = WiFi.localIP().toString();

    JsonArray plantasArr = doc.createNestedArray("plantas");
    for (int i = 0; i < MAX_SLOTS; i++) {
        JsonObject p = plantasArr.createNestedObject();
        p["slot"]     = i;
        p["plantKey"] = plants[i].plantKey;
        p["humedad"]  = roundf(plants[i].humidity * 10) / 10.0f;
        p["regadoEn"] = plants[i].lastWatered;
    }

    String json;
    serializeJson(doc, json);
    tcpClient.println(json);

    if (tcpClient.getWriteError()) {
        Serial.println("[TCP] Error de escritura. Desconectando…");
        tcpConnected = false;
        tcpClient.stop();
        return;
    }

    Serial.printf("[TX #%d] T=%.1f° H=%.1f%% RSSI=%d dBm\n",
        msgCount,
        isnan(g_temp) ? 0.0f : g_temp,
        isnan(g_hum)  ? 0.0f : g_hum,
        WiFi.RSSI());
}

// ══════════════════════════════════════════════════════════════════════
//  RECEPCIÓN DE COMANDOS DEL SERVIDOR
// ══════════════════════════════════════════════════════════════════════

void readServerCommands() {
    while (tcpClient.available()) {
        char c = tcpClient.read();
        if (c == '\n') {
            rxBuffer.trim();
            if (rxBuffer.length() > 0) {
                handleCommand(rxBuffer);
            }
            rxBuffer = "";
        } else {
            rxBuffer += c;
            // Protección contra desbordamiento de buffer
            if (rxBuffer.length() > 2048) {
                Serial.println("[TCP] Buffer RX desbordado. Descartando.");
                rxBuffer = "";
            }
        }
    }
}

void handleCommand(const String& json) {
    Serial.printf("[CMD] %s\n", json.c_str());

    StaticJsonDocument<512> doc;
    DeserializationError err = deserializeJson(doc, json);
    if (err) {
        Serial.printf("[CMD] JSON inválido: %s\n", err.c_str());
        return;
    }

    const char* cmd = doc["cmd"];
    if (!cmd) return;

    if (strcmp(cmd, "rename") == 0) {
        handleRename(doc);
    } else if (strcmp(cmd, "assignPlant") == 0) {
        handleAssignPlant(doc);
    } else if (strcmp(cmd, "water") == 0) {
        handleWater(doc);
    } else {
        Serial.printf("[CMD] Comando desconocido: %s\n", cmd);
    }
}

// ── Rename ─────────────────────────────────────────────────────────────
void handleRename(const JsonDocument& doc) {
    const char* newName = doc["name"];
    if (!newName || strlen(newName) == 0) return;
    strncpy(deviceName, newName, sizeof(deviceName) - 1);
    deviceName[sizeof(deviceName) - 1] = '\0';
    Serial.printf("[CMD] Renombrado a: %s\n", deviceName);
    saveToNVS();
}

// ── AssignPlant ────────────────────────────────────────────────────────
void handleAssignPlant(const JsonDocument& doc) {
    int slot = doc["slot"] | -1;
    if (slot < 0 || slot >= MAX_SLOTS) {
        Serial.printf("[CMD] Slot inválido: %d\n", slot);
        return;
    }

    const char* plantKey = doc["plantKey"] | "";
    strncpy(plants[slot].plantKey, plantKey, sizeof(plants[slot].plantKey) - 1);
    plants[slot].plantKey[sizeof(plants[slot].plantKey) - 1] = '\0';

    if (strlen(plantKey) == 0) {
        // Vaciar slot
        plants[slot].humMin      = 0.0f;
        plants[slot].humMax      = 100.0f;
        plants[slot].humOpt      = 50.0f;
        plants[slot].lastWatered = 0;
        Serial.printf("[CMD] Slot %d vaciado.\n", slot);
    } else {
        plants[slot].humMin = doc["humMin"] | 0.0f;
        plants[slot].humMax = doc["humMax"] | 100.0f;
        plants[slot].humOpt = doc["humOpt"] | 50.0f;
        Serial.printf("[CMD] Slot %d <- '%s' (%.0f-%.0f%%, opt=%.0f%%)\n",
            slot, plantKey,
            plants[slot].humMin, plants[slot].humMax, plants[slot].humOpt);
    }

    saveToNVS();
}

// ── Water ──────────────────────────────────────────────────────────────
void handleWater(const JsonDocument& doc) {
    int slot = doc["slot"] | -1;
    if (slot < 0 || slot >= MAX_SLOTS) {
        Serial.printf("[CMD] Riego: slot inválido %d\n", slot);
        return;
    }
    if (strlen(plants[slot].plantKey) == 0) {
        Serial.printf("[CMD] Riego: slot %d sin planta asignada. Ignorando.\n", slot);
        return;
    }

    // Marcar como pendiente para ejecutar en el loop principal
    // (evitar delays dentro de callbacks TCP)
    plants[slot].waterPending = true;
    Serial.printf("[CMD] Riego pendiente para slot %d ('%s').\n",
        slot, plants[slot].plantKey);
}

// ══════════════════════════════════════════════════════════════════════
//  GESTIÓN DE RELÉS Y RIEGO
// ══════════════════════════════════════════════════════════════════════

// ── Riego automático local ────────────────────────────────────────────
// Recorre los slots con planta asignada y, si la humedad de sustrato
// está por debajo del mínimo (humMin) configurado para esa planta,
// marca waterPending = true. Esto reutiliza exactamente el mismo camino
// de ejecución que un riego pedido por el servidor (handlePendingWater +
// handleRelays), así que el comportamiento físico del relé es idéntico
// sin importar quién ordenó el riego.
//
// Es completamente independiente de tcpConnected: se evalúa con la
// última lectura local del sensor, tanto si hay servidor como si no.
void checkAutoWater() {
    unsigned long now = millis();

    // Gracia tras el arranque: los ADC pueden dar lecturas inestables
    // en los primeros segundos (rail de alimentación asentándose), y no
    // queremos disparar un riego por una lectura falsa de "0% húmedo".
    if (now - (unsigned long)bootTimeMs < AUTO_WATER_BOOT_GRACE_MS) return;

    for (int i = 0; i < MAX_SLOTS; i++) {
        // Slot vacío: no hay umbral (humMin) válido que evaluar.
        if (strlen(plants[i].plantKey) == 0) continue;

        // Ya hay un riego en curso o ya en cola: no dupliques la orden.
        if (relayActive[i] || plants[i].waterPending) continue;

        // Humedad todavía aceptable: nada que hacer.
        if (plants[i].humidity >= plants[i].humMin) continue;

        // Cooldown: tras regar, el agua tarda en filtrarse y en que el
        // sensor lo refleje. Sin este freno, el sistema seguiría
        // detectando "húmedo bajo mínimo" y regaría en cada ciclo,
        // encharcando la maceta.
        if (plants[i].lastWatered != 0 &&
            (now - (unsigned long)plants[i].lastWatered) < AUTO_WATER_COOLDOWN_MS) {
            continue;
        }

        plants[i].waterPending = true;
        Serial.printf(
            "[AUTO-RIEGO] Slot %d ('%s'): humedad %.1f%% < minimo %.1f%%. "
            "Riego automatico local disparado%s.\n",
            i, plants[i].plantKey, plants[i].humidity, plants[i].humMin,
            tcpConnected ? "" : " (servidor desconectado)");
    }
}

void handlePendingWater() {
    for (int i = 0; i < MAX_SLOTS; i++) {
        if (!plants[i].waterPending) continue;
        plants[i].waterPending = false;

        // Activar relé/bomba
        digitalWrite(relayPins[i], HIGH);
        relayActive[i]  = true;
        relayOnSince[i] = millis();
        plants[i].lastWatered = millis();   // timestamp local (epoch relativo al boot)

        Serial.printf("[AGUA] Regando slot %d ('%s') por %dms.\n",
            i, plants[i].plantKey, WATER_RELAY_MS);

        saveToNVS();   // guardar timestamp del riego
    }
}

void handleRelays() {
    unsigned long now = millis();
    for (int i = 0; i < MAX_SLOTS; i++) {
        if (relayActive[i] && (now - relayOnSince[i] >= (unsigned long)WATER_RELAY_MS)) {
            digitalWrite(relayPins[i], LOW);
            relayActive[i] = false;
            Serial.printf("[AGUA] Riego slot %d completado.\n", i);
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  LED DE ESTADO
//  Conectado al servidor  → encendido fijo
//  Sin servidor, WiFi OK  → parpadeo lento (1s)
//  Sin WiFi              → parpadeo rápido (200ms)
// ══════════════════════════════════════════════════════════════════════

void blinkLed() {
    static unsigned long lastBlink = 0;
    static bool ledState = false;
    unsigned long now = millis();

    if (WiFi.status() != WL_CONNECTED) {
        if (now - lastBlink > 200) {
            lastBlink = now;
            ledState = !ledState;
            digitalWrite(LED_PIN, ledState ? HIGH : LOW);
        }
    } else if (!tcpConnected) {
        if (now - lastBlink > 1000) {
            lastBlink = now;
            ledState = !ledState;
            digitalWrite(LED_PIN, ledState ? HIGH : LOW);
        }
    }
    // Si tcpConnected: LED se pone fijo HIGH en attemptTcpReconnect()
}
