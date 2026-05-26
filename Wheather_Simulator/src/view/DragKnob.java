package view;

import model.AppColors;
import model.SensorMath;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

import static model.AppColors.*;

/**
 * VISTA — Knob giratorio con arrastre y scroll.
 */
public class DragKnob extends JPanel {

    public interface Callback { void call(float v); }

    private final String label, unit;
    private final float  min, max;
    private final Color  colorLow, colorHigh;

    private float value, animValue;
    private long  tick = 0;
    private int   dragStartX;
    private float dragStartVal;
    private boolean dragging = false;

    public Callback onValueChange;

    public DragKnob(String label, String unit,
                    float min, float max, float init,
                    Color colorLow, Color colorHigh) {
        this.label    = label;
        this.unit     = unit;
        this.min      = min;
        this.max      = max;
        this.value    = init;
        this.animValue = init;
        this.colorLow  = colorLow;
        this.colorHigh = colorHigh;

        setBackground(BG2);
        setBorder(BorderFactory.createLineBorder(BORDER, 1));
        setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragging    = true;
                dragStartX   = e.getX();
                dragStartVal = value;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
            @Override public void mouseReleased(MouseEvent e) {
                dragging = false;
                setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                float newVal = SensorMath.clamp(
                    dragStartVal - (dragStartX - e.getX()) * (max - min) / 200f, min, max);
                setValue(newVal);
                if (onValueChange != null) onValueChange.call(value);
            }
        });

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

        tick++;
        animValue += (value - animValue) * 0.12f;
        int W = getWidth(), H = getHeight(), cx = W / 2, cy = H / 2 + 10;
        int R = Math.min(W, H) / 2 - 22;

        g2.setFont(new Font("Monospaced", Font.BOLD, 10));
        g2.setColor(MUTED);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, cx - fm.stringWidth(label) / 2, 18);

        float pct = (animValue - min) / (max - min);
        Color col = SensorMath.interpolateColor(colorLow, colorHigh, pct);
        final int SA = 220, TS = 260;

        g2.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(25, 38, 70));
        g2.drawArc(cx - R, cy - R, R * 2, R * 2, SA, -TS);
        int sweep = (int)(TS * pct);
        g2.setColor(col);
        g2.drawArc(cx - R, cy - R, R * 2, R * 2, SA, -sweep);

        if (dragging) {
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 30));
            g2.setStroke(new BasicStroke(16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(cx - R, cy - R, R * 2, R * 2, SA, -sweep);
        }

        double angle = Math.toRadians(SA - sweep);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine((int)(cx + Math.cos(angle) * (R - 10)), (int)(cy - Math.sin(angle) * (R - 10)),
                    (int)(cx + Math.cos(angle) * (R + 2)),  (int)(cy - Math.sin(angle) * (R + 2)));

        int iR = R - 18;
        g2.setColor(BG3);
        g2.fillOval(cx - iR, cy - iR, iR * 2, iR * 2);
        g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 40));
        g2.fillOval(cx - iR, cy - iR, iR * 2, iR * 2);
        g2.setColor(BORDER);
        g2.setStroke(new BasicStroke(1));
        g2.drawOval(cx - iR, cy - iR, iR * 2, iR * 2);

        String vs = String.format("%.1f", animValue);
        g2.setFont(new Font("Monospaced", Font.BOLD, 20));
        fm = g2.getFontMetrics();
        g2.setColor(col);
        g2.drawString(vs, cx - fm.stringWidth(vs) / 2, cy + 7);

        g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
        fm = g2.getFontMetrics();
        g2.setColor(MUTED);
        g2.drawString(unit, cx - fm.stringWidth(unit) / 2, cy + 22);

        g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
        g2.setColor(new Color(50, 70, 120));
        double sR = Math.toRadians(SA), eR = Math.toRadians(SA - TS);
        g2.drawString(String.valueOf((int) min),
            (int)(cx + Math.cos(sR) * (R + 12)) - 8, (int)(cy - Math.sin(sR) * (R + 12)) + 4);
        g2.drawString(String.valueOf((int) max),
            (int)(cx + Math.cos(eR) * (R + 12)) - 4, (int)(cy - Math.sin(eR) * (R + 12)) + 4);

        g2.setColor(new Color(50, 75, 130));
        g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g2.drawString("↕", cx - 4, H - 6);
    }
}
