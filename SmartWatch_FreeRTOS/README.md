# SmartWatch_FreeRTOS

这是从稳定裸机版 `SmartWatch_Final` 复制出来的 FreeRTOS 迁移版。

`SmartWatch_Final` 保留为可随时演示的稳定版本；本目录用于课设 FreeRTOS 阶段验收。

## 当前硬件接线

硬件接线不变：

```text
OLED SSD1306   PB6/PB7    I2C1
MPU 模块        PB10/PB11  I2C2, address 0x68, WHO_AM_I 0x70 已兼容
HC-05          PA2/PA3    USART2 9600 8N1
HC-05 STATE    PB0
Encoder A/B    PA6/PA7    TIM3 encoder mode
Button          PB1        独立按键，按下接 GND
ST-LINK         PA13/PA14  SWD
```

更详细接线仍可参考 `README_Final.md`。

## FreeRTOS 任务划分

| 任务 | 周期 | 优先级 | 作用 |
| --- | --- | --- | --- |
| `clock` | 1000 ms | 4 | 软件时钟、运行时间 |
| `input` | 10 ms | 3 | 编码器翻页、PB1 回表盘、输入调试计数 |
| `sensor` | 50 ms | 3 | MPU 数据、Pitch/Roll、计步、掉线重试 |
| `display` | 事件触发，最短 100 ms | 2 | OLED 页面刷新 |
| `bt` | 50 ms 处理，500 ms 发送 | 1 | HC-05 接收处理、连接状态、传感器帧发送 |

共享的 `SmartWatchData_t watch_data` 使用 FreeRTOS mutex 保护。显示任务读取快照后再刷新 OLED，避免长时间占用数据锁。

## FreeRTOS 配置

`FreeRTOSConfig.h` 位于：

```text
Core/Inc/FreeRTOSConfig.h
```

关键配置：

```text
configCPU_CLOCK_HZ       SystemCoreClock = 72 MHz
configTICK_RATE_HZ       1000
configTOTAL_HEAP_SIZE    8 KB
configUSE_MUTEXES        1
configCHECK_FOR_STACK_OVERFLOW 2
configUSE_MALLOC_FAILED_HOOK   1
```

FreeRTOS Kernel 已复制到本工程：

```text
Middlewares/Third_Party/FreeRTOS
```

本工程编译 FreeRTOS core、GCC ARM_CM3 port 和 `heap_4.c`。

## 中断说明

`SVC_Handler` 和 `PendSV_Handler` 转交给 FreeRTOS。

`SysTick_Handler` 同时执行：

```text
HAL_IncTick()
xPortSysTickHandler()
```

这样 FreeRTOS 能调度任务，HAL 的 `HAL_GetTick()` 和 I2C/UART 超时也继续正常。

## 构建

在 STM32Cube 工具链路径可用时运行：

```powershell
cmake --preset Debug
cmake --build --preset Debug --clean-first
```

当前验证结果：

```text
RAM:   12880 B / 20 KB  = 62.89%
FLASH: 43644 B / 64 KB  = 66.60%
```

烧录文件：

```text
build/Debug/SmartWatch_FreeRTOS.elf
```

## 上板验证顺序

1. 烧录 `SmartWatch_FreeRTOS.elf`。
2. 上电后应先显示表盘页。
3. 旋转编码器应稳定翻页。
4. 按 PB1 应回到表盘页。
5. MPU 页面应显示姿态数据；Watch 页右上 IMU 状态应正常。
6. Bluetooth 页面应显示 USART2/9600 和发送帧计数。

如果 RTOS 版出现异常，先回烧 `SmartWatch_Final` 确认硬件仍正常，再调 RTOS 任务。
