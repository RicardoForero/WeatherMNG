import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/**
 * Gateway web para Weather_Server (sin dependencias, Java 17+).
 * Ejecutar:  ADMIN_PASSWORD=miClaveSegura java WebGateway.java
 *
 * Variables: TCP_HOST (127.0.0.1) · TCP_PORT (2361) · HTTP_PORT (8080)
 *            ADMIN_PASSWORD (obligatoria, min. 8) · KEYSTORE + KEYSTORE_PASS (activa HTTPS)
 *            WEB_ROOT (carpeta de index.html, por defecto ".")
 */
public class WebGateway {

    static final String TCP_HOST = env("TCP_HOST", "127.0.0.1");
    static final int TCP_PORT = Integer.parseInt(env("TCP_PORT", "2361"));
    static final int HTTP_PORT = Integer.parseInt(env("HTTP_PORT", "8080"));
    static final String PASS = env("ADMIN_PASSWORD", "");
    static final Map<String, Long> sessions = new ConcurrentHashMap<>();
    static final SecureRandom RNG = new SecureRandom();
    static boolean https = false;

    static class ClientGone extends RuntimeException {}

    static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isEmpty() ? d : v; }

    public static void main(String[] args) throws Exception {
        if (PASS.length() < 8) {
            System.err.println("Define ADMIN_PASSWORD con al menos 8 caracteres.");
            System.exit(1);
        }
        HttpServer srv;
        String ks = env("KEYSTORE", "");
        if (!ks.isEmpty()) {
            char[] pw = env("KEYSTORE_PASS", "").toCharArray();
            KeyStore k = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(ks))) { k.load(in, pw); }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(k, pw);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            HttpsServer hs = HttpsServer.create(new InetSocketAddress(HTTP_PORT), 0);
            hs.setHttpsConfigurator(new HttpsConfigurator(ctx));
            srv = hs;
            https = true;
        } else {
            srv = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        }
        srv.setExecutor(Executors.newCachedThreadPool());
        srv.createContext("/", WebGateway::index);
        srv.createContext("/api/login", WebGateway::login);
        srv.createContext("/api/stream", WebGateway::stream);
        srv.createContext("/api/cmd", WebGateway::cmd);
        srv.createContext("/api/status", WebGateway::status);
        srv.start();
        System.out.println((https ? "https" : "http") + "://0.0.0.0:" + HTTP_PORT
                + "  ->  TCP " + TCP_HOST + ":" + TCP_PORT);
        if (!https) System.out.println("AVISO: sin HTTPS la contrasena viaja en claro. Usa KEYSTORE o un proxy TLS.");
    }

    // ── Archivos estáticos ───────────────────────────────────
    static void index(HttpExchange x) throws IOException {
        if (!x.getRequestURI().getPath().equals("/")) { send(x, 404, "text/plain", "no encontrado"); return; }
        Path p = Path.of(env("WEB_ROOT", "."), "index.html");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        send(x, 200, "text/html; charset=utf-8", Files.readAllBytes(p));
    }

    // ── Autenticación ────────────────────────────────────────
    static boolean authed(HttpExchange x) {
        String c = x.getRequestHeaders().getFirst("Cookie");
        if (c == null) return false;
        for (String p : c.split(";")) {
            p = p.trim();
            if (p.startsWith("sid=")) {
                Long exp = sessions.get(p.substring(4));
                if (exp != null && exp > System.currentTimeMillis()) return true;
            }
        }
        return false;
    }

    static void login(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { send(x, authed(x) ? 200 : 401, "text/plain", "-"); return; }
        String pw = new String(x.getRequestBody().readNBytes(256), StandardCharsets.UTF_8);
        boolean ok = MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), PASS.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            System.out.println("[login] clave incorrecta desde " + x.getRemoteAddress().getAddress());
            send(x, 401, "text/plain", "clave incorrecta");
            return;
        }
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        sessions.put(token, System.currentTimeMillis() + 12L * 3600_000);
        System.out.println("[login] sesion iniciada desde " + x.getRemoteAddress().getAddress());
        x.getResponseHeaders().add("Set-Cookie",
                "sid=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200" + (https ? "; Secure" : ""));
        send(x, 200, "text/plain", "ok");
    }

       // ── Flujo en vivo (SSE): un socket admin por navegador ───
    static void stream(HttpExchange x) throws IOException {
        if (!authed(x)) { send(x, 401, "text/plain", "auth"); return; }
        Headers h = x.getResponseHeaders();
        h.set("Content-Type", "text/event-stream");
        h.set("Cache-Control", "no-cache");
        h.set("X-Accel-Buffering", "no");
        x.sendResponseHeaders(200, 0);
        OutputStream out = x.getResponseBody();
        String lastErr = "";
        System.out.println("[stream] navegador conectado: " + x.getRemoteAddress().getAddress());
        try {
            while (true) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(TCP_HOST, TCP_PORT), 3000);
                    s.setSoTimeout(15000);
                    new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)
                            .println("ADMIN_v1");
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    System.out.println("[stream] conectado al servidor TCP " + TCP_HOST + ":" + TCP_PORT);
                    lastErr = "";
                    sse(out, "link", "up");
                    while (true) {
                        try {
                            String l = r.readLine();
                            if (l == null) break;
                            l = l.trim();
                            // FILTRO DE SEGURIDAD: Solo reenviar si es un objeto JSON válido
                            if (!l.isBlank() && l.startsWith("{")) {
                                sse(out, "message", l);
                            } else if (!l.isBlank()) {
                                System.out.println("[stream] Mensaje ignorado (no es JSON): " + l);
                            }
                        } catch (SocketTimeoutException e) {
                            raw(out, ": ka\n\n");
                        }
                    }
                } catch (IOException e) {
                    String m = e.getClass().getSimpleName() + ": " + e.getMessage();
                    if (!m.equals(lastErr)) { System.out.println("[stream] sin servidor TCP " + TCP_HOST + ":" + TCP_PORT + " -> " + m); lastErr = m; }
                }
                sse(out, "link", "down");
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        } catch (ClientGone e) {
            System.out.println("[stream] navegador desconectado");
        } finally {
            x.close();
        }
    }

        static void sse(OutputStream o, String ev, String data) { raw(o, "event: " + ev + "\ndata: " + data + "\n\n"); }

        static void raw(OutputStream o, String s) {
            try { o.write(s.getBytes(StandardCharsets.UTF_8)); o.flush(); }
            catch (IOException e) { throw new ClientGone(); }
        }

        // ── Diagnóstico (sin login, no expone datos) ─────────────
        static void status(HttpExchange x) throws IOException {
            String tcp;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(TCP_HOST, TCP_PORT), 2000);
                tcp = "up";
            } catch (SocketTimeoutException e) { tcp = "down:timeout"; }
            catch (ConnectException e) { tcp = "down:refused"; }
            catch (IOException e) { tcp = "down:error"; }
            x.getResponseHeaders().set("Cache-Control", "no-store");
            send(x, 200, "application/json", "{\"gateway\":\"up\",\"tcp\":\"" + tcp + "\",\"authed\":" + authed(x) + "}");
        }

        // ── Comandos: socket admin efímero por comando ───────────
        static void cmd(HttpExchange x) throws IOException {
            if (!authed(x)) { send(x, 401, "text/plain", "auth"); return; }
            if (!x.getRequestMethod().equals("POST")) { send(x, 405, "text/plain", "POST"); return; }
            String body = new String(x.getRequestBody().readNBytes(2048), StandardCharsets.UTF_8).trim();
            if (!body.startsWith("{\"cmd\":") || body.indexOf('\n') >= 0 || body.indexOf('\r') >= 0) {
                send(x, 400, "text/plain", "comando inválido");
                return;
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(TCP_HOST, TCP_PORT), 3000);
                s.setSoTimeout(1500);
                PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                w.println("ADMIN_v1");
                w.println(body);
                s.shutdownOutput();
                try { s.getInputStream().readAllBytes(); } catch (SocketTimeoutException ignored) {}
            } catch (IOException e) {
                send(x, 502, "text/plain", "servidor de riego no disponible");
                return;
            }
            send(x, 200, "text/plain", "ok");
        }

        static void send(HttpExchange x, int code, String type, String body) throws IOException {
            send(x, code, type, body.getBytes(StandardCharsets.UTF_8));
        }

        static void send(HttpExchange x, int code, String type, byte[] b) throws IOException {
            x.getResponseHeaders().set("Content-Type", type);
            x.sendResponseHeaders(code, b.length);
            try (OutputStream o = x.getResponseBody()) { o.write(b); }
        }
    }
