package view;

import model.SensorData;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static model.AppColors.*;

/**
 * Gráfica de líneas pequeña que muestra historial de temperatura y humedad.
 */
public class MiniGraph extends JPanel {

    private final SensorData data;

    public MiniGraph(SensorData data) {
        this.data = data;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int W = getWidth(), H = getHeight();

        g2.setColor(new Color(12, 20, 45, 120));
        g2.fillRoundRect(0, 0, W, H, 6, 6);

        drawSeries(g2, data.histTemp, 0, 50,  W, H, C_WARM,  true);
        drawSeries(g2, data.histHum,  0, 100, W, H, C_HUMID, false);
    }

    private void drawSeries(Graphics2D g2, List<Float> series,
                            float mn, float mx, int W, int H,
                            Color col, boolean fill) {
        List<Float> snap;
        synchronized (series) { snap = new ArrayList<>(series); }
        if (snap.size() < 2) return;

        int n = snap.size();
        int[] xs = new int[n];
        int[] ys = new int[n];

        for (int i = 0; i < n; i++) {
            xs[i] = (int)(W * i / (float)(n - 1));
            float v = Math.max(mn, Math.min(mx, snap.get(i)));
            ys[i] = H - 4 - (int)((H - 8) * (v - mn) / (mx - mn));
        }

        if (fill) {
            Polygon poly = new Polygon(xs, ys, n);
            poly.addPoint(xs[n - 1], H);
            poly.addPoint(xs[0], H);
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 20));
            g2.fillPolygon(poly);
        }

        g2.setColor(fill
            ? col
            : new Color(col.getRed(), col.getGreen(), col.getBlue(), 160));
        g2.setStroke(new BasicStroke(
            fill ? 2.2f : 1.8f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND));

        for (int i = 0; i < n - 1; i++) {
            g2.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1]);
        }
        g2.fillOval(xs[n - 1] - 3, ys[n - 1] - 3, 6, 6);
    }
}
