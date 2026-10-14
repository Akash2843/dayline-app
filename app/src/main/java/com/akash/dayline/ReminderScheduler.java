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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turns the saved task list into exact alarms for the next few days, plus the
 * optional morning summary. Runs after every change in the app, after each
 * reminder fires, shortly after midnight, and after the phone restarts.
 */
public final class ReminderScheduler {

    static final String PREFS = "dayline";
    static final String KEY_TASKS = "tasks";
    static final String KEY_COUNT = "alarm_count";
    static final String KEY_PENDING = "pending_actions";
    static final String KEY_SUMMARY_ON = "summary_on";
    static final String KEY_SUMMARY_TIME = "summary_time";

    static final String ACTION_REMIND = "com.akash.dayline.REMIND";
    static final String ACTION_REFRESH = "com.akash.dayline.REFRESH";
    static final String ACTION_TEST = "com.akash.dayline.TEST";
    static final String ACTION_DONE = "com.akash.dayline.DONE";
    static final String ACTION_SNOOZE = "com.akash.dayline.SNOOZE";
    static final String ACTION_SUMMARY = "com.akash.dayline.SUMMARY";

    private static final int MAX_ALARMS = 60;
    private static final int LOOKAHEAD_DAYS = 3;
    private static final int CODE_REFRESH = 90001;
    private static final int CODE_TEST = 90002;
    private static final int CODE_SUMMARY = 90003;
    private static final int CODE_SNOOZE_BASE = 100000;
    static final int SNOOZE_MINUTES = 10;

    private ReminderScheduler() {
    }

    static final class Item {
        long at;
        String title;
        String text;
        int notifId;
        String taskId;
        String date;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveAndSchedule(Context c, String json) {
        prefs(c).edit().putString(KEY_TASKS, json == null ? "[]" : json).apply();
        schedule(c);
    }

    public static void setSummary(Context c, boolean on, String time) {
        prefs(c).edit().putBoolean(KEY_SUMMARY_ON, on)
                .putString(KEY_SUMMARY_TIME, time == null ? "07:30" : time).apply();
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
                    .putExtra("nid", it.notifId)
                    .putExtra("id", it.taskId)
                    .putExtra("date", it.date);
            PendingIntent pi = PendingIntent.getBroadcast(c, i, in,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            setExact(am, it.at, pi);
        }
        p.edit().putInt(KEY_COUNT, n).apply();

        // Morning summary
        Intent s = new Intent(c, ReminderReceiver.class).setAction(ACTION_SUMMARY);
        PendingIntent sp = PendingIntent.getBroadcast(c, CODE_SUMMARY, s,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (p.getBoolean(KEY_SUMMARY_ON, false)) {
            LocalTime t;
            try {
                t = LocalTime.parse(p.getString(KEY_SUMMARY_TIME, "07:30"));
            } catch (Exception e) {
                t = LocalTime.of(7, 30);
            }
            LocalDateTime next = LocalDate.now().atTime(t);
            if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1);
            setExact(am, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), sp);
        } else {
            am.cancel(sp);
        }

        // Refresh shortly after midnight so repeating tasks keep getting scheduled.
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
                .putExtra("text", "Tasks with a reminder will alert you like this.")
                .putExtra("nid", 4242);
        PendingIntent pi = PendingIntent.getBroadcast(c, CODE_TEST, in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        setExact(am, System.currentTimeMillis() + 5000, pi);
    }

    /** Show the same reminder again in 10 minutes. */
    public static void snooze(Context c, Intent original) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        int nid = original.getIntExtra("nid", 1);
        String text = original.getStringExtra("text");
        if (text != null && !text.startsWith("Snoozed")) text = "Snoozed · " + text;
        Intent in = remindIntent(c)
                .putExtra("title", original.getStringExtra("title"))
                .putExtra("text", text)
                .putExtra("nid", nid)
                .putExtra("id", original.getStringExtra("id"))
                .putExtra("date", original.getStringExtra("date"));
        PendingIntent pi = PendingIntent.getBroadcast(c, CODE_SNOOZE_BASE + (nid & 0xFFFF), in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        setExact(am, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, pi);
    }

