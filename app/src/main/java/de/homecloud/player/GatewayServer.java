package de.homecloud.player;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kleiner Server nur auf dem eigenen Geraet (127.0.0.1). Er spricht dieselbe Sprache wie das PC-Gateway:
 * Die Web-App holt hier Quellenlisten und streamt Dateien (mit Spulen) aus FTP, SMB und WebDAV.
 * Ohne den geheimen Token der App antwortet er nicht.
 */
final class GatewayServer {
    private final Store store;
    private final ServerSocket socket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private static GatewayServer instance;

    static synchronized GatewayServer start(Context c) {
        if (instance == null) {
            try {
                instance = new GatewayServer(c.getApplicationContext());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return instance;
    }

    private final Context ctx;

    private GatewayServer(Context c) throws IOException {
        ctx = c;
        store = new Store(c);
        ServerSocket s;
        try {
            s = new ServerSocket(8765, 50, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            s = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        }
        socket = s;
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    final Socket cs = socket.accept();
                    pool.execute(() -> handle(cs));
                } catch (IOException e) {
                    break;
                }
            }
        }, "homecloud-gateway");
        t.setDaemon(true);
        t.start();
    }

    String base() { return "http://127.0.0.1:" + socket.getLocalPort(); }
    String token() { return store.token(); }

    // ------------------------------------------------------------------ HTTP
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("Zeile zu lang");
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT), target = parts[1];
            Map<String, String> h = new HashMap<>();
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                int i = l.indexOf(':');
                if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            byte[] body = new byte[0];
            int cl = 0;
            try { cl = Integer.parseInt(h.getOrDefault("content-length", "0")); } catch (Exception ignored) { }
            if (cl > 0) {
                cl = Math.min(cl, 32768);
                body = new byte[cl];
                int off = 0;
                while (off < cl) {
                    int n = in.read(body, off, cl - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            route(method, target, h, body, out);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static void head(OutputStream out, int code, String reason, long len, String ctype, Map<String, String> extra) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(ctype).append("\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Headers: Authorization, Content-Type, Range\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Private-Network: true\r\n");
        sb.append("Access-Control-Expose-Headers: Content-Range, Accept-Ranges, Content-Length\r\n");
        sb.append("Connection: close\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void json(OutputStream out, int code, Object o, boolean headOnly) throws IOException {
        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        head(out, code, code == 200 ? "OK" : "Error", b.length, "application/json; charset=utf-8", null);
        if (!headOnly) out.write(b);
    }

    private static JSONObject err(String m) {
        try { return new JSONObject().put("error", m); } catch (Exception e) { return new JSONObject(); }
    }

    private boolean authed(Map<String, String> h, Map<String, String> q) {
        String t = h.getOrDefault("authorization", "");
        t = t.startsWith("Bearer ") ? t.substring(7) : q.getOrDefault("t", "");
        return !t.isEmpty() && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), store.token().getBytes(StandardCharsets.UTF_8));
    }

    private Source source(String id) throws Exception {
        JSONObject c = store.find(id);
        if (c == null) return null;
        return make(c);
    }

    private Source make(JSONObject c) {
        switch (c.optString("type")) {
            case "saf": return new SafSource(ctx, c);
            case "ftp": return new FtpSource(c);
            case "smb": return new SmbSource(c);
            case "webdav": return new DavSource(c);
            default: return null;
        }
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        String l = m.toLowerCase(Locale.ROOT);
        if (l.contains("timed out") || l.contains("timeout")) m += " – Zeitüberschreitung: Adresse richtig? Gerät im selben WLAN? Firewall am NAS?";
        else if (l.contains("refused")) m += " – Verbindung abgelehnt: Dienst am NAS eingeschaltet, Port richtig?";
        else if (l.contains("unknownhost") || l.contains("unable to resolve") || l.contains("no address")) m += " – Name nicht gefunden: IP-Adresse statt Name versuchen.";
        else if (l.contains("logon") || l.contains("access is denied") || l.contains("unauthorized")) m += " – Benutzer, Passwort oder Berechtigung prüfen.";
        else if (l.contains("ssl") || l.contains("tls") || l.contains("certificate")) m += " – Verschlüsselung/Zertifikat passt nicht (FTPS bzw. Zertifikatsoption prüfen).";
        return m;
    }

    private void route(String method, String target, Map<String, String> h, byte[] body, OutputStream out) throws Exception {
        int qi = target.indexOf('?');
        String path = qi < 0 ? target : target.substring(0, qi);
        Map<String, String> q = query(qi < 0 ? "" : target.substring(qi + 1));
        boolean headOnly = method.equals("HEAD");
        if (method.equals("OPTIONS")) { head(out, 204, "No Content", 0, "text/plain", null); return; }
        if (path.equals("/api/ping")) { json(out, 200, new JSONObject().put("ok", true).put("name", "HomeCloud App"), headOnly); return; }
        if (!authed(h, q)) { json(out, 401, err("Anmeldung nötig"), headOnly); return; }
        try {
            if (path.equals("/api/sources")) { json(out, 200, store.meta(), headOnly); return; }
            if (path.equals("/api/scan")) {
                Source s = source(q.getOrDefault("src", ""));
                if (s == null) { json(out, 404, err("Quelle unbekannt"), headOnly); return; }
                try {
                    JSONArray items = new JSONArray();
                    for (Source.Entry e : s.scan()) items.put(new JSONObject().put("p", e.p).put("s", e.s));
                    json(out, 200, new JSONObject().put("items", items), headOnly);
                } catch (Exception e) {
                    json(out, 502, err("Quelle nicht erreichbar: " + message(e)), headOnly);
                }
                return;
            }
            if (path.equals("/stream")) { stream(q, h, headOnly, out); return; }
            if (method.equals("POST") && path.startsWith("/api/source")) {
                JSONObject d = new JSONObject(new String(body, StandardCharsets.UTF_8).isEmpty() ? "{}" : new String(body, StandardCharsets.UTF_8));
                if (path.equals("/api/source/test")) {
                    try {
                        Source s;
                        String id = d.optString("id");
                        if (!id.isEmpty() && d.optString("type").isEmpty()) s = source(id);
                        else {
                            JSONObject n = store.clean(d, id.isEmpty() ? null : store.find(id));
                            n.put("id", "test");
                            s = make(n);
                        }
                        if (s == null) throw new IllegalArgumentException("Quelle unbekannt");
                        json(out, 200, new JSONObject().put("ok", true).put("message", s.test()), headOnly);
                    } catch (Exception e) {
                        json(out, 200, new JSONObject().put("ok", false).put("message", message(e)), headOnly);
                    }
                    return;
                }
                if (path.equals("/api/source")) {
                    String id = store.upsert(d);
                    json(out, 200, new JSONObject().put("id", id).put("sources", store.meta()), headOnly);
                    return;
                }
                if (path.equals("/api/source/delete")) {
                    store.remove(d.optString("id"));
                    json(out, 200, new JSONObject().put("sources", store.meta()), headOnly);
                    return;
                }
            }
            json(out, 404, err("unbekannt"), headOnly);
        } catch (IllegalArgumentException e) {
            json(out, 400, err(e.getMessage()), headOnly);
        }
    }

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".aac")) return "audio/aac";
        return "application/octet-stream";
    }

    private void stream(Map<String, String> q, Map<String, String> h, boolean headOnly, OutputStream out) throws Exception {
        Source src = source(q.getOrDefault("src", ""));
        String rel = q.getOrDefault("p", "");
        if (src == null) { json(out, 404, err("Quelle unbekannt"), headOnly); return; }
        long start = 0, end = -1;
        boolean ranged = false, suffix = false;
        long suffixLen = 0;
        Matcher m = RANGE.matcher(h.getOrDefault("range", "").trim());
        if (m.matches() && (!m.group(1).isEmpty() || !m.group(2).isEmpty())) {
            ranged = true;
            if (!m.group(1).isEmpty()) {
                start = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) end = Long.parseLong(m.group(2));
            } else {
                suffix = true;
                suffixLen = Long.parseLong(m.group(2));
            }
        }
        Source.Opened o = null;
        try {
            if (suffix) {
                long size = src.size(rel);
                start = Math.max(0, size - suffixLen);
            }
            o = src.open(rel, start);
            long size = o.size;
            if (end < 0 || end >= size) end = size - 1;
            if (start >= size || start > end) {
                Map<String, String> ex = new HashMap<>();
                ex.put("Content-Range", "bytes */" + size);
                head(out, 416, "Range Not Satisfiable", 0, "text/plain", ex);
                return;
            }
            Map<String, String> ex = new HashMap<>();
            ex.put("Accept-Ranges", "bytes");
            if (ranged) ex.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
            head(out, ranged ? 206 : 200, ranged ? "Partial Content" : "OK", end - start + 1, mime(rel), ex);
            if (headOnly) return;
            byte[] buf = new byte[65536];
            long left = end - start + 1;
            while (left > 0) {
                int n = o.in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            // Wiedergabe wurde beendet oder Verbindung zur Quelle brach ab
            if (o == null) {
                try { json(out, 502, err("Quelle nicht erreichbar: " + message(e)), headOnly); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            try { json(out, 502, err("Quelle nicht erreichbar: " + message(e)), headOnly); } catch (Exception ignored) { }
        } finally {
            if (o != null) o.close();
        }
    }
}
