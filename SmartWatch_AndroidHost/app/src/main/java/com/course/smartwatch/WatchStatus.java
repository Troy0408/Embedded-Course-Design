package com.course.smartwatch;

public final class WatchStatus {
    public final int hour;
    public final int minute;
    public final int second;
    public final int year;
    public final int month;
    public final int day;
    public final int weekday;
    public final int page;
    public final int steps;
    public final int btFrames;
    public final boolean imuValid;
    public final boolean connected;

    public WatchStatus(int hour, int minute, int second, int year, int month, int day,
                       int weekday, int page, int steps, int btFrames,
                       boolean imuValid, boolean connected) {
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.year = year;
        this.month = month;
        this.day = day;
        this.weekday = weekday;
        this.page = page;
        this.steps = steps;
        this.btFrames = btFrames;
        this.imuValid = imuValid;
        this.connected = connected;
    }
}
