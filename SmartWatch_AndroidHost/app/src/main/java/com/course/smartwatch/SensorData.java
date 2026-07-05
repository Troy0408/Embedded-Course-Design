package com.course.smartwatch;

public final class SensorData {
    public final float ax;
    public final float ay;
    public final float az;
    public final float gx;
    public final float gy;
    public final float gz;

    public SensorData(float ax, float ay, float az, float gx, float gy, float gz) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
    }
}
