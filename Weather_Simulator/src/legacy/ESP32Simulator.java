package legacy;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;
import javax.swing.*;

/**
 * ╔══════════════════════════════════════════════════════════╗
 * ║   ESP32Simulator — Cliente Sensor                       ║
 * ║   Se conecta al WeatherServer y envía lecturas DHT11    ║
 * ╠══════════════════════════════════════════════════════════╣
 * ║  Compilar : javac ESP32Simulator.java                   ║
 * ║  Ejecutar : java ESP32Simulator                         ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 *  Primera línea enviada = JSON de sensor (identificación automática)
 *  Controles:
 *   · Knob de temperatura : arrastrar arriba/abajo o scroll
 *   · Knob de humedad     : arrastrar arriba/abajo o scroll
 *   · Panel XY            : mover cursor = temp(X) + hum(Y)
 *   · Sliders laterales   : clic + arrastre
 */
public class ESP32Simulator extends JFrame {

    static final String DEFAULT_HOST     = "127.0.0.1";
    static final int    DEFAULT_PORT     = 2361;
    static final int    SEND_INTERVAL_MS = 2000;

    static final Color BG0    = new Color(7,  11, 24);
    static final Color BG1    = new Color(13, 20, 42);
    static final Color BG2    = new Color(18, 28, 56);
    static final Color BG3    = new Color(24, 36, 70);
    static final Color COLD   = new Color(56,  182, 255);
    static final Color MILD   = new Color(30,  200, 170);
    static final Color WARM   = new Color(255, 148, 38);
    static final Color HOT    = new Color(255,  72, 48);
    static final Color HUMID  = new Color(80,  150, 255);
    static final Color TEXT   = new Color(215, 230, 255);
    static final Color MUTED  = new Color(90,  118, 175);
    static final Color BORDER = new Color(55,  90, 160, 60);
    static final Color ACCENT = new Color(30,  200, 170);

    volatile float   temperature = 25.0f;
    volatile float   humidity    = 60.0f;
    volatile boolean connected   = false;
    volatile int     messagesSent = 0;

    Socket     socket;
    PrintWriter writer;
    String host = DEFAULT_HOST;
    int    port = DEFAULT_PORT;
    ScheduledExecutorService scheduler;

