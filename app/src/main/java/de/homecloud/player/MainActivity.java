package de.homecloud.player;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.WebViewAssetLoader;

/** Huelle um die HomeCloud-Web-App: eigene https-Adresse fuer die App-Dateien, eingebauter Netzwerk-Server (FTP/SMB/WebDAV). */
public class MainActivity extends Activity {
    private static final int REQ_FILES = 11, REQ_TREE = 12;
    private static final String HOST = "appassets.androidplatform.net";

    private WebView web;
    private GatewayServer server;
    private ValueCallback<Uri[]> filePathCb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = GatewayServer.start(this);

        web = new WebView(this);
        web.setBackgroundColor(0xFF070B1F);
        setContentView(web);
        // Systemleisten (Statusleiste, Navigation, Tastatur) aussparen
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);   // https-App darf den lokalen Server (127.0.0.1) ansprechen

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost()) || "127.0.0.1".equals(u.getHost())) return false;
                try {                                   // Streaming-Dienste usw. im Browser oeffnen
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Musik / Videos wählen"), REQ_FILES);
                } catch (Exception e) {
                    filePathCb = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "HCNative");
        web.loadUrl("https://" + HOST + "/assets/index.html");

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
    }

    private void goBack() {
        web.evaluateJavascript("window.__hcBack?window.__hcBack():false", r -> {
            if (!"true".equals(r)) moveTaskToBack(true);
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) goBack(); else super.onBackPressed();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILES && filePathCb != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                ClipData cd = data.getClipData();
                if (cd != null) {
                    out = new Uri[cd.getItemCount()];
                    for (int i = 0; i < out.length; i++) out[i] = cd.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            filePathCb.onReceiveValue(out);
            filePathCb = null;
            return;
        }
        if (req == REQ_TREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
                String name = "Ordner";
                try (android.database.Cursor q = getContentResolver().query(
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree)),
                        new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                    if (q != null && q.moveToFirst() && q.getString(0) != null) name = q.getString(0);
                } catch (Exception ignored) { }
                web.evaluateJavascript("window.__hcFolder&&window.__hcFolder(" + org.json.JSONObject.quote(tree.toString()) + "," + org.json.JSONObject.quote(name) + ")", null);
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, KeepAliveService.class));
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Wird von der Web-App als window.HCNative benutzt. */
    private class Bridge {
        @JavascriptInterface public String base() { return server.base(); }
        @JavascriptInterface public String token() { return server.token(); }
        /** Ordner auf dem Geraet waehlen (Android-Ordnerauswahl). */
        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(i, REQ_TREE); } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public String version() { return "1.1"; }

        /** Waehrend der Wiedergabe laeuft ein Vordergrunddienst, damit Android die App bei gesperrtem Bildschirm nicht beendet. */
        @JavascriptInterface
        public void playing(final boolean on) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MainActivity.this, KeepAliveService.class);
                    if (on) startForegroundService(i); else stopService(i);
                } catch (Exception ignored) { }
            });
        }
    }
}
