package de.homecloud.player;

import android.net.Uri;
import android.util.Xml;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** WebDAV (viele NAS bieten das an). */
final class DavSource extends Source {
    private final String base, user, pass;
    private final boolean insecure;
    private final OkHttpClient client;

    DavSource(JSONObject c) {
        base = c.optString("url").replaceAll("/+$", "");
        user = c.optString("user");
        pass = c.optString("password");
        insecure = c.optBoolean("insecure", false);
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true);
        if (insecure) {
            try {
                final X509TrustManager tm = new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                    @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{tm}, null);
                b.sslSocketFactory(sc.getSocketFactory(), tm).hostnameVerifier((h, s) -> true);
            } catch (Exception ignored) { }
        }
        client = b.build();
    }

    private Request.Builder req(String url) {
        Request.Builder r = new Request.Builder().url(url);
        if (!user.isEmpty()) r.header("Authorization", Credentials.basic(user, pass, StandardCharsets.UTF_8));
        return r;
    }

    private static final class Res { String href; boolean dir; long size; }

    private List<Res> propfind(String url) throws Exception {
        String xml = "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><resourcetype/><getcontentlength/></prop></propfind>";
        Request rq = req(url).header("Depth", "1")
                .method("PROPFIND", RequestBody.create(xml.getBytes(StandardCharsets.UTF_8), MediaType.parse("application/xml"))).build();
        try (Response r = client.newCall(rq).execute()) {
            if (r.code() == 401) throw new IOException("Anmeldung abgelehnt (Benutzer/Passwort?)");
            if (r.code() != 207 && r.code() != 200) throw new IOException("WebDAV antwortet mit Fehler " + r.code());
            List<Res> out = new ArrayList<>();
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            p.setInput(r.body().byteStream(), "UTF-8");
            Res cur = null;
            String txt = "";
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if (n.equals("response")) cur = new Res();
                    else if (n.equals("collection") && cur != null) cur.dir = true;
                    txt = "";
                } else if (ev == XmlPullParser.TEXT) {
                    txt += p.getText();
                } else if (ev == XmlPullParser.END_TAG && cur != null) {
                    String n = p.getName();
                    if (n.equals("href")) cur.href = txt.trim();
                    else if (n.equals("getcontentlength")) { try { cur.size = Long.parseLong(txt.trim()); } catch (Exception ignored) { } }
                    else if (n.equals("response")) { if (cur.href != null) out.add(cur); cur = null; }
                }
            }
            return out;
        }
    }

    private String origin() {
        Uri u = Uri.parse(base);
        return u.getScheme() + "://" + u.getAuthority();
    }

    private String basePath() {
        return Uri.decode(Uri.parse(base).getEncodedPath() == null ? "" : Uri.parse(base).getEncodedPath()).replaceAll("/+$", "");
    }

    @Override
    List<Entry> scan() throws Exception {
        List<Entry> out = newList();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(base);
        String bp = basePath();
        while (!stack.isEmpty() && out.size() < MAX_ITEMS) {
            String d = stack.pop();
            if (!seen.add(d)) continue;
            String dPath = Uri.decode(Uri.parse(d).getEncodedPath()).replaceAll("/+$", "");
            for (Res r : propfind(d)) {
                String enc = r.href.startsWith("http") ? Uri.parse(r.href).getEncodedPath() : r.href;
                String dec = Uri.decode(enc);
                if (dec.replaceAll("/+$", "").equals(dPath)) continue;
                if (r.dir) stack.push(origin() + enc);
                else if (isMedia(dec)) out.add(new Entry(dec.substring(Math.min(bp.length(), dec.length())).replaceFirst("^/+", ""), r.size));
            }
        }
        return sorted(out);
    }

    @Override
    Opened open(String rel, long start) throws Exception {
        StringBuilder u = new StringBuilder(base);
        for (String part : clean(rel).split("/")) u.append('/').append(Uri.encode(part));
        Request.Builder rb = req(u.toString());
        if (start > 0 && start != Long.MAX_VALUE) rb.header("Range", "bytes=" + start + "-");
        if (start == Long.MAX_VALUE) rb.header("Range", "bytes=0-0");
        final Response r = client.newCall(rb.build()).execute();
        if (r.code() == 404) { r.close(); throw new IOException("Datei nicht gefunden: " + rel); }
        if (r.code() >= 400) { int c = r.code(); r.close(); throw new IOException("WebDAV antwortet mit Fehler " + c); }
        long size = -1;
        String cr = r.header("Content-Range");
        if (cr != null && cr.contains("/")) { try { size = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception ignored) { } }
        if (size < 0) { try { size = Long.parseLong(r.header("Content-Length", "-1")); } catch (Exception ignored) { } }
        if (size < 0) { r.close(); throw new IOException("Dateigröße unbekannt"); }
        if (start >= size) { r.close(); return new Opened(size, new java.io.ByteArrayInputStream(new byte[0]), null); }
        InputStream in = r.body().byteStream();
        if (r.code() == 200 && start > 0) {              // Server ignoriert Range: selbst ueberspringen
            long skip = start;
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) { if (in.read() < 0) break; n = 1; }
                skip -= n;
            }
        }
        return new Opened(size, in, r);
    }

    @Override
    String test() throws Exception {
        int n = propfind(base).size();
        return "WebDAV verbunden (" + Math.max(0, n - 1) + " Einträge im Startordner)";
    }
}
