package view;

import javax.swing.*;
import java.awt.*;

import static model.AppColors.*;

/**
 * Gauge semicircular animado para temperatura o humedad.
 */
public class MiniGauge extends JPanel {

    private final String label, unit;
    private final float  min, max;
    private float        value = Float.NaN;
    private float        animV = 0f;
    private Color        color = C_MILD;

    public MiniGauge(String label, String unit, float min, float max) {
        this.label = label;
        this.unit  = unit;
        this.min   = min;
        this.max   = max;
        setOpaque(false);
        setPreferredSize(new Dimension(150, 110));
    }

    public void setValue(float v) {
        value = v;
        if (!Float.isNaN(v)) animV += (v - animV) * 0.15f;
    }

    public void setColor(Color c) { color = c; }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int W = getWidth(), H = getHeight();
        int cx = W / 2, cy = H - 28;
        int r = Math.min(cx - 8, cy - 10);

        // Etiqueta
        g2.setFont(new Font("Monospaced", Font.BOLD, 9));
        g2.setColor(C_MUTED);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, cx - fm.stringWidth(label) / 2, 14);

        // Arco de fondo
        g2.setColor(new Color(25, 40, 75));
        g2.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawArc(cx - r, cy - r + 15, r * 2, r * 2, 210, -240);

        if (!Float.isNaN(value)) {
            float pct = Math.max(0, Math.min(1, (animV - min) / (max - min)));
            g2.setColor(color);
            g2.drawArc(cx - r, cy - r + 15, r * 2, r * 2, 210, -(int)(240 * pct));

            // Valor numérico
            g2.setFont(new Font("Monospaced", Font.BOLD, 24));
            fm = g2.getFontMetrics();
            String vs = String.format("%.1f", value);
            g2.setColor(color);
            g2.drawString(vs, cx - fm.stringWidth(vs) / 2, cy + 6);

            // Unidad
            g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
            fm = g2.getFontMetrics();
            g2.setColor(C_MUTED);
            g2.drawString(unit, cx - fm.stringWidth(unit) / 2, cy + 20);
        } else {
            g2.setFont(new Font("Monospaced", Font.BOLD, 22));
            g2.setColor(C_MUTED);
            fm = g2.getFontMetrics();
            g2.drawString("—", cx - fm.stringWidth("—") / 2, cy + 6);
        }

        // Límites
        g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
        g2.setColor(new Color(55, 80, 130));
        g2.drawString(String.valueOf((int) min), cx - r - 2, cy + 6);
        g2.drawString(String.valueOf((int) max), cx + r - 14, cy + 6);
    }
}
