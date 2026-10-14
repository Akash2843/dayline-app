package com.akash.dayline;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;

final class Notifier {

    static final String CHANNEL = "reminders";
    static final String CHANNEL_SUMMARY = "summary";
    static final int SUMMARY_ID = 777;
    private static final int ACCENT = 0xFF2F5BD3;

    private Notifier() {
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Task reminders",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alerts when a planned task is about to start");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 300, 200, 300});
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_SUMMARY) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_SUMMARY, "Morning summary",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("One notification each morning with today's plan");
            nm.createNotificationChannel(ch);
        }
    }

    private static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent openApp(Context c) {
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** A task reminder with Done (when it belongs to a task) and Snooze buttons. */
    static void show(Context c, String title, String text, int id, String taskId, String date) {
        ensureChannels(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !allowed(c)) return;
        String body = text == null ? "" : text;
        String head = title == null || title.isEmpty() ? "Dayline" : title;
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_dayline)
                .setContentTitle(head)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setColor(ACCENT)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(openApp(c));
        Icon small = Icon.createWithResource(c, R.drawable.ic_stat_dayline);
        if (taskId != null && !taskId.isEmpty()) {
            Intent done = new Intent(c, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_DONE)
                    .putExtra("id", taskId).putExtra("date", date).putExtra("nid", id);
            PendingIntent dp = PendingIntent.getBroadcast(c, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(small, "Done", dp).build());
        }
        Intent snooze = new Intent(c, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_SNOOZE)
                .putExtra("id", taskId).putExtra("date", date).putExtra("nid", id)
                .putExtra("title", head).putExtra("text", body);
        PendingIntent sp = PendingIntent.getBroadcast(c, id, snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(small,
                "Snooze " + ReminderScheduler.SNOOZE_MINUTES + " min", sp).build());
        nm.notify(id, b.build());
    }

    static void showSummary(Context c, String title, String text) {
        ensureChannels(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !allowed(c)) return;
        Notification n = new Notification.Builder(c, CHANNEL_SUMMARY)
                .setSmallIcon(R.drawable.ic_stat_dayline)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(ACCENT)
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
                .build();
        nm.notify(SUMMARY_ID, n);
    }

    static void cancel(Context c, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }
}
