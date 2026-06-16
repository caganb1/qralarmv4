package com.qralarm.app;

import android.content.Context;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlarmRepository {

    private final AlarmDao          dao;
    private final LiveData<List<Alarm>> allAlarms;
    private final ExecutorService   executor = Executors.newSingleThreadExecutor();

    public AlarmRepository(Context context) {
        AlarmDatabase db = AlarmDatabase.getInstance(context);
        dao       = db.alarmDao();
        allAlarms = dao.getAllAlarms();
    }

    public LiveData<List<Alarm>> getAllAlarms() { return allAlarms; }

    public void insert(Alarm alarm, OnInsertListener l) {
        executor.execute(() -> { long id = dao.insert(alarm); if (l != null) l.onInserted((int) id); });
    }

    public void update(Alarm alarm) {
        executor.execute(() -> dao.update(alarm));
    }

    public void delete(Alarm alarm) {
        executor.execute(() -> dao.delete(alarm));
    }

    public void setEnabled(int id, boolean enabled) {
        executor.execute(() -> dao.setEnabled(id, enabled));
    }

    public void getAlarmById(int id, OnAlarmLoadedListener l) {
        executor.execute(() -> { Alarm a = dao.getAlarmById(id); if (l != null) l.onAlarmLoaded(a); });
    }

    public void getEnabledAlarms(OnAlarmsLoadedListener l) {
        executor.execute(() -> { List<Alarm> list = dao.getEnabledAlarms(); if (l != null) l.onAlarmsLoaded(list); });
    }

    public interface OnInsertListener      { void onInserted(int id); }
    public interface OnAlarmLoadedListener { void onAlarmLoaded(Alarm alarm); }
    public interface OnAlarmsLoadedListener{ void onAlarmsLoaded(List<Alarm> alarms); }
}
