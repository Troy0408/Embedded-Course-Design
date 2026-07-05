package com.course.smartwatch;

import java.util.Arrays;
import java.util.List;

public final class BtProtocolSmokeTest {
    public static void main(String[] args) {
        byte[] time = BtProtocol.buildTimeSyncFrame(14, 5, 9, 26, 7, 5, 0);
        assertHex("time sync", time, "AA 02 07 0E 05 09 1A 07 05 00 1F 55");

        byte[] page = BtProtocol.buildSetPageFrame(3);
        assertHex("set page", page, "AA 10 01 03 12 55");

        byte[] reset = BtProtocol.buildResetStepsFrame();
        assertHex("reset steps", reset, "AA 11 00 11 55");

        byte[] request = BtProtocol.buildRequestStatusFrame();
        assertHex("request status", request, "AA 12 00 12 55");

        BtProtocol.Parser parser = new BtProtocol.Parser();
        byte[] status = hex("AA 04 0E 0A 1E 2D 1A 07 05 00 02 39 30 34 12 01 01 06 55");
        List<BtProtocol.Frame> frames = parser.feed(status, status.length);
        require(frames.size() == 1, "expected one status frame");
        WatchStatus ws = BtProtocol.parseStatus(frames.get(0));
        require(ws.hour == 10 && ws.minute == 30 && ws.second == 45, "bad status time");
        require(ws.page == 2, "bad status page");
        require(ws.steps == 12345, "bad status steps");
        require(ws.btFrames == 0x1234, "bad status frames");
        require(ws.imuValid && ws.connected, "bad status flags");

        byte[] sensor = new byte[] {
            (byte) 0xAA, 0x01, 0x18,
            0x00, 0x00, (byte) 0x80, 0x3F,
            0x00, 0x00, 0x00, 0x40,
            0x00, 0x00, 0x40, 0x40,
            0x00, 0x00, (byte) 0x80, 0x40,
            0x00, 0x00, (byte) 0xA0, 0x40,
            0x00, 0x00, (byte) 0xC0, 0x40,
            0x46, 0x55
        };
        frames = parser.feed(sensor, sensor.length);
        require(frames.size() == 1, "expected one sensor frame");
        SensorData sd = BtProtocol.parseSensor(frames.get(0));
        require(close(sd.ax, 1.0f) && close(sd.ay, 2.0f) && close(sd.az, 3.0f), "bad accel");
        require(close(sd.gx, 4.0f) && close(sd.gy, 5.0f) && close(sd.gz, 6.0f), "bad gyro");

        System.out.println("BtProtocolSmokeTest PASS");
    }

    private static void assertHex(String label, byte[] actual, String expectedHex) {
        byte[] expected = hex(expectedHex);
        if (!Arrays.equals(actual, expected)) {
            throw new AssertionError(label + " expected " + toHex(expected) + " got " + toHex(actual));
        }
    }

    private static byte[] hex(String s) {
        String compact = s.replace(" ", "");
        byte[] out = new byte[compact.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 0.0001f;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
