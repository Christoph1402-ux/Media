package de.homecloud.player;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.TrustManagerUtils;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** FTP und FTPS (explizit). */
final class FtpSource extends Source {
    private final String host, user, pass, base, secure;
    private final int port;

    FtpSource(JSONObject c) {
        host = c.optString("host");
        port = c.optInt("port", 21);
        user = c.optString("user", "anonymous");
        pass = c.optString("password", "");
        secure = c.optString("secure", "");
        String p = c.optString("path", "/").replace('\\', '/');
        if (!p.startsWith("/")) p = "/" + p;
        base = p.length() > 1 ? p.replaceAll("/+$", "") : "/";
    }

    private FTPClient connect() throws IOException {
        FTPClient c;
        if (secure.startsWith("tls")) {
            FTPSClient f = new FTPSClient("TLS", false);
            if (secure.equals("tls-insecure")) f.setTrustManager(TrustManagerUtils.getAcceptAllTrustManager());
            c = f;
        } else {
            c = new FTPClient();
        }
        c.setConnectTimeout(15000);
        c.setDefaultTimeout(60000);
        c.setControlEncoding("UTF-8");
        c.connect(host, port);
        if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
            String r = c.getReplyString();
            c.disconnect();
            throw new IOException("Der Server antwortet nicht wie ein FTP-Server: " + (r == null ? "" : r.trim()));
        }
        if (!c.login(user, pass)) {
            String r = c.getReplyString();
            try { c.disconnect(); } catch (Exception ignored) { }
            throw new IOException("Anmeldung abgelehnt (Benutzer/Passwort, oder der Server verlangt FTPS): " + (r == null ? "" : r.trim()));
        }
        if (c instanceof FTPSClient) {
            ((FTPSClient) c).execPBSZ(0);
            ((FTPSClient) c).execPROT("P");
        }
        c.enterLocalPassiveMode();
        c.setFileType(FTP.BINARY_FILE_TYPE);
        c.setBufferSize(1 << 17);
        return c;
    }

    private static void quit(FTPClient c) {
        try { c.logout(); } catch (Exception ignored) { }
        try { c.disconnect(); } catch (Exception ignored) { }
    }

    private String join(String dir, String name) { return (dir.endsWith("/") ? dir : dir + "/") + name; }

    @Override
    List<Entry> scan() throws Exception {
        FTPClient c = connect();
        List<Entry> out = newList();
        try {
            Deque<String> stack = new ArrayDeque<>();
            stack.push(base);
            while (!stack.isEmpty() && out.size() < MAX_ITEMS) {
                String d = stack.pop();
                if (!c.changeWorkingDirectory(d)) continue;
                FTPFile[] fs = c.listFiles();
                if (fs == null) continue;
                for (FTPFile f : fs) {
                    if (f == null || f.getName() == null) continue;
                    String n = f.getName();
                    n = n.substring(n.lastIndexOf('/') + 1);
                    if (n.isEmpty() || n.equals(".") || n.equals("..")) continue;
                    String full = join(d, n);
                    if (f.isDirectory()) stack.push(full);
                    else if (f.isFile() && isMedia(n)) out.add(new Entry(full.substring(base.length()).replaceFirst("^/+", ""), f.getSize()));
                }
            }
        } finally {
            quit(c);
        }
        return sorted(out);
    }

    @Override
    Opened open(String rel, long start) throws Exception {
        final String path = join(base, clean(rel));
        final FTPClient c = connect();
        try {
            long size = -1;
            c.sendCommand("SIZE", path);
            if (c.getReplyCode() == 213) size = Long.parseLong(c.getReplyString().substring(4).trim());
            else {
                FTPFile[] f = c.listFiles(path);
                if (f != null && f.length == 1) size = f[0].getSize();
            }
            if (size < 0) throw new IOException("Datei nicht gefunden: " + rel);
            if (start >= size) {
                quit(c);
                return new Opened(size, new ByteArrayInputStream(new byte[0]), null);
            }
            InputStream in = null;
            if (start > 0) {
                c.setRestartOffset(start);
                in = c.retrieveFileStream(path);
            }
            long skip = 0;
            if (in == null) {                              // REST nicht unterstuetzt: vom Anfang lesen und ueberspringen
                c.setRestartOffset(0);
                in = c.retrieveFileStream(path);
                skip = start;
            }
            if (in == null) throw new IOException("Datei nicht lesbar: " + c.getReplyString());
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) { if (in.read() < 0) break; n = 1; }
                skip -= n;
            }
            return new Opened(size, in, () -> quit(c));
        } catch (Exception e) {
            quit(c);
            throw e;
        }
    }

    @Override
    String test() throws Exception {
        FTPClient c = connect();
        try {
            if (!c.changeWorkingDirectory(base)) throw new IOException("Startordner nicht gefunden: " + base);
            FTPFile[] fs = c.listFiles();
            return "FTP verbunden (" + (fs == null ? 0 : fs.length) + " Einträge im Startordner)";
        } finally {
            quit(c);
        }
    }
}
