package de.homecloud.player;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/** Windows-Freigaben und NAS per SMB (SMB 2/3). */
final class SmbSource extends Source {
    private final String host, user, pass, domain, path;

    SmbSource(JSONObject c) {
        host = c.optString("host");
        path = c.optString("path").replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
        user = c.optString("user");
        pass = c.optString("password");
        domain = c.optString("domain");
    }

    private CIFSContext ctx() throws Exception {
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.minVersion", "SMB202");
        p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        p.setProperty("jcifs.resolveOrder", "DNS");              // kein NetBIOS-Broadcast (im WLAN oft blockiert)
        p.setProperty("jcifs.smb.client.responseTimeout", "20000");
        p.setProperty("jcifs.smb.client.connTimeout", "15000");
        CIFSContext base = new BaseContext(new PropertyConfiguration(p));
        if (user.isEmpty()) return base.withGuestCredentials();
        return base.withCredentials(new NtlmPasswordAuthenticator(domain.isEmpty() ? null : domain, user, pass));
    }

    private static String enc(String s) {
        return s.replace("%", "%25").replace("#", "%23").replace("?", "%3F");
    }

    private String baseUrl() {
        return "smb://" + host + "/" + enc(path) + "/";
    }

    @Override
    List<Entry> scan() throws Exception {
        CIFSContext c = ctx();
        List<Entry> out = newList();
        try {
            Deque<SmbFile> dirs = new ArrayDeque<>();
            Deque<String> rels = new ArrayDeque<>();
            dirs.push(new SmbFile(baseUrl(), c));
            rels.push("");
            while (!dirs.isEmpty() && out.size() < MAX_ITEMS) {
                SmbFile d = dirs.pop();
                String rel = rels.pop();
                SmbFile[] fs = d.listFiles();
                if (fs == null) continue;
                for (SmbFile f : fs) {
                    String n = f.getName();
                    if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
                    if (n.isEmpty()) continue;
                    if (f.isDirectory()) {
                        dirs.push(f);
                        rels.push(rel + n + "/");
                    } else if (isMedia(n)) {
                        out.add(new Entry(rel + n, f.length()));
                    }
                }
            }
        } finally {
            try { c.close(); } catch (Exception ignored) { }
        }
        return sorted(out);
    }

    @Override
    Opened open(String rel, long start) throws Exception {
        final CIFSContext c = ctx();
        try {
            SmbFile f = new SmbFile(baseUrl() + enc(clean(rel)), c);
            long size = f.length();
            if (start >= size) {
                try { c.close(); } catch (Exception ignored) { }
                return new Opened(size, new java.io.ByteArrayInputStream(new byte[0]), null);
            }
            InputStream in = f.getInputStream();
            long skip = start;
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) { if (in.read() < 0) break; n = 1; }
                skip -= n;
            }
            return new Opened(size, in, () -> c.close());
        } catch (Exception e) {
            try { c.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    @Override
    String test() throws Exception {
        CIFSContext c = ctx();
        try {
            SmbFile d = new SmbFile(baseUrl(), c);
            if (!d.exists()) throw new IOException("Freigabe oder Ordner nicht gefunden: " + path);
            SmbFile[] fs = d.listFiles();
            return "SMB verbunden (" + (fs == null ? 0 : fs.length) + " Einträge im Startordner)";
        } finally {
            try { c.close(); } catch (Exception ignored) { }
        }
    }
}
