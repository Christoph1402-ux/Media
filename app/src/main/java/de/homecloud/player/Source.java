package de.homecloud.player;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Eine Medienquelle (FTP, SMB, WebDAV). */
abstract class Source {
    static final int MAX_ITEMS = 60000;
    static final Set<String> MEDIA = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "webm", "mov", "m4v", "avi", "wmv", "mpg", "mpeg", "flv", "3gp",
            "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "oga"));

    static final class Entry {
        final String p; final long s;
        Entry(String p, long s) { this.p = p; this.s = s; }
    }

    /** Geoeffnete Datei: Groesse und Datenstrom ab der gewuenschten Stelle. */
    static final class Opened implements Closeable {
        final long size; final InputStream in; private final Closeable extra;
        Opened(long size, InputStream in, Closeable extra) { this.size = size; this.in = in; this.extra = extra; }
        @Override public void close() {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (extra != null) extra.close(); } catch (Exception ignored) { }
        }
    }

    static boolean isMedia(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 && MEDIA.contains(name.substring(i + 1).toLowerCase(Locale.ROOT));
    }

    static List<Entry> sorted(List<Entry> l) {
        l.sort((a, b) -> a.p.toLowerCase(Locale.ROOT).compareTo(b.p.toLowerCase(Locale.ROOT)));
        return l;
    }

    static String clean(String rel) throws IOException {
        for (String part : rel.replace('\\', '/').split("/")) if (part.equals("..")) throw new IOException("ungültiger Pfad");
        return rel.replace('\\', '/').replaceAll("^/+", "");
    }

    abstract List<Entry> scan() throws Exception;
    abstract Opened open(String rel, long start) throws Exception;
    abstract String test() throws Exception;

    long size(String rel) throws Exception {
        try (Opened o = open(rel, Long.MAX_VALUE)) { return o.size; }
    }

    static List<Entry> newList() { return new ArrayList<>(); }
}
