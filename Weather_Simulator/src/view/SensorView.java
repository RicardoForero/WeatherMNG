package view;

import model.PlantSlot;
import model.SensorModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;

import static model.AppColors.*;

/**
 * VISTA — Ventana principal Swing del simulador ESP32.
 * Implementa ISensorView e interactúa con el Presenter a través de callbacks.
 *
 * Secciones de UI:
 *  1. Header: título, campos de conexión, botón conectar.
 *  2. Centro: VSliders (temp/hum), DragKnobs, XYPad, WeatherPreview, stats, log.
 *  3. Panel de plantas (SOUTH): 4 filas, una por slot, con slider manual de
 *     humedad de sustrato, nombre de planta asignada, barra de estado, y
 *     botón 💧 para regar desde el simulador (no obligatorio: la UI refleja
 *     el estado más que controlar; el riego real viene del Admin).
 *  4. Footer: barra de estado.
 */
public class SensorView extends JFrame implements ISensorView {

    private static final Color C_PLANT_BG   = new Color(8, 22, 14);
    private static final Color C_PLANT_LINE = new Color(30, 70, 40);
    private static final Color C_HUM_LOW    = new Color(200, 80, 40);
    private static final Color C_HUM_OK     = new Color(50, 190, 90);
    private static final Color C_HUM_HIGH   = new Color(60, 150, 230);
    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    // ── Widgets principales ──────────────────────────────────
    public DragKnob      tempKnob, humKnob;
    public XYPad         xyPad;
    public VSlider       tempSlider, humSlider;
    public WeatherPreview preview;

    // ── Etiquetas de estado ──────────────────────────────────
    private JLabel tempValLbl, humValLbl, heatIdxLbl, msgCountLbl;
    private JLabel statusLbl, connLbl, nameLbl;

    // ── Campos de conexión ───────────────────────────────────
    private JTextField hostField, portField, nameField;
    private JButton    connectBtn;

    // ── Log ─────────────────────────────────────────────────
    private LogArea logArea;

    // ── Componentes de plantas (4 slots) ─────────────────────
    private JProgressBar[]  plantHumBars   = new JProgressBar[PlantSlot.MAX_SLOTS];
    private JLabel[]        plantNameLabels = new JLabel[PlantSlot.MAX_SLOTS];
    private JLabel[]        plantHumLabels  = new JLabel[PlantSlot.MAX_SLOTS];
    private JLabel[]        plantLastWater  = new JLabel[PlantSlot.MAX_SLOTS];
    private JSlider[]       plantHumSliders = new JSlider[PlantSlot.MAX_SLOTS];

    // ── Callbacks al Presenter ───────────────────────────────
    private Runnable onConnectToggle;
    private java.util.function.Consumer<Float> onTempChanged;
    private java.util.function.Consumer<Float> onHumChanged;
    private BiConsumer<Integer, Float>          onPlantHumidityChanged;

