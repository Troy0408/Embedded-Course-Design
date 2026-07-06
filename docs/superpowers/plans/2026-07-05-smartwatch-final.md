# SmartWatch Final Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a standalone `SmartWatch_Final` STM32F103C8T6 project from the working Phase3 reference and adapt it for OLED, MPU6050, HC-05, rotary encoder A/B, an independent PB1 button, page switching, and step counting.

**Architecture:** Use `STM32SmartWatch/Phase3_Bluetooth` as the stable base because it already contains validated OLED, MPU6050, USART2 Bluetooth, DMA, and UI code. Add small focused modules for encoder input and step counting, then update the UI and main loop so the project is a complete bare-metal bring-up target with a clear CubeMX checklist for the FreeRTOS migration required by the course.

**Tech Stack:** STM32F103C8T6, STM32 HAL, CMake STM32CubeMX layout, SSD1306 I2C OLED, MPU6050 I2C, HC-05 on USART2, TIM3 encoder mode, PB1 GPIO input with pull-up.

---

### File Structure

- Create project folder: `C:/Users/Troy/Documents/Embedded Course Design/SmartWatch_Final`
- Base copy: `STM32SmartWatch/Phase3_Bluetooth/*` into `SmartWatch_Final/`
- Modify: `SmartWatch_Final/CMakeLists.txt` to rename the project and include new modules plus `m`
- Modify: `SmartWatch_Final/Phase3_Bluetooth.ioc` and rename to `SmartWatch_Final/SmartWatch_Final.ioc`
- Modify: `SmartWatch_Final/Core/Src/main.c` to add encoder, button, page switching, step counter, and faster sensor loop
- Modify: `SmartWatch_Final/Core/Inc/smartwatch_ui.h` and `SmartWatch_Final/Core/Src/smartwatch_ui.c` to add steps, frame counter, final wiring page, and step page
- Create: `SmartWatch_Final/Core/Inc/encoder.h`
- Create: `SmartWatch_Final/Core/Src/encoder.c`
- Create: `SmartWatch_Final/Core/Inc/step_counter.h`
- Create: `SmartWatch_Final/Core/Src/step_counter.c`
- Create: `SmartWatch_Final/README_Final.md` with exact wiring, bring-up order, HC-05 settings, and FreeRTOS migration checklist

### Task 1: Copy Phase3 Into Final Project

**Files:**
- Create: `SmartWatch_Final/`
- Copy from: `STM32SmartWatch/Phase3_Bluetooth/`

- [ ] **Step 1: Verify target does not already contain a final project**

Run: `Test-Path 'SmartWatch_Final'`
Expected: `False`, or remove only if it was created by this implementation run.

- [ ] **Step 2: Copy Phase3 project**

Run: `Copy-Item -Recurse -LiteralPath 'STM32SmartWatch\Phase3_Bluetooth' -Destination 'SmartWatch_Final'`
Expected: `SmartWatch_Final/Core/Src/main.c` exists.

- [ ] **Step 3: Rename IOC file**

Run: `Rename-Item 'SmartWatch_Final\Phase3_Bluetooth.ioc' 'SmartWatch_Final.ioc'`
Expected: `SmartWatch_Final/SmartWatch_Final.ioc` exists.

### Task 2: Add Encoder And Button Module

**Files:**
- Create: `SmartWatch_Final/Core/Inc/encoder.h`
- Create: `SmartWatch_Final/Core/Src/encoder.c`

- [ ] **Step 1: Add `encoder.h`**

```c
#ifndef __ENCODER_H
#define __ENCODER_H

#include "main.h"
#include <stdint.h>

typedef enum {
    ENCODER_EVENT_NONE = 0,
    ENCODER_EVENT_CW,
    ENCODER_EVENT_CCW,
    ENCODER_EVENT_PRESS
} EncoderEvent_t;

void Encoder_Init(void);
EncoderEvent_t Encoder_Poll(void);

#endif /* __ENCODER_H */
```

- [ ] **Step 2: Add `encoder.c`**

