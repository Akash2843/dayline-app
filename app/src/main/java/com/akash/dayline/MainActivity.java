package com.akash.dayline;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final int REQ_EXPORT = 41;
    private static final int REQ_IMPORT = 42;
    private static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;

    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    private WebView web;
    private String pendingExport;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);

        web = new WebView(this);
        applyBars(isSystemDark());
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(this, web), "AndroidApp");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        Notifier.ensureChannels(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(NOTIFY_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{NOTIFY_PERMISSION}, 1);
        }
        ReminderScheduler.schedule(this);
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current.clear();
        super.onDestroy();
    }

    /** Called when a reminder's Done button is tapped, so an open screen updates. */
    static void refreshIfOpen() {
        MainActivity a = current.get();
        if (a != null) a.runOnUiThread(() -> a.js("window.__dayResume && window.__dayResume()"));
    }

    void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    boolean isSystemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Match the status and navigation bars to the app's light or dark look. */
    @SuppressWarnings("deprecation")
    void applyBars(boolean dark) {
        int bg = dark ? 0xFF0F1012 : 0xFFF7F7F8;
        int nav = dark ? 0xFF17181B : 0xFFFFFFFF;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(nav);
        if (web != null) web.setBackgroundColor(bg);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            View v = getWindow().getDecorView();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int f = v.getSystemUiVisibility();
            v.setSystemUiVisibility(dark ? (f & ~mask) : (f | mask));
        }
    }

    void keepScreenOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @SuppressWarnings("deprecation")
    void startExport(String json, String fileName) {
        pendingExport = json;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, fileName);
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            pendingExport = null;
            js("window.__backupDone && window.__backupDone(false)");
        }
    }

    @SuppressWarnings("deprecation")
    void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            js("window.__importDone && window.__importDone(null)");
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data == null ? null : data.getData();
        if (requestCode == REQ_EXPORT) {
            String json = pendingExport;
            pendingExport = null;
            if (resultCode != RESULT_OK || uri == null || json == null) return;
            boolean ok = false;
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os != null) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                }
            } catch (Exception e) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
            }
            js("window.__backupDone && window.__backupDone(" + ok + ")");
        } else if (requestCode == REQ_IMPORT) {
            if (resultCode != RESULT_OK || uri == null) return;
            String text = null;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is != null) text = Bridge.readAll(is, MAX_IMPORT_BYTES);
            } catch (Exception ignored) {
            }
            js("window.__importDone && window.__importDone(" + (text == null ? "null" : JSONObject.quote(text)) + ")");
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        js("window.__dayTheme && window.__dayTheme()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.__dayResume && window.__dayResume()");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        js("window.__dayResume && window.__dayResume()");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript("(window.__dayBack && window.__dayBack()) ? 1 : 0", value -> {
            if (!"1".equals(value)) finish();
        });
    }
}
