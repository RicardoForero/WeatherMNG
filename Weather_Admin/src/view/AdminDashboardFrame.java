package view;

import model.DashboardModel;
import model.SensorData;
import presenter.DashboardPresenter;
import model.AppColors;
import model.AppConfig;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

import static model.AppColors.*;

/**
 * Vista principal. Implementa {@link DashboardView}.
 *
 * Responsabilidades:
 *  • Construir y gestionar todos los componentes Swing.
 *  • Delegar acciones del usuario al Presenter.
 *  • Nunca acceder directamente al Modelo.
 */
public class AdminDashboardFrame extends JFrame implements DashboardView {

    /* ── Presenter (inyectado en construcción) ─────────── */
    private DashboardPresenter presenter;

    /* ── Mapa interno de tarjetas (estado UI puro) ─────── */
    private final ConcurrentHashMap<String, ClientCard> cards = new ConcurrentHashMap<>();

    /* ── Componentes UI ─────────────────────────────────── */
    private JPanel      gridPanel;
    private JScrollPane scrollPane;
    private LogPanel    logPanel;
    private JLabel      clockLbl, sensorCountLbl, msgCountLbl, connStatusLbl, statusLbl;
    private JTextField  hostField, portField;
    private JButton     connectBtn;
    private JPanel      placeholderPanel;

    

