# SmartWatch Bluetooth Sync Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add HC-05 Android phone control and data sync to the working FreeRTOS STM32 smart watch.

**Architecture:** Keep the existing STM32 `AA CMD LEN DATA CHK 55` UART frame format, extend it with status and control commands, and expose those commands through the existing FreeRTOS Bluetooth task. Add a native Java Android app that connects to an already paired HC-05 over classic Bluetooth SPP, parses telemetry, and sends control frames.

**Tech Stack:** STM32 HAL, FreeRTOS, CMake/Ninja ARM GCC build, Java Android app source, classic Bluetooth RFCOMM SPP UUID `00001101-0000-1000-8000-00805F9B34FB`, desktop `javac` smoke tests for protocol code.

---

## File Map

Firmware files:

- Modify: `SmartWatch_FreeRTOS/Core/Inc/bluetooth.h`
  - Add command ids, ACK status constants, page/status payload helpers, and control event types.
- Modify: `SmartWatch_FreeRTOS/Core/Src/bluetooth.c`
  - Extend parser, queue pending control events, add ACK/status frame builders, add transmit busy protection.
- Modify: `SmartWatch_FreeRTOS/Core/Inc/step_counter.h`
  - Add `StepCounter_Reset`.
- Modify: `SmartWatch_FreeRTOS/Core/Src/step_counter.c`
  - Reuse `StepCounter_Init` to clear the counter state.
- Modify: `SmartWatch_FreeRTOS/Core/Src/app_freertos.c`
  - Apply Bluetooth control events, send periodic status, and wake display after phone commands.

Android files:

- Create: `SmartWatch_AndroidHost/settings.gradle`
  - Android Studio project root.
- Create: `SmartWatch_AndroidHost/build.gradle`
  - Root Gradle plugin configuration.
- Create: `SmartWatch_AndroidHost/app/build.gradle`
  - App module configuration.
- Create: `SmartWatch_AndroidHost/app/src/main/AndroidManifest.xml`
  - Bluetooth permissions and `MainActivity`.
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/BtProtocol.java`
  - Frame parser and frame builders.
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/WatchStatus.java`
  - Parsed status model.
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/SensorData.java`
  - Parsed sensor model.
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/BluetoothSppClient.java`
  - Background RFCOMM connect/read/write worker.
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/MainActivity.java`
  - Single-screen Android upper computer UI.
- Create: `SmartWatch_AndroidHost/app/src/test/java/com/course/smartwatch/BtProtocolSmokeTest.java`
  - Pure Java protocol smoke test runnable with local `javac`.
- Create: `SmartWatch_AndroidHost/README.md`
  - Pairing, build, install, and demo steps.

Documentation files:

- Modify: `docs/superpowers/specs/2026-07-05-smartwatch-bluetooth-sync-design.md`
  - Add final implementation notes only if the implementation changes a documented field.

---

## Task 1: Android Protocol Smoke Test

**Files:**
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/BtProtocol.java`
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/WatchStatus.java`
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/SensorData.java`
- Create: `SmartWatch_AndroidHost/app/src/test/java/com/course/smartwatch/BtProtocolSmokeTest.java`

- [ ] **Step 1: Write the failing protocol smoke test**

Create `SmartWatch_AndroidHost/app/src/test/java/com/course/smartwatch/BtProtocolSmokeTest.java` with this content:

```java
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
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 0.0001f;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
javac -d SmartWatch_AndroidHost\build\protocol-test `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\BtProtocol.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\WatchStatus.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\SensorData.java `
  SmartWatch_AndroidHost\app\src\test\java\com\course\smartwatch\BtProtocolSmokeTest.java
```

Expected: FAIL because `BtProtocol.java`, `WatchStatus.java`, and `SensorData.java` do not exist yet.

- [ ] **Step 3: Create the protocol model files**

Create `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/WatchStatus.java`:

```java
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
```

Create `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/SensorData.java`:

```java
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
```

Create `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/BtProtocol.java`:

