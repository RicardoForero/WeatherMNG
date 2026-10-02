package model;

import java.awt.Color;

/**
 * MODEL — Funciones de cálculo y utilidades puras.
 * Sin dependencias de Swing ni de estado mutable.
 */
public final class SensorMath {

    private SensorMath() {}

    /** Índice de calor (fórmula NOAA). Devuelve la temperatura si < 27 °C. */
    public static float computeHeatIndex(float t, float h) {
        if (t < 27) return t;
        double hi = -8.784695
            + 1.61139411 * t  + 2.33854883 * h
            - 0.14611605 * t  * h  - 0.01230809 * t * t
            - 0.01642483 * h  * h  + 0.00221173 * t * t * h
            + 0.00072546 * t  * h  * h - 0.00000358 * t * t * h * h;
        return (float) hi;
    }

    public static float clamp(float v, float mn, float mx) {
        return Math.max(mn, Math.min(mx, v));
    }

    public static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public static Color interpolateColor(Color a, Color b, float t) {
        t = clamp01(t);
        return new Color(
            (int)(a.getRed()   + (b.getRed()   - a.getRed())   * t),
            (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }

    public static Color tempColor(float t) {
        if (t < 10) return AppColors.COLD;
        if (t < 22) return new Color(60, 200, 220);
        if (t < 30) return AppColors.MILD;
        if (t < 37) return AppColors.WARM;
        return AppColors.HOT;
    }
}
