package view;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import static model.AppColors.*;
import model.SensorMath;

/**
 * VISTA — Slider vertical con animación suave.
 */
public class VSlider extends JPanel {

    public interface Callback { void call(float v); }

    private final String label;
    private final float  min, max;
    private final Color  color;

    private float   value, animValue;
    private boolean dragging = false;

    public Callback onValueChange;

    public VSlider(String label, float min, float max, float init, Color color) {
        this.label     = label;
        this.min       = min;
        this.max       = max;
        this.value     = init;
        this.animValue = init;
        this.color     = color;

        setBackground(BG2);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1),
            BorderFactory.createEmptyBorder(2, 0, 2, 0)));
        setCursor(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR));

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { dragging = true;  update(e); }
            @Override public void mouseReleased(MouseEvent e) { dragging = false; }
            @Override public void mouseDragged(MouseEvent e)  { update(e); }

            void update(MouseEvent e) {
                int   tH = getHeight() - 40;
                int   y  = Math.max(0, Math.min(tH, e.getY() - 20));
                float p  = 1f - y / (float) tH;
                float nv = SensorMath.clamp(min + p * (max - min), min, max);
                setValue(nv);
                if (onValueChange != null) onValueChange.call(value);
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(e -> {
            setValue(SensorMath.clamp(
                value + (max - min) * 0.01f * (float)(-e.getPreciseWheelRotation()), min, max));
            if (onValueChange != null) onValueChange.call(value);
        });
    }

    public void setValue(float v) { value = SensorMath.clamp(v, min, max); }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        animValue += (value - animValue) * 0.15f;
        int W = getWidth(), H = getHeight(), cx = W / 2;
        int tT = 24, tB = H - 16, tH = tB - tT, tW = 10;
        float pct  = (animValue - min) / (max - min);
        int   fillY = (int)(tB - tH * pct);

        g2.setFont(new Font("Monospaced", Font.BOLD, 9));
        g2.setColor(MUTED);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, cx - fm.stringWidth(label) / 2, 14);

        g2.setColor(new Color(20, 32, 62));
        g2.fillRoundRect(cx - tW / 2, tT, tW, tH, 5, 5);
        g2.setColor(color);
        g2.fillRoundRect(cx - tW / 2, fillY, tW, tB - fillY, 5, 5);

        int tr = 9;
        if (dragging) {
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
            g2.fillOval(cx - tr - 4, fillY - tr - 4, (tr + 4) * 2, (tr + 4) * 2);
        }
        g2.setColor(BG3);
        g2.fillOval(cx - tr, fillY - tr, tr * 2, tr * 2);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(2));
        g2.drawOval(cx - tr, fillY - tr, tr * 2, tr * 2);

        g2.setFont(new Font("Monospaced", Font.BOLD, 9));
        g2.setColor(color);
        String vs = String.format("%.0f", animValue);
        g2.drawString(vs, cx - fm.stringWidth(vs) / 2, tB + 12);
    }
}
