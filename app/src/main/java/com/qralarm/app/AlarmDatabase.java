package com.qralarm.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Alarm.class}, version = 2, exportSchema = false)
public abstract class AlarmDatabase extends RoomDatabase {

    private static volatile AlarmDatabase INSTANCE;

    public abstract AlarmDao alarmDao();

    // ── Migration 1 → 2 ──────────────────────────────────────────────────────
    // Adds uri_calm, uri_medium, uri_loud; drops old sound_uri, vibrate, snooze_minutes.
    // SQLite does not support DROP COLUMN before API 35, so we use the
    // table-rebuild pattern: create new table → copy → drop old → rename.
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // 1. Create new table with final schema
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `alarms_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "`hour` INTEGER NOT NULL," +
                "`minute` INTEGER NOT NULL," +
                "`label` TEXT," +
                "`is_enabled` INTEGER NOT NULL," +
                "`repeat_days` TEXT," +
                "`qr_code_value` TEXT," +
                "`qr_code_label` TEXT," +
                "`uri_calm` TEXT," +
                "`uri_medium` TEXT," +
                "`uri_loud` TEXT)"
            );
            // 2. Copy surviving columns (old sound_uri mapped to uri_calm as best-effort)
            db.execSQL(
                "INSERT INTO alarms_new " +
                "(id, hour, minute, label, is_enabled, repeat_days, " +
                " qr_code_value, qr_code_label, uri_calm, uri_medium, uri_loud) " +
                "SELECT id, hour, minute, label, is_enabled, repeat_days, " +
                "       qr_code_value, qr_code_label, sound_uri, NULL, NULL " +
                "FROM alarms"
            );
            // 3. Swap
            db.execSQL("DROP TABLE alarms");
            db.execSQL("ALTER TABLE alarms_new RENAME TO alarms");
        }
    };

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static AlarmDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AlarmDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AlarmDatabase.class,
                            "alarm_database"
                    )
                    .addMigrations(MIGRATION_1_2)
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
