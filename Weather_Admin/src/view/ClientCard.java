package view;

import model.PlantCatalog;
import model.PlantSlot;
import model.PlantSpec;
import model.SensorData;
import presenter.DashboardPresenter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.GeneralPath;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static model.AppColors.*;

/**
 * Tarjeta visual de un sensor ESP32.
 *
 * Secciones:
 *  1. Header con nombre editable + IP + RSSI.
 *  2. Escena animada (fondo dinámico según temp/hum).
 *  3. Gauges de temperatura y humedad ambiental + mini-gráfica.
 *  4. Panel de plantas: hasta 4 slots, cada uno con:
 *     • Combo desplegable para seleccionar especie del catálogo.
 *     • Mini-gauge de humedad del sustrato.
 *     • Botón "REGAR" para riego manual.
 *     • Timestamp del último riego.
 */
public class ClientCard extends JPanel {

    private static final Color C_PLANT_BG   = new Color(8, 22, 14);
    private static final Color C_PLANT_LINE = new Color(30, 70, 40);
    private static final Color C_WATER_BTN  = new Color(40, 130, 200);
    private static final Color C_HUM_LOW    = new Color(200, 80,  40);
    private static final Color C_HUM_OK     = new Color(50,  190, 90);
    private static final Color C_HUM_HIGH   = new Color(60,  150, 230);
    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    private final SensorData       data;
    private final DashboardPresenter presenter;

    // Animación
    private long              animTick = 0;
    private float             alpha    = 0f;
    private final List<float[]> particles = new ArrayList<>();
    private final Random      rnd      = new Random();

    // Componentes de cabecera
    private JTextField nameFld;
    private JLabel     condLbl, rssiLbl, uptimeLbl, msgCountLbl2;
    private MiniGauge  tempGauge, humGauge;

    // Componentes de slots de planta (por slot)
    private JComboBox<String>[] plantCombos;
    private JLabel[]            humLabels;
    private JProgressBar[]      humBars;
    private JLabel[]            lastWaterLabels;
    private JButton[]           waterBtns;

    // Catálogo actual (puede actualizarse desde AdminDashboardFrame)
    private PlantCatalog catalog = new PlantCatalog();

    // Evita que sincronizar el combo programáticamente dispare sendAssignPlant.
    private final boolean[] comboUpdating = new boolean[PlantSlot.MAX_SLOTS];

    @SuppressWarnings("unchecked")
    public ClientCard(SensorData data, DashboardPresenter presenter) {
        this.data      = data;
        this.presenter = presenter;
        plantCombos      = new JComboBox[PlantSlot.MAX_SLOTS];
        humLabels        = new JLabel[PlantSlot.MAX_SLOTS];
        humBars          = new JProgressBar[PlantSlot.MAX_SLOTS];
        lastWaterLabels  = new JLabel[PlantSlot.MAX_SLOTS];
        waterBtns        = new JButton[PlantSlot.MAX_SLOTS];

        setBackground(C_BG3);
        setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
        setLayout(new BorderLayout());
        buildCard();
    }

    /** Compatibilidad con el código anterior que no pasa presenter (no se usa plant features). */
    public ClientCard(SensorData data) {
        this(data, null);
    }

    /* ── API pública ──────────────────────────────────────── */

    public void updateName(String newName) {
        data.name = newName;
        if (!nameFld.hasFocus()) nameFld.setText(newName);
    }

    /** Llamado por AdminDashboardFrame cuando llega un nuevo catálogo del server. */
    public void updateCatalog(PlantCatalog catalog) {
        this.catalog = catalog;
        rebuildCombos();
    }

    public void refresh() {
        animTick++;
        alpha = Math.min(1f, alpha + 0.04f);

        condLbl.setText(data.condition());
        condLbl.setForeground(data.online ? data.tempColor() : C_MUTED);
        if (!nameFld.hasFocus()) {
            nameFld.setText(data.name);
            nameFld.setForeground(data.online ? C_TEXT : C_MUTED);
        }
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

        refreshPlantSlots();
        repaint();
    }

