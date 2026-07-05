package com.course.smartwatch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class BtProtocol {
    public static final int STX = 0xAA;
    public static final int ETX = 0x55;
    public static final int CMD_SENSOR_DATA = 0x01;
    public static final int CMD_TIME_SYNC = 0x02;
    public static final int CMD_ACK = 0x03;
    public static final int CMD_STATUS = 0x04;
    public static final int CMD_SET_PAGE = 0x10;
    public static final int CMD_RESET_STEPS = 0x11;
    public static final int CMD_REQUEST_STATUS = 0x12;

    private static final int HEADER_SIZE = 3;
    private static final int CHECKSUM_SIZE = 1;
    private static final int FOOTER_SIZE = 1;
    private static final int MAX_FRAME_SIZE = 256;
    private static final int MAX_PAYLOAD_SIZE = MAX_FRAME_SIZE - HEADER_SIZE - CHECKSUM_SIZE - FOOTER_SIZE;

    private BtProtocol() {
    }

    public static byte[] buildTimeSyncFrame(int hour, int minute, int second,
                                            int yearOffset, int month, int day, int weekday) {
        return buildFrame(CMD_TIME_SYNC, new byte[] {
            b(hour), b(minute), b(second), b(yearOffset), b(month), b(day), b(weekday)
        });
    }

    public static byte[] buildSetPageFrame(int page) {
        return buildFrame(CMD_SET_PAGE, new byte[] { b(page) });
    }

    public static byte[] buildResetStepsFrame() {
        return buildFrame(CMD_RESET_STEPS, new byte[0]);
    }

    public static byte[] buildRequestStatusFrame() {
        return buildFrame(CMD_REQUEST_STATUS, new byte[0]);
    }

    public static byte[] buildFrame(int cmd, byte[] payload) {
        byte[] data = payload == null ? new byte[0] : payload;
        require(data.length <= MAX_PAYLOAD_SIZE, "payload too large");

        int len = data.length;
        byte[] frame = new byte[HEADER_SIZE + len + CHECKSUM_SIZE + FOOTER_SIZE];
        frame[0] = (byte) STX;
        frame[1] = b(cmd);
        frame[2] = b(len);
        System.arraycopy(data, 0, frame, HEADER_SIZE, len);
        frame[HEADER_SIZE + len] = checksum(cmd, len, data);
        frame[HEADER_SIZE + len + CHECKSUM_SIZE] = (byte) ETX;
        return frame;
    }

    public static WatchStatus parseStatus(Frame frame) {
        require(frame != null, "frame required");
        require(frame.cmd == CMD_STATUS, "status command expected");
        require(frame.payloadLength() == 14, "status payload length expected");

        byte[] p = frame.payload();
        int steps = u16le(p, 8);
        int btFrames = u16le(p, 10);
        return new WatchStatus(
                u8(p[0]),
                u8(p[1]),
                u8(p[2]),
                2000 + u8(p[3]),
                u8(p[4]),
                u8(p[5]),
                u8(p[6]),
                u8(p[7]),
                steps,
                btFrames,
                u8(p[12]) != 0,
                u8(p[13]) != 0);
    }

    public static SensorData parseSensor(Frame frame) {
        require(frame != null, "frame required");
        require(frame.cmd == CMD_SENSOR_DATA, "sensor command expected");
        require(frame.payloadLength() == 24, "sensor payload length expected");

        ByteBuffer data = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
        return new SensorData(
                data.getFloat(),
                data.getFloat(),
                data.getFloat(),
                data.getFloat(),
                data.getFloat(),
                data.getFloat());
    }

    private static byte checksum(int cmd, int len, byte[] payload) {
        int chk = (cmd & 0xFF) ^ (len & 0xFF);
        for (byte value : payload) {
            chk ^= value & 0xFF;
        }
        return b(chk);
    }

    private static byte b(int value) {
        return (byte) (value & 0xFF);
    }

    private static int u8(byte value) {
        return value & 0xFF;
    }

    private static int u16le(byte[] payload, int offset) {
        return u8(payload[offset]) | (u8(payload[offset + 1]) << 8);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    public static final class Frame {
        public final int cmd;
        private final byte[] payload;

        public Frame(int cmd, byte[] payload) {
            this.cmd = cmd & 0xFF;
            this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        }

        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }

        public int payloadLength() {
            return payload.length;
        }
    }

    public static final class Parser {
        private final byte[] buffer = new byte[MAX_FRAME_SIZE];
        private int index;
        private int expectedLen;

        public List<Frame> feed(byte[] input, int count) {
            require(input != null, "input required");
            require(count >= 0 && count <= input.length, "invalid count");

            List<Frame> frames = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Frame frame = feedOne(input[i]);
                if (frame != null) {
                    frames.add(frame);
                }
            }
            return frames;
        }

        private Frame feedOne(byte value) {
            int u = value & 0xFF;
            if (index == 0) {
                if (u == STX) {
                    buffer[index++] = value;
                }
                return null;
            }

            if (index == 1) {
                buffer[index++] = value;
                return null;
            }

            if (index == 2) {
                expectedLen = u;
                if (expectedLen > MAX_PAYLOAD_SIZE) {
                    reset();
                    return null;
                }
                buffer[index++] = value;
                return null;
            }

            if (index < HEADER_SIZE + expectedLen) {
                buffer[index++] = value;
                return null;
            }

            if (index == HEADER_SIZE + expectedLen) {
                buffer[index++] = value;
                return null;
            }

            if (index == HEADER_SIZE + expectedLen + CHECKSUM_SIZE) {
                Frame frame = null;
                if (u == ETX) {
                    int cmd = u8(buffer[1]);
                    int len = u8(buffer[2]);
                    byte[] payload = Arrays.copyOfRange(buffer, HEADER_SIZE, HEADER_SIZE + len);
                    if (checksum(cmd, len, payload) == buffer[HEADER_SIZE + len]) {
                        frame = new Frame(cmd, payload);
                    }
                }
                reset();
                return frame;
            }

            reset();
            return null;
        }

        private void reset() {
            index = 0;
            expectedLen = 0;
        }
    }
}
