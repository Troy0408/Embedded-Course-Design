# SmartWatch_FreeRTOS 接线说明

这是从 `STM32SmartWatch/Phase3_Bluetooth` 复制并改造出的最终课设工程。当前版本优先保证实物可演示：

- OLED SSD1306 显示
- MPU6050 姿态数据
- HC-05 蓝牙数据发送
- 旋转编码器切换页面
- 独立按钮回到表盘页
- MPU6050 简单计步

## 1. 最终接线

所有模块必须共地。

### OLED SSD1306

| OLED | STM32F103C8T6 |
| --- | --- |
| VCC | 3.3V |
| GND | GND |
| SCL | PB6 |
| SDA | PB7 |

### MPU6050

| MPU6050 | STM32F103C8T6 |
| --- | --- |
| VCC | 3.3V |
| GND | GND |
| SCL | PB10 |
| SDA | PB11 |

### HC-05

| HC-05 | STM32F103C8T6 |
| --- | --- |
| VCC | 5V |
| GND | GND |
| RXD | PA2 |
| TXD | PA3 |
| STATE | PB0 |

HC-05 数据模式必须和 STM32 一致：

```text
9600 8N1
```

### 旋转编码器和独立按钮

| 输入 | STM32F103C8T6 |
| --- | --- |
| 编码器 CLK / A | PA6 |
| 编码器 DT / B | PA7 |
| 独立按钮一端 | PB1 |
| 独立按钮另一端 | GND |
| 编码器 VCC | 3.3V |
| 编码器 GND | GND |

PB1 使用内部上拉，按下时接地触发。

### ST-LINK

| ST-LINK | STM32F103C8T6 |
| --- | --- |
| SWDIO | PA13 |
| SWCLK | PA14 |
| GND | GND |
| 3.3V / VTref | 3.3V |

BOOT0 保持低电平。

## 2. 第一次上电验收顺序

1. 只接 OLED、ST-LINK 和供电，烧录工程。
2. OLED 应显示表盘页。
3. 接上 MPU6050，复位后右上角 IMU 状态点应变为实心，IMU 页能看到数据。
4. 旋转编码器应能切换页面：
   - Watch
   - IMU
   - Steps
   - Bluetooth
   - Device Info
5. 按 PB1 独立按钮，应回到表盘页。
6. 接 HC-05，蓝牙页应显示 `USART2 9600` 和帧计数。

## 3. 页面说明

| 页面 | 内容 |
| --- | --- |
| Watch | 时间、Pitch/Roll、步数 |
| IMU | 加速度、角速度、Pitch/Roll、温度 |
| Steps | 今日步数、估算距离、估算热量、计步阈值 |
| Bluetooth | HC-05 状态、USART2 引脚、发送帧计数 |
| Device Info | 最终硬件接线摘要 |

## 4. CubeMX 配置摘要

当前 `SmartWatch_FreeRTOS.ioc` 保留了实物验收版本的关键外设，引脚如下：

```text
RCC: HSE 8MHz, PLL x9, SYSCLK 72MHz
SYS: Serial Wire
I2C1: PB6/PB7, OLED SSD1306
I2C2: PB10/PB11, MPU6050
USART2: PA2/PA3, 9600 8N1, HC-05
DMA1 Channel7: USART2_TX
DMA1 Channel6: USART2_RX Circular
PB0: HC-05 STATE, input pull-down
PA6: Encoder A / TIM3_CH1, input pull-up
PA7: Encoder B / TIM3_CH2, input pull-up
PB1: Independent button, input pull-up
```

不要把编码器按钮接到 PB10。PB10 已经是 MPU6050 的 I2C2_SCL。

## 5. 后续 FreeRTOS 迁移设置

课程基本要求需要 FreeRTOS。建议先用当前工程完成裸机演示，再迁移 RTOS。

CubeMX 中增加：

```text
SYS Timebase Source: TIM1
Middleware -> FreeRTOS: CMSIS_V1
TOTAL_HEAP_SIZE: 8192
TICK_RATE_HZ: 1000
Memory management: heap_4
```

建议任务：

| 任务 | 周期 | 功能 |
| --- | --- | --- |
| taskTime | 1000 ms | 软件时间 |
| taskSensor | 50 ms | MPU6050 读取和计步 |
| taskDisplay | 100 ms | OLED 刷新 |
| taskInput | 20 ms | 编码器和 PB1 按钮 |
| taskBluetooth | 500 ms | 蓝牙发送 |

迁移时保持当前引脚不变：

```text
OLED: PB6/PB7
MPU6050: PB10/PB11
HC-05: PA2/PA3
Encoder: PA6/PA7, TIM3 encoder mode
Button: PB1
```

## 6. 常见问题

| 现象 | 先检查 |
| --- | --- |
| OLED 不亮 | VCC/GND、PB6/PB7 是否接反、地址是否为 0x3C |
| MPU6050 NOT FOUND | PB10/PB11、3.3V、GND、AD0 是否接地 |
| 蓝牙乱码 | HC-05 数据模式和 STM32 是否都是 9600 |
| 蓝牙无数据 | PA2 接 RXD，PA3 接 TXD，TX/RX 必须交叉 |
| 编码器乱跳 | PA6/PA7 是否接反，必要时 A/B 到 GND 加 100nF 电容 |
| PB1 按钮无反应 | 按钮是否一端接 PB1、另一端接 GND |