    DragKnob   tempKnob, humKnob;
    XYPad      xyPad;
    VSlider    tempSlider, humSlider;
    WeatherPreview preview;
    JLabel     tempValLbl, humValLbl, heatIdxLbl, msgCountLbl;
    JLabel     statusLbl, connLbl;
    JTextField hostField, portField, nameField;
    JButton    connectBtn;
    LogArea    logArea;

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ESP32Simulator().setVisible(true));
    }

    ESP32Simulator() {
        super("ESP32 DHT11 Simulator — Sensor");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(980, 720);
        setMinimumSize(new Dimension(860, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG0);
        setLayout(new BorderLayout(0, 0));
        buildUI();
        startRenderLoop();
    }

    void buildUI() {
        add(buildHeader(),  BorderLayout.NORTH);
        add(buildCenter(),  BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);
    }

    JPanel buildHeader() {
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
        hostField  = makeTextField(DEFAULT_HOST, 10);
        portField  = makeTextField(String.valueOf(DEFAULT_PORT), 5);
        nameField  = makeTextField("ESP32-SIM-01", 10);
        connectBtn = new JButton("CONECTAR");
        styleButton(connectBtn, ACCENT, BG2);
        connectBtn.addActionListener(e -> toggleConnection());

        conn.add(makeLabel("HOST:",   10, MUTED)); conn.add(hostField);
        conn.add(makeLabel("PORT:",   10, MUTED)); conn.add(portField);
        conn.add(makeLabel("NOMBRE:", 10, MUTED)); conn.add(nameField);
        conn.add(Box.createHorizontalStrut(4));
        conn.add(connectBtn);

        h.add(left, BorderLayout.WEST);
        h.add(conn, BorderLayout.EAST);
        return h;
    }

    JPanel buildCenter() {
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

    JPanel buildSliderPanel() {
        JPanel p = new JPanel(new GridLayout(2, 1, 0, 10));
        p.setBackground(BG0);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        tempSlider = new VSlider("TEMP", -10, 50, temperature, WARM);
        tempSlider.onValueChange = v -> { temperature = v; syncAll(); };
        humSlider  = new VSlider("HUM",   0, 100, humidity,    HUMID);
        humSlider.onValueChange  = v -> { humidity    = v; syncAll(); };
        p.add(tempSlider); p.add(humSlider);
        return p;
    }

    JPanel buildMainControls() {
        JPanel p = new JPanel(new BorderLayout(10, 10));
        p.setBackground(BG0);

        JPanel knobRow = new JPanel(new GridLayout(1, 2, 16, 0));
        knobRow.setBackground(BG0);
        knobRow.setPreferredSize(new Dimension(0, 220));

        tempKnob = new DragKnob("TEMPERATURA", "°C", -10, 50, temperature, WARM, HOT);
        tempKnob.onValueChange = v -> { temperature = v; syncAll(); };
        humKnob  = new DragKnob("HUMEDAD",     "%",   0, 100, humidity, new Color(60,140,255), HUMID);
        humKnob.onValueChange  = v -> { humidity    = v; syncAll(); };
        knobRow.add(tempKnob); knobRow.add(humKnob);

        xyPad = new XYPad();
        xyPad.onMove = (tx, hy) -> { temperature = tx; humidity = hy; syncAll(); };

        preview = new WeatherPreview();
        preview.setPreferredSize(new Dimension(0, 130));

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setBackground(BG0);
        bottom.add(xyPad,   BorderLayout.CENTER);
        bottom.add(preview, BorderLayout.SOUTH);

        p.add(knobRow, BorderLayout.NORTH);
        p.add(bottom,  BorderLayout.CENTER);
        return p;
    }

    JPanel buildRightPanel() {
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

    JPanel buildFooter() {
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

    /* ── Sincronización entre controles ──────────────────── */
    void syncAll() {
        tempKnob.setValue(temperature); humKnob.setValue(humidity);
        tempSlider.setValue(temperature); humSlider.setValue(humidity);
        xyPad.setValues(temperature, humidity);
        updateLabels();
    }

    void updateLabels() {
        Color tc = tempColor(temperature);
        tempValLbl.setText(String.format("%.1f°C", temperature)); tempValLbl.setForeground(tc);
        humValLbl.setText(String.format("%.1f%%", humidity));     humValLbl.setForeground(HUMID);
        float hi = computeHeatIndex(temperature, humidity);
        heatIdxLbl.setText(String.format("%.1f°C", hi)); heatIdxLbl.setForeground(tempColor(hi));
        msgCountLbl.setText(String.valueOf(messagesSent));
        statusLbl.setText(String.format("T=%.1f°C  H=%.1f%%  HI=%.1f°C  |  %s",
            temperature, humidity, hi,
            connected ? "Enviando a " + host + ":" + port : "Desconectado"));
    }

    /* ── Conexión TCP ────────────────────────────────────── */
    void toggleConnection() { if (connected) disconnect(); else connect(); }

    void connect() {
        host = hostField.getText().trim();
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { port = DEFAULT_PORT; }
        String name = nameField.getText().trim();
        if (name.isEmpty()) name = "ESP32-SIM-01";

        try {
            socket = new Socket(host, port);
            writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream())), true);
            connected = true; messagesSent = 0;

            connectBtn.setText("DESCONECTAR"); connectBtn.setForeground(HOT);
            connLbl.setText("● CONECTADO — " + host + ":" + port);
            connLbl.setForeground(ACCENT); connLbl.setBackground(new Color(8, 50, 35));
            logArea.log("✓ Conectado a " + host + ":" + port, ACCENT);
            statusLbl.setText("Conectado · Enviando cada " + SEND_INTERVAL_MS/1000 + "s");

            final String deviceName = name;
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> sendData(deviceName), 0, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (IOException ex) {
            logArea.log("✗ Error: " + ex.getMessage(), HOT);
            statusLbl.setText("Error: " + ex.getMessage());
        }
    }

    void disconnect() {
        connected = false;
        if (scheduler != null) scheduler.shutdownNow();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        writer = null;
        connectBtn.setText("CONECTAR"); connectBtn.setForeground(ACCENT);
        connLbl.setText("● DESCONECTADO"); connLbl.setForeground(HOT);
        connLbl.setBackground(new Color(60, 12, 12));
        logArea.log("— Desconectado", MUTED);
        statusLbl.setText("Desconectado. Presiona CONECTAR para enviar datos.");
    }

    void sendData(String deviceName) {
        if (!connected || writer == null) return;
        try {
            float hi   = computeHeatIndex(temperature, humidity);
            int   rssi = -50 - (int)(Math.random() * 30);
            String ip  = socket.getLocalAddress().getHostAddress();
            messagesSent++;

            // JSON igual al ESP32 real — el servidor lo identifica como sensor automáticamente
            String json = String.format(
                "{\"id\":%d,\"dispositivo\":\"%s\",\"timestamp\":%d," +
                "\"sensores\":{\"temperatura\":%.1f,\"humedad\":%.1f,\"indice_calor\":%.1f}," +
                "\"red\":{\"rssi\":%d,\"ip\":\"%s\"}}",
                messagesSent, deviceName, System.currentTimeMillis(),
                temperature, humidity, hi, rssi, ip);

            writer.println(json);
            if (writer.checkError()) throw new IOException("Broken pipe");

            SwingUtilities.invokeLater(() -> {
                msgCountLbl.setText(String.valueOf(messagesSent));
                logArea.log(String.format("→ #%d  T=%.1f°  H=%.0f%%  HI=%.1f°",
                    messagesSent, temperature, humidity, hi), TEXT);
            });
        } catch (IOException ex) {
            SwingUtilities.invokeLater(() -> { logArea.log("✗ Fallo: " + ex.getMessage(), HOT); disconnect(); });
        }
    }

    /* ── Render loop ─────────────────────────────────────── */
    void startRenderLoop() {
        new Timer(33, e -> {
            tempKnob.repaint(); humKnob.repaint(); xyPad.repaint(); preview.repaint();
            updateLabels();
        }).start();
    }

    /* ── Utilidades ──────────────────────────────────────── */
    static float computeHeatIndex(float t, float h) {
        if (t < 27) return t;
        double hi = -8.784695 + 1.61139411*t + 2.33854883*h - 0.14611605*t*h
            - 0.01230809*t*t - 0.01642483*h*h + 0.00221173*t*t*h
            + 0.00072546*t*h*h - 0.00000358*t*t*h*h;
        return (float) hi;
    }
    static Color tempColor(float t) {
        if (t < 10) return COLD; if (t < 22) return new Color(60,200,220);
        if (t < 30) return MILD; if (t < 37) return WARM; return HOT;
    }
    static float clamp(float v, float mn, float mx)  { return Math.max(mn, Math.min(mx, v)); }
    static float clamp01(float v)                     { return Math.max(0f, Math.min(1f, v)); }
    static Color interpolateColor(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color((int)(a.getRed()+(b.getRed()-a.getRed())*t),
            (int)(a.getGreen()+(b.getGreen()-a.getGreen())*t),
            (int)(a.getBlue()+(b.getBlue()-a.getBlue())*t));
    }
    JLabel makeLabel(String text, int size, Color color) {
        JLabel l = new JLabel(text); l.setFont(new Font("Monospaced", Font.PLAIN, size)); l.setForeground(color); return l;
    }
    JTextField makeTextField(String text, int cols) {
        JTextField tf = new JTextField(text, cols);
        tf.setFont(new Font("Monospaced", Font.PLAIN, 11));
        tf.setBackground(BG2); tf.setForeground(TEXT); tf.setCaretColor(ACCENT);
        tf.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER,1),BorderFactory.createEmptyBorder(4,6,4,6)));
        return tf;
    }
    void styleButton(JButton btn, Color fg, Color bg) {
        btn.setFont(new Font("Monospaced", Font.BOLD, 11)); btn.setForeground(fg); btn.setBackground(bg);
        btn.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(fg.darker(),1),BorderFactory.createEmptyBorder(5,14,5,14)));
        btn.setFocusPainted(false); btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setBackground(fg.darker()); btn.setForeground(TEXT); }
            @Override public void mouseExited(MouseEvent e)  { btn.setBackground(bg); btn.setForeground(fg); }
        });
    }
    JLabel makeStatRow(JPanel parent, String label, String initial) {
        JPanel row = new JPanel(new BorderLayout()); row.setBackground(BG2);
        JLabel lbl = new JLabel(label); lbl.setFont(new Font("Monospaced",Font.PLAIN,9)); lbl.setForeground(MUTED);
        JLabel val = new JLabel(initial); val.setFont(new Font("Monospaced",Font.BOLD,14)); val.setForeground(TEXT);
        val.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(lbl, BorderLayout.WEST); row.add(val, BorderLayout.EAST);
        parent.add(row); return val;
    }

    /* ══════════════════ DRAG KNOB ══════════════════════════ */
    class DragKnob extends JPanel {
        final String label, unit; final float min, max; final Color colorLow, colorHigh;
        float value, animValue; long tick = 0;
        int dragStartX; float dragStartVal; boolean dragging = false;
        interface Callback { void call(float v); } Callback onValueChange;

        DragKnob(String label, String unit, float min, float max, float init, Color cL, Color cH) {
            this.label=label; this.unit=unit; this.min=min; this.max=max;
            this.value=init; this.animValue=init; this.colorLow=cL; this.colorHigh=cH;
            setBackground(BG2); setBorder(BorderFactory.createLineBorder(BORDER,1));
            setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e)  { dragging=true; dragStartX=e.getX(); dragStartVal=value; setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)); }
                @Override public void mouseReleased(MouseEvent e) { dragging=false; setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)); }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    float newVal = clamp(dragStartVal-(dragStartX-e.getX())*(max-min)/200f, min, max);
                    setValue(newVal); if (onValueChange != null) onValueChange.call(value);
                }
            });
            addMouseWheelListener(e -> { setValue(clamp(value+(max-min)*0.01f*(float)(-e.getPreciseWheelRotation()),min,max)); if(onValueChange!=null)onValueChange.call(value); });
        }
        void setValue(float v) { value = clamp(v, min, max); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D)g; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            tick++; animValue += (value-animValue)*0.12f;
            int W=getWidth(),H=getHeight(),cx=W/2,cy=H/2+10,R=Math.min(W,H)/2-22;
            g2.setFont(new Font("Monospaced",Font.BOLD,10)); g2.setColor(MUTED);
            FontMetrics fm=g2.getFontMetrics(); g2.drawString(label,cx-fm.stringWidth(label)/2,18);
            float pct=(animValue-min)/(max-min); Color col=interpolateColor(colorLow,colorHigh,pct);
            final int SA=220,TS=260;
            g2.setStroke(new BasicStroke(8,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(25,38,70)); g2.drawArc(cx-R,cy-R,R*2,R*2,SA,-TS);
            int sweep=(int)(TS*pct); g2.setColor(col); g2.drawArc(cx-R,cy-R,R*2,R*2,SA,-sweep);
            if(dragging){g2.setColor(new Color(col.getRed(),col.getGreen(),col.getBlue(),30));g2.setStroke(new BasicStroke(16,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g2.drawArc(cx-R,cy-R,R*2,R*2,SA,-sweep);}
            double angle=Math.toRadians(SA-sweep);
            g2.setColor(Color.WHITE); g2.setStroke(new BasicStroke(2.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g2.drawLine((int)(cx+Math.cos(angle)*(R-10)),(int)(cy-Math.sin(angle)*(R-10)),(int)(cx+Math.cos(angle)*(R+2)),(int)(cy-Math.sin(angle)*(R+2)));
            int iR=R-18; g2.setColor(BG3); g2.fillOval(cx-iR,cy-iR,iR*2,iR*2);
            g2.setColor(new Color(col.getRed(),col.getGreen(),col.getBlue(),40)); g2.fillOval(cx-iR,cy-iR,iR*2,iR*2);
            g2.setColor(BORDER); g2.setStroke(new BasicStroke(1)); g2.drawOval(cx-iR,cy-iR,iR*2,iR*2);
            String vs=String.format("%.1f",animValue); g2.setFont(new Font("Monospaced",Font.BOLD,20)); fm=g2.getFontMetrics();
            g2.setColor(col); g2.drawString(vs,cx-fm.stringWidth(vs)/2,cy+7);
            g2.setFont(new Font("Monospaced",Font.PLAIN,11)); fm=g2.getFontMetrics(); g2.setColor(MUTED); g2.drawString(unit,cx-fm.stringWidth(unit)/2,cy+22);
            g2.setFont(new Font("Monospaced",Font.PLAIN,9)); g2.setColor(new Color(50,70,120));
            double sR=Math.toRadians(SA),eR=Math.toRadians(SA-TS);
            g2.drawString(String.valueOf((int)min),(int)(cx+Math.cos(sR)*(R+12))-8,(int)(cy-Math.sin(sR)*(R+12))+4);
            g2.drawString(String.valueOf((int)max),(int)(cx+Math.cos(eR)*(R+12))-4,(int)(cy-Math.sin(eR)*(R+12))+4);
            g2.setColor(new Color(50,75,130)); g2.setFont(new Font("Monospaced",Font.PLAIN,10)); g2.drawString("↕",cx-4,H-6);
        }
    }

    /* ══════════════════ V SLIDER ═══════════════════════════ */
    class VSlider extends JPanel {
        final String label; final float min,max; final Color color; float value,animValue; boolean dragging=false;
        interface Callback { void call(float v); } Callback onValueChange;
        VSlider(String label,float min,float max,float init,Color color){
            this.label=label;this.min=min;this.max=max;this.value=init;this.animValue=init;this.color=color;
            setBackground(BG2);setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER,1),BorderFactory.createEmptyBorder(2,0,2,0)));
            setCursor(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR));
            MouseAdapter ma=new MouseAdapter(){
                @Override public void mousePressed(MouseEvent e){dragging=true;update(e);}
                @Override public void mouseReleased(MouseEvent e){dragging=false;}
                @Override public void mouseDragged(MouseEvent e){update(e);}
                void update(MouseEvent e){int tH=getHeight()-40;int y=Math.max(0,Math.min(tH,e.getY()-20));float p=1f-y/(float)tH;float nv=clamp(min+p*(max-min),min,max);setValue(nv);if(onValueChange!=null)onValueChange.call(value);}
            };
            addMouseListener(ma);addMouseMotionListener(ma);
            addMouseWheelListener(e->{setValue(clamp(value+(max-min)*0.01f*(float)(-e.getPreciseWheelRotation()),min,max));if(onValueChange!=null)onValueChange.call(value);});
        }
        void setValue(float v){value=clamp(v,min,max);}
        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);Graphics2D g2=(Graphics2D)g;g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            animValue+=(value-animValue)*0.15f;int W=getWidth(),H=getHeight(),cx=W/2,tT=24,tB=H-16,tH=tB-tT,tW=10;
            float pct=(animValue-min)/(max-min);int fillY=(int)(tB-tH*pct);
            g2.setFont(new Font("Monospaced",Font.BOLD,9));g2.setColor(MUTED);FontMetrics fm=g2.getFontMetrics();g2.drawString(label,cx-fm.stringWidth(label)/2,14);
            g2.setColor(new Color(20,32,62));g2.fillRoundRect(cx-tW/2,tT,tW,tH,5,5);
            g2.setColor(color);g2.fillRoundRect(cx-tW/2,fillY,tW,tB-fillY,5,5);
            int tr=9;if(dragging){g2.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),40));g2.fillOval(cx-tr-4,fillY-tr-4,(tr+4)*2,(tr+4)*2);}
            g2.setColor(BG3);g2.fillOval(cx-tr,fillY-tr,tr*2,tr*2);g2.setColor(color);g2.setStroke(new BasicStroke(2));g2.drawOval(cx-tr,fillY-tr,tr*2,tr*2);
            g2.setFont(new Font("Monospaced",Font.BOLD,9));g2.setColor(color);String vs=String.format("%.0f",animValue);g2.drawString(vs,cx-fm.stringWidth(vs)/2,tB+12);
        }
    }

    /* ══════════════════ XY PAD ═════════════════════════════ */
    class XYPad extends JPanel {
        float cursorX=(temperature-(-10))/60f,cursorY=1f-humidity/100f,animX=0.5f,animY=0.5f;
        boolean dragging=false;long tick=0;
        interface Callback{void call(float t,float h);}Callback onMove;
        XYPad(){setBackground(BG2);setBorder(BorderFactory.createLineBorder(BORDER,1));setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter ma=new MouseAdapter(){
                @Override public void mousePressed(MouseEvent e){dragging=true;update(e);}
                @Override public void mouseReleased(MouseEvent e){dragging=false;}
                @Override public void mouseDragged(MouseEvent e){update(e);}
                void update(MouseEvent e){int pad=20,W=getWidth()-pad*2,H=getHeight()-pad*2;cursorX=clamp01((e.getX()-pad)/(float)W);cursorY=clamp01((e.getY()-pad)/(float)H);float t=-10+cursorX*60f,h=100-cursorY*100f;temperature=clamp(t,-10,50);humidity=clamp(h,0,100);if(onMove!=null)onMove.call(temperature,humidity);}
            };addMouseListener(ma);addMouseMotionListener(ma);}
        void setValues(float t,float h){cursorX=(t-(-10))/60f;cursorY=1f-h/100f;}
        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);Graphics2D g2=(Graphics2D)g;g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            tick++;animX+=(cursorX-animX)*0.15f;animY+=(cursorY-animY)*0.15f;
            int W=getWidth(),H=getHeight(),pad=20,iW=W-pad*2,iH=H-pad*2;
            for(int x=0;x<iW;x++){Color col=interpolateColor(COLD,HOT,x/(float)iW);g2.setColor(new Color(col.getRed(),col.getGreen(),col.getBlue(),40));g2.fillRect(pad+x,pad,1,iH);}
            for(int y=0;y<iH;y++){g2.setColor(new Color(HUMID.getRed(),HUMID.getGreen(),HUMID.getBlue(),(int)(40*(1-y/(float)iH))));g2.fillRect(pad,pad+y,iW,1);}
            g2.setColor(new Color(40,60,110,40));g2.setStroke(new BasicStroke(0.5f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,1f,new float[]{4,6},0));
            for(int i=1;i<4;i++){g2.drawLine(pad+iW*i/4,pad,pad+iW*i/4,pad+iH);g2.drawLine(pad,pad+iH*i/4,pad+iW,pad+iH*i/4);}
            int px=pad+(int)(animX*iW),py=pad+(int)(animY*iH);Color cc=interpolateColor(COLD,HOT,animX);
            g2.setStroke(new BasicStroke(1f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,1f,new float[]{3,4},0));
            g2.setColor(new Color(cc.getRed(),cc.getGreen(),cc.getBlue(),140));g2.drawLine(pad,py,pad+iW,py);g2.drawLine(px,pad,px,pad+iH);
            int hR=dragging?22:16;g2.setColor(new Color(cc.getRed(),cc.getGreen(),cc.getBlue(),25));g2.setStroke(new BasicStroke(1));g2.fillOval(px-hR,py-hR,hR*2,hR*2);
            int cr=7;g2.setColor(BG0);g2.fillOval(px-cr,py-cr,cr*2,cr*2);g2.setColor(cc);g2.setStroke(new BasicStroke(2.5f));g2.drawOval(px-cr,py-cr,cr*2,cr*2);g2.fillOval(px-3,py-3,6,6);
            g2.setFont(new Font("Monospaced",Font.PLAIN,9));g2.setColor(MUTED);
            g2.drawString("← FRÍO     TEMPERATURA     CALIENTE →",pad,H-5);g2.drawString("↑ HÚMEDO",1,pad+10);g2.drawString("↓ SECO",1,pad+iH-2);
            g2.setFont(new Font("Monospaced",Font.BOLD,11));String info=String.format("T=%.1f°  H=%.0f%%",temperature,humidity);
            FontMetrics fm=g2.getFontMetrics();g2.setColor(new Color(0,0,0,120));g2.fillRoundRect(px+10,py-20,fm.stringWidth(info)+10,18,4,4);g2.setColor(cc);g2.drawString(info,px+15,py-6);
        }
    }

    /* ══════════════════ WEATHER PREVIEW ════════════════════ */
    class WeatherPreview extends JPanel {
        long tick=0;final java.util.List<float[]> drops=new ArrayList<>();final Random rnd=new Random();
        WeatherPreview(){setBackground(BG2);setBorder(BorderFactory.createLineBorder(BORDER,1));}
        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);Graphics2D g2=(Graphics2D)g;g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            tick++;int W=getWidth(),H=getHeight();
            Color bgT=temperature<10?new Color(8,20,70):temperature<25?new Color(10,30,80):temperature<33?new Color(50,28,12):new Color(90,14,8);
            Color bgB=temperature<10?new Color(14,40,100):temperature<25?new Color(18,55,110):temperature<33?new Color(140,75,18):new Color(190,42,12);
            g2.setPaint(new GradientPaint(0,0,bgT,0,H,bgB));g2.fillRect(0,0,W,H);g2.setPaint(null);
            if(humidity>65&&drops.size()<60&&tick%2==0)drops.add(new float[]{rnd.nextInt(W*3/4),-6,(rnd.nextFloat()-.5f)*1.2f,4+rnd.nextFloat()*3,60});
            if(temperature<8&&drops.size()<40&&tick%3==0)drops.add(new float[]{rnd.nextInt(W*3/4),-6,(rnd.nextFloat()-.5f)*.8f,1.2f+rnd.nextFloat()*1.2f,100});
            drops.removeIf(d->{d[0]+=d[2];d[1]+=d[3];d[4]--;return d[1]>H+10||d[4]<=0;});
            for(float[]d:drops){float a=Math.min(1f,d[4]/30f);if(temperature<8){g2.setColor(new Color(210,230,255,(int)(180*a)));g2.fillOval((int)d[0]-2,(int)d[1]-2,4,4);}else{g2.setColor(new Color(140,190,255,(int)(140*a)));g2.setStroke(new BasicStroke(1.2f));g2.drawLine((int)d[0],(int)d[1],(int)(d[0]+d[2]*3),(int)(d[1]+d[3]*3));}}
            float pulse=(float)(Math.sin(tick*.07)*.1+1.0);int cx=W/4,cy=H/2;
            if(temperature>=32){int sr=(int)(28*pulse);for(int i=3;i>0;i--){g2.setColor(new Color(255,150,0,12*i));g2.fillOval(cx-sr-i*8,cy-sr-i*8,(sr+i*8)*2,(sr+i*8)*2);}g2.setColor(new Color(255,200,50,200));g2.setStroke(new BasicStroke(2.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));for(int i=0;i<8;i++){double a=Math.toRadians(i*45+tick*.4);g2.drawLine((int)(cx+Math.cos(a)*(sr+2)),(int)(cy+Math.sin(a)*(sr+2)),(int)(cx+Math.cos(a)*(sr+12)),(int)(cy+Math.sin(a)*(sr+12)));}g2.setColor(new Color(255,210,55,230));g2.fillOval(cx-sr,cy-sr,sr*2,sr*2);}
            else if(humidity>65){int s=28;g2.setColor(new Color(190,210,235,200));g2.fillOval(cx-s,cy-s/2,s*2,s);g2.fillOval(cx-s/3,cy-s*3/4,(int)(s*1.3),(int)(s*.95));g2.fillOval(cx+s/5,cy-s/2,(int)(s*1.3),s);}
            else{int sr=(int)(24*pulse);g2.setColor(new Color(255,205,55,220));g2.setStroke(new BasicStroke(2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));for(int i=0;i<8;i++){double a=Math.toRadians(i*45+tick*.35);g2.drawLine((int)(cx+Math.cos(a)*(sr+2)),(int)(cy+Math.sin(a)*(sr+2)),(int)(cx+Math.cos(a)*(sr+11)),(int)(cy+Math.sin(a)*(sr+11)));}g2.setColor(new Color(255,210,60,230));g2.fillOval(cx-sr,cy-sr,sr*2,sr*2);}
            if(temperature<8){g2.setColor(new Color(200,228,255,180));g2.setStroke(new BasicStroke(1.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));int sR=14;double rot=Math.toRadians(tick*.7);for(int i=0;i<6;i++){double a=rot+i*60*Math.PI/180;g2.drawLine(cx,cy+50,(int)(cx+Math.cos(a)*sR),(int)(cy+50+Math.sin(a)*sR));}g2.fillOval(cx-3,cy+47,6,6);}
            Color tc=tempColor(temperature);int rx=W/2+10;
            g2.setFont(new Font("Monospaced",Font.BOLD,40));String ts=String.format("%.1f°",temperature);
            g2.setColor(new Color(0,0,0,80));g2.drawString(ts,rx+2,H-22+2);g2.setColor(tc);g2.drawString(ts,rx,H-22);
            g2.setFont(new Font("Monospaced",Font.PLAIN,14));g2.setColor(new Color(130,185,255,200));g2.drawString(String.format("%.0f%% HR",humidity),rx,H-4);
            float hi=computeHeatIndex(temperature,humidity);g2.setFont(new Font("Monospaced",Font.PLAIN,10));g2.setColor(MUTED);g2.drawString(String.format("HI: %.1f°C",hi),rx,16);
            String cond=temperature<5?"Helada":temperature<15?"Frío":temperature<22?"Fresco":temperature<28&&humidity<60?"Confort":humidity>85?"Muy húmedo":humidity>70?"Húmedo":temperature<32?"Cálido":temperature<38?"Caluroso":"Extremo";
            g2.setFont(new Font("Monospaced",Font.BOLD,11));g2.setColor(tc);g2.drawString(cond,rx,30);
        }
    }

    /* ══════════════════ LOG AREA ════════════════════════════ */
    class LogArea extends JPanel {
        final DefaultListModel<Object[]> model=new DefaultListModel<>();
        LogArea(){
            setLayout(new BorderLayout());setBackground(BG1);
            JLabel title=new JLabel("  LOG DE ENVÍOS");title.setFont(new Font("Monospaced",Font.BOLD,9));title.setForeground(MUTED);
            title.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,1,0,BORDER),BorderFactory.createEmptyBorder(5,0,5,0)));
            title.setBackground(BG1);title.setOpaque(true);
            JList<Object[]> list=new JList<>(model);list.setBackground(BG1);list.setFont(new Font("Monospaced",Font.PLAIN,10));list.setFixedCellHeight(16);
            list.setBorder(BorderFactory.createEmptyBorder(2,6,2,6));
            list.setCellRenderer((l,v,i,sel,foc)->{Object[]row=(Object[])v;JLabel lb=new JLabel((String)row[0]);lb.setFont(new Font("Monospaced",Font.PLAIN,10));lb.setForeground((Color)row[1]);lb.setBackground(i%2==0?BG1:new Color(14,22,44));lb.setOpaque(true);lb.setBorder(BorderFactory.createEmptyBorder(1,4,1,4));return lb;});
            JScrollPane sp=new JScrollPane(list);sp.setBorder(BorderFactory.createEmptyBorder());sp.setBackground(BG1);sp.getViewport().setBackground(BG1);sp.getVerticalScrollBar().setPreferredSize(new Dimension(4,0));sp.getVerticalScrollBar().setBackground(BG0);
            add(title,BorderLayout.NORTH);add(sp,BorderLayout.CENTER);setPreferredSize(new Dimension(0,200));
        }
        void log(String msg,Color color){SwingUtilities.invokeLater(()->{String time=LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));model.add(0,new Object[]{time+" "+msg,color});if(model.size()>100)model.remove(model.size()-1);});}
    }
}
