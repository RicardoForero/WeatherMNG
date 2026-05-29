package legacy;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║   AdminDashboard — Cliente Admin                        ║
 * ║   Muestra el dashboard completo de sensores conectados  ║
 * ║   recibiendo datos en tiempo real desde el servidor     ║
 * ╠══════════════════════════════════════════════════════════╣
 * ║  Compilar : javac AdminDashboard.java                   ║
 * ║  Ejecutar : java AdminDashboard                         ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 *  Protocolo: envía "ADMIN_v1" al conectarse → el servidor
 *  lo identifica como admin y le emite broadcasts JSON.
 *
 *  Mensajes recibidos:
 *   {"type":"snapshot","sensors":[...]}  → estado inicial
 *   {"type":"update",  "sensor":{...}}   → actualización
 *   {"type":"remove",  "id":"..."}       → sensor eliminado
 */
public class AdminDashboard extends JFrame {

    /* ── Configuración ───────────────────────────────────── */
    static final String DEFAULT_HOST  = "127.0.0.1";
    static final int    DEFAULT_PORT  = 2361;
    static final String ADMIN_HELLO   = "ADMIN_v1";
    static final int    HISTORY_SIZE  = 80;
    static final int    ANIM_FPS      = 30;

    /* ── Paleta ──────────────────────────────────────────── */
    static final Color C_BG0    = new Color(6,   10,  22);
    static final Color C_BG1    = new Color(11,  18,  38);
    static final Color C_BG2    = new Color(16,  26,  52);
    static final Color C_BG3    = new Color(22,  34,  66);
    static final Color C_COLD   = new Color(56,  182, 255);
    static final Color C_MILD   = new Color(30,  200, 170);
    static final Color C_WARM   = new Color(255, 148, 38);
    static final Color C_HOT    = new Color(255,  72, 48);
    static final Color C_HUMID  = new Color(90,  160, 255);
    static final Color C_TEXT   = new Color(210, 228, 255);
    static final Color C_MUTED  = new Color(90,  118, 175);
    static final Color C_BORDER = new Color(55,   90, 160, 50);
    static final Color C_ACCENT = new Color(30,  200, 170);
    static final Color C_ADMIN  = new Color(120, 180, 255);

    /* ── Modelo de sensor (recibido vía red) ─────────────── */
    static class SensorData {
        String  id, ip, name;
        float   temp = Float.NaN, hum = Float.NaN, heatIndex = Float.NaN;
        int     rssi = 0, msgCount = 0;
        boolean online = true;
        long    uptime = 0;
        final List<Float> histTemp = Collections.synchronizedList(new ArrayList<>());
        final List<Float> histHum  = Collections.synchronizedList(new ArrayList<>());

        SensorData(String id, String ip, String name) {
            this.id = id; this.ip = ip; this.name = name;
        }

        Color tempColor() {
            if (Float.isNaN(temp)) return C_MUTED;
            if (temp < 10) return C_COLD;
            if (temp < 22) return new Color(60, 200, 220);
            if (temp < 30) return C_MILD;
            if (temp < 37) return C_WARM;
            return C_HOT;
        }

        String condition() {
            if (Float.isNaN(temp) || Float.isNaN(hum)) return "Sin datos";
            if (temp<5)  return "Helada";  if (temp<15) return "Frío";
            if (temp<22) return "Fresco";  if (temp<28&&hum<60) return "Confort";
            if (hum>85)  return "Muy húmedo"; if (hum>70) return "Húmedo";
            if (temp<32) return "Cálido";  if (temp<38) return "Caluroso";
            return "Extremo";
        }
    }

    /* ── Estado ──────────────────────────────────────────── */
    final ConcurrentHashMap<String, SensorData> sensors = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, ClientCard>  cards   = new ConcurrentHashMap<>();
    volatile int totalMessages = 0;

    /* ── Red ─────────────────────────────────────────────── */
    Socket     socket;
    BufferedReader netReader;
    boolean    connected = false;
    ExecutorService netPool = Executors.newSingleThreadExecutor();

    /* ── UI ──────────────────────────────────────────────── */
    JPanel      gridPanel;
    JScrollPane scrollPane;
    LogPanel    logPanel;
    JLabel      clockLbl, sensorCountLbl, msgCountLbl, connStatusLbl;
    JLabel      statusLbl;
    JTextField  hostField, portField;
    JButton     connectBtn;
    JPanel      placeholderPanel;

