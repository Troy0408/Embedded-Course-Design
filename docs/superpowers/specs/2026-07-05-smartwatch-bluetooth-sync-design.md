# STM32 Smart Watch Bluetooth Sync Design

## Goal

Add Android phone control and data sync for the existing STM32F103C8T6 smart watch using the installed HC-05 classic Bluetooth serial module.

The current FreeRTOS firmware already displays the watch UI, reads MPU data, handles the rotary encoder, and has a UART Bluetooth task with a binary frame protocol. This design extends that protocol and adds a native Android host app.

## Hardware Assumptions

- Bluetooth module: standard HC-05 classic Bluetooth SPP module.
- Phone: Android.
- STM32 UART: USART2.
- HC-05 wiring remains unchanged:
  - STM32 PA2 TX -> HC-05 RXD
  - STM32 PA3 RX <- HC-05 TXD
  - STM32 PB0 <- HC-05 STATE
  - Power and ground as already connected.
- UART configuration remains 38400 baud, 8 data bits, no parity, 1 stop bit.

## Scope

In scope:

- Extend the firmware Bluetooth protocol.
- Allow the Android app to sync time to the watch.
- Allow the Android app to control the displayed page.
- Allow the Android app to reset the step count.
- Send live watch status and MPU data to the Android app.
- Create a native Android project under `SmartWatch_AndroidHost`.
- Add a short protocol note so the project can be explained during course defense.

Out of scope:

- iPhone support.
- BLE support.
- Cloud sync.
- Account login.
- Firmware OTA update.
- Replacing HC-05 with another Bluetooth module.

## Existing Protocol

The current firmware uses a compact binary frame:

```text
AA CMD LEN DATA... CHK 55
```

- `AA`: start byte.
- `CMD`: command id.
- `LEN`: payload length in bytes.
- `DATA`: payload.
- `CHK`: XOR checksum over `CMD`, `LEN`, and every payload byte.
- `55`: end byte.

Existing commands:

- `0x01`: watch sends MPU sensor data.
- `0x02`: phone or host sends time sync.
- `0x03`: ACK.

This frame format should be kept because it is already implemented and works over a UART stream.

## Extended Commands

The extended protocol keeps existing command ids and adds control/status commands:

| Command | Direction | Payload | Purpose |
| --- | --- | --- | --- |
| `0x01` | Watch -> Android | 6 floats, 24 bytes | MPU accel and gyro data. |
| `0x02` | Android -> Watch | 7 bytes | Sync time: hour, minute, second, year offset from 2000, month, day, weekday. |
| `0x03` | Both | 2 bytes | ACK: acknowledged command id and status code. |
| `0x04` | Watch -> Android | fixed status payload | Current time, page, steps, Bluetooth frame count, IMU status, connection status. |
| `0x10` | Android -> Watch | 1 byte | Set current page. |
| `0x11` | Android -> Watch | 0 bytes | Reset step count. |
| `0x12` | Android -> Watch | 0 bytes | Request immediate status frame. |

Status codes for ACK:

- `0x00`: OK.
- `0x01`: invalid length.
- `0x02`: invalid value.
- `0x03`: command unsupported.

Page values for `0x10` and the status payload:

| Value | Page |
| --- | --- |
| `0` | Watch face |
| `1` | MPU |
| `2` | Steps |
| `3` | Bluetooth |
| `4` | Device info |

## Status Payload

`0x04` status payload layout:

| Offset | Type | Field |
| --- | --- | --- |
| 0 | `uint8_t` | hour |
| 1 | `uint8_t` | minute |
| 2 | `uint8_t` | second |
| 3 | `uint8_t` | year offset from 2000 |
| 4 | `uint8_t` | month |
| 5 | `uint8_t` | day |
| 6 | `uint8_t` | weekday |
| 7 | `uint8_t` | current page |
| 8 | `uint16_t` little-endian | steps |
| 10 | `uint16_t` little-endian | Bluetooth frame count |
| 12 | `uint8_t` | IMU status, 1 means valid |
| 13 | `uint8_t` | Bluetooth connection status, 1 means connected |

Total length: 14 bytes.

