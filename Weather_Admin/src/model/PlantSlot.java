package model;

/**
 * Estado de un slot de planta de un ESP32, tal como lo reporta el Server.
 * Modelo de datos puro (mutable, sin lógica de red ni de presentación).
 */
public class PlantSlot {

    public static final int MAX_SLOTS = 4;

    public int    slot;
    public String plantKey    = ""; // "" = slot vacío
    public float  humidity    = 0f;
    public long   lastWatered = 0L; // epoch millis; 0 = nunca regada

    public PlantSlot(int slot) {
        this.slot = slot;
    }

    public boolean isEmpty() {
        return plantKey == null || plantKey.isBlank();
    }
}
