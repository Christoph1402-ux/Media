package de.homecloud.player;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.List;

/** Ein Ordner auf dem Handy/Tablet (per Android-Ordnerauswahl freigegeben, auch SD-Karte/USB). */
final class SafSource extends Source {
    private final ContentResolver cr;
    private final Uri tree;

    SafSource(Context ctx, JSONObject c) {
        cr = ctx.getContentResolver();
        tree = Uri.parse(c.optString("uri"));
    }

    private static final String[] COLS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE};

    private static boolean isDir(String mime) { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); }

    @Override
    List<Entry> scan() throws Exception {
        List<Entry> out = newList();
        ArrayDeque<String[]> todo = new ArrayDeque<>();       // {Dokument-ID, relativer Pfad}
        todo.add(new String[]{DocumentsContract.getTreeDocumentId(tree), ""});
        while (!todo.isEmpty() && out.size() < MAX_ITEMS) {
            String[] cur = todo.poll();
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, cur[0]);
            try (Cursor q = cr.query(kids, COLS, null, null, null)) {
                if (q == null) throw new java.io.IOException("Ordner nicht lesbar – bitte neu auswählen");
                while (q.moveToNext()) {
                    String id = q.getString(0), name = q.getString(1), mime = q.getString(2);
                    if (name == null) continue;
                    String rel = cur[1].isEmpty() ? name : cur[1] + "/" + name;
                    if (isDir(mime)) { if (!name.startsWith(".")) todo.add(new String[]{id, rel}); }
                    else if (isMedia(name)) out.add(new Entry(rel, q.isNull(3) ? 0 : q.getLong(3)));
                }
            }
        }
        return sorted(out);
    }

    /** Dokument-ID zu einem relativen Pfad finden (Namen Ebene fuer Ebene). */
    private String resolve(String rel) throws Exception {
        String id = DocumentsContract.getTreeDocumentId(tree);
        for (String part : clean(rel).split("/")) {
            if (part.isEmpty()) continue;
            String found = null;
            try (Cursor q = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), COLS, null, null, null)) {
                while (q != null && q.moveToNext()) if (part.equals(q.getString(1))) { found = q.getString(0); break; }
            }
            if (found == null) throw new java.io.FileNotFoundException(part);
            id = found;
        }
        return id;
    }

    @Override
    Opened open(String rel, long start) throws Exception {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, resolve(rel));
        long size = 0;
        try (Cursor q = cr.query(doc, COLS, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(3)) size = q.getLong(3);
        }
        ParcelFileDescriptor pfd = cr.openFileDescriptor(doc, "r");
        if (pfd == null) throw new java.io.IOException("Datei nicht lesbar");
        if (size <= 0) size = pfd.getStatSize();
        FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
        if (start > 0 && start < size) {
            try { in.getChannel().position(start); }
            catch (Exception e) { long n = start; while (n > 0) { long k = in.skip(n); if (k <= 0) break; n -= k; } }
        }
        return new Opened(size, in, pfd);
    }

    @Override
    String test() throws Exception {
        int n = scan().size();
        return n + " Titel gefunden";
    }
}
