package model.persistance;

import model.PlantSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.*;

/**
 * Carga y mantiene en memoria el catálogo de plantas leído del archivo
 * {@code data/plantas.xlsx} o de las rutas estándar del proyecto.
 *
 * Columnas esperadas (detectadas dinámicamente o por posición):
 *   ID | Nombre Común | Nombre Científico | Nombre Alternativo |
 *   Humedad Mín (%) | Humedad Máx (%) | Humedad Óptima (%) | Rango (%) | Notas de Cuidado
 */
public class PlantCatalog {

    // Índices por defecto (fallback si la cabecera no se reconoce)
    private static final int DEF_COL_COMMON     = 1;
    private static final int DEF_COL_SCIENTIFIC = 2;
    private static final int DEF_COL_ALT        = 3;
    private static final int DEF_COL_HUM_MIN    = 4;
    private static final int DEF_COL_HUM_MAX    = 5;
    private static final int DEF_COL_HUM_OPT    = 6;
    private static final int DEF_COL_NOTES      = 8; // o 7

    private final Map<String, PlantSpec> byKey = new LinkedHashMap<>();
    private Path xlsxPath;
    private List<String> lastWarnings = new ArrayList<>();

    public PlantCatalog() {
        this(resolveXlsxPath());
    }

    public PlantCatalog(Path xlsxPath) {
        this.xlsxPath = xlsxPath;
        reload();
    }

    /** Advertencias producidas durante la última llamada a {@link #reload()}. */
    public synchronized List<String> lastWarnings() {
        return Collections.unmodifiableList(lastWarnings);
    }

    /** Vuelve a leer el archivo .xlsx desde disco, reemplazando el catálogo en memoria. */
    public synchronized List<String> reload() {
        List<String> warnings = new ArrayList<>();
        byKey.clear();

        // Si la ruta previa no existe, intentar re-resolver
        if (xlsxPath == null || !Files.exists(xlsxPath)) {
            xlsxPath = resolveXlsxPath();
        }

        try {
            ensureFileExists(warnings);
            if (!Files.exists(xlsxPath)) {
                lastWarnings = warnings;
                return warnings;
            }

            List<List<String>> rows = XlsxReader.readFirstSheet(xlsxPath);
            if (rows.isEmpty()) {
                warnings.add("El archivo Excel no contiene filas (" + xlsxPath + ")");
                lastWarnings = warnings;
                return warnings;
            }

            // Detectar columnas dinámicamente analizando la fila 0 (encabezado)
            List<String> header = rows.get(0);
            int colCommon     = findColumn(header, DEF_COL_COMMON, "común", "comun", "nombre");
            int colScientific = findColumn(header, DEF_COL_SCIENTIFIC, "científico", "cientifico");
            int colAlt        = findColumn(header, DEF_COL_ALT, "alternativo", "otro nombre");
            int colHumMin     = findColumn(header, DEF_COL_HUM_MIN, "mín", "min");
            int colHumMax     = findColumn(header, DEF_COL_HUM_MAX, "máx", "max");
            int colHumOpt     = findColumn(header, DEF_COL_HUM_OPT, "óptim", "optim", "promedio");
            int colNotes      = findColumn(header, DEF_COL_NOTES, "cuidado", "nota", "categoría", "categoria");

            for (int r = 1; r < rows.size(); r++) { // fila 0 = encabezado
                List<String> row = rows.get(r);
                PlantSpec spec = rowToSpec(row, colCommon, colScientific, colAlt, colHumMin, colHumMax, colHumOpt, colNotes);
                if (spec != null) {
                    byKey.put(spec.key(), spec);
                }
            }

            if (byKey.isEmpty()) {
                warnings.add("El catálogo de plantas se cargó vacío (" + xlsxPath + ")");
            }
        } catch (Exception e) {
            warnings.add("No se pudo leer el catálogo de plantas (" + xlsxPath + "): " + e.getMessage());
        }

        lastWarnings = warnings;
        return warnings;
    }

    private static int findColumn(List<String> header, int defaultCol, String... keywords) {
        if (header == null) return defaultCol;
        for (int i = 0; i < header.size(); i++) {
            String val = header.get(i).toLowerCase(Locale.ROOT);
            for (String kw : keywords) {
                if (val.contains(kw)) return i;
            }
        }
        return defaultCol;
    }

    private PlantSpec rowToSpec(List<String> row,
                                int colCommon, int colScientific, int colAlt,
                                int colHumMin, int colHumMax, int colHumOpt, int colNotes) {
        String common = get(row, colCommon);
        if (common.isBlank()) return null; // fila vacía o sin nombre

        String rawMin = get(row, colHumMin);
        String rawMax = get(row, colHumMax);
        String rawOpt = get(row, colHumOpt);

        float min = parseHumValue(rawMin, 0, 40f);
        float max = parseHumValue(rawMax, 1, 80f);
        float opt = parseHumValue(rawOpt, 2, (min + max) / 2f);

        // Validación de consistencia
        if (min > max) { float tmp = min; min = max; max = tmp; }
        if (opt < min || opt > max) opt = (min + max) / 2f;

        String sci = get(row, colScientific);
        String alt = get(row, colAlt);
        String notes = get(row, colNotes);

        return new PlantSpec(
            common.trim(),
            sci.trim(),
            alt.trim(),
            min,
            max,
            opt,
            notes.trim()
        );
    }

