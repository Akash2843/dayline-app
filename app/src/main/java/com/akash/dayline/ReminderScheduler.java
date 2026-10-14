package com.akash.dayline;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the saved task list into exact alarms for the next few days.
 * Runs after every change in the app, after each reminder fires, shortly
 * after midnight, and after the phone restarts.
 */
public final class ReminderScheduler {

    static final String PREFS = "dayline";
    static final String KEY_TASKS = "tasks";
    static final String KEY_COUNT = "alarm_count";
    static final String ACTION_REMIND = "com.akash.dayline.REMIND";
    static final String ACTION_REFRESH = "com.akash.dayline.REFRESH";
    static final String ACTION_TEST = "com.akash.dayline.TEST";

    private static final int MAX_ALARMS = 60;
    private static final int LOOKAHEAD_DAYS = 3;
    private static final int CODE_REFRESH = 90001;
    private static final int CODE_TEST = 90002;

    private ReminderScheduler() {
    }

    static final class Item {
        long at;
        String title;
        String text;
        int notifId;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveAndSchedule(Context c, String json) {
        prefs(c).edit().putString(KEY_TASKS, json == null ? "[]" : json).apply();
        schedule(c);
    }

    public static synchronized void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        SharedPreferences p = prefs(c);

        int old = p.getInt(KEY_COUNT, 0);
        for (int i = 0; i < old; i++) {
            PendingIntent pi = PendingIntent.getBroadcast(c, i, remindIntent(c),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }

        List<Item> items = upcoming(p.getString(KEY_TASKS, "[]"));
        int n = Math.min(items.size(), MAX_ALARMS);
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            Intent in = remindIntent(c)
                    .putExtra("title", it.title)
                    .putExtra("text", it.text)
                    .putExtra("nid", it.notifId);
            PendingIntent pi = PendingIntent.getBroadcast(c, i, in,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            setExact(am, it.at, pi);
        }
        p.edit().putInt(KEY_COUNT, n).apply();

        long refreshAt = LocalDate.now().plusDays(1).atTime(0, 5)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Intent r = new Intent(c, ReminderReceiver.class).setAction(ACTION_REFRESH);
        PendingIntent rp = PendingIntent.getBroadcast(c, CODE_REFRESH, r,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, refreshAt, rp);
    }

    public static void scheduleTest(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent in = new Intent(c, ReminderReceiver.class).setAction(ACTION_TEST)
                .putExtra("title", "Dayline reminders work")
                .putExtra("text", "Tasks with a bell will alert you like this.")
                .putExtra("nid", 4242);
        PendingIntent pi = PendingIntent.getBroadcast(c, CODE_TEST, in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        setExact(am, System.currentTimeMillis() + 5000, pi);
    }

    private static Intent remindIntent(Context c) {
        return new Intent(c, ReminderReceiver.class).setAction(ACTION_REMIND);
    }

    private static void setExact(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static List<Item> upcoming(String json) {
        List<Item> out = new ArrayList<>();
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            return out;
        }
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            int remind = t.optInt("remind", -1);
            String date = str(t, "date");
            String start = str(t, "start");
            if (remind < 0 || date.isEmpty() || start.length() < 4) continue;

            int startMin;
            try {
                String[] hm = start.split(":");
                startMin = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
            } catch (Exception e) {
                continue;
            }
            int dur = Math.max(5, t.optInt("duration", 30));
            String title = str(t, "title");
            if (title.isEmpty()) title = "Task";

            for (int d = 0; d < LOOKAHEAD_DAYS; d++) {
                LocalDate day = today.plusDays(d);
                String ds = day.toString();
                if (!occursOn(t, date, ds, day) || isDone(t, ds)) continue;
                LocalDateTime startAt = day.atStartOfDay().plusMinutes(startMin);
                long at = startAt.minusMinutes(remind).atZone(zone).toInstant().toEpochMilli();
                if (at <= now) continue;

                Item it = new Item();
                it.at = at;
                it.title = title;
                String range = fmt(startMin) + " – " + fmt(startMin + dur);
                it.text = remind == 0 ? "Starting now · " + range
                        : "Starts in " + remind + " min · " + range;
                it.notifId = (str(t, "id") + "|" + ds).hashCode();
                out.add(it);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    private static String repeatOf(JSONObject t) {
        String rep = str(t, "repeat");
        return rep.isEmpty() ? "none" : rep;
    }

    private static boolean occursOn(JSONObject t, String date, String ds, LocalDate day) {
        String rep = repeatOf(t);
        if (rep.equals("none")) return date.equals(ds);
        if (ds.compareTo(date) < 0) return false;
        JSONObject skip = t.optJSONObject("skipOn");
        if (skip != null && skip.optBoolean(ds, false)) return false;
        DayOfWeek dow = day.getDayOfWeek();
        switch (rep) {
            case "daily":
                return true;
            case "weekdays":
                return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
            case "weekly":
                try {
                    return LocalDate.parse(date).getDayOfWeek() == dow;
                } catch (Exception e) {
                    return false;
                }
            default:
                return false;
        }
    }

    private static boolean isDone(JSONObject t, String ds) {
        if (repeatOf(t).equals("none")) return t.optBoolean("done", false);
        JSONObject doneOn = t.optJSONObject("doneOn");
        return doneOn != null && doneOn.optBoolean(ds, false);
    }

    private static String str(JSONObject t, String key) {
        Object v = t.opt(key);
        return (v == null || v == JSONObject.NULL) ? "" : String.valueOf(v);
    }

    private static String fmt(int min) {
        int h = (min / 60) % 24;
        int m = min % 60;
        int hh = h % 12 == 0 ? 12 : h % 12;
        return hh + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " AM" : " PM");
    }
}
