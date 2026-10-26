/*
 * ═══════════════════════════════════════════════════════════════════════
 *  app_config.h — Configuración centralizada del firmware
 *  ESP32 Plant Monitor v5 (ESP-IDF)
 *
 *  Todas las constantes, pines, temporización y tipos compartidos
 *  se definen aquí para facilitar la configuración sin tocar
 *  la lógica de negocio.
 * ═══════════════════════════════════════════════════════════════════════
 */

#pragma once

#include <stdint.h>
#include <stdbool.h>

/* ── Credenciales WiFi ──────────────────────────────────────────────── */
#ifndef WIFI_SSID
  #define WIFI_SSID   "CLARO-B750"
#endif
#ifndef WIFI_PASS
  #define WIFI_PASS   "ueUFhJk9cG"
#endif

/* ── Servidor TCP ───────────────────────────────────────────────────── */
#ifndef SERVER_HOST
  #define SERVER_HOST "192.168.128.11"
#endif
#ifndef SERVER_PORT
  #define SERVER_PORT 2361
#endif

/* ── Pines de hardware ──────────────────────────────────────────────── */
#define DHT_PIN         GPIO_NUM_4
#define DHT_TYPE_11     1           /* 1 = DHT11, 0 = DHT22 */

#define SOIL_PIN_0      GPIO_NUM_34   /* ADC1_CH6 */
#define SOIL_PIN_1      GPIO_NUM_35   /* ADC1_CH7 */
#define SOIL_PIN_2      GPIO_NUM_32   /* ADC1_CH4 */
#define SOIL_PIN_3      GPIO_NUM_33   /* ADC1_CH5 */

#define RELAY_PIN_0     GPIO_NUM_25
#define RELAY_PIN_1     GPIO_NUM_26
#define RELAY_PIN_2     GPIO_NUM_27
#define RELAY_PIN_3     GPIO_NUM_14

#define LED_STATUS_PIN  GPIO_NUM_2    /* LED onboard */

/* ── Calibración de sensores de suelo ────────────────────────────────
 *  SOIL_RAW_DRY  = lectura ADC cuando el sustrato está seco
 *  SOIL_RAW_WET  = lectura ADC cuando el sustrato está saturado        */
#define SOIL_RAW_DRY    3120
#define SOIL_RAW_WET    1180

/* ── Temporización (milisegundos) ────────────────────────────────────  */
#define SEND_INTERVAL_MS        2000U
#define RECONNECT_MIN_MS        2000U
#define RECONNECT_MAX_MS        60000U
#define WIFI_TIMEOUT_MS         15000U
#define WATER_RELAY_MS          3000U

/* ── Riego automático local ──────────────────────────────────────────  */
#define AUTO_WATER_COOLDOWN_MS  (6UL * 60UL * 60UL * 1000UL)  /* 6 horas */
#define AUTO_WATER_BOOT_GRACE_MS (10UL * 1000UL)               /* 10 seg  */

/* ── Slots de planta ─────────────────────────────────────────────────  */
#define MAX_SLOTS  4

/* ── NVS ─────────────────────────────────────────────────────────────  */
#define NVS_NAMESPACE   "plantmon"

/* ── Tamaños de stack para tareas FreeRTOS ───────────────────────────  */
#define SENSOR_TASK_STACK    4096
#define TCP_TASK_STACK       8192
#define RELAY_TASK_STACK     3072
#define LED_TASK_STACK       2048

/* ── Prioridades de tareas ───────────────────────────────────────────  */
#define SENSOR_TASK_PRIO     3
#define TCP_TASK_PRIO        2
#define RELAY_TASK_PRIO      4
#define LED_TASK_PRIO        1

/* ── Cores ───────────────────────────────────────────────────────────  */
#define CORE_SENSORS    0    /* Core 0: sensores + relés (tiempo real) */
#define CORE_NETWORK    1    /* Core 1: WiFi + TCP (tolera latencia)   */

/* ── Event Group Bits ────────────────────────────────────────────────  */
#define WIFI_CONNECTED_BIT   (1 << 0)
#define TCP_CONNECTED_BIT    (1 << 1)
#define NEW_READING_BIT      (1 << 2)

/* ═══════════════════════════════════════════════════════════════════════
 *  TIPOS DE DATOS COMPARTIDOS
 * ═══════════════════════════════════════════════════════════════════════ */

/**
 * Slot de planta — información de una maceta/planta monitoreada.
 */
typedef struct {
    char    plant_key[48];      /**< Clave del catálogo; "" = vacío           */
    float   hum_min;            /**< Humedad mínima aceptable (%)            */
    float   hum_max;            /**< Humedad máxima aceptable (%)            */
    float   hum_opt;            /**< Humedad óptima (%)                      */
    float   soil_moisture;      /**< Lectura actual de humedad de sustrato   */
    int64_t last_watered;       /**< Timestamp del último riego (ms boot)    */
    bool    water_pending;      /**< Riego solicitado pero aún no ejecutado  */
} plant_slot_t;

/**
 * Estado completo del dispositivo — protegido por mutex.
 */
typedef struct {
    /* Identidad */
    char        device_name[48];

    /* Sensores ambientales */
    float       temperature;        /**< °C (NAN si no disponible)  */
    float       humidity;           /**< % relativa                 */
    float       heat_index;         /**< °C índice de calor         */

    /* Slots de planta */
    plant_slot_t slots[MAX_SLOTS];

    /* Contadores */
    int         msg_count;
    int64_t     boot_time_ms;

    /* Red (actualizado por wifi_manager y tcp_client) */
    int32_t     wifi_rssi;
    char        ip_addr[16];
} device_state_t;

/**
 * Comando recibido del servidor.
 */
typedef enum {
    CMD_NONE = 0,
    CMD_RENAME,
    CMD_ASSIGN_PLANT,
    CMD_WATER,
} cmd_type_t;

typedef struct {
    cmd_type_t type;
    int        slot;            /**< Slot destino (para assignPlant/water) */
    char       name[48];        /**< Nuevo nombre (para rename)            */
    char       plant_key[48];   /**< Clave planta (para assignPlant)       */
    float      hum_min;
    float      hum_max;
    float      hum_opt;
} server_command_t;

