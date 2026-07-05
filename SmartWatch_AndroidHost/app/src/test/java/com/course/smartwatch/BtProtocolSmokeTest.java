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

        byte[] maxPayload = new byte[251];
        byte[] maxFrame = BtProtocol.buildFrame(BtProtocol.CMD_ACK, maxPayload);
        require(maxFrame.length == 256, "max payload frame size");
        expectThrows("oversized payload rejected", new ThrowingRunnable() {
            @Override
            public void run() {
                BtProtocol.buildFrame(BtProtocol.CMD_ACK, new byte[252]);
            }
        });

        BtProtocol.Parser parser = new BtProtocol.Parser();
        byte[] status = hex("AA 04 0E 0A 1E 2D 1A 07 05 00 02 39 30 34 12 01 01 06 55");
        List<BtProtocol.Frame> frames = parser.feed(status, status.length);
        require(frames.size() == 1, "expected one status frame");
        BtProtocol.Frame statusFrame = frames.get(0);
        require(statusFrame.payloadLength() == 14, "status payload length");
        byte[] statusPayload = statusFrame.payload();
        statusPayload[0] = 99;
        require(statusFrame.payload()[0] == 0x0A, "frame payload should be immutable");
        WatchStatus ws = BtProtocol.parseStatus(statusFrame);
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

        assertFramePayloadIsImmutable();
        assertSplitFrameParsesAfterSecondFeed();
        assertTwoFramesParseFromOneFeed();
        assertLeadingNoiseIsIgnored();
        assertBadChecksumRecovers();
        assertBadEtxRecovers();
        assertOversizedLengthMarkerRecovers();

        System.out.println("BtProtocolSmokeTest PASS");
    }

    private static void assertFramePayloadIsImmutable() {
        byte[] payload = new byte[] { 1, 2, 3 };
        BtProtocol.Frame frame = new BtProtocol.Frame(BtProtocol.CMD_ACK, payload);
        payload[0] = 9;
        require(frame.payload()[0] == 1, "frame should copy constructor payload");

        byte[] copy = frame.payload();
        copy[1] = 9;
        require(frame.payload()[1] == 2, "frame should return payload copy");
        require(frame.payloadLength() == 3, "frame payload length accessor");
    }

    private static void assertSplitFrameParsesAfterSecondFeed() {
        BtProtocol.Parser parser = new BtProtocol.Parser();
        byte[] page = BtProtocol.buildSetPageFrame(7);
        List<BtProtocol.Frame> frames = parser.feed(page, 2);
        require(frames.size() == 0, "split frame should wait for remaining bytes");

        byte[] remainder = Arrays.copyOfRange(page, 2, page.length);
        frames = parser.feed(remainder, remainder.length);
        require(frames.size() == 1, "split frame should parse after second feed");
        require(frames.get(0).cmd == BtProtocol.CMD_SET_PAGE, "split frame command");
        require(frames.get(0).payload()[0] == 7, "split frame payload");
    }

    private static void assertTwoFramesParseFromOneFeed() {
        BtProtocol.Parser parser = new BtProtocol.Parser();
        byte[] combined = concat(BtProtocol.buildResetStepsFrame(), BtProtocol.buildRequestStatusFrame());
        List<BtProtocol.Frame> frames = parser.feed(combined, combined.length);
        require(frames.size() == 2, "expected two frames from one feed");
        require(frames.get(0).cmd == BtProtocol.CMD_RESET_STEPS, "first combined command");
        require(frames.get(1).cmd == BtProtocol.CMD_REQUEST_STATUS, "second combined command");
        require(frames.get(0).payloadLength() == 0 && frames.get(1).payloadLength() == 0,
                "combined frame payload lengths");
    }

    private static void assertLeadingNoiseIsIgnored() {
        BtProtocol.Parser parser = new BtProtocol.Parser();
        byte[] noisy = concat(new byte[] { 0x00, 0x54, 0x55, 0x7F }, BtProtocol.buildRequestStatusFrame());
        List<BtProtocol.Frame> frames = parser.feed(noisy, noisy.length);
        require(frames.size() == 1, "leading noise should be ignored");
        require(frames.get(0).cmd == BtProtocol.CMD_REQUEST_STATUS, "noise recovery command");
    }

    private static void assertBadChecksumRecovers() {
        BtProtocol.Parser parser = new BtProtocol.Parser();
        byte[] bad = corruptChecksum(BtProtocol.buildSetPageFrame(4));
        byte[] good = BtProtocol.buildRequestStatusFrame();
        List<BtProtocol.Frame> frames = parser.feed(concat(bad, good), bad.length + good.length);
        require(frames.size() == 1, "bad checksum should be ignored");
        require(frames.get(0).cmd == BtProtocol.CMD_REQUEST_STATUS, "bad checksum recovery command");
    }

    private static void assertBadEtxRecovers() {
        BtProtocol.Parser parser = new BtProtocol.Parser();
        byte[] bad = corruptEtx(BtProtocol.buildSetPageFrame(5));
        byte[] good = BtProtocol.buildResetStepsFrame();
        List<BtProtocol.Frame> frames = parser.feed(concat(bad, good), bad.length + good.length);
        require(frames.size() == 1, "bad ETX should be ignored");
        require(frames.get(0).cmd == BtProtocol.CMD_RESET_STEPS, "bad ETX recovery command");
    }

    private static void assertOversizedLengthMarkerRecovers() {
        BtProtocol.Parser parser = new BtProtocol.Parser();
        byte[] oversizedMarker = new byte[] {
            (byte) BtProtocol.STX, (byte) BtProtocol.CMD_STATUS, (byte) 0xFC
        };
        byte[] good = BtProtocol.buildSetPageFrame(6);
        List<BtProtocol.Frame> frames = parser.feed(concat(oversizedMarker, good),
                oversizedMarker.length + good.length);
        require(frames.size() == 1, "oversized length marker should be ignored");
        require(frames.get(0).cmd == BtProtocol.CMD_SET_PAGE, "oversized marker recovery command");
        require(frames.get(0).payload()[0] == 6, "oversized marker recovery payload");
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

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static byte[] corruptChecksum(byte[] frame) {
        byte[] bad = Arrays.copyOf(frame, frame.length);
        bad[bad.length - 2] ^= 0x01;
        return bad;
    }

    private static byte[] corruptEtx(byte[] frame) {
        byte[] bad = Arrays.copyOf(frame, frame.length);
        bad[bad.length - 1] = 0x54;
        return bad;
    }

    private static void expectThrows(String label, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(label);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private interface ThrowingRunnable {
        void run();
    }
}