```c
#include "encoder.h"

static uint8_t last_ab = 0;
static uint32_t last_button_tick = 0;

static uint8_t Encoder_ReadAB(void)
{
    uint8_t a = (HAL_GPIO_ReadPin(GPIOA, GPIO_PIN_6) == GPIO_PIN_SET) ? 1U : 0U;
    uint8_t b = (HAL_GPIO_ReadPin(GPIOA, GPIO_PIN_7) == GPIO_PIN_SET) ? 1U : 0U;
    return (uint8_t)((a << 1) | b);
}

void Encoder_Init(void)
{
    last_ab = Encoder_ReadAB();
    last_button_tick = HAL_GetTick();
}

EncoderEvent_t Encoder_Poll(void)
{
    static const int8_t table[16] = {
         0, -1,  1,  0,
         1,  0,  0, -1,
        -1,  0,  0,  1,
         0,  1, -1,  0
    };
    static int8_t accum = 0;

    uint32_t now = HAL_GetTick();
    if (HAL_GPIO_ReadPin(GPIOB, GPIO_PIN_1) == GPIO_PIN_RESET) {
        if (now - last_button_tick > 250U) {
            last_button_tick = now;
            return ENCODER_EVENT_PRESS;
        }
    }

    uint8_t ab = Encoder_ReadAB();
    if (ab != last_ab) {
        uint8_t idx = (uint8_t)((last_ab << 2) | ab);
        accum += table[idx & 0x0F];
        last_ab = ab;

        if (accum >= 4) {
            accum = 0;
            return ENCODER_EVENT_CW;
        }
        if (accum <= -4) {
            accum = 0;
            return ENCODER_EVENT_CCW;
        }
    }

    return ENCODER_EVENT_NONE;
}
```

### Task 3: Add Step Counter Module

**Files:**
- Create: `SmartWatch_Final/Core/Inc/step_counter.h`
- Create: `SmartWatch_Final/Core/Src/step_counter.c`

- [ ] **Step 1: Add `step_counter.h`**

```c
#ifndef __STEP_COUNTER_H
#define __STEP_COUNTER_H

#include <stdint.h>

typedef struct {
    uint32_t steps;
    float filtered_g;
    float threshold_g;
    float window_min_g;
    float window_max_g;
    uint8_t sample_count;
    uint8_t above_threshold;
    uint32_t last_step_tick;
} StepCounter_t;

void StepCounter_Init(StepCounter_t *counter);
void StepCounter_Update(StepCounter_t *counter, float ax_ms2, float ay_ms2, float az_ms2, uint32_t now_ms);

#endif /* __STEP_COUNTER_H */
```

- [ ] **Step 2: Add `step_counter.c`**

```c
#include "step_counter.h"
#include <math.h>

void StepCounter_Init(StepCounter_t *counter)
{
    counter->steps = 0;
    counter->filtered_g = 1.0f;
    counter->threshold_g = 1.12f;
    counter->window_min_g = 2.0f;
    counter->window_max_g = 0.0f;
    counter->sample_count = 0;
    counter->above_threshold = 0;
    counter->last_step_tick = 0;
}

void StepCounter_Update(StepCounter_t *counter, float ax_ms2, float ay_ms2, float az_ms2, uint32_t now_ms)
{
    float mag_g = sqrtf(ax_ms2 * ax_ms2 + ay_ms2 * ay_ms2 + az_ms2 * az_ms2) / 9.81f;
    counter->filtered_g = 0.15f * mag_g + 0.85f * counter->filtered_g;

    if (counter->filtered_g < counter->window_min_g) counter->window_min_g = counter->filtered_g;
    if (counter->filtered_g > counter->window_max_g) counter->window_max_g = counter->filtered_g;

    counter->sample_count++;
    if (counter->sample_count >= 50U) {
        float dynamic = (counter->window_min_g + counter->window_max_g) * 0.5f;
        counter->threshold_g = dynamic > 1.08f ? dynamic : 1.08f;
        counter->window_min_g = 2.0f;
        counter->window_max_g = 0.0f;
        counter->sample_count = 0;
    }

    if (!counter->above_threshold && counter->filtered_g > counter->threshold_g) {
        if (now_ms - counter->last_step_tick > 250U) {
            counter->steps++;
            counter->last_step_tick = now_ms;
        }
        counter->above_threshold = 1;
    } else if (counter->filtered_g < counter->threshold_g - 0.05f) {
        counter->above_threshold = 0;
    }
}
```

