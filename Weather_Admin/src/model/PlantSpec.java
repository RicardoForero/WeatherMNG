package model;

/**
 * Especificación de una especie de planta del catálogo (recibida del Server,
 * que a su vez la lee de data/plantas.xlsx). El Admin nunca lee el Excel
 * directamente: solo consume este catálogo vía el protocolo de red.
 */
public record PlantSpec(
        String key,
        String scientific,
        String alternative,
        float  humMin,
        float  humMax,
        float  humOptimal
) {

    /** Texto descriptivo corto para tooltips/combo, p. ej. "Potos (35–60%)". */
    public String shortLabel() {
        return String.format("%s (%.0f–%.0f%%)", key, humMin, humMax);
    }
}
