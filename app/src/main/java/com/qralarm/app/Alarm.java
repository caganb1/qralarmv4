package com.qralarm.app;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "alarms")
public class Alarm {

    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "hour")           public int     hour;
    @ColumnInfo(name = "minute")         public int     minute;
    @ColumnInfo(name = "label")          public String  label;
    @ColumnInfo(name = "is_enabled")     public boolean isEnabled;
    @ColumnInfo(name = "repeat_days")    public String  repeatDays;   // "0,1,1,0,0,0,0"
    @ColumnInfo(name = "qr_code_value")  public String  qrCodeValue;
    @ColumnInfo(name = "qr_code_label")  public String  qrCodeLabel;

    // Per-stage audio URIs — null means use procedural synth fallback
    @ColumnInfo(name = "uri_calm")       public String  uriCalm;
    @ColumnInfo(name = "uri_medium")     public String  uriMedium;
    @ColumnInfo(name = "uri_loud")       public String  uriLoud;

    public Alarm() {}

    public Alarm(int hour, int minute, String label) {
        this.hour       = hour;
        this.minute     = minute;
        this.label      = label;
        this.isEnabled  = true;
        this.repeatDays = "0,0,0,0,0,0,0";
    }

    public String getTimeString() {
        return String.format("%02d:%02d", hour, minute);
    }

    public boolean hasQrCode() {
        return qrCodeValue != null && !qrCodeValue.isEmpty();
    }

    /** Returns the stored URI string for the given stage (0=calm,1=medium,2=loud). */
    public String uriForStage(int stage) {
        switch (stage) {
            case 0:  return uriCalm;
            case 1:  return uriMedium;
            default: return uriLoud;
        }
    }

    public boolean[] getRepeatDaysArray() {
        boolean[] days = new boolean[7];
        if (repeatDays == null) return days;
        String[] parts = repeatDays.split(",");
        for (int i = 0; i < 7 && i < parts.length; i++)
            days[i] = "1".equals(parts[i].trim());
        return days;
    }

    public void setRepeatDaysArray(boolean[] days) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (i > 0) sb.append(",");
            sb.append(days[i] ? "1" : "0");
        }
        this.repeatDays = sb.toString();
    }

    public boolean repeats() {
        if (repeatDays == null) return false;
        for (boolean d : getRepeatDaysArray()) if (d) return true;
        return false;
    }
}