### Task 4: Update UI Data And Pages

**Files:**
- Modify: `SmartWatch_Final/Core/Inc/smartwatch_ui.h`
- Modify: `SmartWatch_Final/Core/Src/smartwatch_ui.c`

- [ ] **Step 1: Add `PAGE_STEPS`, `steps`, `bt_frames`, and `current_page` to UI types**

Expected result: UI pages include watch face, IMU, steps, Bluetooth, and device info. Data contains `steps`, `step_filtered_g`, `step_threshold_g`, `bt_frames`, and `current_page`.

- [ ] **Step 2: Add step page rendering**

Expected result: OLED page shows `Today`, `Dist`, `Cal`, `Acc`, and `Thr`.

- [ ] **Step 3: Update Bluetooth and device pages**

Expected result: Bluetooth page shows `Frames`; device page shows final wiring with `BTN: PB1`.

### Task 5: Update Main Loop

**Files:**
- Modify: `SmartWatch_Final/Core/Src/main.c`

- [ ] **Step 1: Include `encoder.h` and `step_counter.h`**

Expected result: main has encoder and step counter module access.

- [ ] **Step 2: Initialize encoder and step counter**

Expected result: after `BT_Init()`, call `Encoder_Init()` and `StepCounter_Init(&step_counter)`.

- [ ] **Step 3: Poll encoder every loop**

Expected result: clockwise moves to next page, counter-clockwise moves to previous page, independent PB1 button returns to watch face.

- [ ] **Step 4: Read MPU6050 every 50 ms**

Expected result: sensor page refresh is smooth and step counter has a 20 Hz sample rate.

- [ ] **Step 5: Send Bluetooth frame every 500 ms**

Expected result: `bt_frames` increments after each send, and Bluetooth page can show activity.

### Task 6: Update CubeMX Metadata And Build Files

**Files:**
- Modify: `SmartWatch_Final/CMakeLists.txt`
- Modify: `SmartWatch_Final/SmartWatch_Final.ioc`
- Create: `SmartWatch_Final/README_Final.md`

- [ ] **Step 1: Rename CMake project**

Expected result: `CMAKE_PROJECT_NAME` is `SmartWatch_Final`.

- [ ] **Step 2: Add sources and math library**

Expected result: `encoder.c`, `step_counter.c`, and `m` are linked.

- [ ] **Step 3: Update `.ioc` with final pins**

Expected result: project uses `I2C1 PB6/PB7`, `I2C2 PB10/PB11`, `USART2 PA2/PA3`, `PB0 HC05_STATE`, `PA6/PA7 encoder`, and `PB1` button.

- [ ] **Step 4: Add final README**

Expected result: README contains exact wiring, bring-up order, and FreeRTOS migration settings.

### Task 7: Verify

**Files:**
- Inspect generated project files.

- [ ] **Step 1: Search for conflicting pins**

Run: `rg -n "USART1|PA9|PA10|PB10.*EXTI|OLED \\+ MPU6050" SmartWatch_Final`
Expected: no final configuration instructs using USART1, PA9/PA10, or PB10 as the encoder button.

- [ ] **Step 2: Check expected modules exist**

Run: `Test-Path SmartWatch_Final\Core\Src\encoder.c; Test-Path SmartWatch_Final\Core\Src\step_counter.c; Test-Path SmartWatch_Final\README_Final.md`
Expected: all return `True`.

- [ ] **Step 3: Check CMake source registration**

Run: `rg -n "encoder.c|step_counter.c|SmartWatch_Final|target_link_libraries" SmartWatch_Final\CMakeLists.txt`
Expected: both new modules and final project name are present.

