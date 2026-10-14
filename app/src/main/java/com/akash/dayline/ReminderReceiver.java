package com.akash.dayline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        int nid = intent.getIntExtra("nid", 1);
        switch (action) {
            case ReminderScheduler.ACTION_REMIND:
            case ReminderScheduler.ACTION_TEST:
                Notifier.show(context, intent.getStringExtra("title"), intent.getStringExtra("text"),
                        nid, intent.getStringExtra("id"), intent.getStringExtra("date"));
                break;
            case ReminderScheduler.ACTION_DONE:
                ReminderScheduler.markDone(context, intent.getStringExtra("id"), intent.getStringExtra("date"));
                Notifier.cancel(context, nid);
                MainActivity.refreshIfOpen();
                break;
            case ReminderScheduler.ACTION_SNOOZE:
                Notifier.cancel(context, nid);
                ReminderScheduler.snooze(context, intent);
                return;
            case ReminderScheduler.ACTION_SUMMARY: {
                String[] s = ReminderScheduler.summary(context);
                Notifier.showSummary(context, s[0], s[1]);
                break;
            }
            default:
                break;
        }
        if (!ReminderScheduler.ACTION_TEST.equals(action)) {
            ReminderScheduler.schedule(context);
        }
    }
}
