package com.akash.dayline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ReminderScheduler.ACTION_REMIND.equals(action) || ReminderScheduler.ACTION_TEST.equals(action)) {
            Notifier.show(context,
                    intent.getStringExtra("title"),
                    intent.getStringExtra("text"),
                    intent.getIntExtra("nid", 1));
        }
        if (!ReminderScheduler.ACTION_TEST.equals(action)) {
            ReminderScheduler.schedule(context);
        }
    }
}
