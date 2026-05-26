package view;

import model.SensorMath;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

import static model.AppColors.*;

/**
 * VISTA — Panel XY: mueve el cursor para cambiar temperatura (X) y humedad (Y).
 */
public class XYPad extends JPanel {

    public interface Callback { void call(float t, float h); }

    private float   cursorX, cursorY, animX, animY;
    private boolean dragging = false;
    private long    tick = 0;

    public Callback onMove;

    public XYPad(float initTemperature, float initHumidity) {
        cursorX = (initTemperature - (-10)) / 60f;
        cursorY = 1f - initHumidity / 100f;
        animX   = cursorX;
        animY   = cursorY;

        setBackground(BG2);
        setBorder(BorderFactory.createLineBorder(BORDER, 1));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { dragging = true;  update(e); }
            @Override public void mouseReleased(MouseEvent e) { dragging = false; }
            @Override public void mouseDragged(MouseEvent e)  { update(e); }

            void update(MouseEvent e) {
                int   pad = 20;
                int   W   = getWidth()  - pad * 2;
                int   H   = getHeight() - pad * 2;
                cursorX = SensorMath.clamp01((e.getX() - pad) / (float) W);
                cursorY = SensorMath.clamp01((e.getY() - pad) / (float) H);
                float t = -10 + cursorX * 60f;
                float h = 100 - cursorY * 100f;
                if (onMove != null) onMove.call(t, h);
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    public void setValues(float t, float h) {
        cursorX = (t - (-10)) / 60f;
        cursorY = 1f - h / 100f;
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        tick++;
        animX += (cursorX - animX) * 0.15f;
        animY += (cursorY - animY) * 0.15f;

        int W = getWidth(), H = getHeight(), pad = 20, iW = W - pad * 2, iH = H - pad * 2;

        // Gradiente temperatura (horizontal)
        for (int x = 0; x < iW; x++) {
            Color col = SensorMath.interpolateColor(COLD, HOT, x / (float) iW);
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 40));
            g2.fillRect(pad + x, pad, 1, iH);
        }
        // Gradiente humedad (vertical)
        for (int y = 0; y < iH; y++) {
            g2.setColor(new Color(HUMID.getRed(), HUMID.getGreen(), HUMID.getBlue(),
                (int)(40 * (1 - y / (float) iH))));
            g2.fillRect(pad, pad + y, iW, 1);
        }

        // Grid
        g2.setColor(new Color(40, 60, 110, 40));
        g2.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            1f, new float[]{4, 6}, 0));
        for (int i = 1; i < 4; i++) {
            g2.drawLine(pad + iW * i / 4, pad, pad + iW * i / 4, pad + iH);
            g2.drawLine(pad, pad + iH * i / 4, pad + iW, pad + iH * i / 4);
        }

        // Cursor
        int   px = pad + (int)(animX * iW);
        int   py = pad + (int)(animY * iH);
        Color cc = SensorMath.interpolateColor(COLD, HOT, animX);

        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            1f, new float[]{3, 4}, 0));
        g2.setColor(new Color(cc.getRed(), cc.getGreen(), cc.getBlue(), 140));
        g2.drawLine(pad, py, pad + iW, py);
        g2.drawLine(px, pad, px, pad + iH);

        int hR = dragging ? 22 : 16;
        g2.setColor(new Color(cc.getRed(), cc.getGreen(), cc.getBlue(), 25));
        g2.setStroke(new BasicStroke(1));
        g2.fillOval(px - hR, py - hR, hR * 2, hR * 2);

        int cr = 7;
        g2.setColor(BG0);
        g2.fillOval(px - cr, py - cr, cr * 2, cr * 2);
        g2.setColor(cc);
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawOval(px - cr, py - cr, cr * 2, cr * 2);
        g2.fillOval(px - 3, py - 3, 6, 6);

        // Etiquetas
        g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
        g2.setColor(MUTED);
        g2.drawString("← FRÍO     TEMPERATURA     CALIENTE →", pad, H - 5);
        g2.drawString("↑ HÚMEDO", 1, pad + 10);
        g2.drawString("↓ SECO",   1, pad + iH - 2);

        // Tooltip de valor
        float dispT = -10 + cursorX * 60f;
        float dispH = 100 - cursorY * 100f;
        String info = String.format("T=%.1f°  H=%.0f%%", dispT, dispH);
        g2.setFont(new Font("Monospaced", Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(new Color(0, 0, 0, 120));
        g2.fillRoundRect(px + 10, py - 20, fm.stringWidth(info) + 10, 18, 4, 4);
        g2.setColor(cc);
        g2.drawString(info, px + 15, py - 6);
    }
}