    public SensorView() {
        super("ESP32 DHT11 Simulator — Sensor");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1020, 800);
        setMinimumSize(new Dimension(880, 700));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG0);
        setLayout(new BorderLayout(0, 0));
        buildUI();
    }

    // ── Registro de listeners ────────────────────────────────
    public void setOnConnectToggle(Runnable r)                        { onConnectToggle = r; }
    public void setOnTempChanged(java.util.function.Consumer<Float> c){ onTempChanged = c; }
    public void setOnHumChanged(java.util.function.Consumer<Float> c) { onHumChanged = c; }
    public void setOnPlantHumidityChanged(BiConsumer<Integer, Float> c){ onPlantHumidityChanged = c; }

    // ══════════════ ISensorView ══════════════════════════════

    @Override public void setTemperatureDisplay(float value, Color color) {
        SwingUtilities.invokeLater(() -> {
            tempValLbl.setText(String.format("%.1f°C", value));
            tempValLbl.setForeground(color);
        });
    }

    @Override public void setHumidityDisplay(float value) {
        SwingUtilities.invokeLater(() -> {
            humValLbl.setText(String.format("%.1f%%", value));
            humValLbl.setForeground(HUMID);
        });
    }

    @Override public void setHeatIndexDisplay(float value, Color color) {
        SwingUtilities.invokeLater(() -> {
            heatIdxLbl.setText(String.format("%.1f°C", value));
            heatIdxLbl.setForeground(color);
        });
    }

    @Override public void setMessageCount(int count) {
        SwingUtilities.invokeLater(() -> msgCountLbl.setText(String.valueOf(count)));
    }

    @Override public void setStatusText(String text) {
        SwingUtilities.invokeLater(() -> statusLbl.setText(text));
    }

    @Override public void showConnected(String host, int port) {
        SwingUtilities.invokeLater(() -> {
            connectBtn.setText("DESCONECTAR");
            connectBtn.setForeground(HOT);
            connLbl.setText("● CONECTADO — " + host + ":" + port);
            connLbl.setForeground(ACCENT);
            connLbl.setBackground(new Color(8, 50, 35));
        });
    }

    @Override public void showDisconnected() {
        SwingUtilities.invokeLater(() -> {
            connectBtn.setText("CONECTAR");
            connectBtn.setForeground(ACCENT);
            connLbl.setText("● DESCONECTADO");
            connLbl.setForeground(HOT);
            connLbl.setBackground(new Color(60, 12, 12));
        });
    }

    @Override public void syncControls(float temperature, float humidity) {
        SwingUtilities.invokeLater(() -> {
            tempKnob.setValue(temperature);
            humKnob.setValue(humidity);
            tempSlider.setValue(temperature);
            humSlider.setValue(humidity);
            xyPad.setValues(temperature, humidity);
            preview.update(temperature, humidity);
        });
    }

    @Override public void syncPlantSlots(PlantSlot[] plants) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < PlantSlot.MAX_SLOTS; i++) {
                PlantSlot ps = plants[i];

                boolean hasPlant = !ps.isEmpty();
                plantNameLabels[i].setText(hasPlant ? ps.getPlantKey() : "(vacío)");
                plantNameLabels[i].setForeground(hasPlant ? C_HUM_OK : MUTED);

                int humPct = Math.round(ps.getHumidity());
                plantHumBars[i].setValue(Math.max(0, Math.min(100, humPct)));
                plantHumLabels[i].setText(String.format("%3d%%", humPct));

                // Color según rango
                Color hColor;
                if (!hasPlant || ps.getHumidity() == 0f) {
                    hColor = MUTED;
                } else if (ps.getHumidity() < ps.getHumMin()) {
                    hColor = C_HUM_LOW;
                } else if (ps.getHumidity() > ps.getHumMax()) {
                    hColor = C_HUM_HIGH;
                } else {
                    hColor = C_HUM_OK;
                }
                plantHumBars[i].setForeground(hColor);
                plantHumLabels[i].setForeground(hColor);

                // Slider manual — sincronizar sin disparar evento
                JSlider sl = plantHumSliders[i];
                int newVal = Math.round(ps.getHumidity());
                if (sl.getValue() != newVal) {
                    // Suspendemos el ChangeListener temporalmente bloqueando
                    // con setValueIsAdjusting (no dispara cambios al soltar).
                    // Forma segura sin remover listeners.
                    boolean wasAdjusting = sl.getValueIsAdjusting();
                    sl.setValueIsAdjusting(true);
                    sl.setValue(newVal);
                    sl.setValueIsAdjusting(wasAdjusting);
                }

                // Último riego
                if (ps.getLastWatered() > 0) {
                    plantLastWater[i].setText("💧 " + DT_FMT.format(
                        Instant.ofEpochMilli(ps.getLastWatered())));
                } else {
                    plantLastWater[i].setText("Sin regar");
                }
            }
        });
    }

    @Override public void setDeviceNameDisplay(String name) {
        SwingUtilities.invokeLater(() -> {
            nameLbl.setText("  Nombre: " + name);
            nameField.setText(name);
        });
    }

    @Override public void appendLog(String message, Color color) {
        logArea.log(message, color);
    }

    @Override public String getHostInput() { return hostField.getText().trim(); }
    @Override public String getPortInput() { return portField.getText().trim(); }
    @Override public String getNameInput() { return nameField.getText().trim(); }

    // ══════════════ Construcción de la UI ════════════════════

    private void buildUI() {
        add(buildHeader(),  BorderLayout.NORTH);
        add(buildCenter(),  BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);
    }

    private JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout(16, 0));
        h.setBackground(BG1);
        h.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
            BorderFactory.createEmptyBorder(12, 20, 12, 20)));

        JPanel left = new JPanel(new BorderLayout(0, 3));
        left.setBackground(BG1);
        JLabel title = new JLabel("ESP32 DHT11 SIMULATOR");
        title.setFont(new Font("Monospaced", Font.BOLD, 18));
        title.setForeground(ACCENT);
        nameLbl = new JLabel("  Nombre: ESP32-SIM-01");
        nameLbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        nameLbl.setForeground(MUTED);
        JLabel sub = new JLabel("Cliente Sensor TCP · multi-planta · Envía lecturas al WeatherServer");
        sub.setFont(new Font("Monospaced", Font.PLAIN, 10));
        sub.setForeground(new Color(40, 80, 60));
        JPanel titlePanel = new JPanel(new BorderLayout(0, 2));
        titlePanel.setBackground(BG1);
        titlePanel.add(title,   BorderLayout.NORTH);
        titlePanel.add(nameLbl, BorderLayout.CENTER);
        titlePanel.add(sub,     BorderLayout.SOUTH);
        left.add(titlePanel, BorderLayout.NORTH);

        JPanel conn = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        conn.setBackground(BG1);
        hostField  = makeTextField(SensorModel.DEFAULT_HOST, 10);
        portField  = makeTextField(String.valueOf(SensorModel.DEFAULT_PORT), 5);
        nameField  = makeTextField("ESP32-SIM-01", 10);
        connectBtn = new JButton("CONECTAR");
        styleButton(connectBtn, ACCENT, BG2);
        connectBtn.addActionListener(e -> { if (onConnectToggle != null) onConnectToggle.run(); });

        conn.add(makeLabel("HOST:",   10, MUTED)); conn.add(hostField);
        conn.add(makeLabel("PORT:",   10, MUTED)); conn.add(portField);
        conn.add(makeLabel("NOMBRE:", 10, MUTED)); conn.add(nameField);
        conn.add(Box.createHorizontalStrut(4));
        conn.add(connectBtn);

        h.add(left, BorderLayout.WEST);
        h.add(conn, BorderLayout.EAST);
        return h;
    }

    private JPanel buildCenter() {
        JPanel center = new JPanel(new BorderLayout(10, 0));
        center.setBackground(BG0);
        center.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));

        JPanel sliderPanel = buildSliderPanel();
        sliderPanel.setPreferredSize(new Dimension(60, 0));
        JPanel right = buildRightPanel();
        right.setPreferredSize(new Dimension(220, 0));

        JPanel mainAndPlants = new JPanel(new BorderLayout(0, 6));
        mainAndPlants.setBackground(BG0);
        mainAndPlants.add(buildMainControls(), BorderLayout.CENTER);
        mainAndPlants.add(buildPlantsPanel(),  BorderLayout.SOUTH);

        center.add(sliderPanel,    BorderLayout.WEST);
        center.add(mainAndPlants,  BorderLayout.CENTER);
        center.add(right,          BorderLayout.EAST);
        return center;
    }

    private JPanel buildSliderPanel() {
        JPanel p = new JPanel(new GridLayout(2, 1, 0, 10));
        p.setBackground(BG0);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        tempSlider = new VSlider("TEMP", -10, 50, 25f, WARM);
        tempSlider.onValueChange = v -> { if (onTempChanged != null) onTempChanged.accept(v); };

        humSlider = new VSlider("HUM", 0, 100, 60f, HUMID);
        humSlider.onValueChange = v -> { if (onHumChanged != null) onHumChanged.accept(v); };

        p.add(tempSlider);
        p.add(humSlider);
        return p;
    }

    private JPanel buildMainControls() {
        JPanel p = new JPanel(new BorderLayout(10, 10));
        p.setBackground(BG0);

        JPanel knobRow = new JPanel(new GridLayout(1, 2, 16, 0));
        knobRow.setBackground(BG0);
        knobRow.setPreferredSize(new Dimension(0, 200));

        tempKnob = new DragKnob("TEMPERATURA", "°C", -10, 50, 25f, WARM, HOT);
        tempKnob.onValueChange = v -> { if (onTempChanged != null) onTempChanged.accept(v); };

        humKnob = new DragKnob("HUMEDAD", "%", 0, 100, 60f,
            new Color(60, 140, 255), HUMID);
        humKnob.onValueChange = v -> { if (onHumChanged != null) onHumChanged.accept(v); };

        knobRow.add(tempKnob);
        knobRow.add(humKnob);

        xyPad = new XYPad(25f, 60f);
        xyPad.onMove = (t, h) -> {
            if (onTempChanged != null) onTempChanged.accept(t);
            if (onHumChanged  != null) onHumChanged.accept(h);
        };

        preview = new WeatherPreview(25f, 60f);
        preview.setPreferredSize(new Dimension(0, 110));

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setBackground(BG0);
        bottom.add(xyPad,   BorderLayout.CENTER);
        bottom.add(preview, BorderLayout.SOUTH);

        p.add(knobRow, BorderLayout.NORTH);
        p.add(bottom,  BorderLayout.CENTER);
        return p;
    }

    // ── Panel de plantas ─────────────────────────────────────

    private JPanel buildPlantsPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(C_PLANT_BG);
        outer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(2, 0, 0, 0, C_PLANT_LINE),
            BorderFactory.createEmptyBorder(4, 0, 4, 0)));

        JLabel title = new JLabel("  🌱 GESTIÓN DE PLANTAS  (los valores de humedad se drenan solos; el riego llega desde Admin o puedes ajustar el slider)", SwingConstants.LEFT);
        title.setFont(new Font("Monospaced", Font.BOLD, 9));
        title.setForeground(new Color(80, 200, 100));
        title.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        outer.add(title, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(PlantSlot.MAX_SLOTS, 1, 0, 2));
        grid.setBackground(C_PLANT_BG);
        grid.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));

        for (int i = 0; i < PlantSlot.MAX_SLOTS; i++) {
            grid.add(buildPlantSlotRow(i));
        }
        outer.add(grid, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildPlantSlotRow(int slot) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(new Color(10, 28, 18));
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_PLANT_LINE, 1),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        // Etiqueta slot
        JLabel slotLbl = new JLabel("S" + slot);
        slotLbl.setFont(new Font("Monospaced", Font.BOLD, 10));
        slotLbl.setForeground(new Color(70, 180, 90));
        slotLbl.setPreferredSize(new Dimension(20, 0));

        // Nombre de la planta
        JLabel nameLbl = new JLabel("(vacío)");
        nameLbl.setFont(new Font("Monospaced", Font.BOLD, 11));
        nameLbl.setForeground(MUTED);
        nameLbl.setPreferredSize(new Dimension(160, 0));
        plantNameLabels[slot] = nameLbl;

        // Barra de progreso de humedad
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(0);
        bar.setStringPainted(false);
        bar.setPreferredSize(new Dimension(80, 8));
        bar.setBorderPainted(false);
        bar.setBackground(new Color(20, 45, 28));
        bar.setForeground(C_HUM_OK);
        plantHumBars[slot] = bar;

        JLabel humLbl = new JLabel("  0%");
        humLbl.setFont(new Font("Monospaced", Font.BOLD, 10));
        humLbl.setForeground(MUTED);
        humLbl.setPreferredSize(new Dimension(36, 0));
        plantHumLabels[slot] = humLbl;

        // Slider manual de humedad (permite override local)
        JSlider slider = new JSlider(0, 100, 0);
        slider.setBackground(new Color(10, 28, 18));
        slider.setForeground(C_HUM_OK);
        slider.setPreferredSize(new Dimension(120, 24));
        slider.setToolTipText("Ajustar humedad de sustrato manualmente");
        final int slotIdx = slot;
        slider.addChangeListener(e -> {
            if (!slider.getValueIsAdjusting() && onPlantHumidityChanged != null)
                onPlantHumidityChanged.accept(slotIdx, (float) slider.getValue());
        });
        plantHumSliders[slot] = slider;

        // Último riego
        JLabel wLbl = new JLabel("Sin regar");
        wLbl.setFont(new Font("Monospaced", Font.PLAIN, 9));
        wLbl.setForeground(new Color(60, 100, 70));
        wLbl.setPreferredSize(new Dimension(120, 0));
        wLbl.setHorizontalAlignment(SwingConstants.RIGHT);
        plantLastWater[slot] = wLbl;

        // Ensamblado
        JPanel left = new JPanel(new BorderLayout(4, 0));
        left.setBackground(new Color(10, 28, 18));
        left.add(slotLbl, BorderLayout.WEST);
        left.add(nameLbl, BorderLayout.CENTER);

        JPanel humPanel = new JPanel(new BorderLayout(4, 0));
        humPanel.setBackground(new Color(10, 28, 18));
        humPanel.add(bar,     BorderLayout.CENTER);
        humPanel.add(humLbl,  BorderLayout.EAST);

        JPanel midPanel = new JPanel(new BorderLayout(4, 0));
        midPanel.setBackground(new Color(10, 28, 18));
        midPanel.add(humPanel, BorderLayout.NORTH);
        midPanel.add(slider,   BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(4, 0));
        right.setBackground(new Color(10, 28, 18));
        right.add(midPanel, BorderLayout.CENTER);
        right.add(wLbl,     BorderLayout.EAST);

        row.add(left,  BorderLayout.WEST);
        row.add(right, BorderLayout.CENTER);
        return row;
    }

    private JPanel buildRightPanel() {
        JPanel stats = new JPanel(new GridLayout(4, 1, 0, 4));
        stats.setBackground(BG2);
        stats.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)));

        tempValLbl  = makeStatRow(stats, "TEMPERATURA",   "—°C");
        humValLbl   = makeStatRow(stats, "HUMEDAD AMB",   "—%");
        heatIdxLbl  = makeStatRow(stats, "ÍND. CALOR",   "—°C");
        msgCountLbl = makeStatRow(stats, "MENSAJES ENV.", "0");

        connLbl = new JLabel("● DESCONECTADO");
        connLbl.setFont(new Font("Monospaced", Font.BOLD, 11));
        connLbl.setForeground(HOT);
        connLbl.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        connLbl.setHorizontalAlignment(SwingConstants.CENTER);
        connLbl.setOpaque(true);
        connLbl.setBackground(new Color(60, 12, 12));

        logArea = new LogArea();

        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.setBackground(BG0);
        wrap.add(stats, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 6));
        mid.setBackground(BG0);
        mid.add(connLbl, BorderLayout.NORTH);
        mid.add(logArea, BorderLayout.CENTER);
        wrap.add(mid, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildFooter() {
        JPanel f = new JPanel(new BorderLayout());
        f.setBackground(new Color(5, 8, 18));
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
            BorderFactory.createEmptyBorder(5, 14, 5, 14)));

        statusLbl = new JLabel("Listo. Configura host/port y presiona CONECTAR.");
        statusLbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
        statusLbl.setForeground(MUTED);

        JLabel hint = new JLabel("Knob: arrastrar↕  |  XY-Pad: mover cursor  |  Plantas: asignar desde Admin");
        hint.setFont(new Font("Monospaced", Font.PLAIN, 10));
        hint.setForeground(new Color(45, 65, 110));
        hint.setHorizontalAlignment(SwingConstants.RIGHT);

        f.add(statusLbl, BorderLayout.WEST);
        f.add(hint,      BorderLayout.EAST);
        return f;
    }

    // ══════════════ Utilidades privadas de UI ════════════════

    private JLabel makeLabel(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.PLAIN, size));
        l.setForeground(color);
        return l;
    }

    private JTextField makeTextField(String text, int cols) {
        JTextField tf = new JTextField(text, cols);
        tf.setFont(new Font("Monospaced", Font.PLAIN, 11));
        tf.setBackground(BG2);
        tf.setForeground(TEXT);
        tf.setCaretColor(ACCENT);
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        return tf;
    }

    private void styleButton(JButton btn, Color fg, Color bg) {
        btn.setFont(new Font("Monospaced", Font.BOLD, 11));
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(fg.darker(), 1),
            BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setBackground(fg.darker()); btn.setForeground(TEXT); }
            @Override public void mouseExited(MouseEvent e)  { btn.setBackground(bg); btn.setForeground(fg); }
        });
    }

    private JLabel makeStatRow(JPanel parent, String label, String initial) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BG2);
        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Monospaced", Font.PLAIN, 9));
        lbl.setForeground(MUTED);
        JLabel val = new JLabel(initial);
        val.setFont(new Font("Monospaced", Font.BOLD, 14));
        val.setForeground(TEXT);
        val.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(lbl, BorderLayout.WEST);
        row.add(val, BorderLayout.EAST);
        parent.add(row);
        return val;
    }
}
