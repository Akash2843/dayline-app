package com.akash.dayline;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

/** Methods the planner page can call as window.AndroidApp.* */
public class Bridge {

    private final Activity activity;

    Bridge(Activity activity) {
        this.activity = activity;
    }

    /** The page sends its full task list after every change. */
    @JavascriptInterface
    public void syncTasks(String json) {
        ReminderScheduler.saveAndSchedule(activity.getApplicationContext(), json);
    }

    @JavascriptInterface
    public void testReminder() {
        ReminderScheduler.scheduleTest(activity.getApplicationContext());
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
}
