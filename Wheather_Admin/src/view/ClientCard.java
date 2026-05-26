package view;

import model.SensorData;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static model.AppColors.*;

/**
 * Tarjeta visual de un sensor.
 * Sólo depende de {@link SensorData} (modelo), sin referencias al Presenter ni al Frame.
 */
public class ClientCard extends JPanel {

    private final SensorData data;

    // Animación
    private long         animTick = 0;
    private float        alpha    = 0f;
    private final List<float[]> particles = new ArrayList<>();
    private final Random rnd = new Random();

    // Componentes
    private JLabel    nameLbl, condLbl, rssiLbl, uptimeLbl, msgCountLbl2;
    private MiniGauge tempGauge, humGauge;

    public ClientCard(SensorData data) {
        this.data = data;
        setPreferredSize(new Dimension(340, 480));
        setBackground(C_BG3);
        setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
        setLayout(new BorderLayout());
        buildCard();
    }

    public void updateName(String newName) {
        data.name = newName;
        nameLbl.setText(newName);
    }

    public void refresh() {
        animTick++;
        alpha = Math.min(1f, alpha + 0.04f);

        condLbl.setText(data.condition());
        condLbl.setForeground(data.online ? data.tempColor() : C_MUTED);
        nameLbl.setForeground(data.online ? C_TEXT : C_MUTED);
        rssiLbl.setText(data.rssi == 0 ? "— dBm" : data.rssi + " dBm");
        long up = data.uptime;
        uptimeLbl.setText(String.format("Uptime: %02d:%02d:%02d", up / 3600, (up % 3600) / 60, up % 60));
        msgCountLbl2.setText(data.msgCount + " msg");

        tempGauge.setValue(data.temp);
        tempGauge.setColor(data.tempColor());
        tempGauge.repaint();

        humGauge.setValue(data.hum);
        humGauge.setColor(C_HUMID);
        humGauge.repaint();

        repaint();
    }

