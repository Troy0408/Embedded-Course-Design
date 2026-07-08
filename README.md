# 基于 STM32F103C8T6 的智能手表设计

## 项目简介

本项目是嵌入式系统课程设计作品，使用 STM32F103C8T6 最小系统板、SSD1306 OLED、MPU6050、HC-05 蓝牙模块、旋转编码器和独立按键，实现一个可演示的 FreeRTOS 智能手表原型。

系统能够在 OLED 上显示表盘、传感器数据、步数、蓝牙状态和设备信息；通过旋转编码器切换页面，通过 PB1 返回表盘；通过 HC-05 与 Android 手机上位机通信，实现手机同步时间、切换页面、清零步数、请求状态和查看 MPU6050 数据。

## 硬件组成

| 模块 | 型号/说明 | 接口 |
| --- | --- | --- |
| 主控 | STM32F103C8T6 最小系统板 | SWD / GPIO / I2C / USART |
| 显示屏 | SSD1306 OLED | I2C1: PB6 SCL, PB7 SDA |
| 惯性传感器 | MPU6050 | I2C2: PB10 SCL, PB11 SDA |
| 蓝牙 | HC-05 经典蓝牙 SPP | USART2: PA2 TX, PA3 RX, PB0 STATE |
| 输入 | 旋转编码器 | PA6 / PA7 |
| 按键 | 独立按键 | PB1，返回表盘 |

蓝牙串口参数为 `9600 8N1`。HC-05 需要先在 Android 系统蓝牙设置中完成配对，再由 Android App 连接。

## 软件架构

### STM32 固件

固件位于 `SmartWatch_FreeRTOS/`，基于 STM32 HAL、CMake/Ninja 和 FreeRTOS 构建。

主要模块：

- `Core/Src/app_freertos.c`：创建 FreeRTOS 任务，维护共享手表数据。
- `Core/Src/smartwatch_ui.c`：OLED 页面绘制和页面调度。
- `Core/Src/oled.c`：SSD1306 OLED 驱动和绘图基础函数。
- `Core/Src/mpu6050.c`：MPU6050 初始化、检测和数据读取。
- `Core/Src/encoder.c`：旋转编码器输入处理。
- `Core/Src/step_counter.c`：基于加速度的计步逻辑。
- `Core/Src/bluetooth.c`：HC-05 串口协议、帧解析、状态/传感器数据发送。

FreeRTOS 任务划分：

- 时钟任务：维护软件时间。
- 传感器任务：周期读取 MPU6050，更新姿态和计步输入数据。
- 显示任务：根据当前页面刷新 OLED。
- 蓝牙任务：处理手机控制命令，发送状态和传感器数据。

### Android 上位机

Android 工程位于 `SmartWatch_AndroidHost/`，使用 Java 原生 Android 实现经典蓝牙 SPP 客户端。

主要功能：

- 选择已配对 HC-05 设备并连接。
- 显示手表时间、页面、步数、蓝牙帧计数、IMU 状态。
- 显示 MPU6050 加速度和陀螺仪数据。
- 发送同步时间、切换页面、返回表盘、清零步数和请求状态命令。

## 已实现功能

- OLED 表盘显示：状态栏、电量、BT/IMU 状态图标、大号时间、日期。
- OLED 多页面：表盘、IMU、步数、蓝牙、设备信息。
- 旋转编码器翻页。
- PB1 按键返回表盘。
- MPU6050 数据采集、姿态简化显示和 I2C 状态提示。
- 基于 MPU6050 的计步与步数清零。
- FreeRTOS 多任务调度与互斥保护。
- HC-05 蓝牙数据同步。
- Android 上位机控制与状态显示。
- 蓝牙二进制帧协议：`AA CMD LEN DATA... CHK 55`。

## 项目目录说明

```text
.
├── SmartWatch_FreeRTOS/        # STM32 FreeRTOS 固件主工程
├── SmartWatch_AndroidHost/     # Android 蓝牙上位机
├── CourseReport/               # 课程设计实验报告源码、图片和 PDF
├── docs/                       # 设计记录、实现计划、检查报告
├── pictures/                   # 原始实物照片和演示截图
├── 课程报告LaTex模板/           # 个人总结报告 LaTeX 模板
├── 嵌入式系统课程设计.md        # 课程设计要求
├── 项目计划书.md                # 项目计划材料
├── 设计文档提交与汇报.md        # 设计文档/汇报材料
├── README.md                   # 本说明文件
└── .gitignore                  # Git 忽略规则
```

最终课程设计报告 PDF 位于：

```text
CourseReport/
```

## 编译和烧录方法

### STM32 固件

在已安装 STM32CubeCLT/CMake/Ninja 工具链的 Windows 环境中执行：

```powershell
$env:PATH='C:\Users\Troy\AppData\Local\stm32cube\bundles\gnu-tools-for-stm32\14.3.1+st.2\bin;C:\Users\Troy\AppData\Local\stm32cube\bundles\ninja\1.13.2+st.1\bin;' + $env:PATH
cd SmartWatch_FreeRTOS
& 'C:\Users\Troy\AppData\Local\stm32cube\bundles\cmake\4.2.3+st.1\bin\cmake.exe' --preset Debug
& 'C:\Users\Troy\AppData\Local\stm32cube\bundles\cmake\4.2.3+st.1\bin\cmake.exe' --build --preset Debug
```

生成文件：

```text
SmartWatch_FreeRTOS/build/Debug/SmartWatch_FreeRTOS.elf
```

使用 STM32CubeProgrammer 或 ST-LINK 工具将该 ELF 烧录到 STM32F103C8T6。

### Android 上位机

使用 Android Studio 打开：

```text
SmartWatch_AndroidHost
```

连接 Android 手机后安装调试版 App。首次使用前，需要在 Android 系统蓝牙设置中配对 HC-05，配对码为 `1234`。

## 使用说明

1. 按硬件连接表接线，并保证所有模块共地。
2. 烧录 STM32 固件。
3. OLED 表盘正常显示后，旋转编码器切换页面，PB1 返回表盘。
4. Android 系统蓝牙设置中配对 HC-05。
5. 打开 Android 上位机，选择已配对 HC-05，点击连接。
6. 测试同步时间、页面切换、返回表盘、清零步数、请求状态和 MPU 数据显示。

## 课程设计完成情况

| 要求 | 完成情况 |
| --- | --- |
| STM32F103C8T6 智能手表原型 | 已完成 |
| OLED 时间页和传感器页 | 已完成 |
| FreeRTOS 多任务 | 已完成 |
| MPU6050 读取 | 已完成 |
| 旋转编码器菜单/页面切换 | 已完成 |
| HC-05 蓝牙同步 | 已完成 |
| Android 上位机 | 已完成 |
| 计步功能 | 已完成基础版本 |
| PCB 设计 | 教师已确认可不做 PCB，当前使用最小系统板和模块化连线 |
| 课程报告 | 已完成，见 `CourseReport/main.pdf` |

## 后续可改进方向

- 增加真实 RTC 或掉电保持时间。
- 对计步算法做更多实测标定。
- 增加电池电压采样和真实电量估算。
- 优化 Android UI 视觉效果并打包 APK。
- 绘制正式原理图或 PCB。
