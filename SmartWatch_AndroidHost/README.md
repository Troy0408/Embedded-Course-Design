# SmartWatch Android Host

Android upper computer app for the STM32F103C8T6 smart watch. The app connects to the watch through an HC-05 classic Bluetooth SPP serial link, receives watch telemetry, and sends control frames back to the firmware.

## Pairing

1. Power on the watch and HC-05 module.
2. Open Android system Bluetooth settings.
3. Pair with the HC-05 device.
4. Use PIN `1234` or `0000` if Android asks for a pairing code.
5. Open the app and choose the paired HC-05 device.

## Features

- Connect and disconnect the HC-05.
- Sync Android phone time to the watch.
- Read watch status: time, page, steps, frame count, and IMU state.
- Read MPU6050 acceleration and gyroscope data.
- Switch watch pages from the phone.
- Return to the watch face.
- Reset the step count.

## Protocol

Frames use this binary format over the HC-05 serial stream:

```text
AA CMD LEN DATA... CHK 55
```

- `AA`: start byte.
- `CMD`: command id.
- `LEN`: payload length in bytes.
- `DATA`: payload bytes.
- `CHK`: XOR of `CMD`, `LEN`, and every data byte.
- `55`: end byte.

| Command | Direction | Description |
| --- | --- | --- |
| `0x01` | Watch -> Android | MPU sensor data |
| `0x02` | Android -> Watch | Sync time |
| `0x03` | Both | ACK |
| `0x04` | Watch -> Android | Watch status |
| `0x10` | Android -> Watch | Set page |
| `0x11` | Android -> Watch | Reset steps |
| `0x12` | Android -> Watch | Request status |

## Build And Install

Open `SmartWatch_AndroidHost` in Android Studio, then install it on an Android phone with Bluetooth enabled.

This local PC currently has no Gradle, Android SDK, or `adb` detected, so APK build must be done in Android Studio or after installing Android SDK and Gradle.

## Desktop Protocol Smoke Test

The protocol parser and frame builders can be checked on the desktop with `javac`:

```powershell
javac -d SmartWatch_AndroidHost\build\protocol-test `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\BtProtocol.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\WatchStatus.java `
  SmartWatch_AndroidHost\app\src\main\java\com\course\smartwatch\SensorData.java `
  SmartWatch_AndroidHost\app\src\test\java\com\course\smartwatch\BtProtocolSmokeTest.java
java -cp SmartWatch_AndroidHost\build\protocol-test com.course.smartwatch.BtProtocolSmokeTest
```

Expected output:

```text
BtProtocolSmokeTest PASS
```

## Hardware Acceptance Checklist

- Flash `SmartWatch_FreeRTOS/build/Debug/SmartWatch_FreeRTOS.elf`.
- Confirm the OLED shows the watch face.
- Confirm the encoder switches pages and PB1 returns to the watch face.
- Confirm the MPU page shows data.
- Confirm the Bluetooth page/status frame count changes.
- Confirm the Android app connects, syncs time, changes page, resets steps, requests status, and displays MPU values.
