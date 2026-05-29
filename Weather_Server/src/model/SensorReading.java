package model;

/**
 * Value Object inmutable: una lectura cruda proveniente de un sensor.
 * Se construye desde el protocolo y se entrega al Presenter.
 */
public record SensorReading(
        float  temp,
        float  hum,
        float  heatIndex,
        int    rssi,
        String deviceName
) {}
