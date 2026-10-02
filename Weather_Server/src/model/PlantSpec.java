package model;

/**
 * Especificación de una especie de planta leída del catálogo (Excel).
 * Entidad de dominio inmutable.
 */
public record PlantSpec(
        String key,        // Nombre común — identificador único usado en el protocolo
        String scientific,  // Nombre científico
        String alternative, // Nombre(s) alternativo(s)
        float  humMin,      // Humedad mínima recomendada de sustrato (%)
        float  humMax,      // Humedad máxima recomendada de sustrato (%)
        float  humOptimal,  // Humedad óptima de sustrato (%)
        String careNotes    // Notas de cuidado
) {

    /** Convierte esta especificación a un fragmento JSON para el protocolo. */
    public String toJson() {
        return String.format(java.util.Locale.ROOT, 
            "{\"key\":\"%s\",\"sci\":\"%s\",\"alt\":\"%s\",\"humMin\":%.0f,\"humMax\":%.0f,\"humOpt\":%.0f}",
            escape(key), escape(scientific), escape(alternative), humMin, humMax, humOptimal);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