    /* ── Construcción ─────────────────────────────────── */

    private void buildCard() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(C_BG3);
        top.add(buildHeader(),  BorderLayout.NORTH);

        ScenePanel scene = new ScenePanel();
        scene.setPreferredSize(new Dimension(380, 190));
        top.add(scene, BorderLayout.CENTER);

        JPanel body = buildBodyPanel();
        JPanel plants = buildPlantsPanel();

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(C_BG3);
        south.add(body,   BorderLayout.NORTH);
        south.add(plants, BorderLayout.CENTER);

        add(top,   BorderLayout.NORTH);
        add(south, BorderLayout.CENTER);
    }

    private JPanel buildHeader() {
        JPanel info = new JPanel(new BorderLayout(8, 0));
        info.setBackground(C_BG2);
        info.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        // Nombre editable
        nameFld = new JTextField(data.name);
        nameFld.setFont(new Font("Monospaced", Font.BOLD, 12));
        nameFld.setForeground(C_TEXT);
        nameFld.setBackground(C_BG2);
        nameFld.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        nameFld.setToolTipText("Edita el nombre y presiona Enter para renombrar el ESP32");
        nameFld.addActionListener(e -> commitRename());
        nameFld.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitRename(); }
        });

        condLbl = new JLabel("Conectando…");
        condLbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        condLbl.setForeground(C_MUTED);

        JPanel ng = new JPanel(new BorderLayout(0, 4));
        ng.setBackground(C_BG2);
        ng.add(nameFld, BorderLayout.NORTH);
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

        JLabel badge = new JLabel("[ESP32]");
        badge.setFont(new Font("Monospaced", Font.BOLD, 9));
        badge.setForeground(C_ADMIN);
        badge.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel irWrap = new JPanel(new BorderLayout(0, 1));
        irWrap.setBackground(C_BG2);
        irWrap.add(ir,    BorderLayout.CENTER);
        irWrap.add(badge, BorderLayout.SOUTH);

        info.add(ng,     BorderLayout.CENTER);
        info.add(irWrap, BorderLayout.EAST);
        return info;
    }

    private JPanel buildBodyPanel() {
        JPanel gaugeRow = new JPanel(new GridLayout(1, 2, 8, 0));
        gaugeRow.setBackground(C_BG3);
        gaugeRow.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        tempGauge = new MiniGauge("TEMPERATURA", "°C", -10, 50);
        humGauge  = new MiniGauge("HUMEDAD AMB", "%",   0, 100);
        gaugeRow.add(tempGauge);
        gaugeRow.add(humGauge);

        MiniGraph graph = new MiniGraph(data);
        graph.setPreferredSize(new Dimension(380, 60));
        graph.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(C_BG2);
        footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        uptimeLbl = new JLabel("Uptime: 0s");
        uptimeLbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        uptimeLbl.setForeground(new Color(60, 90, 140));
        msgCountLbl2 = new JLabel("0 msg");
        msgCountLbl2.setFont(new Font("Monospaced", Font.PLAIN, 10));
        msgCountLbl2.setForeground(new Color(60, 90, 140));
        msgCountLbl2.setHorizontalAlignment(SwingConstants.RIGHT);
        footer.add(uptimeLbl,    BorderLayout.WEST);
        footer.add(msgCountLbl2, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(C_BG3);
        body.add(gaugeRow, BorderLayout.NORTH);
        body.add(graph,    BorderLayout.CENTER);
        body.add(footer,   BorderLayout.SOUTH);
        return body;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildPlantsPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(C_PLANT_BG);
        outer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(2, 0, 0, 0, C_PLANT_LINE),
            BorderFactory.createEmptyBorder(4, 0, 4, 0)));

        JLabel title = new JLabel("  🌱 PLANTAS (hasta 4 slots)", SwingConstants.LEFT);
        title.setFont(new Font("Monospaced", Font.BOLD, 10));
        title.setForeground(new Color(80, 200, 100));
        title.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        outer.add(title, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(PlantSlot.MAX_SLOTS, 1, 0, 2));
        grid.setBackground(C_PLANT_BG);
        grid.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));

        for (int i = 0; i < PlantSlot.MAX_SLOTS; i++) {
            grid.add(buildSlotRow(i));
        }
        outer.add(grid, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildSlotRow(int slot) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(new Color(10, 28, 18));
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_PLANT_LINE, 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        // Etiqueta de slot
        JLabel slotLbl = new JLabel("S" + slot);
        slotLbl.setFont(new Font("Monospaced", Font.BOLD, 9));
        slotLbl.setForeground(new Color(70, 180, 90));
        slotLbl.setPreferredSize(new Dimension(18, 0));

        // Combo de selección de planta
        JComboBox<String> combo = new JComboBox<>();
        combo.setFont(new Font("Monospaced", Font.PLAIN, 10));
        combo.setBackground(new Color(14, 35, 22));
        combo.setForeground(C_TEXT);
        combo.setToolTipText("Selecciona la planta asignada a este slot");
        populateCombo(combo, data.plants[slot].plantKey);
        combo.addActionListener(e -> {
            if (comboUpdating[slot] || !combo.isEnabled()) return;
            String selected = (String) combo.getSelectedItem();
            if (selected == null) return;
            String key = selected.equals("(vacío)") ? "" : selected.split(" \\(")[0];
            if (presenter != null) presenter.sendAssignPlant(data.id, slot, key);
        });
        plantCombos[slot] = combo;

        // Barra de humedad del sustrato
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(0);
        bar.setStringPainted(false);
        bar.setPreferredSize(new Dimension(50, 8));
        bar.setBorderPainted(false);
        bar.setBackground(new Color(20, 45, 28));
        bar.setForeground(C_HUM_OK);
        humBars[slot] = bar;

        JLabel humLbl = new JLabel("---%");
        humLbl.setFont(new Font("Monospaced", Font.BOLD, 10));
        humLbl.setForeground(C_HUM_OK);
        humLbl.setPreferredSize(new Dimension(38, 0));
        humLbl.setHorizontalAlignment(SwingConstants.RIGHT);
        humLabels[slot] = humLbl;

        // Botón regar
        JButton waterBtn = new JButton("💧");
        waterBtn.setFont(new Font("Monospaced", Font.PLAIN, 11));
        waterBtn.setForeground(C_WATER_BTN);
        waterBtn.setBackground(new Color(10, 28, 18));
        waterBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_WATER_BTN.darker(), 1),
            BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        waterBtn.setFocusPainted(false);
        waterBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        waterBtn.setToolTipText("Regar manualmente este slot");
        waterBtn.addActionListener(e -> {
            if (presenter != null) presenter.sendWater(data.id, slot);
            waterBtn.setForeground(C_HUM_OK);
            Timer reset = new Timer(1500, ev -> waterBtn.setForeground(C_WATER_BTN));
            reset.setRepeats(false);
            reset.start();
        });
        waterBtns[slot] = waterBtn;

        // Timestamp último riego
        JLabel wLbl = new JLabel("Sin regar");
        wLbl.setFont(new Font("Monospaced", Font.PLAIN, 8));
        wLbl.setForeground(new Color(60, 100, 70));
        lastWaterLabels[slot] = wLbl;

        // Layout interno
        JPanel left = new JPanel(new BorderLayout(3, 0));
        left.setBackground(new Color(10, 28, 18));
        left.add(slotLbl, BorderLayout.WEST);
        left.add(combo,   BorderLayout.CENTER);

        JPanel humPanel = new JPanel(new BorderLayout(3, 2));
        humPanel.setBackground(new Color(10, 28, 18));
        humPanel.add(bar,    BorderLayout.CENTER);
        humPanel.add(humLbl, BorderLayout.EAST);

        JPanel centerPanel = new JPanel(new BorderLayout(2, 1));
        centerPanel.setBackground(new Color(10, 28, 18));
        centerPanel.add(humPanel, BorderLayout.NORTH);
        centerPanel.add(wLbl,     BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(4, 0));
        right.setBackground(new Color(10, 28, 18));
        right.add(centerPanel, BorderLayout.CENTER);
        right.add(waterBtn,    BorderLayout.EAST);

        row.add(left,  BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    /* ── Actualización de slots de planta ─────────────── */

    private void refreshPlantSlots() {
        for (int i = 0; i < PlantSlot.MAX_SLOTS; i++) {
            PlantSlot ps  = data.plants[i];
            JProgressBar bar = humBars[i];
            JLabel humLbl    = humLabels[i];
            JLabel wLbl      = lastWaterLabels[i];
            JButton wBtn     = waterBtns[i];
            JComboBox<String> combo = plantCombos[i];

            boolean hasPlant = !ps.isEmpty();

            // Sincronizar combo sin disparar actionListener
            if (hasPlant) {
                String target = findComboKey(combo, ps.plantKey);
                if (target != null && !target.equals(combo.getSelectedItem())) {
                    comboUpdating[i] = true;
                    combo.setSelectedItem(target);
                    comboUpdating[i] = false;
                }
            } else if (!"(vacío)".equals(combo.getSelectedItem())) {
                comboUpdating[i] = true;
                combo.setSelectedItem("(vacío)");
                comboUpdating[i] = false;
            }

            // Humedad
            int humPct = Math.round(ps.humidity);
            bar.setValue(Math.max(0, Math.min(100, humPct)));
            humLbl.setText(String.format("%3d%%", humPct));

            // Color según rango si hay planta asignada
            PlantSpec spec = catalog.get(ps.plantKey);
            if (spec != null && !ps.isEmpty()) {
                Color humColor;
                if (ps.humidity < spec.humMin())     humColor = C_HUM_LOW;
                else if (ps.humidity > spec.humMax()) humColor = C_HUM_HIGH;
                else                                  humColor = C_HUM_OK;
                bar.setForeground(humColor);
                humLbl.setForeground(humColor);
            } else {
                bar.setForeground(new Color(50, 80, 60));
                humLbl.setForeground(new Color(60, 100, 70));
            }

            // Último riego
            if (ps.lastWatered > 0) {
                wLbl.setText("Regado: " + DT_FMT.format(Instant.ofEpochMilli(ps.lastWatered)));
            } else {
                wLbl.setText("Sin regar");
            }

            wBtn.setEnabled(hasPlant && data.online);
        }
    }

    private void rebuildCombos() {
        for (int i = 0; i < PlantSlot.MAX_SLOTS; i++) {
            comboUpdating[i] = true;
            populateCombo(plantCombos[i], data.plants[i].plantKey);
            comboUpdating[i] = false;
        }
    }

    private void populateCombo(JComboBox<String> combo, String currentKey) {
        combo.removeAllItems();
        combo.addItem("(vacío)");
        String toSelect = "(vacío)";
        for (PlantSpec spec : catalog.all()) {
            String label = spec.key() + " (" + (int)spec.humMin() + "–" + (int)spec.humMax() + "%)";
            combo.addItem(label);
            if (spec.key().equals(currentKey)) toSelect = label;
        }
        combo.setSelectedItem(toSelect);
    }

    private String findComboKey(JComboBox<String> combo, String plantKey) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            String item = combo.getItemAt(i);
            if (!item.equals("(vacío)") && item.split(" \\(")[0].equals(plantKey)) return item;
        }
        return null;
    }

    private void commitRename() {
        String newName = nameFld.getText().trim();
        if (!newName.isBlank() && !newName.equals(data.name) && presenter != null) {
            presenter.sendRename(data.id, newName);
        }
    }

    /* ══════════════════════════════════════════════════════
       ESCENA ANIMADA (conservada íntegra del original)
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
                g2.setColor(new Color(C_BG3.getRed(), C_BG3.getGreen(), C_BG3.getBlue(),
                    (int)(255 * (1 - alpha))));
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
                particles.add(new float[]{rnd.nextInt(W), -8,
                    (rnd.nextFloat() - .5f) * 1.5f, 6 + rnd.nextFloat() * 3, 70, 0});
            else if (data.hum > 55 && particles.size() < 40)
                particles.add(new float[]{rnd.nextInt(W), -8,
                    (rnd.nextFloat() - .5f), 3.5f + rnd.nextFloat() * 2, 70, 1});
            if (data.temp < 10 && particles.size() < 50)
                particles.add(new float[]{rnd.nextInt(W), -8,
                    (rnd.nextFloat() - .5f), 1.2f + rnd.nextFloat() * 1.2f, 110, 2});
            if (data.temp > 32 && particles.size() < 12 && rnd.nextInt(4) == 0)
                particles.add(new float[]{W / 2f + rnd.nextInt(80) - 40,
                    H / 2f + rnd.nextInt(60) - 30,
                    (rnd.nextFloat() - .5f) * 2.5f, (rnd.nextFloat() - .5f) * 2.5f, 35, 3});
            particles.removeIf(p -> {
                p[0] += p[2]; p[1] += p[3]; p[4]--;
                return p[1] > H + 10 || p[4] <= 0;
            });
        }

        private void drawParticles(Graphics2D g2, int H) {
            for (float[] p : new ArrayList<>(particles)) {
                float a = Math.min(1f, p[4] / 30f);
                int type = (int) p[5];
                if (type == 0 || type == 1) {
                    g2.setColor(new Color(140, 190, 255, (int)(140 * a)));
                    g2.setStroke(new BasicStroke(type == 0 ? 1.2f : 0.8f));
                    g2.drawLine((int)p[0], (int)p[1],
                        (int)(p[0] + p[2] * 4), (int)(p[1] + p[3] * 4));
                } else if (type == 2) {
                    g2.setColor(new Color(220, 238, 255, (int)(200 * a)));
                    g2.fillOval((int)p[0] - 2, (int)p[1] - 2, 4, 4);
                } else {
                    int sl = (int)(10 * a);
                    g2.setColor(new Color(255, 210, 70, (int)(220 * a)));
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
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
            int cx = W / 2, cy = H / 2 - 10;
            float pulse = (float)(Math.sin(animTick * 0.06) * 0.1 + 1.0);

            if (data.temp >= 32) {
                int r = (int)(32 * pulse);
                for (int i = 3; i > 0; i--) {
                    g2.setColor(new Color(255, 180, 0, 10 * i));
                    g2.fillOval(cx - r - i * 9, cy - r - i * 9, (r + i * 9) * 2, (r + i * 9) * 2);
                }
                g2.setColor(new Color(255, 210, 60, 240));
                g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            } else if (data.hum > 70) {
                int s = 40;
                g2.setColor(new Color(195, 215, 238, 210));
                g2.fillOval(cx - s, cy - s / 2, s * 2, s);
                g2.fillOval(cx - s / 3, cy - s * 3 / 4, (int)(s * 1.3), (int)(s * .95));
            } else {
                int r = (int)(30 * pulse);
                g2.setColor(new Color(255, 210, 60, 240));
                g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            }
        }

        private void drawTempOverlay(Graphics2D g2, int W, int H) {
            if (Float.isNaN(data.temp)) return;
            String ts = String.format("%.1f°", data.temp);
            g2.setFont(new Font("Monospaced", Font.BOLD, 38));
            FontMetrics fm = g2.getFontMetrics();
            int tx = W / 2 - fm.stringWidth(ts) / 2, ty = H - 28;
            g2.setColor(new Color(0, 0, 0, 70));
            g2.drawString(ts, tx + 2, ty + 2);
            g2.setColor(data.tempColor());
            g2.drawString(ts, tx, ty);
            if (!Float.isNaN(data.hum)) {
                String hs = String.format("%.0f%% HR", data.hum);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
                fm = g2.getFontMetrics();
                g2.setColor(new Color(140, 195, 255, 200));
                g2.drawString(hs, W / 2 - fm.stringWidth(hs) / 2, H - 10);
            }
        }
    }
}