    /* ── Main ────────────────────────────────────────────── */
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new AdminDashboard().setVisible(true));
    }

    AdminDashboard() {
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

    /* ══════════════════════════════════════════════════════
       LAYOUT
       ══════════════════════════════════════════════════════ */
    void buildLayout() {
        setLayout(new BorderLayout());
        add(buildHeader(),    BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(C_BG0);

        gridPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 10));
        gridPanel.setBackground(C_BG0);
        gridPanel.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));

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

    /* ── Header ──────────────────────────────────────────── */
    JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout(20,0));
        h.setBackground(C_BG1);
        h.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0,0,1,0,C_BORDER),
            BorderFactory.createEmptyBorder(12,20,12,20)));

        // Izquierda: título + conexión
        JPanel left = new JPanel(new BorderLayout(0,8));
        left.setBackground(C_BG1);

        JPanel titleRow = new JPanel(new BorderLayout(0,3));
        titleRow.setBackground(C_BG1);
        JLabel title = new JLabel("WEATHER STATION — ADMIN");
        title.setFont(new Font("Monospaced", Font.BOLD, 19));
        title.setForeground(C_ADMIN);
        JLabel sub = new JLabel("Dashboard de monitorización en tiempo real · Solo lectura");
        sub.setFont(new Font("Monospaced", Font.PLAIN, 11));
        sub.setForeground(C_MUTED);
        titleRow.add(title, BorderLayout.NORTH);
        titleRow.add(sub,   BorderLayout.SOUTH);

        // Fila de conexión
        JPanel connRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        connRow.setBackground(C_BG1);
        hostField  = makeTextField(DEFAULT_HOST, 11);
        portField  = makeTextField(String.valueOf(DEFAULT_PORT), 5);
        connectBtn = new JButton("CONECTAR AL SERVIDOR");
        styleButton(connectBtn, C_ADMIN, C_BG2);
        connectBtn.addActionListener(e -> toggleConnection());

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

        // Centro: estadísticas
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 0));
        stats.setBackground(C_BG1);
        sensorCountLbl = makeStatLabel("0", "SENSORES");
        msgCountLbl    = makeStatLabel("0", "MENSAJES");
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

    JPanel buildStatusBar() {
        JPanel sb = new JPanel(new BorderLayout());
        sb.setBackground(new Color(5,8,20));
        sb.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1,0,0,0,C_BORDER),
            BorderFactory.createEmptyBorder(4,14,4,14)));
        statusLbl = new JLabel("Configura host/port y presiona CONECTAR AL SERVIDOR");
        statusLbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
        statusLbl.setForeground(C_MUTED);
        JLabel ver = new JLabel("WeatherSystem v3.0 — Admin · puerto " + DEFAULT_PORT);
        ver.setFont(new Font("Monospaced", Font.PLAIN, 10));
        ver.setForeground(new Color(50,70,110));
        sb.add(statusLbl, BorderLayout.WEST);
        sb.add(ver,       BorderLayout.EAST);
        return sb;
    }

    /* ══════════════════════════════════════════════════════
       CONEXIÓN TCP AL SERVIDOR
       ══════════════════════════════════════════════════════ */
    void toggleConnection() { if (connected) disconnect(); else connect(); }

    void connect() {
        String h = hostField.getText().trim();
        int p;
        try { p = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { p = DEFAULT_PORT; }
        final String host = h; final int port = p;

        connectBtn.setEnabled(false);
        netPool.submit(() -> {
            try {
                socket = new Socket(host, port);
                PrintWriter writer = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream())), true);
                netReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // Identificarse como admin
                writer.println(ADMIN_HELLO);

                connected = true;
                SwingUtilities.invokeLater(() -> {
                    connectBtn.setText("DESCONECTAR"); connectBtn.setEnabled(true);
                    styleButton(connectBtn, C_HOT, C_BG2);
                    connStatusLbl.setText("● CONECTADO — " + host + ":" + port);
                    connStatusLbl.setForeground(C_ACCENT);
                    statusLbl.setText("Conectado al servidor " + host + ":" + port + " · Esperando datos...");
                    logPanel.addEntry("✓ Conectado al servidor " + host + ":" + port);
                });

                // Leer mensajes del servidor en loop
                String line;
                while ((line = netReader.readLine()) != null) {
                    final String msg = line.trim();
                    if (!msg.isEmpty()) SwingUtilities.invokeLater(() -> handleMessage(msg));
                }
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    logPanel.addEntry("✗ Error: " + ex.getMessage());
                    statusLbl.setText("Error: " + ex.getMessage());
                    connectBtn.setEnabled(true);
                    doDisconnectUI();
                });
            } finally {
                connected = false;
                SwingUtilities.invokeLater(() -> doDisconnectUI());
            }
        });
    }

    void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        doDisconnectUI();
        clearAllCards();
        logPanel.addEntry("— Desconectado del servidor");
    }

    void doDisconnectUI() {
        connectBtn.setText("CONECTAR AL SERVIDOR");
        connectBtn.setEnabled(true);
        styleButton(connectBtn, C_ADMIN, C_BG2);
        connStatusLbl.setText("● DESCONECTADO");
        connStatusLbl.setForeground(C_HOT);
        statusLbl.setText("Desconectado. Presiona CONECTAR AL SERVIDOR para monitorizar.");
    }

    /* ══════════════════════════════════════════════════════
       PROTOCOLO DE MENSAJES DEL SERVIDOR
       ══════════════════════════════════════════════════════ */
    void handleMessage(String json) {
        String type = parseStr(json, "type");
        switch (type) {
            case "snapshot": handleSnapshot(json);        break;
            case "update":   handleUpdate(json);          break;
            case "remove":   handleRemove(parseStr(json, "id")); break;
        }
    }

    void handleSnapshot(String json) {
        // Extraer el array "sensors"
        int start = json.indexOf("[");
        int end   = json.lastIndexOf("]");
        if (start < 0 || end < 0) return;

        String arrStr = json.substring(start+1, end).trim();
        if (arrStr.isEmpty()) {
            logPanel.addEntry("★ Snapshot recibido · 0 sensores activos");
            statusLbl.setText("Conectado · Sin sensores activos en el servidor");
            return;
        }

        List<String> objs = splitTopLevelObjects(arrStr);
        for (String obj : objs) parseSensorJson(obj);

        int tot = parseIntVal(json, "totalMessages");
        if (tot > 0) totalMessages = tot;

        logPanel.addEntry("★ Snapshot recibido · " + objs.size() + " sensores");
        statusLbl.setText("Conectado · " + sensors.size() + " sensores activos");
        updateHeaderStats();
    }

    void handleUpdate(String json) {
        int si = json.indexOf("{\"id\":");
        if (si < 0) return;
        String sensorJson = json.substring(si, json.lastIndexOf("}")+1);
        SensorData sd = parseSensorJson(sensorJson);
        if (sd != null) {
            statusLbl.setText(String.format("Actualización: %s  T=%.1f°C  H=%.0f%%",
                sd.name, sd.temp, sd.hum));
            totalMessages++;
            updateHeaderStats();
        }
    }

    void handleRemove(String id) {
        sensors.remove(id);
        removeCard(id);
        logPanel.addEntry("✗ Sensor eliminado: " + id.split(":")[0]);
        updateHeaderStats();
    }

    /* ── Parseo de un objeto sensor JSON ─────────────────── */
    SensorData parseSensorJson(String json) {
        try {
            String id   = parseStr(json, "id");
            String ip   = parseStr(json, "ip");
            String name = parseStr(json, "name");
            if ("N/A".equals(id)) return null;

            SensorData sd = sensors.computeIfAbsent(id, k -> new SensorData(id, ip, name));
            sd.name      = name;
            sd.temp      = parseFloat(json, "temp");
            sd.hum       = parseFloat(json, "hum");
            sd.heatIndex = parseFloat(json, "heatIndex");
            sd.rssi      = parseIntVal(json, "rssi");
            sd.msgCount  = parseIntVal(json, "msgCount");
            sd.online    = "true".equals(parseBool(json, "online"));
            sd.uptime    = parseLong(json, "uptime");

            // Historial de temperatura
            int htStart = json.indexOf("\"histTemp\":[");
            if (htStart >= 0) {
                htStart = json.indexOf("[", htStart);
                int htEnd = json.indexOf("]", htStart);
                if (htEnd > htStart) {
                    String arr = json.substring(htStart+1, htEnd).trim();
                    if (!arr.isEmpty()) {
                        synchronized (sd.histTemp) {
                            sd.histTemp.clear();
                            for (String v : arr.split(",")) {
                                try { sd.histTemp.add(Float.parseFloat(v.trim())); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }

            // Historial de humedad
            int hhStart = json.indexOf("\"histHum\":[");
            if (hhStart >= 0) {
                hhStart = json.indexOf("[", hhStart);
                int hhEnd = json.indexOf("]", hhStart);
                if (hhEnd > hhStart) {
                    String arr = json.substring(hhStart+1, hhEnd).trim();
                    if (!arr.isEmpty()) {
                        synchronized (sd.histHum) {
                            sd.histHum.clear();
                            for (String v : arr.split(",")) {
                                try { sd.histHum.add(Float.parseFloat(v.trim())); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }

            // Si llegamos aquí y el sensor no tiene tarjeta, crearla
            if (!cards.containsKey(id)) addCard(sd);
            else {
                // Actualizar nombre si cambió
                ClientCard card = cards.get(id);
                if (card != null) card.updateName(name);
            }
            return sd;
        } catch (Exception e) {
            return null;
        }
    }

    /* ── Separar objetos JSON de nivel superior de un array ─ */
    List<String> splitTopLevelObjects(String s) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) { result.add(s.substring(start, i+1)); start = -1; } }
        }
        return result;
    }

    /* ══════════════════════════════════════════════════════
       GESTIÓN DE TARJETAS
       ══════════════════════════════════════════════════════ */
    void addCard(SensorData sd) {
        SwingUtilities.invokeLater(() -> {
            if (cards.containsKey(sd.id)) return;
            removePlaceholder();
            ClientCard card = new ClientCard(sd);
            cards.put(sd.id, card);
            gridPanel.add(card);
            gridPanel.revalidate(); gridPanel.repaint();
            logPanel.addEntry("+ Nuevo sensor: " + sd.name + " (" + sd.ip + ")");
        });
    }

    void removeCard(String id) {
        SwingUtilities.invokeLater(() -> {
            ClientCard card = cards.remove(id);
            if (card != null) { gridPanel.remove(card); gridPanel.revalidate(); gridPanel.repaint(); }
            if (cards.isEmpty()) { showPlaceholder(); gridPanel.revalidate(); gridPanel.repaint(); }
        });
    }

    void clearAllCards() {
        SwingUtilities.invokeLater(() -> {
            sensors.clear(); cards.clear();
            gridPanel.removeAll(); showPlaceholder();
            gridPanel.revalidate(); gridPanel.repaint();
            sensorCountLbl.setText("0"); msgCountLbl.setText("0");
        });
    }

    void updateHeaderStats() {
        long online = sensors.values().stream().filter(s -> s.online).count();
        sensorCountLbl.setText(String.valueOf(online));
        msgCountLbl.setText(String.valueOf(totalMessages));
    }

    /* ══════════════════════════════════════════════════════
       TIMER DE ANIMACIÓN
       ══════════════════════════════════════════════════════ */
    void startAnimationTimer() {
        new Timer(1000/ANIM_FPS, e -> {
            for (ClientCard card : cards.values()) card.refresh();
        }).start();
    }

    void startClock() {
        new Timer(1000, e ->
            clockLbl.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))).start();
    }

    /* ══════════════════════════════════════════════════════
       TARJETA DE CLIENTE (idéntica visualmente al servidor)
       ══════════════════════════════════════════════════════ */
    class ClientCard extends JPanel {
        final SensorData data;
        long animTick = 0; float alpha = 0f;
        final List<float[]> particles = new ArrayList<>();
        final Random rnd = new Random();

        JLabel nameLbl, condLbl, ipLbl, uptimeLbl, rssiLbl, msgCountLbl2;
        MiniGauge tempGauge, humGauge;

        ClientCard(SensorData d) {
            this.data = d;
            setPreferredSize(new Dimension(340, 480));
            setBackground(C_BG3);
            setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
            setLayout(new BorderLayout());
            buildCard();
        }

        void updateName(String newName) { data.name = newName; nameLbl.setText(newName); }

        void buildCard() {
            // Escena animada
            ScenePanel scene = new ScenePanel();
            scene.setPreferredSize(new Dimension(340, 220));

            // Info header
            JPanel info = new JPanel(new BorderLayout(8,0)); info.setBackground(C_BG2);
            info.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,0,1,0,C_BORDER),
                BorderFactory.createEmptyBorder(8,12,8,12)));

            nameLbl = new JLabel(data.name); nameLbl.setFont(new Font("Monospaced",Font.BOLD,13)); nameLbl.setForeground(C_TEXT);
            condLbl = new JLabel("Conectando…"); condLbl.setFont(new Font("Monospaced",Font.PLAIN,10)); condLbl.setForeground(C_MUTED);
            JPanel ng = new JPanel(new BorderLayout(0,2)); ng.setBackground(C_BG2);
            ng.add(nameLbl,BorderLayout.NORTH); ng.add(condLbl,BorderLayout.SOUTH);

            ipLbl   = new JLabel(data.ip); ipLbl.setFont(new Font("Monospaced",Font.PLAIN,10)); ipLbl.setForeground(C_MUTED);
            rssiLbl = new JLabel("— dBm"); rssiLbl.setFont(new Font("Monospaced",Font.PLAIN,10)); rssiLbl.setForeground(C_MUTED);
            rssiLbl.setHorizontalAlignment(SwingConstants.RIGHT);
            JPanel ir = new JPanel(new BorderLayout(0,2)); ir.setBackground(C_BG2);
            ir.add(ipLbl,BorderLayout.NORTH); ir.add(rssiLbl,BorderLayout.SOUTH);

            // Badge ADMIN
            JLabel adminBadge = new JLabel("[ADMIN]");
            adminBadge.setFont(new Font("Monospaced", Font.BOLD, 9));
            adminBadge.setForeground(C_ADMIN);
            adminBadge.setHorizontalAlignment(SwingConstants.RIGHT);

            JPanel irWrap = new JPanel(new BorderLayout(0,1)); irWrap.setBackground(C_BG2);
            irWrap.add(ir, BorderLayout.CENTER); irWrap.add(adminBadge, BorderLayout.SOUTH);

            info.add(ng, BorderLayout.WEST); info.add(irWrap, BorderLayout.EAST);

            // Gauges
            JPanel gaugeRow = new JPanel(new GridLayout(1,2,8,0)); gaugeRow.setBackground(C_BG3);
            gaugeRow.setBorder(BorderFactory.createEmptyBorder(10,10,6,10));
            tempGauge = new MiniGauge("TEMPERATURA","°C",-10,50);
            humGauge  = new MiniGauge("HUMEDAD",   "%",  0,100);
            gaugeRow.add(tempGauge); gaugeRow.add(humGauge);

            // Mini gráfica
            MiniGraph graph = new MiniGraph();
            graph.setPreferredSize(new Dimension(340,70));
            graph.setBorder(BorderFactory.createEmptyBorder(0,10,6,10));

            // Footer
            JPanel footer = new JPanel(new BorderLayout()); footer.setBackground(C_BG2);
            footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1,0,0,0,C_BORDER),
                BorderFactory.createEmptyBorder(5,12,5,12)));
            uptimeLbl = new JLabel("Uptime: 0s"); uptimeLbl.setFont(new Font("Monospaced",Font.PLAIN,10)); uptimeLbl.setForeground(new Color(60,90,140));
            msgCountLbl2 = new JLabel("0 msg"); msgCountLbl2.setFont(new Font("Monospaced",Font.PLAIN,10)); msgCountLbl2.setForeground(new Color(60,90,140));
            msgCountLbl2.setHorizontalAlignment(SwingConstants.RIGHT);
            footer.add(uptimeLbl,BorderLayout.WEST); footer.add(msgCountLbl2,BorderLayout.EAST);

            JPanel body = new JPanel(new BorderLayout()); body.setBackground(C_BG3);
            body.add(gaugeRow,BorderLayout.CENTER); body.add(graph,BorderLayout.SOUTH);
            JPanel wrap = new JPanel(new BorderLayout()); wrap.setBackground(C_BG3);
            wrap.add(body,BorderLayout.NORTH); wrap.add(footer,BorderLayout.SOUTH);

            add(info,BorderLayout.NORTH); add(scene,BorderLayout.CENTER); add(wrap,BorderLayout.SOUTH);
        }

        void refresh() {
            animTick++; alpha = Math.min(1f, alpha+0.04f);
            condLbl.setText(data.condition());
            condLbl.setForeground(data.online ? data.tempColor() : C_MUTED);
            nameLbl.setForeground(data.online ? C_TEXT : C_MUTED);
            rssiLbl.setText(data.rssi==0?"— dBm":data.rssi+" dBm");
            long up=data.uptime;
            uptimeLbl.setText(String.format("Uptime: %02d:%02d:%02d",up/3600,(up%3600)/60,up%60));
            msgCountLbl2.setText(data.msgCount+" msg");
            tempGauge.setValue(data.temp); tempGauge.setColor(data.tempColor()); tempGauge.repaint();
            humGauge.setValue(data.hum);   humGauge.setColor(C_HUMID);          humGauge.repaint();
            repaint();
        }

        /* ── Escena animada ─────────────────────────────────── */
        class ScenePanel extends JPanel {
            ScenePanel() { setOpaque(false); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2=(Graphics2D)g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                int W=getWidth(),H=getHeight();
                drawBg(g2,W,H); spawnUpdate(W,H); drawParticles(g2,H); drawIcon(g2,W,H); drawTempOverlay(g2,W,H);
                if(alpha<1f){g2.setColor(new Color(C_BG3.getRed(),C_BG3.getGreen(),C_BG3.getBlue(),(int)(255*(1-alpha))));g2.fillRect(0,0,W,H);}
            }
            void drawBg(Graphics2D g2,int W,int H){
                Color top,bot;
                if(!data.online||Float.isNaN(data.temp)){top=new Color(14,22,50);bot=new Color(10,16,38);}
                else if(data.temp<5){top=new Color(8,20,70);bot=new Color(14,40,100);}
                else if(data.temp<15){top=new Color(10,30,80);bot=new Color(18,55,110);}
                else if(data.temp<25){top=new Color(12,40,90);bot=new Color(22,70,130);}
                else if(data.temp<32){top=new Color(50,30,15);bot=new Color(130,80,20);}
                else{top=new Color(100,15,8);bot=new Color(200,50,15);}
                g2.setPaint(new GradientPaint(0,0,top,0,H,bot));g2.fillRect(0,0,W,H);g2.setPaint(null);
            }
            void spawnUpdate(int W,int H){
                if(Float.isNaN(data.hum))return;
                if(data.hum>80&&particles.size()<80)particles.add(new float[]{rnd.nextInt(W),-8,(rnd.nextFloat()-.5f)*1.5f,6+rnd.nextFloat()*3,70,0});
                else if(data.hum>55&&particles.size()<40)particles.add(new float[]{rnd.nextInt(W),-8,(rnd.nextFloat()-.5f),3.5f+rnd.nextFloat()*2,70,1});
                if(data.temp<10&&particles.size()<50)particles.add(new float[]{rnd.nextInt(W),-8,(rnd.nextFloat()-.5f),1.2f+rnd.nextFloat()*1.2f,110,2});
                if(data.temp>32&&particles.size()<12&&rnd.nextInt(4)==0)particles.add(new float[]{W/2+rnd.nextInt(80)-40,H/2+rnd.nextInt(60)-30,(rnd.nextFloat()-.5f)*2.5f,(rnd.nextFloat()-.5f)*2.5f,35,3});
                particles.removeIf(p->{p[0]+=p[2];p[1]+=p[3];p[4]--;return p[1]>H+10||p[4]<=0;});
            }
            void drawParticles(Graphics2D g2,int H){
                for(float[]p:new ArrayList<>(particles)){
                    float a=Math.min(1f,p[4]/30f);int type=(int)p[5];
                    if(type==0||type==1){g2.setColor(new Color(140,190,255,(int)(140*a)));g2.setStroke(new BasicStroke(type==0?1.2f:0.8f));g2.drawLine((int)p[0],(int)p[1],(int)(p[0]+p[2]*4),(int)(p[1]+p[3]*4));}
                    else if(type==2){g2.setColor(new Color(220,238,255,(int)(200*a)));g2.fillOval((int)p[0]-2,(int)p[1]-2,4,4);}
                    else{int sl=(int)(10*a);g2.setColor(new Color(255,210,70,(int)(220*a)));g2.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));int px=(int)p[0],py=(int)p[1];g2.drawLine(px-sl,py,px+sl,py);g2.drawLine(px,py-sl,px,py+sl);}
                }
            }
            void drawIcon(Graphics2D g2,int W,int H){
                if(!data.online||Float.isNaN(data.temp)){g2.setColor(new Color(50,70,110,120));g2.setFont(new Font("Monospaced",Font.PLAIN,32));g2.drawString("✕",W/2-12,H/2+10);return;}
                int cx=W/2,cy=H/2-20;float pulse=(float)(Math.sin(animTick*0.06)*0.1+1.0);
                if(data.temp>=32){int r=(int)(38*pulse);for(int i=3;i>0;i--){g2.setColor(new Color(255,180,0,10*i));g2.fillOval(cx-r-i*9,cy-r-i*9,(r+i*9)*2,(r+i*9)*2);}g2.setColor(new Color(255,210,60,200));g2.setStroke(new BasicStroke(2.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));for(int i=0;i<8;i++){double an=Math.toRadians(i*45+animTick*0.35);g2.drawLine((int)(cx+Math.cos(an)*(r+3)),(int)(cy+Math.sin(an)*(r+3)),(int)(cx+Math.cos(an)*(r+14)),(int)(cy+Math.sin(an)*(r+14)));}g2.setColor(new Color(255,210,60,240));g2.fillOval(cx-r,cy-r,r*2,r*2);}
                else if(data.hum>70){int s=46;g2.setColor(new Color(195,215,238,210));g2.fillOval(cx-s,cy-s/2,s*2,s);g2.fillOval(cx-s/3,cy-s*3/4,(int)(s*1.3),(int)(s*.95));g2.fillOval(cx+s/4,cy-s/2,(int)(s*1.3),s);if(data.hum>85){g2.setColor(new Color(130,185,255,190));g2.setStroke(new BasicStroke(1.4f));int off=(int)(animTick*3)%18;for(int i=0;i<5;i++){int dx=cx-s/2+i*(s/3);g2.drawLine(dx,cy+s/3+off,dx-2,cy+s/3+off+10);}}}
                else if(data.hum>50){int r=(int)(24*pulse);g2.setColor(new Color(255,210,60,240));g2.fillOval(cx-18-r,cy-6-r,r*2,r*2);int s=34;g2.setColor(new Color(195,215,238,210));g2.fillOval(cx+14-s/2,cy+4-s/4,s,s/2+4);}
                else{int r=(int)(36*pulse);g2.setColor(new Color(255,210,60,240));g2.fillOval(cx-r,cy-r,r*2,r*2);}
                if(data.temp<10){g2.setColor(new Color(200,228,255,190));g2.setStroke(new BasicStroke(1.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));int sR=16;double rot=Math.toRadians(animTick*0.6);for(int i=0;i<6;i++){double an=rot+Math.toRadians(i*60);g2.drawLine(cx,cy+60,(int)(cx+Math.cos(an)*sR),(int)(cy+60+Math.sin(an)*sR));}g2.fillOval(cx-3,cy+57,6,6);}
            }
            void drawTempOverlay(Graphics2D g2,int W,int H){
                if(Float.isNaN(data.temp))return;
                String ts=String.format("%.1f°",data.temp);g2.setFont(new Font("Monospaced",Font.BOLD,46));FontMetrics fm=g2.getFontMetrics();
                int tx=W/2-fm.stringWidth(ts)/2,ty=H-36;g2.setColor(new Color(0,0,0,70));g2.drawString(ts,tx+2,ty+2);g2.setColor(data.tempColor());g2.drawString(ts,tx,ty);
                if(!Float.isNaN(data.hum)){String hs=String.format("%.0f%% HR",data.hum);g2.setFont(new Font("Monospaced",Font.PLAIN,14));fm=g2.getFontMetrics();g2.setColor(new Color(140,195,255,200));g2.drawString(hs,W/2-fm.stringWidth(hs)/2,H-14);}
            }
        }

        /* ── Gauge ─────────────────────────────────────────── */
        class MiniGauge extends JPanel {
            final String label,unit;final float min,max;float value=Float.NaN,animV=0;Color color=C_MILD;
            MiniGauge(String l,String u,float mn,float mx){label=l;unit=u;min=mn;max=mx;setOpaque(false);setPreferredSize(new Dimension(150,110));}
            void setValue(float v){value=v;if(!Float.isNaN(v))animV+=(v-animV)*0.15f;}
            void setColor(Color c){color=c;}
            @Override protected void paintComponent(Graphics g){
                Graphics2D g2=(Graphics2D)g;g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                int W=getWidth(),H=getHeight(),cx=W/2,cy=H-28,r=Math.min(cx-8,cy-10);
                g2.setFont(new Font("Monospaced",Font.BOLD,9));g2.setColor(C_MUTED);FontMetrics fm=g2.getFontMetrics();g2.drawString(label,cx-fm.stringWidth(label)/2,14);
                g2.setColor(new Color(25,40,75));g2.setStroke(new BasicStroke(7,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g2.drawArc(cx-r,cy-r+15,r*2,r*2,210,-240);
                if(!Float.isNaN(value)){float pct=Math.max(0,Math.min(1,(animV-min)/(max-min)));g2.setColor(color);g2.drawArc(cx-r,cy-r+15,r*2,r*2,210,-(int)(240*pct));
                    g2.setFont(new Font("Monospaced",Font.BOLD,24));fm=g2.getFontMetrics();String vs=String.format("%.1f",value);g2.setColor(color);g2.drawString(vs,cx-fm.stringWidth(vs)/2,cy+6);
                    g2.setFont(new Font("Monospaced",Font.PLAIN,10));fm=g2.getFontMetrics();g2.setColor(C_MUTED);g2.drawString(unit,cx-fm.stringWidth(unit)/2,cy+20);
                }else{g2.setFont(new Font("Monospaced",Font.BOLD,22));g2.setColor(C_MUTED);fm=g2.getFontMetrics();g2.drawString("—",cx-fm.stringWidth("—")/2,cy+6);}
                g2.setFont(new Font("Monospaced",Font.PLAIN,8));g2.setColor(new Color(55,80,130));g2.drawString(String.valueOf((int)min),cx-r-2,cy+6);g2.drawString(String.valueOf((int)max),cx+r-14,cy+6);
            }
        }

        /* ── Mini gráfica ──────────────────────────────────── */
        class MiniGraph extends JPanel {
            MiniGraph(){setOpaque(false);}
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
                        float mn, float mx,
                        int W, int H,
                        Color col, boolean fill) {

    List<Float> snap;

    synchronized (series) {
        snap = new ArrayList<>(series);
    }
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

        g2.setColor(new Color(
            col.getRed(),
            col.getGreen(),
            col.getBlue(),
            20
        ));

        g2.fillPolygon(poly);
    }

    g2.setColor(fill
        ? col
        : new Color(
            col.getRed(),
            col.getGreen(),
            col.getBlue(),
            160
        ));

    g2.setStroke(new BasicStroke(
        fill ? 2.2f : 1.8f,
        BasicStroke.CAP_ROUND,
        BasicStroke.JOIN_ROUND
    ));

    for (int i = 0; i < n - 1; i++) {

        g2.drawLine(
            xs[i], ys[i],
            xs[i + 1], ys[i + 1]
        );
    }

    g2.fillOval(
        xs[n - 1] - 3,
        ys[n - 1] - 3,
        6,
        6
    );
    }
        }
    }

    /* ══════════════════════════════════════════════════════
       LOG PANEL
       ══════════════════════════════════════════════════════ */
    class LogPanel extends JPanel {
        final DefaultListModel<String> model=new DefaultListModel<>();
        LogPanel(){
            setLayout(new BorderLayout());setBackground(C_BG1);setBorder(BorderFactory.createMatteBorder(0,1,0,0,C_BORDER));
            JLabel title=new JLabel("  EVENTOS ADMIN");title.setFont(new Font("Monospaced",Font.BOLD,10));title.setForeground(C_MUTED);
            title.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,C_BORDER),BorderFactory.createEmptyBorder(8,0,8,0)));
            title.setBackground(C_BG1);title.setOpaque(true);
            JList<String>list=new JList<>(model);list.setBackground(C_BG1);list.setForeground(C_MUTED);list.setFont(new Font("Monospaced",Font.PLAIN,10));list.setFixedCellHeight(18);
            list.setBorder(BorderFactory.createEmptyBorder(2,8,2,8));
            list.setCellRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?>l,Object v,int i,boolean sel,boolean foc){JLabel lb=(JLabel)super.getListCellRendererComponent(l,v,i,sel,foc);String s=v.toString();lb.setBackground(i%2==0?C_BG1:new Color(14,22,46));lb.setForeground(s.contains("✓")||s.contains("+")?C_ACCENT:s.contains("✗")||s.contains("!")?C_HOT:s.contains("★")?C_ADMIN:C_MUTED);lb.setFont(new Font("Monospaced",Font.PLAIN,10));lb.setBorder(BorderFactory.createEmptyBorder(1,6,1,6));return lb;}});
            JScrollPane sp=new JScrollPane(list);sp.setBorder(BorderFactory.createEmptyBorder());sp.setBackground(C_BG1);sp.getViewport().setBackground(C_BG1);styleScrollBar(sp.getVerticalScrollBar());
            add(title,BorderLayout.NORTH);add(sp,BorderLayout.CENTER);
        }
        void addEntry(String msg){SwingUtilities.invokeLater(()->{String t=LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));model.add(0,t+"  "+msg);if(model.size()>300)model.remove(model.size()-1);});}
    }

    /* ══════════════════════════════════════════════════════
       PLACEHOLDER
       ══════════════════════════════════════════════════════ */
    void showPlaceholder(){
        placeholderPanel=new JPanel(new GridBagLayout());placeholderPanel.setBackground(C_BG0);placeholderPanel.setPreferredSize(new Dimension(500,400));
        JPanel inner=new JPanel(new BorderLayout(0,12));inner.setBackground(C_BG0);
        JLabel icon=new JLabel("★",SwingConstants.CENTER);icon.setFont(new Font("Monospaced",Font.PLAIN,60));icon.setForeground(new Color(40,65,110));
        JLabel msg=new JLabel("Conecta al servidor para ver sensores en tiempo real",SwingConstants.CENTER);msg.setFont(new Font("Monospaced",Font.BOLD,14));msg.setForeground(C_MUTED);
        JLabel hint=new JLabel("Introduce host y port del WeatherServer y pulsa CONECTAR",SwingConstants.CENTER);hint.setFont(new Font("Monospaced",Font.PLAIN,11));hint.setForeground(new Color(55,80,130));
        inner.add(icon,BorderLayout.NORTH);inner.add(msg,BorderLayout.CENTER);inner.add(hint,BorderLayout.SOUTH);
        placeholderPanel.add(inner);gridPanel.add(placeholderPanel);
    }
    void removePlaceholder(){if(placeholderPanel!=null&&placeholderPanel.getParent()==gridPanel)gridPanel.remove(placeholderPanel);}

    /* ══════════════════════════════════════════════════════
       PARSEO JSON
       ══════════════════════════════════════════════════════ */
    static float  parseFloat(String j,String k){String s=parseStr(j,k);return "N/A".equals(s)?Float.NaN:Float.parseFloat(s);}
    static int    parseIntVal(String j,String k){String s=parseStr(j,k);return "N/A".equals(s)?0:(int)Float.parseFloat(s);}
    static long   parseLong(String j,String k)  {String s=parseStr(j,k);return "N/A".equals(s)?0:(long)Double.parseDouble(s);}
    static String parseBool(String j,String k)  {return parseStr(j,k);}
    static String parseStr(String json,String key){
        int i=json.indexOf("\""+key+"\"");if(i<0)return"N/A";
        int c=json.indexOf(':',i);if(c<0)return"N/A";
        int s=c+1;while(s<json.length()&&json.charAt(s)==' ')s++;if(s>=json.length())return"N/A";
        if(json.charAt(s)=='"'){int e=json.indexOf('"',s+1);return e<0?"N/A":json.substring(s+1,e);}
        int e=s;while(e<json.length()&&(Character.isLetterOrDigit(json.charAt(e))||json.charAt(e)=='.'||json.charAt(e)=='-'))e++;
        return json.substring(s,e);
    }

    /* ══════════════════════════════════════════════════════
       HELPERS UI
       ══════════════════════════════════════════════════════ */
    JLabel makeStatLabel(String value,String label){
        JPanel box=new JPanel(new BorderLayout(0,1));box.setBackground(C_BG1);
        JLabel val=new JLabel(value,SwingConstants.CENTER);val.setFont(new Font("Monospaced",Font.BOLD,26));val.setForeground(C_TEXT);
        JLabel lbl=new JLabel(label,SwingConstants.CENTER);lbl.setFont(new Font("Monospaced",Font.PLAIN,9));lbl.setForeground(C_MUTED);
        box.add(val,BorderLayout.CENTER);box.add(lbl,BorderLayout.SOUTH);return val;
    }
    JLabel makeLabel(String text,int size,Color color){JLabel l=new JLabel(text);l.setFont(new Font("Monospaced",Font.PLAIN,size));l.setForeground(color);return l;}
    JTextField makeTextField(String text,int cols){
        JTextField tf=new JTextField(text,cols);tf.setFont(new Font("Monospaced",Font.PLAIN,11));tf.setBackground(C_BG2);tf.setForeground(C_TEXT);tf.setCaretColor(C_ACCENT);
        tf.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(C_BORDER,1),BorderFactory.createEmptyBorder(4,6,4,6)));return tf;
    }
    void styleButton(JButton btn,Color fg,Color bg){
        btn.setFont(new Font("Monospaced",Font.BOLD,11));btn.setForeground(fg);btn.setBackground(bg);
        btn.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(fg.darker(),1),BorderFactory.createEmptyBorder(5,14,5,14)));
        btn.setFocusPainted(false);btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
    void styleScrollBar(JScrollBar sb){
        sb.setBackground(C_BG0);sb.setPreferredSize(new Dimension(6,6));
        sb.setUI(new BasicScrollBarUI(){
            @Override protected void configureScrollBarColors(){thumbColor=new Color(50,80,140);trackColor=C_BG0;}
            @Override protected JButton createDecreaseButton(int o){return z();}
            @Override protected JButton createIncreaseButton(int o){return z();}
            JButton z(){JButton b=new JButton();b.setPreferredSize(new Dimension(0,0));return b;}
        });
    }

    /* ══════════════════════════════════════════════════════
       WRAP LAYOUT
       ══════════════════════════════════════════════════════ */
    static class WrapLayout extends FlowLayout {
        WrapLayout(int align,int hgap,int vgap){super(align,hgap,vgap);}
        @Override public Dimension preferredLayoutSize(Container t){return ls(t,true);}
        @Override public Dimension minimumLayoutSize(Container t){Dimension d=ls(t,false);d.width-=(getHgap()+1);return d;}
        Dimension ls(Container target,boolean preferred){
            synchronized(target.getTreeLock()){
                int tw=target.getSize().width;if(tw==0)tw=Integer.MAX_VALUE;
                Insets ins=target.getInsets();int maxW=tw-ins.left-ins.right-getHgap()*2;
                Dimension dim=new Dimension(0,0);int rW=0,rH=0;
                for(int i=0;i<target.getComponentCount();i++){Component m=target.getComponent(i);if(!m.isVisible())continue;
                    Dimension d=preferred?m.getPreferredSize():m.getMinimumSize();
                    if(rW+d.width>maxW){dim.width=Math.max(dim.width,rW);dim.height+=rH+getVgap();rW=0;rH=0;}
                    if(rW!=0)rW+=getHgap();rW+=d.width;rH=Math.max(rH,d.height);}
                dim.width=Math.max(dim.width,rW);dim.height+=rH+getVgap()*2;
                dim.width+=ins.left+ins.right+getHgap()*2;dim.height+=ins.top+ins.bottom;return dim;}
        }
    }
}
