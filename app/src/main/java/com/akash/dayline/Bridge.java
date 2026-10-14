package com.akash.dayline;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Methods the planner page can call as window.AndroidApp.* */
public class Bridge {

    private static final String AI_PREFS = "dayline_ai";
    private static final String DEFAULT_MODEL = "claude-haiku-5-5";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final MainActivity activity;
    private final WebView web;

    Bridge(MainActivity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    private Context app() {
        return activity.getApplicationContext();
    }

    /* ---------- reminders ---------- */

    /** The page sends its full task list after every change. */
    @JavascriptInterface
    public void syncTasks(String json) {
        ReminderScheduler.saveAndSchedule(app(), json);
    }

    @JavascriptInterface
    public void testReminder() {
        ReminderScheduler.scheduleTest(app());
    }

    @JavascriptInterface
    public void setSummary(boolean on, String time) {
        ReminderScheduler.setSummary(app(), on, time);
    }

    /** Done taps from notifications made while the app was closed. */
    @JavascriptInterface
    public String takePendingActions() {
        return ReminderScheduler.takePending(app());
    }

    @JavascriptInterface
    public boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    @JavascriptInterface
    public void openNotificationSettings() {
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
                activity.startActivity(i);
            } catch (Exception e) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            }
        });
    }

    /* ---------- look and feel ---------- */

    @JavascriptInterface
    public boolean isSystemDark() {
        return activity.isSystemDark();
    }

    @JavascriptInterface
    public void setBars(boolean dark) {
        activity.runOnUiThread(() -> activity.applyBars(dark));
    }

    @JavascriptInterface
    public void keepScreenOn(boolean on) {
        activity.runOnUiThread(() -> activity.keepScreenOn(on));
    }

    @JavascriptInterface
    public String getVersion() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------- backup ---------- */

    @JavascriptInterface
    public void exportBackup(String json, String fileName) {
        activity.runOnUiThread(() -> activity.startExport(json, fileName));
    }

    @JavascriptInterface
    public void importBackup() {
        activity.runOnUiThread(activity::startImport);
    }

    /* ---------- Claude ---------- */

    private SharedPreferences aiPrefs() {
        return app().getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public boolean hasApiKey() {
        return !aiPrefs().getString("key", "").isEmpty();
    }

    /** The key is kept on the phone only and never handed back to the page. */
    @JavascriptInterface
    public void saveApiKey(String key) {
        aiPrefs().edit().putString("key", key == null ? "" : key.trim()).apply();
    }

    @JavascriptInterface
    public void clearApiKey() {
        aiPrefs().edit().remove("key").apply();
    }

    @JavascriptInterface
    public String getModel() {
        return aiPrefs().getString("model", DEFAULT_MODEL);
    }

    @JavascriptInterface
    public void setModel(String model) {
        if (model != null && model.startsWith("claude-")) {
            aiPrefs().edit().putString("model", model).apply();
        }
    }

    /**
     * Sends a Messages API request in the background and hands the result to
     * window.__claudeDone(callbackId, httpStatus, responseBody). Status 0 = no connection.
     */
    @JavascriptInterface
    public void askClaude(final String body, final String callbackId) {
        final String key = aiPrefs().getString("key", "");
        new Thread(() -> {
            int status = 0;
            String resp = "";
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(API_URL).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(100000);
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                c.setRequestProperty("x-api-key", key);
                c.setRequestProperty("anthropic-version", "2023-06-01");
                byte[] out = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(out.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(out);
                }
                status = c.getResponseCode();
                InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                resp = is == null ? "" : readAll(is, 4 * 1024 * 1024);
            } catch (Exception e) {
                status = 0;
                resp = "";
            } finally {
                if (c != null) c.disconnect();
            }
            final String js = "window.__claudeDone && window.__claudeDone("
                    + JSONObject.quote(callbackId) + "," + status + "," + JSONObject.quote(resp) + ")";
            activity.runOnUiThread(() -> web.evaluateJavascript(js, null));
        }).start();
    }

    static String readAll(InputStream is, int maxBytes) throws IOException {
        try (InputStream in = is; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(b)) != -1) {
                total += n;
                if (total > maxBytes) throw new IOException("File too large");
                buf.write(b, 0, n);
            }
            return buf.toString("UTF-8");
        }
    }
}