    public AdminDashboardFrame() {
        super("Weather Station — Admin Dashboard");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1360, 860);
        setMinimumSize(new Dimension(960, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(C_BG0);
        buildLayout();
        startAnimationTimer();
        startClock();
    }

    public void setPresenter(DashboardPresenter p) { this.presenter = p; }

    /* ══════════════════════════════════════════════════════
       LAYOUT
       ══════════════════════════════════════════════════════ */
    private void buildLayout() {
        setLayout(new BorderLayout());
        add(buildHeader(),    BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(C_BG0);

        gridPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 10));
        gridPanel.setBackground(C_BG0);
        gridPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        scrollPane = new JScrollPane(gridPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setBackground(C_BG0);
        scrollPane.getViewport().setBackground(C_BG0);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        styleScrollBar(scrollPane.getVerticalScrollBar());

        logPanel = new LogPanel();
        logPanel.setPreferredSize(new Dimension(270, 0));

        center.add(scrollPane, BorderLayout.CENTER);
        center.add(logPanel,   BorderLayout.EAST);

        add(center,           BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        showPlaceholder();
    }

    private JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout(20, 0));
        h.setBackground(C_BG1);
        h.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
            BorderFactory.createEmptyBorder(12, 20, 12, 20)));

        // Título + conexión
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setBackground(C_BG1);

        JPanel titleRow = new JPanel(new BorderLayout(0, 3));
        titleRow.setBackground(C_BG1);
        JLabel title = new JLabel("WEATHER STATION — ADMIN");
        title.setFont(new Font("Monospaced", Font.BOLD, 19));
        title.setForeground(C_ADMIN);
        JLabel sub = new JLabel("Dashboard de monitorización en tiempo real · Solo lectura");
        sub.setFont(new Font("Monospaced", Font.PLAIN, 11));
        sub.setForeground(C_MUTED);
        titleRow.add(title, BorderLayout.NORTH);
        titleRow.add(sub,   BorderLayout.SOUTH);

        // Fila conexión
        JPanel connRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        connRow.setBackground(C_BG1);
        hostField  = makeTextField(AppConfig.DEFAULT_HOST, 11);
        portField  = makeTextField(String.valueOf(AppConfig.DEFAULT_PORT), 5);
        connectBtn = new JButton("CONECTAR AL SERVIDOR");
        styleButton(connectBtn, C_ADMIN, C_BG2);
        connectBtn.addActionListener(e ->
            presenter.onToggleConnection(hostField.getText(), portField.getText()));

        connStatusLbl = new JLabel("● DESCONECTADO");
        connStatusLbl.setFont(new Font("Monospaced", Font.BOLD, 11));
        connStatusLbl.setForeground(C_HOT);

        connRow.add(makeLabel("HOST:", 10, C_MUTED)); connRow.add(hostField);
        connRow.add(makeLabel("PORT:", 10, C_MUTED)); connRow.add(portField);
        connRow.add(Box.createHorizontalStrut(4));
        connRow.add(connectBtn);
        connRow.add(Box.createHorizontalStrut(10));
        connRow.add(connStatusLbl);

        left.add(titleRow, BorderLayout.NORTH);
        left.add(connRow,  BorderLayout.SOUTH);

        // Estadísticas centro
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 0));
        stats.setBackground(C_BG1);
        sensorCountLbl = makeStatValue("0", "SENSORES");
        msgCountLbl    = makeStatValue("0", "MENSAJES");
        stats.add(sensorCountLbl.getParent());
        stats.add(msgCountLbl.getParent());

        // Reloj
        clockLbl = new JLabel("--:--:--");
        clockLbl.setFont(new Font("Monospaced", Font.PLAIN, 30));
        clockLbl.setForeground(C_TEXT);
        clockLbl.setHorizontalAlignment(SwingConstants.RIGHT);

        h.add(left,     BorderLayout.WEST);
        h.add(stats,    BorderLayout.CENTER);
        h.add(clockLbl, BorderLayout.EAST);
        return h;
    }

    private JPanel buildStatusBar() {
        JPanel sb = new JPanel(new BorderLayout());
        sb.setBackground(new Color(5, 8, 20));
        sb.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER),
            BorderFactory.createEmptyBorder(4, 14, 4, 14)));
        statusLbl = new JLabel("Configura host/port y presiona CONECTAR AL SERVIDOR");
        statusLbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
        statusLbl.setForeground(C_MUTED);
        JLabel ver = new JLabel("WeatherSystem v3.0 — Admin · puerto " + AppConfig.DEFAULT_PORT);
        ver.setFont(new Font("Monospaced", Font.PLAIN, 10));
        ver.setForeground(new Color(50, 70, 110));
        sb.add(statusLbl, BorderLayout.WEST);
        sb.add(ver,       BorderLayout.EAST);
        return sb;
    }

    /* ══════════════════════════════════════════════════════
       IMPLEMENTACIÓN DE DashboardView
       ══════════════════════════════════════════════════════ */

    @Override
    public void showConnected(String host, int port) {
        connectBtn.setText("DESCONECTAR");
        connectBtn.setEnabled(true);
        styleButton(connectBtn, C_HOT, C_BG2);
        connStatusLbl.setText("● CONECTADO — " + host + ":" + port);
        connStatusLbl.setForeground(C_ACCENT);
        statusLbl.setText("Conectado al servidor " + host + ":" + port + " · Esperando datos...");
        logPanel.addEntry("✓ Conectado al servidor " + host + ":" + port);
    }

    @Override
    public void showDisconnected() {
        connectBtn.setText("CONECTAR AL SERVIDOR");
        connectBtn.setEnabled(true);
        styleButton(connectBtn, C_ADMIN, C_BG2);
        connStatusLbl.setText("● DESCONECTADO");
        connStatusLbl.setForeground(C_HOT);
        statusLbl.setText("Desconectado. Presiona CONECTAR AL SERVIDOR para monitorizar.");
    }

    @Override
    public void showConnectionError(String message) {
        connectBtn.setEnabled(true);
        logPanel.addEntry("✗ Error: " + message);
        statusLbl.setText("Error: " + message);
        showDisconnected();
    }

    @Override
    public void addSensorCard(SensorData sd) {
        if (cards.containsKey(sd.id)) return;
        removePlaceholder();
        ClientCard card = new ClientCard(sd);
        cards.put(sd.id, card);
        gridPanel.add(card);
        gridPanel.revalidate();
        gridPanel.repaint();
        logPanel.addEntry("+ Nuevo sensor: " + sd.name + " (" + sd.ip + ")");
    }

    @Override
    public void removeSensorCard(String id) {
        ClientCard card = cards.remove(id);
        if (card != null) {
            gridPanel.remove(card);
            gridPanel.revalidate();
            gridPanel.repaint();
        }
        if (cards.isEmpty()) {
            showPlaceholder();
            gridPanel.revalidate();
            gridPanel.repaint();
        }
    }

    @Override
    public void clearAllCards() {
        cards.clear();
        gridPanel.removeAll();
        showPlaceholder();
        gridPanel.revalidate();
        gridPanel.repaint();
        sensorCountLbl.setText("0");
        msgCountLbl.setText("0");
    }

    @Override
    public void renameSensorCard(String id, String newName) {
        ClientCard card = cards.get(id);
        if (card != null) card.updateName(newName);
    }

    @Override
    public void updateStats(long onlineCount, int totalMessages) {
        sensorCountLbl.setText(String.valueOf(onlineCount));
        msgCountLbl.setText(String.valueOf(totalMessages));
    }

    @Override
    public void setStatus(String message) {
        statusLbl.setText(message);
    }

    @Override
    public void logEvent(String entry) {
        logPanel.addEntry(entry);
    }

    /* ══════════════════════════════════════════════════════
       PLACEHOLDER
       ══════════════════════════════════════════════════════ */
    private void showPlaceholder() {
        placeholderPanel = new JPanel(new GridBagLayout());
        placeholderPanel.setBackground(C_BG0);
        placeholderPanel.setPreferredSize(new Dimension(500, 400));
        JPanel inner = new JPanel(new BorderLayout(0, 12));
        inner.setBackground(C_BG0);
        JLabel icon = new JLabel("★", SwingConstants.CENTER);
        icon.setFont(new Font("Monospaced", Font.PLAIN, 60));
        icon.setForeground(new Color(40, 65, 110));
        JLabel msg  = new JLabel("Conecta al servidor para ver sensores en tiempo real", SwingConstants.CENTER);
        msg.setFont(new Font("Monospaced", Font.BOLD, 14));
        msg.setForeground(C_MUTED);
        JLabel hint = new JLabel("Introduce host y port del WeatherServer y pulsa CONECTAR", SwingConstants.CENTER);
        hint.setFont(new Font("Monospaced", Font.PLAIN, 11));
        hint.setForeground(new Color(55, 80, 130));
        inner.add(icon, BorderLayout.NORTH);
        inner.add(msg,  BorderLayout.CENTER);
        inner.add(hint, BorderLayout.SOUTH);
        placeholderPanel.add(inner);
        gridPanel.add(placeholderPanel);
    }

    private void removePlaceholder() {
        if (placeholderPanel != null && placeholderPanel.getParent() == gridPanel) {
            gridPanel.remove(placeholderPanel);
        }
    }

    /* ══════════════════════════════════════════════════════
       TIMERS
       ══════════════════════════════════════════════════════ */
    private void startAnimationTimer() {
        new Timer(1000 / AppConfig.ANIM_FPS, e -> {
            for (ClientCard card : cards.values()) card.refresh();
        }).start();
    }

    private void startClock() {
        new Timer(1000, e ->
            clockLbl.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
        ).start();
    }

    /* ══════════════════════════════════════════════════════
       HELPERS UI
       ══════════════════════════════════════════════════════ */

    /** Crea el par (JLabel valor + JLabel etiqueta) y devuelve el JLabel de valor. */
    private JLabel makeStatValue(String value, String label) {
        JPanel box = new JPanel(new BorderLayout(0, 1));
        box.setBackground(C_BG1);
        JLabel val = new JLabel(value, SwingConstants.CENTER);
        val.setFont(new Font("Monospaced", Font.BOLD, 26));
        val.setForeground(C_TEXT);
        JLabel lbl = new JLabel(label, SwingConstants.CENTER);
        lbl.setFont(new Font("Monospaced", Font.PLAIN, 9));
        lbl.setForeground(C_MUTED);
        box.add(val, BorderLayout.CENTER);
        box.add(lbl, BorderLayout.SOUTH);
        return val;
    }

    private JLabel makeLabel(String text, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.PLAIN, size));
        l.setForeground(color);
        return l;
    }

    private JTextField makeTextField(String text, int cols) {
        JTextField tf = new JTextField(text, cols);
        tf.setFont(new Font("Monospaced", Font.PLAIN, 11));
        tf.setBackground(C_BG2);
        tf.setForeground(C_TEXT);
        tf.setCaretColor(C_ACCENT);
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        return tf;
    }

    public static void styleButton(JButton btn, Color fg, Color bg) {
        btn.setFont(new Font("Monospaced", Font.BOLD, 11));
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(fg.darker(), 1),
            BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public static void styleScrollBar(JScrollBar sb) {
        sb.setBackground(C_BG0);
        sb.setPreferredSize(new Dimension(6, 6));
        sb.setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                thumbColor = new Color(50, 80, 140);
                trackColor = C_BG0;
            }
            @Override protected JButton createDecreaseButton(int o) { return emptyBtn(); }
            @Override protected JButton createIncreaseButton(int o) { return emptyBtn(); }
            private JButton emptyBtn() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }
        });
    }
}
