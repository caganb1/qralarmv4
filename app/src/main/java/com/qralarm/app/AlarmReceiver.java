package com.qralarm.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlarmReceiver extends BroadcastReceiver {

    public static final String EXTRA_ALARM_ID = "extra_alarm_id";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            AlarmScheduler.rescheduleAfterBoot(ctx);
            return;
        }

        int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1);
        if (alarmId == -1) return;

        // Single entry point: AlarmService acquires WakeLock and launches AlarmRingActivity.
        Intent svc = new Intent(ctx, AlarmService.class);
        svc.putExtra(AlarmService.EXTRA_ALARM_ID, alarmId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }
}