    /** Marks a task done in the saved copy, so no more reminders ring for it. */
    public static synchronized void markDone(Context c, String id, String date) {
        if (id == null || id.isEmpty()) return;
        SharedPreferences p = prefs(c);
        try {
            JSONArray arr = new JSONArray(p.getString(KEY_TASKS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null || !id.equals(str(t, "id"))) continue;
                if (repeatOf(t).equals("none")) {
                    t.put("done", true);
                } else {
                    JSONObject doneOn = t.optJSONObject("doneOn");
                    if (doneOn == null) {
                        doneOn = new JSONObject();
                        t.put("doneOn", doneOn);
                    }
                    doneOn.put(date == null || date.isEmpty() ? LocalDate.now().toString() : date, true);
                }
            }
            p.edit().putString(KEY_TASKS, arr.toString()).apply();

            JSONArray pending = new JSONArray(p.getString(KEY_PENDING, "[]"));
            JSONObject a = new JSONObject();
            a.put("type", "done");
            a.put("id", id);
            a.put("date", date == null ? "" : date);
            pending.put(a);
            p.edit().putString(KEY_PENDING, pending.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Actions taken from notifications while the app was closed; cleared once read. */
    public static synchronized String takePending(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString(KEY_PENDING, "[]");
        p.edit().remove(KEY_PENDING).apply();
        return s;
    }

    /** Title and text for the morning summary notification. */
    public static String[] summary(Context c) {
        LocalDate day = LocalDate.now();
        String ds = day.toString();
        String title = "Today · " + day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH));
        int count = 0;
        int firstMin = Integer.MAX_VALUE;
        String firstTitle = null;
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_TASKS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                String date = str(t, "date"), start = str(t, "start");
                if (date.isEmpty() || start.length() < 4) continue;
                if (!occursOn(t, date, ds, day) || isDone(t, ds)) continue;
                count++;
                int m = minutes(start);
                if (m < firstMin) {
                    firstMin = m;
                    firstTitle = str(t, "title");
                }
            }
        } catch (Exception ignored) {
        }
        String text;
        if (count == 0) {
            text = "Nothing planned yet. Open Dayline to plan your day.";
        } else {
            text = count + (count == 1 ? " task" : " tasks") + " today. First: "
                    + (firstTitle == null || firstTitle.isEmpty() ? "Task" : firstTitle)
                    + " at " + fmt(firstMin) + ".";
        }
        return new String[]{title, text};
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
            int startMin = minutes(start);
            if (startMin < 0) continue;
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
                it.taskId = str(t, "id");
                it.date = ds;
                it.notifId = (it.taskId + "|" + ds).hashCode();
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

    static boolean occursOn(JSONObject t, String date, String ds, LocalDate day) {
        String rep = repeatOf(t);
        if (rep.equals("none")) return date.equals(ds);
        if (ds.compareTo(date) < 0) return false;
        JSONObject skip = t.optJSONObject("skipOn");
        if (skip != null && skip.optBoolean(ds, false)) return false;
        DayOfWeek dow = day.getDayOfWeek();
        int jsDay = dow.getValue() % 7; // 0 = Sunday, like the app
        switch (rep) {
            case "daily":
                return true;
            case "days": {
                JSONArray days = t.optJSONArray("days");
                if (days == null) return false;
                for (int i = 0; i < days.length(); i++) {
                    if (days.optInt(i, -1) == jsDay) return true;
                }
                return false;
            }
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

    static boolean isDone(JSONObject t, String ds) {
        if (repeatOf(t).equals("none")) return t.optBoolean("done", false);
        JSONObject doneOn = t.optJSONObject("doneOn");
        return doneOn != null && doneOn.optBoolean(ds, false);
    }

    private static int minutes(String hm) {
        try {
            String[] p = hm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    static String str(JSONObject t, String key) {
        Object v = t.opt(key);
        return (v == null || v == JSONObject.NULL) ? "" : String.valueOf(v);
    }

    static String fmt(int min) {
        int h = (min / 60) % 24;
        int m = min % 60;
        int hh = h % 12 == 0 ? 12 : h % 12;
        return hh + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " AM" : " PM");
    }
}
