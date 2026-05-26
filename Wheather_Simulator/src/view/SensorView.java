package view;

import model.SensorModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static model.AppColors.*;

/**
 * VISTA — Ventana principal Swing.
 * Implementa ISensorView e interactúa con el Presenter a través de callbacks.
 */
public class SensorView extends JFrame implements ISensorView {

    // ── Widgets ─────────────────────────────────────────────
    public DragKnob      tempKnob, humKnob;
    public XYPad         xyPad;
    public VSlider       tempSlider, humSlider;
    public WeatherPreview preview;

    // ── Etiquetas de estado ──────────────────────────────────
    private JLabel tempValLbl, humValLbl, heatIdxLbl, msgCountLbl;
    private JLabel statusLbl, connLbl;

    // ── Campos de conexión ───────────────────────────────────
    private JTextField hostField, portField, nameField;
    private JButton    connectBtn;

    // ── Log ─────────────────────────────────────────────────
    private LogArea logArea;

    // ── Callback al Presenter ────────────────────────────────
    private Runnable onConnectToggle;
    private java.util.function.Consumer<Float> onTempChanged;
    private java.util.function.Consumer<Float> onHumChanged;

    public SensorView() {
        super("ESP32 DHT11 Simulator — Sensor");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(980, 720);
        setMinimumSize(new Dimension(860, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG0);
        setLayout(new BorderLayout(0, 0));
        buildUI();
    }

    // ── Registro de listeners del Presenter ─────────────────
    public void setOnConnectToggle(Runnable r)                              { onConnectToggle = r;  }
    public void setOnTempChanged(java.util.function.Consumer<Float> c)      { onTempChanged   = c;  }
    public void setOnHumChanged(java.util.function.Consumer<Float> c)       { onHumChanged    = c;  }

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
        JLabel sub = new JLabel("Cliente Sensor TCP · Envía lecturas al WeatherServer");
        sub.setFont(new Font("Monospaced", Font.PLAIN, 10));
        sub.setForeground(MUTED);
        left.add(title, BorderLayout.NORTH);
        left.add(sub,   BorderLayout.SOUTH);

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
        center.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel sliderPanel = buildSliderPanel();
        sliderPanel.setPreferredSize(new Dimension(60, 0));
        JPanel right = buildRightPanel();
        right.setPreferredSize(new Dimension(220, 0));

        center.add(sliderPanel,       BorderLayout.WEST);
        center.add(buildMainControls(), BorderLayout.CENTER);
        center.add(right,             BorderLayout.EAST);
        return center;
    }

    private JPanel buildSliderPanel() {
        JPanel p = new JPanel(new GridLayout(2, 1, 0, 10));
        p.setBackground(BG0);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        tempSlider = new VSlider("TEMP", -10, 50, 25f, WARM);
        tempSlider.onValueChange = v -> { if (onTempChanged != null) onTempChanged.accept(v); };

        humSlider = new VSlider("HUM", 0, 100, 60f, HUMID);
        humSlider.onValueChange = v -> { if (onHumChanged  != null) onHumChanged.accept(v); };

        p.add(tempSlider);
        p.add(humSlider);
        return p;
    }

    private JPanel buildMainControls() {
        JPanel p = new JPanel(new BorderLayout(10, 10));
        p.setBackground(BG0);

        JPanel knobRow = new JPanel(new GridLayout(1, 2, 16, 0));
        knobRow.setBackground(BG0);
        knobRow.setPreferredSize(new Dimension(0, 220));

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
        preview.setPreferredSize(new Dimension(0, 130));

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setBackground(BG0);
        bottom.add(xyPad,   BorderLayout.CENTER);
        bottom.add(preview, BorderLayout.SOUTH);

        p.add(knobRow, BorderLayout.NORTH);
        p.add(bottom,  BorderLayout.CENTER);
        return p;
    }

    private JPanel buildRightPanel() {
        JPanel stats = new JPanel(new GridLayout(4, 1, 0, 4));
        stats.setBackground(BG2);
        stats.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)));

        tempValLbl  = makeStatRow(stats, "TEMPERATURA",   "—°C");
        humValLbl   = makeStatRow(stats, "HUMEDAD",       "—%");
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

        JLabel hint = new JLabel("Knob: arrastrar↕  |  XY-Pad: mover cursor  |  Scroll: ajuste fino");
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
            @Override public void mouseExited(MouseEvent e)  { btn.setBackground(bg);          btn.setForeground(fg);   }
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