## Firmware Design

Firmware changes stay inside the `SmartWatch_FreeRTOS` project.

Bluetooth driver responsibilities:

- Keep the existing UART DMA receive and transmit path.
- Continue parsing the `AA ... 55` binary frame.
- Add parsing for `0x10`, `0x11`, and `0x12`.
- Provide small control events to the FreeRTOS Bluetooth task instead of directly spreading UI logic through the driver.
- Add builders for ACK and status frames.

Application task responsibilities:

- `TaskBluetooth` continues to call `BT_Process` every 50 ms.
- Every 500 ms, if connected, the task continues sending MPU data.
- Every 1000 ms, if connected, the task sends a status frame.
- When a control command arrives:
  - time sync updates `watch_data.time`;
  - set page updates `watch_data.current_page` and wakes the display task;
  - reset steps clears the step counter and displayed step value;
  - request status sends a status frame immediately.

The display, encoder, MPU, and clock tasks keep their current responsibilities.

## Android Host Design

Create a native Android app project:

```text
SmartWatch_AndroidHost/
```

Recommended app style:

- Java native Android.
- Classic Bluetooth SPP connection.
- RFCOMM UUID: `00001101-0000-1000-8000-00805F9B34FB`.
- Pair HC-05 in Android system Bluetooth settings first, then connect from the app.

Main screen:

- Connection area:
  - paired device selector;
  - connect button;
  - disconnect button;
  - connection state text.
- Watch status area:
  - watch time;
  - current page;
  - steps;
  - Bluetooth frame count;
  - IMU valid state.
- Sensor area:
  - accelerometer X/Y/Z;
  - gyroscope X/Y/Z.
- Control area:
  - sync phone time;
  - previous page;
  - next page;
  - return to dial page;
  - reset steps;
  - request status.

Android permissions:

- On Android 12 and newer, request `BLUETOOTH_CONNECT` at runtime.
- On older Android versions, declare classic Bluetooth permissions.

The first version lists already paired devices only and does not scan. This keeps the permissions smaller and is more reliable for HC-05.

## Data Flow

Normal telemetry:

```text
STM32 MPU task -> watch_data -> Bluetooth task -> HC-05 -> Android parser -> UI
```

Phone control:

```text
Android button -> protocol frame -> HC-05 -> USART2 -> firmware parser -> BT control event -> watch_data/display/step counter
```

Time sync:

```text
Android current time -> 0x02 frame -> firmware time update -> status frame confirms new time
```

## Error Handling

Firmware:

- Drop frames with invalid start byte, end byte, length, or checksum.
- Reply with ACK error for known commands with invalid payload.
- Ignore unsupported commands after sending unsupported ACK when possible.
- Avoid blocking inside the Bluetooth task; UART transmit should remain short and bounded.

Android:

- Show disconnected state when socket read/write fails.
- Keep the UI responsive by doing Bluetooth I/O on a background thread.
- Reconnect only by explicit user action in the first version.
- Ignore malformed frames and continue parsing subsequent bytes.

## Testing Plan

Firmware tests:

- Build `SmartWatch_FreeRTOS`.
- Confirm existing OLED display, encoder, button, MPU, and time display still work.
- Confirm HC-05 page shows frame count increasing.
- Send test frames from Android or a serial Bluetooth terminal:
  - time sync changes the watch time;
  - set page changes the OLED page;
  - reset steps clears steps;
  - request status produces a status frame.

Android tests:

- Pair with HC-05 in Android settings.
- Connect from the app.
- Confirm live MPU data updates.
- Press sync time and verify the watch dial.
- Press page controls and verify OLED page changes.
- Press reset steps and verify both app and OLED state.
- Disconnect and reconnect without restarting the app.

## Course Defense Talking Points

- HC-05 provides a transparent UART bridge, so the Bluetooth protocol is designed as a UART-safe binary frame.
- The checksum and frame delimiters allow the receiver to recover from noisy or partial serial data.
- FreeRTOS separates display, input, sensor, clock, and Bluetooth responsibilities.
- The Android app is the upper computer: it receives sensor/status data and sends control commands back to the embedded device.
