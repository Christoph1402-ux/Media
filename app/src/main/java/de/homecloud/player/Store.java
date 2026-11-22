package de.homecloud.player;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Speichert Quellen (FTP, SMB, WebDAV) samt Zugangsdaten im privaten App-Speicher. */
final class Store {
    private final File file;
    private JSONObject root;

    Store(Context c) {
        file = new File(c.getFilesDir(), "quellen.json");
        try {
            root = new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            root = new JSONObject();
        }
        try {
            if (!root.has("token")) {
                byte[] b = new byte[24];
                new SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x));
                root.put("token", sb.toString());
                root.put("sources", new JSONArray());
                save();
            }
        } catch (Exception ignored) { }
    }

    synchronized String token() { return root.optString("token"); }

    private synchronized void save() throws Exception {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file)) throw new Exception("Speichern fehlgeschlagen");
    }

    synchronized JSONArray rawSources() { return root.optJSONArray("sources"); }

    synchronized JSONObject find(String id) {
        JSONArray a = rawSources();
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i);
        return null;
    }

    /** Liste ohne Passwoerter fuer die App. */
    synchronized JSONArray meta() {
        JSONArray out = new JSONArray();
        JSONArray a = rawSources();
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.getJSONObject(i), m = new JSONObject();
                for (String k : new String[]{"id", "name", "type", "host", "port", "user", "path", "url", "insecure", "secure", "domain", "uri"})
                    if (s.has(k)) m.put(k, s.get(k));
                m.put("readonly", false);
                m.put("hasPass", !s.optString("password").isEmpty());
                out.put(m);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Eingaben pruefen und bereinigen. Leeres Passwort beim Bearbeiten = unveraendert. */
    JSONObject clean(JSONObject d, JSONObject old) throws Exception {
        String t = d.optString("type");
        if (!(t.equals("ftp") || t.equals("smb") || t.equals("webdav") || t.equals("saf"))) throw new IllegalArgumentException("Unbekannter Typ");
        String name = d.optString("name").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Bitte einen Namen eingeben");
        if (name.length() > 60) name = name.substring(0, 60);
        JSONObject n = new JSONObject();
        n.put("type", t);
        n.put("name", name);
        String pass = d.optString("password", "");
        if (pass.isEmpty() && old != null && t.equals(old.optString("type"))) pass = old.optString("password", "");
        if (t.equals("ftp")) {
            String host = d.optString("host").trim().replaceFirst("^ftp://", "").replaceAll("/+$", "");
            if (host.isEmpty()) throw new IllegalArgumentException("Bitte die FTP-Adresse eingeben");
            n.put("host", host);
            n.put("port", d.optInt("port", 21) > 0 ? d.optInt("port", 21) : 21);
            n.put("user", d.optString("user").trim().isEmpty() ? "anonymous" : d.optString("user").trim());
            n.put("password", pass);
            String path = d.optString("path").trim();
            n.put("path", path.isEmpty() ? "/" : path);
            String sec = d.optString("secure");
            n.put("secure", sec.equals("tls") || sec.equals("tls-insecure") ? sec : "");
        } else if (t.equals("saf")) {
            String uri = d.optString("uri").trim();
            if (uri.isEmpty() && old != null) uri = old.optString("uri");
            if (!uri.startsWith("content://")) throw new IllegalArgumentException("Ordner nicht ausgewählt");
            n.put("uri", uri);
        } else if (t.equals("smb")) {
            String host = d.optString("host").trim().replaceFirst("^smb://", "").replaceAll("^[\\\\/]+", "").replaceAll("[\\\\/]+$", "");
            String path = d.optString("path").trim().replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
            if (host.contains("/")) {                      // "nas/Musik" im Hostfeld -> aufteilen
                if (path.isEmpty()) path = host.substring(host.indexOf('/') + 1);
                host = host.substring(0, host.indexOf('/'));
            }
            if (host.isEmpty()) throw new IllegalArgumentException("Bitte Name oder IP des NAS eingeben");
            if (path.isEmpty()) throw new IllegalArgumentException("Bitte den Namen der Freigabe eingeben (z. B. Musik)");
            n.put("host", host);
            n.put("path", path);
            n.put("user", d.optString("user").trim());
            n.put("password", pass);
            n.put("domain", d.optString("domain").trim());
        } else {
            String url = d.optString("url").trim();
            if (!Pattern.matches("(?i)^https?://.+", url)) throw new IllegalArgumentException("Die WebDAV-Adresse muss mit http:// oder https:// beginnen");
            n.put("url", url.replaceAll("/+$", ""));
            n.put("user", d.optString("user").trim());
            n.put("password", pass);
            n.put("insecure", d.optBoolean("insecure", false));
        }
        return n;
    }

    synchronized String upsert(JSONObject d) throws Exception {
        String id = d.optString("id");
        JSONObject old = id.isEmpty() ? null : find(id);
        JSONObject n = clean(d, old);
        JSONArray a = rawSources();
        if (old != null) {
            n.put("id", id);
            for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).optString("id"))) a.put(i, n);
        } else {
            String base = n.getString("name").toLowerCase().replaceAll("[^a-z0-9]+", "");
            if (base.isEmpty()) base = "quelle";
            String sid = base;
            for (int k = 2; find(sid) != null; k++) sid = base + k;
            n.put("id", sid);
            a.put(n);
        }
        save();
        return n.getString("id");
    }

    synchronized void remove(String id) throws Exception {
        JSONArray a = rawSources();
        JSONArray b = new JSONArray();
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            if (id.equals(a.getJSONObject(i).optString("id"))) found = true; else b.put(a.get(i));
        }
        if (!found) throw new IllegalArgumentException("Quelle unbekannt");
        root.put("sources", b);
        save();
    }

    List<String> ids() {
        List<String> l = new ArrayList<>();
        JSONArray a = rawSources();
        for (int i = 0; i < a.length(); i++) l.add(a.optJSONObject(i).optString("id"));
        return l;
    }
}
