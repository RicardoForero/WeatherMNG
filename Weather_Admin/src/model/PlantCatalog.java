package model;

import java.util.*;

/**
 * Catálogo de plantas disponible en el Admin. A diferencia del Server, el
 * Admin NUNCA lee el archivo Excel: este catálogo se puebla enteramente a
 * partir del mensaje {@code {"type":"catalog","plants":[...]}} que envía
 * el Server al conectar (o al recargar el catálogo).
 */
public class PlantCatalog {

    private final Map<String, PlantSpec> byKey = new LinkedHashMap<>();

    public synchronized void replaceAll(Collection<PlantSpec> specs) {
        byKey.clear();
        for (PlantSpec s : specs) byKey.put(s.key(), s);
    }

    public synchronized PlantSpec get(String key) {
        return key == null ? null : byKey.get(key);
    }

    public synchronized List<PlantSpec> all() {
        return new ArrayList<>(byKey.values());
    }

    public synchronized boolean isEmpty() {
        return byKey.isEmpty();
    }

    public synchronized int size() {
        return byKey.size();
    }
}
