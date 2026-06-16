package com.qralarm.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/**
 * Schedules / cancels alarms via AlarmManager.setExactAndAllowWhileIdle()
 * which is guaranteed to fire even in Doze mode.
 */
public class AlarmScheduler {

    private static final String TAG = "AlarmScheduler";

    // ── Public API ────────────────────────────────────────────────────────────

    public static void schedule(Context ctx, Alarm alarm) {
        if (!alarm.isEnabled) return;
        AlarmManager am = am(ctx);
        if (am == null) return;

        if (alarm.repeats()) {
            boolean[] days = alarm.getRepeatDaysArray();
            for (int i = 0; i < 7; i++) {
                if (days[i]) scheduleDay(ctx, am, alarm, i);
            }
        } else {
            scheduleOnce(ctx, am, alarm);
        }
    }

    public static void cancel(Context ctx, Alarm alarm) {
        AlarmManager am = am(ctx);
        if (am == null) return;
        if (alarm.repeats()) {
            for (int i = 0; i < 7; i++) cancel(ctx, am, alarm.id * 10 + i, alarm.id);
        } else {
            cancel(ctx, am, alarm.id, alarm.id);
        }
    }

    public static void rescheduleAfterBoot(Context ctx) {
        new AlarmRepository(ctx).getEnabledAlarms(alarms -> {
            for (Alarm a : alarms) schedule(ctx, a);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void scheduleOnce(Context ctx, AlarmManager am, Alarm alarm) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, alarm.hour);
        c.set(Calendar.MINUTE,      alarm.minute);
        c.set(Calendar.SECOND,      0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        setExact(am, c.getTimeInMillis(), pi(ctx, alarm.id, alarm.id));
        Log.d(TAG, "Scheduled once: alarm=" + alarm.id + " at " + c.getTime());
    }

    private static void scheduleDay(Context ctx, AlarmManager am, Alarm alarm, int dayIdx) {
        // dayIdx: 0=Mon … 6=Sun  |  Calendar: 2=Mon … 1=Sun
        int calDay = (dayIdx == 6) ? Calendar.SUNDAY : (Calendar.MONDAY + dayIdx);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_WEEK,  calDay);
        c.set(Calendar.HOUR_OF_DAY,  alarm.hour);
        c.set(Calendar.MINUTE,       alarm.minute);
        c.set(Calendar.SECOND,       0);
        c.set(Calendar.MILLISECOND,  0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.WEEK_OF_YEAR, 1);
        int rc = alarm.id * 10 + dayIdx;
        setExact(am, c.getTimeInMillis(), pi(ctx, rc, alarm.id));
        Log.d(TAG, "Scheduled repeat: alarm=" + alarm.id + " day=" + dayIdx + " at " + c.getTime());
    }

    /** setExactAndAllowWhileIdle → fires even in Doze; no batching, no deferral. */
    private static void setExact(AlarmManager am, long triggerMs, PendingIntent pi) {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi);
    }

    private static void cancel(Context ctx, AlarmManager am, int requestCode, int alarmId) {
        am.cancel(pi(ctx, requestCode, alarmId));
    }

    private static PendingIntent pi(Context ctx, int requestCode, int alarmId) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags);
    }

    private static AlarmManager am(Context ctx) {
        return (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    }
}
