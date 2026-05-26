package view;

import model.SensorMath;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static model.AppColors.*;

/**
 * VISTA — Panel de previsualización meteorológica animada.
 * Recibe temperatura y humedad desde fuera (el Presenter las inyecta).
 */
public class WeatherPreview extends JPanel {

    private float temperature;
    private float humidity;

    private long tick = 0;
    private final List<float[]> drops = new ArrayList<>();
    private final Random rnd = new Random();

    public WeatherPreview(float initTemperature, float initHumidity) {
        this.temperature = initTemperature;
        this.humidity    = initHumidity;
        setBackground(BG2);
        setBorder(BorderFactory.createLineBorder(BORDER, 1));
    }

    public void update(float temperature, float humidity) {
        this.temperature = temperature;
        this.humidity    = humidity;
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        tick++;
        int W = getWidth(), H = getHeight();

        // Fondo degradado según temperatura
        Color bgT = temperature < 10 ? new Color(8, 20, 70)
                  : temperature < 25 ? new Color(10, 30, 80)
                  : temperature < 33 ? new Color(50, 28, 12)
                  : new Color(90, 14, 8);
        Color bgB = temperature < 10 ? new Color(14, 40, 100)
                  : temperature < 25 ? new Color(18, 55, 110)
                  : temperature < 33 ? new Color(140, 75, 18)
                  : new Color(190, 42, 12);
        g2.setPaint(new GradientPaint(0, 0, bgT, 0, H, bgB));
        g2.fillRect(0, 0, W, H);
        g2.setPaint(null);

        // Partículas: lluvia o nieve
        if (humidity > 65 && drops.size() < 60 && tick % 2 == 0)
            drops.add(new float[]{rnd.nextInt(W * 3 / 4), -6,
                (rnd.nextFloat() - .5f) * 1.2f, 4 + rnd.nextFloat() * 3, 60});
        if (temperature < 8 && drops.size() < 40 && tick % 3 == 0)
            drops.add(new float[]{rnd.nextInt(W * 3 / 4), -6,
                (rnd.nextFloat() - .5f) * .8f, 1.2f + rnd.nextFloat() * 1.2f, 100});

        drops.removeIf(d -> { d[0] += d[2]; d[1] += d[3]; d[4]--; return d[1] > H + 10 || d[4] <= 0; });
        for (float[] d : drops) {
            float a = Math.min(1f, d[4] / 30f);
            if (temperature < 8) {
                g2.setColor(new Color(210, 230, 255, (int)(180 * a)));
                g2.fillOval((int) d[0] - 2, (int) d[1] - 2, 4, 4);
            } else {
                g2.setColor(new Color(140, 190, 255, (int)(140 * a)));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine((int) d[0], (int) d[1],
                    (int)(d[0] + d[2] * 3), (int)(d[1] + d[3] * 3));
            }
        }

        float pulse = (float)(Math.sin(tick * .07) * .1 + 1.0);
        int cx = W / 4, cy = H / 2;

        if (temperature >= 32) {
            // Sol intenso
            int sr = (int)(28 * pulse);
            for (int i = 3; i > 0; i--) {
                g2.setColor(new Color(255, 150, 0, 12 * i));
                g2.fillOval(cx - sr - i * 8, cy - sr - i * 8, (sr + i * 8) * 2, (sr + i * 8) * 2);
            }
            g2.setColor(new Color(255, 200, 50, 200));
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 8; i++) {
                double ang = Math.toRadians(i * 45 + tick * .4);
                g2.drawLine((int)(cx + Math.cos(ang) * (sr + 2)),  (int)(cy + Math.sin(ang) * (sr + 2)),
                            (int)(cx + Math.cos(ang) * (sr + 12)), (int)(cy + Math.sin(ang) * (sr + 12)));
            }
            g2.setColor(new Color(255, 210, 55, 230));
            g2.fillOval(cx - sr, cy - sr, sr * 2, sr * 2);

        } else if (humidity > 65) {
            // Nube
            int s = 28;
            g2.setColor(new Color(190, 210, 235, 200));
            g2.fillOval(cx - s, cy - s / 2, s * 2, s);
            g2.fillOval(cx - s / 3, cy - s * 3 / 4, (int)(s * 1.3), (int)(s * .95));
            g2.fillOval(cx + s / 5, cy - s / 2, (int)(s * 1.3), s);

        } else {
            // Sol normal
            int sr = (int)(24 * pulse);
            g2.setColor(new Color(255, 205, 55, 220));
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 8; i++) {
                double ang = Math.toRadians(i * 45 + tick * .35);
                g2.drawLine((int)(cx + Math.cos(ang) * (sr + 2)),  (int)(cy + Math.sin(ang) * (sr + 2)),
                            (int)(cx + Math.cos(ang) * (sr + 11)), (int)(cy + Math.sin(ang) * (sr + 11)));
            }
            g2.setColor(new Color(255, 210, 60, 230));
            g2.fillOval(cx - sr, cy - sr, sr * 2, sr * 2);
        }

        // Copo de nieve
        if (temperature < 8) {
            g2.setColor(new Color(200, 228, 255, 180));
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int sR = 14;
            double rot = Math.toRadians(tick * .7);
            for (int i = 0; i < 6; i++) {
                double ang = rot + i * 60 * Math.PI / 180;
                g2.drawLine(cx, cy + 50,
                    (int)(cx + Math.cos(ang) * sR), (int)(cy + 50 + Math.sin(ang) * sR));
            }
            g2.fillOval(cx - 3, cy + 47, 6, 6);
        }

        // Texto temperatura / humedad / índice de calor
        Color tc  = SensorMath.tempColor(temperature);
        int   rx  = W / 2 + 10;
        String ts = String.format("%.1f°", temperature);
        g2.setFont(new Font("Monospaced", Font.BOLD, 40));
        g2.setColor(new Color(0, 0, 0, 80));
        g2.drawString(ts, rx + 2, H - 22 + 2);
        g2.setColor(tc);
        g2.drawString(ts, rx, H - 22);

        g2.setFont(new Font("Monospaced", Font.PLAIN, 14));
        g2.setColor(new Color(130, 185, 255, 200));
        g2.drawString(String.format("%.0f%% HR", humidity), rx, H - 4);

        float hi = SensorMath.computeHeatIndex(temperature, humidity);
        g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g2.setColor(MUTED);
        g2.drawString(String.format("HI: %.1f°C", hi), rx, 16);

        String cond = temperature < 5  ? "Helada"
                    : temperature < 15 ? "Frío"
                    : temperature < 22 ? "Fresco"
                    : temperature < 28 && humidity < 60 ? "Confort"
                    : humidity > 85    ? "Muy húmedo"
                    : humidity > 70    ? "Húmedo"
                    : temperature < 32 ? "Cálido"
                    : temperature < 38 ? "Caluroso"
                    : "Extremo";
        g2.setFont(new Font("Monospaced", Font.BOLD, 11));
        g2.setColor(tc);
        g2.drawString(cond, rx, 30);
    }
}
