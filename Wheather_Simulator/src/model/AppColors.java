package model;

import java.awt.Color;

/**
 * Paleta de colores compartida por todas las capas.
 * Se ubica en model porque no depende de ninguna vista concreta.
 */
public final class AppColors {

    private AppColors() {}

    public static final Color BG0    = new Color(7,  11, 24);
    public static final Color BG1    = new Color(13, 20, 42);
    public static final Color BG2    = new Color(18, 28, 56);
    public static final Color BG3    = new Color(24, 36, 70);
    public static final Color COLD   = new Color(56,  182, 255);
    public static final Color MILD   = new Color(30,  200, 170);
    public static final Color WARM   = new Color(255, 148, 38);
    public static final Color HOT    = new Color(255,  72, 48);
    public static final Color HUMID  = new Color(80,  150, 255);
    public static final Color TEXT   = new Color(215, 230, 255);
    public static final Color MUTED  = new Color(90,  118, 175);
    public static final Color BORDER = new Color(55,  90, 160, 60);
    public static final Color ACCENT = new Color(30,  200, 170);
}
