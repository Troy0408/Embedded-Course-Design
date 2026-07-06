# SmartWatch FreeRTOS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `SmartWatch_FreeRTOS` as a FreeRTOS migration of the stable `SmartWatch_Final` firmware while preserving the working hardware pinout.

**Architecture:** Copy the stable final project, add official STM32Cube F1 FreeRTOS Kernel sources, and split the old super-loop into five FreeRTOS tasks. Shared watch state stays in one `SmartWatchData_t` instance protected by a mutex; task-to-display refresh uses a binary semaphore.

**Tech Stack:** STM32F103C8T6, STM32 HAL, CMake/Ninja, FreeRTOS Kernel from `STM32Cube_FW_F1_V1.8.7`, SSD1306 OLED, MPU-compatible IMU at `0x68/WHO=0x70`, HC-05 on USART2, TIM3 encoder mode on PA6/PA7, PB1 button.

---

### Task 1: Project Copy And Naming

**Files:**
- Create folder: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS`
- Modify: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\CMakeLists.txt`
- Modify: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\CMakePresets.json`
- Rename: `SmartWatch_Final.ioc` to `SmartWatch_FreeRTOS.ioc`

- [ ] Copy `SmartWatch_Final` to `SmartWatch_FreeRTOS`, excluding `build`.
- [ ] Replace project name `SmartWatch_Final` with `SmartWatch_FreeRTOS`.
- [ ] Keep the proven pins: OLED PB6/PB7, MPU PB10/PB11, HC-05 PA2/PA3, encoder PA6/PA7, PB1 button.
- [ ] Build once before RTOS changes.

### Task 2: Add FreeRTOS Kernel

**Files:**
- Copy: `C:\Users\Troy\STM32Cube\Repository\STM32Cube_FW_F1_V1.8.7\Middlewares\Third_Party\FreeRTOS`
- Create: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\Core\Inc\FreeRTOSConfig.h`
- Modify: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\cmake\stm32cubemx\CMakeLists.txt`

- [ ] Copy the official FreeRTOS middleware into the new project.
- [ ] Add `FreeRTOSConfig.h` for Cortex-M3, 72 MHz CPU, 1 kHz tick, 8 KB heap, mutexes, binary semaphores, stack overflow hook, malloc failed hook.
- [ ] Add FreeRTOS sources to CMake: `tasks.c`, `queue.c`, `list.c`, `timers.c`, `event_groups.c`, `stream_buffer.c`, `portable/GCC/ARM_CM3/port.c`, `portable/MemMang/heap_4.c`.
- [ ] Add FreeRTOS include directories.

### Task 3: RTOS Application Layer

**Files:**
- Create: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\Core\Inc\app_freertos.h`
- Create: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\Core\Src\app_freertos.c`
- Modify: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\CMakeLists.txt`

- [ ] Move the old `while(1)` behavior into tasks:
  - `TaskClock`: 1000 ms software clock.
  - `TaskInput`: 10 ms encoder/PB1 polling and page changes.
  - `TaskSensor`: 50 ms MPU read, angle calculation, step counting, retry every 3000 ms when offline.
  - `TaskDisplay`: wait for display semaphore, rate-limit OLED refresh to about 100 ms.
  - `TaskBluetooth`: 50 ms BT receive/connection processing, 500 ms sensor frame send.
- [ ] Protect `SmartWatchData_t` with a mutex whenever tasks read/write shared fields.
- [ ] Use the existing TIM3 encoder code unchanged.

### Task 4: Main And Interrupt Integration

**Files:**
- Modify: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\Core\Src\main.c`
- Modify: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\Core\Src\stm32f1xx_it.c`
- Modify: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\Core\Inc\stm32f1xx_it.h`

- [ ] Keep hardware init in `main.c`: HAL, clocks, GPIO, DMA, I2C1, I2C2, USART2, OLED, MPU diagnostic init, Bluetooth, encoder, step counter.
- [ ] Call `App_FreeRTOS_Start()` after the first watch face draw.
- [ ] Remove the old super-loop behavior from `main.c`.
- [ ] Route `SVC_Handler`, `PendSV_Handler`, and `SysTick_Handler` to FreeRTOS; keep `HAL_IncTick()` in SysTick so HAL timeouts still work.

### Task 5: Docs And Verification

**Files:**
- Create or modify: `C:\Users\Troy\Documents\Embedded Course Design\SmartWatch_FreeRTOS\README.md`

- [ ] Document that this is the RTOS migration of `SmartWatch_Final`.
- [ ] Document task periods and the unchanged hardware pinout.
- [ ] Build with the STM32Cube CMake preset.
- [ ] Report RAM/Flash usage and the generated ELF path.
