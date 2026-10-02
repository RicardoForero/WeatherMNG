package model.persistance;

import model.PlantSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.*;

/**
 * Carga y mantiene en memoria el catálogo de plantas leído del archivo
 * {@code data/plantas.xlsx} (ubicado junto al .jar/clase principal, igual
 * que las carpetas {@code logs/} y {@code files/}).
 *
 * Columnas esperadas en la primera hoja (con encabezado en la fila 1):
 *   ID | Nombre Común | Nombre Científico | Nombre Alternativo |
 *   Humedad Mín (%) | Humedad Máx (%) | Humedad Óptima (%) | Notas de Cuidado
 *
 * El orden de columnas se resuelve por posición (no por nombre de encabezado)
 * para mantener el parser simple; si el usuario reemplaza el archivo debe
 * conservar este orden de columnas.
 */
public class PlantCatalog {

    private static final int COL_COMMON     = 1;
    private static final int COL_SCIENTIFIC = 2;
    private static final int COL_ALT        = 3;
    private static final int COL_HUM_MIN    = 4;
    private static final int COL_HUM_MAX    = 5;
    private static final int COL_HUM_OPT    = 6;
    private static final int COL_NOTES      = 7;

    private final Map<String, PlantSpec> byKey = new LinkedHashMap<>();
    private final Path xlsxPath;
    private List<String> lastWarnings = new ArrayList<>();

    public PlantCatalog() {
        this(resolveDataDir().resolve("plantas.xlsx"));
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
        try {
            ensureFileExists(warnings);
            if (!Files.exists(xlsxPath)) { lastWarnings = warnings; return warnings; }

            List<List<String>> rows = XlsxReader.readFirstSheet(xlsxPath);
            for (int r = 1; r < rows.size(); r++) { // fila 0 = encabezado
                List<String> row = rows.get(r);
                PlantSpec spec = rowToSpec(row);
                if (spec != null) byKey.put(spec.key(), spec);
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

    private PlantSpec rowToSpec(List<String> row) {
        String common = get(row, COL_COMMON);
        if (common.isBlank()) return null; // fila vacía o de relleno
        return new PlantSpec(
            common.trim(),
            get(row, COL_SCIENTIFIC),
            get(row, COL_ALT),
            parseFloatSafe(get(row, COL_HUM_MIN), 0f),
            parseFloatSafe(get(row, COL_HUM_MAX), 100f),
            parseFloatSafe(get(row, COL_HUM_OPT), 50f),
            get(row, COL_NOTES));
    }

    private static String get(List<String> row, int idx) {
        return idx < row.size() ? row.get(idx) : "";
    }

    private static float parseFloatSafe(String s, float def) {
        try { return Float.parseFloat(s.trim()); }
        catch (Exception e) { return def; }
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

    /* ── Resolución de la carpeta data/ y archivo por defecto ──── */

    private void ensureFileExists(List<String> warnings) throws Exception {
        if (Files.exists(xlsxPath)) return;
        Files.createDirectories(xlsxPath.getParent());
        warnings.add("No se encontró " + xlsxPath + " — coloca ahí tu catálogo de plantas " +
                      "(columnas: ID, Nombre Común, Nombre Científico, Nombre Alternativo, " +
                      "Humedad Mín, Humedad Máx, Humedad Óptima, Notas).");
    }

    /**
     * Resuelve la carpeta {@code data/} en el mismo directorio que el JAR ejecutable,
     * igual que {@code FileLogView.resolveLogsDir()}. Si no se puede determinar
     * (por ejemplo, ejecutando desde el IDE), usa el directorio de trabajo actual.
     */
    private static Path resolveDataDir() {
        try {
            ProtectionDomain pd = PlantCatalog.class.getProtectionDomain();
            Path jarPath = Path.of(pd.getCodeSource().getLocation().toURI());
            Path base = Files.isRegularFile(jarPath) ? jarPath.getParent() : jarPath;
            return base.resolve("data");
        } catch (Exception e) {
            return Path.of(System.getProperty("user.dir"), "data");
        }
    }
}