```java
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
        int len = payload == null ? 0 : payload.length;
        byte[] frame = new byte[len + 5];
        frame[0] = (byte) STX;
        frame[1] = b(cmd);
        frame[2] = b(len);
        if (len > 0) {
            System.arraycopy(payload, 0, frame, 3, len);
        }
        frame[3 + len] = checksum(cmd, len, payload);
        frame[4 + len] = (byte) ETX;
        return frame;
    }

    public static WatchStatus parseStatus(Frame frame) {
        require(frame.cmd == CMD_STATUS, "status command expected");
        require(frame.payload.length == 14, "status payload length expected");
        byte[] p = frame.payload;
        int steps = u16le(p, 8);
        int btFrames = u16le(p, 10);
        return new WatchStatus(u8(p[0]), u8(p[1]), u8(p[2]), 2000 + u8(p[3]),
                u8(p[4]), u8(p[5]), u8(p[6]), u8(p[7]), steps, btFrames,
                u8(p[12]) != 0, u8(p[13]) != 0);
    }

    public static SensorData parseSensor(Frame frame) {
        require(frame.cmd == CMD_SENSOR_DATA, "sensor command expected");
        require(frame.payload.length == 24, "sensor payload length expected");
        ByteBuffer bb = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN);
        return new SensorData(bb.getFloat(), bb.getFloat(), bb.getFloat(),
                bb.getFloat(), bb.getFloat(), bb.getFloat());
    }

    private static byte checksum(int cmd, int len, byte[] payload) {
        int chk = (cmd & 0xFF) ^ (len & 0xFF);
        if (payload != null) {
            for (byte value : payload) {
                chk ^= value & 0xFF;
            }
        }
        return b(chk);
    }

    private static byte b(int value) {
        return (byte) (value & 0xFF);
    }

    private static int u8(byte value) {
        return value & 0xFF;
    }

    private static int u16le(byte[] p, int offset) {
        return u8(p[offset]) | (u8(p[offset + 1]) << 8);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    public static final class Frame {
        public final int cmd;
        public final byte[] payload;

        Frame(int cmd, byte[] payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    public static final class Parser {
        private final byte[] buffer = new byte[256];
        private int index;
        private int expectedLen;

        public List<Frame> feed(byte[] input, int count) {
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
                if (expectedLen > 251) {
                    reset();
                    return null;
                }
                buffer[index++] = value;
                return null;
            }
            if (index < 3 + expectedLen) {
                buffer[index++] = value;
                return null;
            }
            if (index == 3 + expectedLen) {
                buffer[index++] = value;
                return null;
            }
            if (index == 4 + expectedLen) {
                Frame frame = null;
                if (u == ETX) {
                    int cmd = buffer[1] & 0xFF;
                    int len = buffer[2] & 0xFF;
                    byte[] payload = Arrays.copyOfRange(buffer, 3, 3 + len);
                    if (checksum(cmd, len, payload) == buffer[3 + len]) {
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
```

- [ ] **Step 4: Run the protocol smoke test to verify it passes**

Run:

```powershell
javac -d SmartWatch_AndroidHost\build\protocol-test `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\BtProtocol.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\WatchStatus.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\SensorData.java `
  SmartWatch_AndroidHost\app\src\test\java\com\course\smartwatch\BtProtocolSmokeTest.java
java -cp SmartWatch_AndroidHost\build\protocol-test com.course.smartwatch.BtProtocolSmokeTest
```

Expected:

```text
BtProtocolSmokeTest PASS
```

- [ ] **Step 5: Commit protocol core**

Run:

```powershell
git add -- SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/BtProtocol.java `
  SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/WatchStatus.java `
  SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/SensorData.java `
  SmartWatch_AndroidHost/app/src/test/java/com/course/smartwatch/BtProtocolSmokeTest.java
git commit -m "test: add smartwatch bluetooth protocol smoke test"
```

---

## Task 2: Firmware Bluetooth Protocol API

**Files:**
- Modify: `SmartWatch_FreeRTOS/Core/Inc/bluetooth.h`
- Modify: `SmartWatch_FreeRTOS/Core/Src/bluetooth.c`

- [ ] **Step 1: Update the firmware Bluetooth header**

In `SmartWatch_FreeRTOS/Core/Inc/bluetooth.h`, replace the command section and function declarations with these definitions:

```c
/* Command IDs */
#define BT_CMD_SENSOR_DATA      0x01U
#define BT_CMD_TIME_SYNC        0x02U
#define BT_CMD_ACK              0x03U
#define BT_CMD_STATUS           0x04U
#define BT_CMD_SET_PAGE         0x10U
#define BT_CMD_RESET_STEPS      0x11U
#define BT_CMD_REQUEST_STATUS   0x12U

/* ACK status values */
#define BT_ACK_OK               0x00U
#define BT_ACK_INVALID_LENGTH   0x01U
#define BT_ACK_INVALID_VALUE    0x02U
#define BT_ACK_UNSUPPORTED      0x03U

#define BT_EVENT_TIME_SYNC      0x01U
#define BT_EVENT_SET_PAGE       0x02U
#define BT_EVENT_RESET_STEPS    0x04U
#define BT_EVENT_REQUEST_STATUS 0x08U
```

Add this structure after `BT_TimeSync_t`:

```c
typedef struct {
    uint8_t flags;
    BT_TimeSync_t time;
    UIPage_t page;
} BT_ControlEvent_t;
```

Replace the public function declarations at the bottom with:

```c
void BT_Init(void);
uint8_t BT_SendSensorData(const SmartWatchData_t *data);
uint8_t BT_SendStatus(const SmartWatchData_t *data);
uint8_t BT_SendAck(uint8_t acknowledged_cmd, uint8_t status);
uint8_t BT_Process(SmartWatchData_t *data, BT_ControlEvent_t *event);
void BT_RxCpltCallback(uint16_t size);
uint8_t BT_IsConnected(void);
```

- [ ] **Step 2: Build to verify call sites fail before implementation**

Run:

```powershell
$env:PATH='C:\Users\Troy\AppData\Local\stm32cube\bundles\gnu-tools-for-stm32\14.3.1+st.2\bin;C:\Users\Troy\AppData\Local\stm32cube\bundles\ninja\1.13.2+st.1\bin;' + $env:PATH
& 'C:\Users\Troy\AppData\Local\stm32cube\bundles\cmake\4.2.3+st.1\bin\cmake.exe' --build --preset Debug
```

Expected: FAIL because `bluetooth.c` still has the old `BT_Process` and `BT_SendSensorData` signatures.

- [ ] **Step 3: Extend parser state in `bluetooth.c`**

In `SmartWatch_FreeRTOS/Core/Src/bluetooth.c`, replace the time-sync globals with:

```c
static BT_ControlEvent_t pending_event;
static volatile uint8_t pending_flags = 0;
static volatile uint8_t pending_ack_available = 0;
static uint8_t pending_ack_cmd = 0;
static uint8_t pending_ack_status = 0;
static volatile uint8_t tx_busy = 0;
```

Add these helper functions before `BT_ParseByte`:

```c
static void BT_ResetParser(void)
{
    frame_idx = 0;
    frame_expected_len = 0;
}