    /* ── Construcción ─────────────────────────────────── */
    private void buildCard() {
        ScenePanel scene = new ScenePanel();
        scene.setPreferredSize(new Dimension(340, 220));

        // Header
        JPanel info = new JPanel(new BorderLayout(8, 0));
        info.setBackground(C_BG2);
        info.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        nameLbl = new JLabel(data.name);
        nameLbl.setFont(new Font("Monospaced", Font.BOLD, 13));
        nameLbl.setForeground(C_TEXT);
        condLbl = new JLabel("Conectando…");
        condLbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        condLbl.setForeground(C_MUTED);
        JPanel ng = new JPanel(new BorderLayout(0, 2));
        ng.setBackground(C_BG2);
        ng.add(nameLbl, BorderLayout.NORTH);
        ng.add(condLbl, BorderLayout.SOUTH);

        JLabel ipLbl = new JLabel(data.ip);
        ipLbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        ipLbl.setForeground(C_MUTED);
        rssiLbl = new JLabel("— dBm");
        rssiLbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        rssiLbl.setForeground(C_MUTED);
        rssiLbl.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel ir = new JPanel(new BorderLayout(0, 2));
        ir.setBackground(C_BG2);
        ir.add(ipLbl,   BorderLayout.NORTH);
        ir.add(rssiLbl, BorderLayout.SOUTH);

        JLabel badge = new JLabel("[ADMIN]");
        badge.setFont(new Font("Monospaced", Font.BOLD, 9));
        badge.setForeground(C_ADMIN);
        badge.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel irWrap = new JPanel(new BorderLayout(0, 1));
        irWrap.setBackground(C_BG2);
        irWrap.add(ir,    BorderLayout.CENTER);
        irWrap.add(badge, BorderLayout.SOUTH);

        info.add(ng,     BorderLayout.WEST);
        info.add(irWrap, BorderLayout.EAST);

        // Gauges
        JPanel gaugeRow = new JPanel(new GridLayout(1, 2, 8, 0));
        gaugeRow.setBackground(C_BG3);
        gaugeRow.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        tempGauge = new MiniGauge("TEMPERATURA", "°C", -10, 50);
        humGauge  = new MiniGauge("HUMEDAD",     "%",   0, 100);
        gaugeRow.add(tempGauge);
        gaugeRow.add(humGauge);

        // Mini gráfica
        MiniGraph graph = new MiniGraph(data);
        graph.setPreferredSize(new Dimension(340, 70));
        graph.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));

        // Footer
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(C_BG2);
        footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER),
            BorderFactory.createEmptyBorder(5, 12, 5, 12)));
        uptimeLbl = new JLabel("Uptime: 0s");
        uptimeLbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        uptimeLbl.setForeground(new Color(60, 90, 140));
        msgCountLbl2 = new JLabel("0 msg");
        msgCountLbl2.setFont(new Font("Monospaced", Font.PLAIN, 10));
        msgCountLbl2.setForeground(new Color(60, 90, 140));
        msgCountLbl2.setHorizontalAlignment(SwingConstants.RIGHT);
        footer.add(uptimeLbl,   BorderLayout.WEST);
        footer.add(msgCountLbl2, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(C_BG3);
        body.add(gaugeRow, BorderLayout.CENTER);
        body.add(graph,    BorderLayout.SOUTH);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(C_BG3);
        wrap.add(body,   BorderLayout.NORTH);
        wrap.add(footer, BorderLayout.SOUTH);

        add(info,  BorderLayout.NORTH);
        add(scene, BorderLayout.CENTER);
        add(wrap,  BorderLayout.SOUTH);
    }

    /* ══════════════════════════════════════════════════════
       ESCENA ANIMADA
       ══════════════════════════════════════════════════════ */
    private class ScenePanel extends JPanel {
        ScenePanel() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int W = getWidth(), H = getHeight();
            drawBg(g2, W, H);
            spawnUpdate(W, H);
            drawParticles(g2, H);
            drawIcon(g2, W, H);
            drawTempOverlay(g2, W, H);
            if (alpha < 1f) {
                g2.setColor(new Color(C_BG3.getRed(), C_BG3.getGreen(), C_BG3.getBlue(), (int)(255 * (1 - alpha))));
                g2.fillRect(0, 0, W, H);
            }
        }

        private void drawBg(Graphics2D g2, int W, int H) {
            Color top, bot;
            if (!data.online || Float.isNaN(data.temp)) {
                top = new Color(14, 22, 50); bot = new Color(10, 16, 38);
            } else if (data.temp < 5) {
                top = new Color(8, 20, 70);   bot = new Color(14, 40, 100);
            } else if (data.temp < 15) {
                top = new Color(10, 30, 80);  bot = new Color(18, 55, 110);
            } else if (data.temp < 25) {
                top = new Color(12, 40, 90);  bot = new Color(22, 70, 130);
            } else if (data.temp < 32) {
                top = new Color(50, 30, 15);  bot = new Color(130, 80, 20);
            } else {
                top = new Color(100, 15, 8);  bot = new Color(200, 50, 15);
            }
            g2.setPaint(new GradientPaint(0, 0, top, 0, H, bot));
            g2.fillRect(0, 0, W, H);
            g2.setPaint(null);
        }

        private void spawnUpdate(int W, int H) {
            if (Float.isNaN(data.hum)) return;
            if (data.hum > 80 && particles.size() < 80)
                particles.add(new float[]{rnd.nextInt(W), -8, (rnd.nextFloat() - .5f) * 1.5f, 6 + rnd.nextFloat() * 3, 70, 0});
            else if (data.hum > 55 && particles.size() < 40)
                particles.add(new float[]{rnd.nextInt(W), -8, (rnd.nextFloat() - .5f), 3.5f + rnd.nextFloat() * 2, 70, 1});
            if (data.temp < 10 && particles.size() < 50)
                particles.add(new float[]{rnd.nextInt(W), -8, (rnd.nextFloat() - .5f), 1.2f + rnd.nextFloat() * 1.2f, 110, 2});
            if (data.temp > 32 && particles.size() < 12 && rnd.nextInt(4) == 0)
                particles.add(new float[]{W / 2f + rnd.nextInt(80) - 40, H / 2f + rnd.nextInt(60) - 30,
                    (rnd.nextFloat() - .5f) * 2.5f, (rnd.nextFloat() - .5f) * 2.5f, 35, 3});
            particles.removeIf(p -> { p[0] += p[2]; p[1] += p[3]; p[4]--; return p[1] > H + 10 || p[4] <= 0; });
        }

        private void drawParticles(Graphics2D g2, int H) {
            for (float[] p : new ArrayList<>(particles)) {
                float a = Math.min(1f, p[4] / 30f);
                int type = (int) p[5];
                if (type == 0 || type == 1) {
                    g2.setColor(new Color(140, 190, 255, (int)(140 * a)));
                    g2.setStroke(new BasicStroke(type == 0 ? 1.2f : 0.8f));
                    g2.drawLine((int)p[0], (int)p[1], (int)(p[0] + p[2] * 4), (int)(p[1] + p[3] * 4));
                } else if (type == 2) {
                    g2.setColor(new Color(220, 238, 255, (int)(200 * a)));
                    g2.fillOval((int)p[0] - 2, (int)p[1] - 2, 4, 4);
                } else {
                    int sl = (int)(10 * a);
                    g2.setColor(new Color(255, 210, 70, (int)(220 * a)));
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int px = (int)p[0], py = (int)p[1];
                    g2.drawLine(px - sl, py, px + sl, py);
                    g2.drawLine(px, py - sl, px, py + sl);
                }
            }
        }

        private void drawIcon(Graphics2D g2, int W, int H) {
            if (!data.online || Float.isNaN(data.temp)) {
                g2.setColor(new Color(50, 70, 110, 120));
                g2.setFont(new Font("Monospaced", Font.PLAIN, 32));
                g2.drawString("✕", W / 2 - 12, H / 2 + 10);
                return;
            }
            int cx = W / 2, cy = H / 2 - 20;
            float pulse = (float)(Math.sin(animTick * 0.06) * 0.1 + 1.0);

            if (data.temp >= 32) {
                int r = (int)(38 * pulse);
                for (int i = 3; i > 0; i--) {
                    g2.setColor(new Color(255, 180, 0, 10 * i));
                    g2.fillOval(cx - r - i * 9, cy - r - i * 9, (r + i * 9) * 2, (r + i * 9) * 2);
                }
                g2.setColor(new Color(255, 210, 60, 200));
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 8; i++) {
                    double an = Math.toRadians(i * 45 + animTick * 0.35);
                    g2.drawLine((int)(cx + Math.cos(an) * (r + 3)), (int)(cy + Math.sin(an) * (r + 3)),
                                (int)(cx + Math.cos(an) * (r + 14)), (int)(cy + Math.sin(an) * (r + 14)));
                }
                g2.setColor(new Color(255, 210, 60, 240));
                g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            } else if (data.hum > 70) {
                int s = 46;
                g2.setColor(new Color(195, 215, 238, 210));
                g2.fillOval(cx - s, cy - s / 2, s * 2, s);
                g2.fillOval(cx - s / 3, cy - s * 3 / 4, (int)(s * 1.3), (int)(s * .95));
                g2.fillOval(cx + s / 4, cy - s / 2, (int)(s * 1.3), s);
                if (data.hum > 85) {
                    g2.setColor(new Color(130, 185, 255, 190));
                    g2.setStroke(new BasicStroke(1.4f));
                    int off = (int)(animTick * 3) % 18;
                    for (int i = 0; i < 5; i++) {
                        int dx = cx - s / 2 + i * (s / 3);
                        g2.drawLine(dx, cy + s / 3 + off, dx - 2, cy + s / 3 + off + 10);
                    }
                }
            } else if (data.hum > 50) {
                int r = (int)(24 * pulse);
                g2.setColor(new Color(255, 210, 60, 240));
                g2.fillOval(cx - 18 - r, cy - 6 - r, r * 2, r * 2);
                int s = 34;
                g2.setColor(new Color(195, 215, 238, 210));
                g2.fillOval(cx + 14 - s / 2, cy + 4 - s / 4, s, s / 2 + 4);
            } else {
                int r = (int)(36 * pulse);
                g2.setColor(new Color(255, 210, 60, 240));
                g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            }
            if (data.temp < 10) {
                g2.setColor(new Color(200, 228, 255, 190));
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int sR = 16;
                double rot = Math.toRadians(animTick * 0.6);
                for (int i = 0; i < 6; i++) {
                    double an = rot + Math.toRadians(i * 60);
                    g2.drawLine(cx, cy + 60,
                        (int)(cx + Math.cos(an) * sR), (int)(cy + 60 + Math.sin(an) * sR));
                }
                g2.fillOval(cx - 3, cy + 57, 6, 6);
            }
        }

        private void drawTempOverlay(Graphics2D g2, int W, int H) {
            if (Float.isNaN(data.temp)) return;
            String ts = String.format("%.1f°", data.temp);
            g2.setFont(new Font("Monospaced", Font.BOLD, 46));
            FontMetrics fm = g2.getFontMetrics();
            int tx = W / 2 - fm.stringWidth(ts) / 2, ty = H - 36;
            g2.setColor(new Color(0, 0, 0, 70));
            g2.drawString(ts, tx + 2, ty + 2);
            g2.setColor(data.tempColor());
            g2.drawString(ts, tx, ty);
            if (!Float.isNaN(data.hum)) {
                String hs = String.format("%.0f%% HR", data.hum);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 14));
                fm = g2.getFontMetrics();
                g2.setColor(new Color(140, 195, 255, 200));
                g2.drawString(hs, W / 2 - fm.stringWidth(hs) / 2, H - 14);
            }
        }
    }
}
