package model;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static model.AppColors.C_COLD;
import static model.AppColors.C_HOT;
import static model.AppColors.C_MILD;
import static model.AppColors.C_MUTED;
import static model.AppColors.C_WARM;

/**
 * Modelo de datos de un sensor recibido vía red.
 * No contiene lógica de red ni de presentación.
 */
public class SensorData {

    public String  id, ip, name;
    public float   temp      = Float.NaN;
    public float   hum       = Float.NaN;
    public float   heatIndex = Float.NaN;
    public int     rssi      = 0;
    public int     msgCount  = 0;
    public boolean online    = true;
    public long    uptime    = 0;

    public final List<Float> histTemp = Collections.synchronizedList(new ArrayList<>());
    public final List<Float> histHum  = Collections.synchronizedList(new ArrayList<>());

    public SensorData(String id, String ip, String name) {
        this.id = id;
        this.ip = ip;
        this.name = name;
    }

    /** Color representativo según temperatura actual. */
    public Color tempColor() {
        if (Float.isNaN(temp)) return C_MUTED;
        if (temp < 10) return C_COLD;
        if (temp < 22) return new Color(60, 200, 220);
        if (temp < 30) return C_MILD;
        if (temp < 37) return C_WARM;
        return C_HOT;
    }

    /** Descripción textual de la condición ambiental. */
    public String condition() {
        if (Float.isNaN(temp) || Float.isNaN(hum)) return "Sin datos";
        if (temp < 5)               return "Helada";
        if (temp < 15)              return "Frío";
        if (temp < 22)              return "Fresco";
        if (temp < 28 && hum < 60)  return "Confort";
        if (hum  > 85)              return "Muy húmedo";
        if (hum  > 70)              return "Húmedo";
        if (temp < 32)              return "Cálido";
        if (temp < 38)              return "Caluroso";
        return "Extremo";
    }
}