    private static String get(List<String> row, int col) {
        return (row != null && col >= 0 && col < row.size()) ? row.get(col).trim() : "";
    }

    private static float parseHumValue(String s, int partIndex, float def) {
        if (s == null || s.isBlank()) return def;
        String cleaned = s.replace("%", "").replace(",", ".").trim();

        // Caso: rango "40 - 60"
        if (cleaned.contains("-")) {
            String[] parts = cleaned.split("-");
            try {
                if (parts.length >= 2) {
                    float v0 = Float.parseFloat(parts[0].trim());
                    float v1 = Float.parseFloat(parts[1].trim());
                    if (partIndex == 0) return v0;
                    if (partIndex == 1) return v1;
                    return (v0 + v1) / 2f;
                }
            } catch (Exception ignored) {}
        }

        try {
            return Float.parseFloat(cleaned);
        } catch (Exception e) {
            return def;
        }
    }

    /* ── API pública ──────────────────────────────────────────── */

    public synchronized Collection<PlantSpec> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(byKey.values()));
    }

    public synchronized PlantSpec get(String key) {
        return key == null ? null : byKey.get(key);
    }

    public synchronized boolean contains(String key) {
        return key != null && byKey.containsKey(key);
    }

    public synchronized int size() {
        return byKey.size();
    }

    public Path getXlsxPath() {
        return xlsxPath;
    }

    /** Construye el fragmento JSON de tipo "catalog" con todas las especies disponibles. */
    public synchronized String toCatalogJson() {
        StringBuilder sb = new StringBuilder("{\"type\":\"catalog\",\"plants\":[");
        boolean first = true;
        for (PlantSpec p : byKey.values()) {
            if (!first) sb.append(",");
            sb.append(p.toJson());
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    /* ── Resolución del archivo .xlsx ─────────────────────────── */

    private void ensureFileExists(List<String> warnings) throws Exception {
        if (Files.exists(xlsxPath)) return;
        if (xlsxPath.getParent() != null) {
            Files.createDirectories(xlsxPath.getParent());
        }
        warnings.add("No se encontró " + xlsxPath + " — coloca ahí tu catálogo de plantas " +
                      "(columnas: ID, Nombre Común, Nombre Científico, Nombre Alternativo, " +
                      "Humedad Mín, Humedad Máx, Humedad Óptima, Notas).");
    }

    /**
     * Resuelve inteligentemente la ruta del archivo {@code plantas.xlsx} buscando
     * en variables de entorno, directorios estándar de ejecución y rutas relativas.
     */
    public static Path resolveXlsxPath() {
        // 1. Variable de entorno opcional
        String envPath = System.getenv("PLANT_CATALOG_PATH");
        if (envPath != null && !envPath.isBlank()) {
            Path p = Path.of(envPath);
            if (Files.exists(p)) return p.toAbsolutePath().normalize();
        }

        // 2. Rutas candidatas desde el directorio de trabajo
        Path[] candidates = new Path[] {
            Path.of("Weather_Server", "data", "plantas.xlsx"),
            Path.of("data", "plantas.xlsx"),
            Path.of("Weather_Server", "src", "presenter", "data", "plantas.xlsx"),
            Path.of("src", "presenter", "data", "plantas.xlsx"),
            Path.of("..", "Weather_Server", "data", "plantas.xlsx"),
            Path.of("..", "data", "plantas.xlsx"),
            // Fallback a legacy si existe
            Path.of("Weather_Server", "data", "plantas_legacy.xlsx"),
            Path.of("data", "plantas_legacy.xlsx")
        };

        for (Path c : candidates) {
            if (Files.exists(c)) {
                return c.toAbsolutePath().normalize();
            }
        }

        // 3. Rutas relativas al CodeSource (JAR o carpeta bin/ de clases)
        try {
            ProtectionDomain pd = PlantCatalog.class.getProtectionDomain();
            if (pd != null && pd.getCodeSource() != null) {
                Path loc = Path.of(pd.getCodeSource().getLocation().toURI());
                Path dir = Files.isRegularFile(loc) ? loc.getParent() : loc;
                
                Path p1 = dir.resolve("data").resolve("plantas.xlsx");
                if (Files.exists(p1)) return p1.toAbsolutePath().normalize();

                if (dir.getParent() != null) {
                    Path p2 = dir.getParent().resolve("data").resolve("plantas.xlsx");
                    if (Files.exists(p2)) return p2.toAbsolutePath().normalize();

                    Path p3 = dir.getParent().resolve("Weather_Server").resolve("data").resolve("plantas.xlsx");
                    if (Files.exists(p3)) return p3.toAbsolutePath().normalize();
                }
            }
        } catch (Exception ignored) {}

        // Fallback por defecto preferente
        Path defaultPath = Files.exists(Path.of("Weather_Server"))
                ? Path.of("Weather_Server", "data", "plantas.xlsx")
                : Path.of("data", "plantas.xlsx");
        return defaultPath.toAbsolutePath().normalize();
    }
}