static uint8_t BT_Checksum(uint8_t cmd, uint8_t len, const uint8_t *payload)
{
    uint8_t chk = cmd ^ len;
    for (uint8_t i = 0; i < len; i++) {
        chk ^= payload[i];
    }
    return chk;
}

static uint8_t BT_SendFrame(uint8_t cmd, const uint8_t *payload, uint8_t len)
{
    if (len > (BT_TX_BUF_SIZE - 5U)) {
        return 0U;
    }
    if (tx_busy != 0U || huart2.gState != HAL_UART_STATE_READY) {
        return 0U;
    }

    tx_buf[0] = BT_STX;
    tx_buf[1] = cmd;
    tx_buf[2] = len;
    if (payload != NULL && len > 0U) {
        memcpy(&tx_buf[3], payload, len);
    }
    tx_buf[3U + len] = BT_Checksum(cmd, len, payload == NULL ? &tx_buf[3] : payload);
    tx_buf[4U + len] = BT_ETX;

    tx_busy = 1U;
    if (HAL_UART_Transmit_DMA(&huart2, tx_buf, 5U + len) != HAL_OK) {
        tx_busy = 0U;
        return 0U;
    }
    return 1U;
}

static void BT_QueueAck(uint8_t acknowledged_cmd, uint8_t status)
{
    pending_ack_cmd = acknowledged_cmd;
    pending_ack_status = status;
    pending_ack_available = 1U;
}
```

Add this callback after `HAL_UARTEx_RxEventCallback`:

```c
void HAL_UART_TxCpltCallback(UART_HandleTypeDef *huart)
{
    if (huart->Instance == USART2) {
        tx_busy = 0U;
    }
}
```

- [ ] **Step 4: Add command handler**

Add this helper before `BT_ParseByte`:

```c
static void BT_HandleFrame(uint8_t cmd, const uint8_t *payload, uint8_t len)
{
    if (cmd == BT_CMD_TIME_SYNC) {
        if (len != 7U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        pending_event.time.hour = payload[0];
        pending_event.time.minute = payload[1];
        pending_event.time.second = payload[2];
        pending_event.time.year = (uint16_t)(2000U + payload[3]);
        pending_event.time.month = payload[4];
        pending_event.time.day = payload[5];
        pending_event.time.weekday = payload[6];
        pending_flags |= BT_EVENT_TIME_SYNC;
        BT_QueueAck(cmd, BT_ACK_OK);
        return;
    }

    if (cmd == BT_CMD_SET_PAGE) {
        if (len != 1U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        if (payload[0] >= PAGE_MAX) {
            BT_QueueAck(cmd, BT_ACK_INVALID_VALUE);
            return;
        }
        pending_event.page = (UIPage_t)payload[0];
        pending_flags |= BT_EVENT_SET_PAGE;
        BT_QueueAck(cmd, BT_ACK_OK);
        return;
    }

    if (cmd == BT_CMD_RESET_STEPS) {
        if (len != 0U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        pending_flags |= BT_EVENT_RESET_STEPS;
        BT_QueueAck(cmd, BT_ACK_OK);
        return;
    }

    if (cmd == BT_CMD_REQUEST_STATUS) {
        if (len != 0U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        pending_flags |= BT_EVENT_REQUEST_STATUS;
        BT_QueueAck(cmd, BT_ACK_OK);
        return;
    }

    BT_QueueAck(cmd, BT_ACK_UNSUPPORTED);
}
```

- [ ] **Step 5: Replace the checksum branch in `BT_ParseByte`**

In the `frame_idx == 4 + frame_expected_len` branch, replace the nested time-sync-only logic with:

```c
frame_buf[frame_idx] = byte; /* ETX */
if (byte == BT_ETX)
{
    uint8_t cmd = frame_buf[1];
    uint8_t len = frame_buf[2];
    uint8_t chk = BT_Checksum(cmd, len, &frame_buf[3]);
    if (chk == frame_buf[3U + len])
    {
        BT_HandleFrame(cmd, &frame_buf[3], len);
    }
}
BT_ResetParser();
```

Also replace all parser reset assignments inside `BT_ParseByte` with `BT_ResetParser();`.

- [ ] **Step 6: Replace sender and process functions**

Replace `BT_SendSensorData`, remove `BT_GetTimeSync`, and replace `BT_Process` with:

```c
uint8_t BT_SendSensorData(const SmartWatchData_t *data)
{
    uint8_t payload[24];
    uint8_t *p = payload;
    memcpy(p, &data->accel.ax, 4); p += 4;
    memcpy(p, &data->accel.ay, 4); p += 4;
    memcpy(p, &data->accel.az, 4); p += 4;
    memcpy(p, &data->gyro.gx,  4); p += 4;
    memcpy(p, &data->gyro.gy,  4); p += 4;
    memcpy(p, &data->gyro.gz,  4);
    return BT_SendFrame(BT_CMD_SENSOR_DATA, payload, sizeof(payload));
}

uint8_t BT_SendStatus(const SmartWatchData_t *data)
{
    uint8_t payload[14];
    uint16_t steps = (data->steps > 0xFFFFU) ? 0xFFFFU : (uint16_t)data->steps;
    uint16_t frames = (data->bt_frames > 0xFFFFU) ? 0xFFFFU : (uint16_t)data->bt_frames;

    payload[0] = data->hour;
    payload[1] = data->minute;
    payload[2] = data->second;
    payload[3] = (data->year >= 2000U) ? (uint8_t)(data->year - 2000U) : 0U;
    payload[4] = data->month;
    payload[5] = data->day;
    payload[6] = data->weekday;
    payload[7] = (uint8_t)data->current_page;
    payload[8] = (uint8_t)(steps & 0xFFU);
    payload[9] = (uint8_t)(steps >> 8);
    payload[10] = (uint8_t)(frames & 0xFFU);
    payload[11] = (uint8_t)(frames >> 8);
    payload[12] = data->imu_status;
    payload[13] = data->bt_connected;

    return BT_SendFrame(BT_CMD_STATUS, payload, sizeof(payload));
}

uint8_t BT_SendAck(uint8_t acknowledged_cmd, uint8_t status)
{
    uint8_t payload[2] = { acknowledged_cmd, status };
    return BT_SendFrame(BT_CMD_ACK, payload, sizeof(payload));
}

uint8_t BT_Process(SmartWatchData_t *data, BT_ControlEvent_t *event)
{
    if (pending_ack_available != 0U) {
        if (BT_SendAck(pending_ack_cmd, pending_ack_status) != 0U) {
            pending_ack_available = 0U;
        }
    }

    __disable_irq();
    uint8_t flags = pending_flags;
    BT_ControlEvent_t local_event = pending_event;
    pending_flags = 0U;
    __enable_irq();

    if (flags == 0U) {
        if (event != NULL) {
            memset(event, 0, sizeof(*event));
        }
        return 0U;
    }

    if (event != NULL) {
        *event = local_event;
        event->flags = flags;
    }

    if (data != NULL && (flags & BT_EVENT_TIME_SYNC) != 0U) {
        data->hour = local_event.time.hour;
        data->minute = local_event.time.minute;
        data->second = local_event.time.second;
        data->year = local_event.time.year;
        data->month = local_event.time.month;
        data->day = local_event.time.day;
        data->weekday = local_event.time.weekday;
    }

    if (data != NULL && (flags & BT_EVENT_SET_PAGE) != 0U) {
        data->current_page = local_event.page;
    }

    return flags;
}
```

- [ ] **Step 7: Build to verify protocol API compiles or reveals app call-site errors**

Run:

```powershell
$env:PATH='C:\Users\Troy\AppData\Local\stm32cube\bundles\gnu-tools-for-stm32\14.3.1+st.2\bin;C:\Users\Troy\AppData\Local\stm32cube\bundles\ninja\1.13.2+st.1\bin;' + $env:PATH
& 'C:\Users\Troy\AppData\Local\stm32cube\bundles\cmake\4.2.3+st.1\bin\cmake.exe' --build --preset Debug
```

Expected: FAIL only at the old `BT_Process(&watch_data)` call in `app_freertos.c`, because Task 3 has not integrated the new event signature yet.

- [ ] **Step 8: Commit firmware protocol API**

Run:

```powershell
git add -- SmartWatch_FreeRTOS/Core/Inc/bluetooth.h SmartWatch_FreeRTOS/Core/Src/bluetooth.c
git commit -m "feat: extend smartwatch bluetooth protocol"
```

---

## Task 3: Firmware FreeRTOS Control Integration

**Files:**
- Modify: `SmartWatch_FreeRTOS/Core/Inc/step_counter.h`
- Modify: `SmartWatch_FreeRTOS/Core/Src/step_counter.c`
- Modify: `SmartWatch_FreeRTOS/Core/Src/app_freertos.c`

- [ ] **Step 1: Add a reset helper for the step counter**

In `SmartWatch_FreeRTOS/Core/Inc/step_counter.h`, add:

```c
void StepCounter_Reset(StepCounter_t *counter);
```

In `SmartWatch_FreeRTOS/Core/Src/step_counter.c`, add:

```c
void StepCounter_Reset(StepCounter_t *counter)
{
    StepCounter_Init(counter);
}
```

- [ ] **Step 2: Build to verify the helper compiles**

Run:

```powershell
$env:PATH='C:\Users\Troy\AppData\Local\stm32cube\bundles\gnu-tools-for-stm32\14.3.1+st.2\bin;C:\Users\Troy\AppData\Local\stm32cube\bundles\ninja\1.13.2+st.1\bin;' + $env:PATH
& 'C:\Users\Troy\AppData\Local\stm32cube\bundles\cmake\4.2.3+st.1\bin\cmake.exe' --build --preset Debug
```

Expected: FAIL only because `app_freertos.c` still calls the old `BT_Process` signature.

- [ ] **Step 3: Replace `TaskBluetooth` with control-event logic**

In `SmartWatch_FreeRTOS/Core/Src/app_freertos.c`, replace the whole `TaskBluetooth` function with:

```c
static void TaskBluetooth(void *argument)
{
    (void)argument;
    TickType_t last_wake = xTaskGetTickCount();
    TickType_t last_sensor_send = 0;
    TickType_t last_status_send = 0;

    for (;;) {
        vTaskDelayUntil(&last_wake, pdMS_TO_TICKS(50));

        uint8_t changed = 0U;
        uint8_t request_status = 0U;
        BT_ControlEvent_t event;

        Data_Lock();
        uint8_t flags = BT_Process(&watch_data, &event);
        if ((flags & BT_EVENT_RESET_STEPS) != 0U) {
            StepCounter_Reset(&step_counter);
            watch_data.steps = 0U;
            watch_data.step_filtered_g = step_counter.filtered_g;
            watch_data.step_threshold_g = step_counter.threshold_g;
            changed = 1U;
        }
        if ((flags & (BT_EVENT_TIME_SYNC | BT_EVENT_SET_PAGE)) != 0U) {
            changed = 1U;
        }
        if ((flags & BT_EVENT_REQUEST_STATUS) != 0U) {
            request_status = 1U;
        }

        uint8_t connected = BT_IsConnected();
        if (watch_data.bt_connected != connected) {
            watch_data.bt_connected = connected;
            changed = 1U;
            request_status = connected;
        }
        Data_Unlock();

        TickType_t now = xTaskGetTickCount();

        if ((now - last_sensor_send) >= pdMS_TO_TICKS(500)) {
            SmartWatchData_t snapshot;
            uint8_t send_frame = 0U;

            Data_Lock();
            snapshot = watch_data;
            send_frame = (uint8_t)(watch_data.bt_connected != 0U && watch_data.imu_status != 0U);
            Data_Unlock();

            if (send_frame != 0U && BT_SendSensorData(&snapshot) != 0U) {
                Data_Lock();
                watch_data.bt_frames++;
                Data_Unlock();
                changed = 1U;
            }

            last_sensor_send = now;
        }

        if (request_status != 0U || (now - last_status_send) >= pdMS_TO_TICKS(1000)) {
            SmartWatchData_t snapshot;
            uint8_t send_frame = 0U;

            Data_Lock();
            snapshot = watch_data;
            send_frame = watch_data.bt_connected;
            Data_Unlock();

            if (send_frame != 0U && BT_SendStatus(&snapshot) != 0U) {
                Data_Lock();
                watch_data.bt_frames++;
                Data_Unlock();
                changed = 1U;
            }

            last_status_send = now;
        }

        if (changed != 0U) {
            SignalDisplay();
        }
    }
}
```

- [ ] **Step 4: Build the firmware**

Run:

```powershell
$env:PATH='C:\Users\Troy\AppData\Local\stm32cube\bundles\gnu-tools-for-stm32\14.3.1+st.2\bin;C:\Users\Troy\AppData\Local\stm32cube\bundles\ninja\1.13.2+st.1\bin;' + $env:PATH
& 'C:\Users\Troy\AppData\Local\stm32cube\bundles\cmake\4.2.3+st.1\bin\cmake.exe' --build --preset Debug --clean-first
```

Expected: PASS and produce `SmartWatch_FreeRTOS/build/Debug/SmartWatch_FreeRTOS.elf`.

- [ ] **Step 5: Commit RTOS integration**

Run:

```powershell
git add -- SmartWatch_FreeRTOS/Core/Inc/step_counter.h `
  SmartWatch_FreeRTOS/Core/Src/step_counter.c `
  SmartWatch_FreeRTOS/Core/Src/app_freertos.c
git commit -m "feat: handle bluetooth watch controls"
```

---

## Task 4: Android Project Skeleton

**Files:**
- Create: `SmartWatch_AndroidHost/settings.gradle`
- Create: `SmartWatch_AndroidHost/build.gradle`
- Create: `SmartWatch_AndroidHost/app/build.gradle`
- Create: `SmartWatch_AndroidHost/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create project Gradle files**

Create `SmartWatch_AndroidHost/settings.gradle`:

```gradle
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = 'SmartWatchAndroidHost'
include ':app'
```

Create `SmartWatch_AndroidHost/build.gradle`:

```gradle
plugins {
    id 'com.android.application' version '8.5.2' apply false
}
```

Create `SmartWatch_AndroidHost/app/build.gradle`:

```gradle
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.course.smartwatch'
    compileSdk 35

    defaultConfig {
        applicationId 'com.course.smartwatch'
        minSdk 23
        targetSdk 35
        versionCode 1
        versionName '1.0'
    }
}
```

- [ ] **Step 2: Create Android manifest**

Create `SmartWatch_AndroidHost/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <application
        android:allowBackup="false"
        android:label="SmartWatch Host"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: Add minimal Android resources**

Create `SmartWatch_AndroidHost/app/src/main/res/values/styles.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:colorAccent">#1565C0</item>
    </style>
</resources>
```

- [ ] **Step 4: Commit Android skeleton**

Run:

```powershell
git add -- SmartWatch_AndroidHost/settings.gradle `
  SmartWatch_AndroidHost/build.gradle `
  SmartWatch_AndroidHost/app/build.gradle `
  SmartWatch_AndroidHost/app/src/main/AndroidManifest.xml `
  SmartWatch_AndroidHost/app/src/main/res/values/styles.xml
git commit -m "feat: scaffold android bluetooth host"
```

---

## Task 5: Android Bluetooth SPP Client

**Files:**
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/BluetoothSppClient.java`

- [ ] **Step 1: Create the Bluetooth client**

Create `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/BluetoothSppClient.java`:

```java
package com.course.smartwatch;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BluetoothSppClient {
    public interface Listener {
        void onState(String text, boolean connected);
        void onFrame(BtProtocol.Frame frame);
        void onError(String text);
    }

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BtProtocol.Parser parser = new BtProtocol.Parser();

    private BluetoothSocket socket;
    private OutputStream output;
    private volatile boolean running;

    public BluetoothSppClient(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    public Set<BluetoothDevice> getBondedDevices() {
        return adapter.getBondedDevices();
    }

    public void connect(BluetoothDevice device) {
        disconnect();
        worker.execute(() -> {
            postState("Connecting " + device.getName(), false);
            try {
                BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP_UUID);
                adapter.cancelDiscovery();
                s.connect();
                socket = s;
                output = s.getOutputStream();
                running = true;
                postState("Connected " + device.getName(), true);
                readLoop(s.getInputStream());
            } catch (IOException e) {
                closeSocket();
                postError("Connect failed: " + e.getMessage());
                postState("Disconnected", false);
            }
        });
    }

    public void write(byte[] frame) {
        worker.execute(() -> {
            try {
                if (output != null) {
                    output.write(frame);
                    output.flush();
                }
            } catch (IOException e) {
                closeSocket();
                postError("Write failed: " + e.getMessage());
                postState("Disconnected", false);
            }
        });
    }

    public void disconnect() {
        worker.execute(() -> {
            running = false;
            closeSocket();
            postState("Disconnected", false);
        });
    }

    public void shutdown() {
        running = false;
        closeSocket();
        worker.shutdownNow();
    }

    private void readLoop(InputStream input) throws IOException {
        byte[] buf = new byte[128];
        while (running) {
            int n = input.read(buf);
            if (n < 0) {
                break;
            }
            List<BtProtocol.Frame> frames = parser.feed(buf, n);
            for (BtProtocol.Frame frame : frames) {
                main.post(() -> listener.onFrame(frame));
            }
        }
        closeSocket();
        postState("Disconnected", false);
    }

    private void closeSocket() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        output = null;
    }

    private void postState(String text, boolean connected) {
        main.post(() -> listener.onState(text, connected));
    }

    private void postError(String text) {
        main.post(() -> listener.onError(text));
    }
}
```

- [ ] **Step 2: Run protocol smoke test after adding Android-dependent file**

Run:

```powershell
javac -d SmartWatch_AndroidHost\build\protocol-test `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\BtProtocol.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\WatchStatus.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\SensorData.java `
  SmartWatch_AndroidHost\app\src\test\java\com\course\smartwatch\BtProtocolSmokeTest.java
java -cp SmartWatch_AndroidHost\build\protocol-test com.course.smartwatch.BtProtocolSmokeTest
```

Expected:

```text
BtProtocolSmokeTest PASS
```

- [ ] **Step 3: Commit Bluetooth client**

Run:

```powershell
git add -- SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/BluetoothSppClient.java
git commit -m "feat: add android bluetooth spp client"
```

---

## Task 6: Android Main Screen

**Files:**
- Create: `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/MainActivity.java`

- [ ] **Step 1: Create single-screen upper computer UI**

Create `SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/MainActivity.java`:

```java
package com.course.smartwatch;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends android.app.Activity implements BluetoothSppClient.Listener {
    private final List<BluetoothDevice> devices = new ArrayList<>();

    private BluetoothSppClient client;
    private Spinner deviceSpinner;
    private TextView stateText;
    private TextView watchTimeText;
    private TextView pageText;
    private TextView stepsText;
    private TextView framesText;
    private TextView imuText;
    private TextView sensorText;
    private int currentPage;
    private boolean connected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            client = new BluetoothSppClient(adapter, this);
        }

        buildUi();
        requestBluetoothPermission();
        loadBondedDevices();
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.shutdown();
        }
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = label("STM32 SmartWatch Host", 24, true);
        root.addView(title);

        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner);

        LinearLayout row = row();
        Button connect = button("Connect");
        connect.setOnClickListener(v -> connectSelected());
        Button disconnect = button("Disconnect");
        disconnect.setOnClickListener(v -> {
            if (client != null) client.disconnect();
        });
        row.addView(connect);
        row.addView(disconnect);
        root.addView(row);

        stateText = label("Disconnected", 16, false);
        root.addView(stateText);

        watchTimeText = label("Time: --:--:--", 18, true);
        pageText = label("Page: --", 16, false);
        stepsText = label("Steps: 0", 16, false);
        framesText = label("BT Frames: 0", 16, false);
        imuText = label("IMU: --", 16, false);
        sensorText = label("Sensor: --", 16, false);
        root.addView(watchTimeText);
        root.addView(pageText);
        root.addView(stepsText);
        root.addView(framesText);
        root.addView(imuText);
        root.addView(sensorText);

        LinearLayout controls1 = row();
        Button syncTime = button("Sync Time");
        syncTime.setOnClickListener(v -> sendTime());
        Button request = button("Request Status");
        request.setOnClickListener(v -> write(BtProtocol.buildRequestStatusFrame()));
        controls1.addView(syncTime);
        controls1.addView(request);
        root.addView(controls1);

        LinearLayout controls2 = row();
        Button prev = button("Prev Page");
        prev.setOnClickListener(v -> setPage((currentPage + 4) % 5));
        Button next = button("Next Page");
        next.setOnClickListener(v -> setPage((currentPage + 1) % 5));
        controls2.addView(prev);
        controls2.addView(next);
        root.addView(controls2);

        LinearLayout controls3 = row();
        Button dial = button("Dial");
        dial.setOnClickListener(v -> setPage(0));
        Button reset = button("Reset Steps");
        reset.setOnClickListener(v -> write(BtProtocol.buildResetStepsFrame()));
        controls3.addView(dial);
        controls3.addView(reset);
        root.addView(controls3);

        setContentView(scroll);
    }

    private void loadBondedDevices() {
        devices.clear();
        List<String> names = new ArrayList<>();
        if (client != null && hasBtConnectPermission()) {
            for (BluetoothDevice device : client.getBondedDevices()) {
                devices.add(device);
                names.add(device.getName() + "  " + device.getAddress());
            }
        }
        if (names.isEmpty()) {
            names.add("No paired HC-05 device");
        }
        deviceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    private void connectSelected() {
        if (client == null) {
            toast("Bluetooth is not available");
            return;
        }
        if (!hasBtConnectPermission()) {
            requestBluetoothPermission();
            return;
        }
        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) {
            toast("Pair HC-05 in Android Bluetooth settings first");
            return;
        }
        client.connect(devices.get(index));
    }

    private void sendTime() {
        Calendar now = Calendar.getInstance();
        write(BtProtocol.buildTimeSyncFrame(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND), now.get(Calendar.YEAR) - 2000,
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.DAY_OF_WEEK) - 1));
    }

    private void setPage(int page) {
        currentPage = page;
        write(BtProtocol.buildSetPageFrame(page));
    }

    private void write(byte[] frame) {
        if (!connected) {
            toast("Connect HC-05 first");
            return;
        }
        client.write(frame);
    }

    @Override
    public void onState(String text, boolean connected) {
        this.connected = connected;
        stateText.setText(text);
    }

    @Override
    public void onFrame(BtProtocol.Frame frame) {
        if (frame.cmd == BtProtocol.CMD_STATUS) {
            WatchStatus status = BtProtocol.parseStatus(frame);
            currentPage = status.page;
            watchTimeText.setText(String.format(Locale.US, "Time: %02d:%02d:%02d",
                    status.hour, status.minute, status.second));
            pageText.setText("Page: " + pageName(status.page));
            stepsText.setText("Steps: " + status.steps);
            framesText.setText("BT Frames: " + status.btFrames);
            imuText.setText("IMU: " + (status.imuValid ? "OK" : "Offline"));
        } else if (frame.cmd == BtProtocol.CMD_SENSOR_DATA) {
            SensorData s = BtProtocol.parseSensor(frame);
            sensorText.setText(String.format(Locale.US,
                    "Accel %.2f %.2f %.2f m/s2\nGyro %.2f %.2f %.2f deg/s",
                    s.ax, s.ay, s.az, s.gx, s.gy, s.gz));
        }
    }

    @Override
    public void onError(String text) {
        toast(text);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7) {
            loadBondedDevices();
        }
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, 7);
        }
    }

    private boolean hasBtConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setPadding(0, dp(8), 0, dp(8));
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private String pageName(int page) {
        switch (page) {
            case 0: return "Watch Face";
            case 1: return "MPU";
            case 2: return "Steps";
            case 3: return "Bluetooth";
            case 4: return "Device Info";
            default: return "Unknown";
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
```

- [ ] **Step 2: Run protocol smoke test**

Run:

```powershell
javac -d SmartWatch_AndroidHost\build\protocol-test `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\BtProtocol.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\WatchStatus.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\SensorData.java `
  SmartWatch_AndroidHost\app\src\test\java\com\course\smartwatch\BtProtocolSmokeTest.java
java -cp SmartWatch_AndroidHost\build\protocol-test com.course.smartwatch.BtProtocolSmokeTest
```

Expected:

```text
BtProtocolSmokeTest PASS
```

- [ ] **Step 3: Commit main screen**

Run:

```powershell
git add -- SmartWatch_AndroidHost/app/src/main/java/com/course/smartwatch/MainActivity.java
git commit -m "feat: add android smartwatch host ui"
```

---

## Task 7: Project README and Verification Notes

**Files:**
- Create: `SmartWatch_AndroidHost/README.md`
- Modify: `docs/superpowers/specs/2026-07-05-smartwatch-bluetooth-sync-design.md`

- [ ] **Step 1: Create Android host README**

Create `SmartWatch_AndroidHost/README.md`:

````markdown
# SmartWatch Android Host

This app is the Android upper computer for the STM32F103C8T6 smart watch. It connects to the HC-05 classic Bluetooth module through SPP serial.

## Pairing

1. Power on the watch and HC-05.
2. Open Android Bluetooth settings.
3. Pair with `HC-05`.
4. Use PIN `1234` or `0000`.
5. Open SmartWatch Host and choose the paired HC-05 device.

## Features

- Connect and disconnect HC-05.
- Sync Android phone time to the watch.
- Read watch status: time, page, steps, frame count, IMU state.
- Read MPU6050 acceleration and gyroscope data.
- Switch pages from the phone.
- Return to the watch face.
- Reset step count.

## Protocol

Frames use:

```text
AA CMD LEN DATA... CHK 55
```

`CHK` is XOR of `CMD`, `LEN`, and every data byte.

Commands:

| Command | Direction | Purpose |
| --- | --- | --- |
| `0x01` | Watch -> Android | MPU sensor data |
| `0x02` | Android -> Watch | Sync time |
| `0x03` | Both | ACK |
| `0x04` | Watch -> Android | Watch status |
| `0x10` | Android -> Watch | Set page |
| `0x11` | Android -> Watch | Reset steps |
| `0x12` | Android -> Watch | Request status |

## Build

Open this folder in Android Studio:

```text
SmartWatch_AndroidHost
```

Install the app on an Android phone with Bluetooth enabled.

This repository also contains a desktop protocol smoke test that can run without Android Studio:

```powershell
javac -d SmartWatch_AndroidHost\build\protocol-test `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\BtProtocol.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\WatchStatus.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\SensorData.java `
  SmartWatch_AndroidHost\app\src\test\java\com\course\smartwatch\BtProtocolSmokeTest.java
java -cp SmartWatch_AndroidHost\build\protocol-test com.course.smartwatch.BtProtocolSmokeTest
```
````

- [ ] **Step 2: Update spec if the implementation differed from the design**

If the command ids, payload sizes, or Android project path changed during implementation, edit `docs/superpowers/specs/2026-07-05-smartwatch-bluetooth-sync-design.md` to match the finished implementation. If no field changed, leave the spec untouched.

- [ ] **Step 3: Run firmware build and protocol smoke test**

Run:

```powershell
$env:PATH='C:\Users\Troy\AppData\Local\stm32cube\bundles\gnu-tools-for-stm32\14.3.1+st.2\bin;C:\Users\Troy\AppData\Local\stm32cube\bundles\ninja\1.13.2+st.1\bin;' + $env:PATH
& 'C:\Users\Troy\AppData\Local\stm32cube\bundles\cmake\4.2.3+st.1\bin\cmake.exe' --build --preset Debug --clean-first

javac -d SmartWatch_AndroidHost\build\protocol-test `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\BtProtocol.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\WatchStatus.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\SensorData.java `
  SmartWatch_AndroidHost\app\src\test\java\com\course\smartwatch\BtProtocolSmokeTest.java
java -cp SmartWatch_AndroidHost\build\protocol-test com.course.smartwatch.BtProtocolSmokeTest
```

Expected:

```text
SmartWatch_FreeRTOS.elf is produced
BtProtocolSmokeTest PASS
```

- [ ] **Step 4: Commit docs and final verification notes**

Run:

```powershell
git add -- SmartWatch_AndroidHost/README.md docs/superpowers/specs/2026-07-05-smartwatch-bluetooth-sync-design.md
git commit -m "docs: describe android bluetooth host"
```

---

## Task 8: Hardware Acceptance

**Files:**
- No source changes unless a verified hardware issue appears.

- [ ] **Step 1: Flash firmware**

Flash:

```text
SmartWatch_FreeRTOS/build/Debug/SmartWatch_FreeRTOS.elf
```

Expected on OLED:

```text
Watch face displays normal time
Encoder switches pages
PB1 returns to watch face
MPU page shows live data
Bluetooth page shows connection/frame information
```

- [ ] **Step 2: Pair Android with HC-05**

On Android:

```text
Settings -> Bluetooth -> Pair new device -> HC-05 -> PIN 1234 or 0000
```

Expected:

```text
HC-05 appears in paired devices
HC-05 LED changes to connected pattern after app connects
```

- [ ] **Step 3: Verify Android controls**

Use the app:

```text
Connect HC-05
Tap Sync Time
Tap Next Page
Tap Prev Page
Tap Dial
Tap Reset Steps
Tap Request Status
```

Expected:

```text
Watch time matches Android phone time
OLED page changes when page buttons are tapped
Steps reset to 0 on OLED and Android
Status values refresh after Request Status
MPU values update on Android while connected
```

- [ ] **Step 4: Record final known-good result**

Add a short final note to the user response with:

```text
Firmware build result
Protocol smoke test result
Android Studio build limitation on this PC if Android SDK is still absent
Hardware checks the user confirmed
```
