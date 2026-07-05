#include "app_freertos.h"

#include "bluetooth.h"
#include "encoder.h"
#include "main.h"
#include "mpu6050.h"
#include "smartwatch_ui.h"
#include "step_counter.h"

#include "FreeRTOS.h"
#include "semphr.h"
#include "task.h"

extern SmartWatchData_t watch_data;
extern StepCounter_t step_counter;

static SemaphoreHandle_t data_mutex = NULL;
static SemaphoreHandle_t display_sem = NULL;

static void TaskClock(void *argument);
static void TaskInput(void *argument);
static void TaskSensor(void *argument);
static void TaskDisplay(void *argument);
static void TaskBluetooth(void *argument);

static void Data_Lock(void)
{
    if (data_mutex != NULL) {
        xSemaphoreTake(data_mutex, portMAX_DELAY);
    }
}

static void Data_Unlock(void)
{
    if (data_mutex != NULL) {
        xSemaphoreGive(data_mutex);
    }
}

static void SignalDisplay(void)
{
    if (display_sem != NULL) {
        xSemaphoreGive(display_sem);
    }
}

static void Clock_TickOneSecond(SmartWatchData_t *data)
{
    data->uptime_seconds++;
    data->second++;
    if (data->second >= 60U) {
        data->second = 0;
        data->minute++;
    }
    if (data->minute >= 60U) {
        data->minute = 0;
        data->hour++;
    }
    if (data->hour >= 24U) {
        data->hour = 0;
    }
}

static void ReadMpuDiagnostics(uint8_t *ready_68, uint8_t *ready_69,
                               uint8_t *who_68, uint8_t *who_69)
{
    *ready_68 = MPU6050_IsReadyAt(0x68);
    *ready_69 = MPU6050_IsReadyAt(0x69);
    *who_68 = (*ready_68 != 0U) ? MPU6050_ReadWhoAmIAt(0x68) : 0x00U;
    *who_69 = (*ready_69 != 0U) ? MPU6050_ReadWhoAmIAt(0x69) : 0x00U;
}

static void UpdateEncoderDiagnostics(SmartWatchData_t *data)
{
    EncoderDebug_t debug;
    Encoder_GetDebug(&debug);
    data->enc_a = debug.a;
    data->enc_b = debug.b;
    data->enc_cw = debug.cw_count;
    data->enc_ccw = debug.ccw_count;
    data->enc_press = debug.press_count;
    data->enc_transitions = debug.transition_count;
}

void App_FreeRTOS_Start(void)
{
    data_mutex = xSemaphoreCreateMutex();
    display_sem = xSemaphoreCreateBinary();

    if (data_mutex == NULL || display_sem == NULL) {
        Error_Handler();
    }

    BaseType_t ok = pdPASS;
    ok &= xTaskCreate(TaskClock, "clock", 128, NULL, 4, NULL);
    ok &= xTaskCreate(TaskInput, "input", 192, NULL, 3, NULL);
    ok &= xTaskCreate(TaskSensor, "sensor", 256, NULL, 3, NULL);
    ok &= xTaskCreate(TaskDisplay, "display", 384, NULL, 2, NULL);
    ok &= xTaskCreate(TaskBluetooth, "bt", 256, NULL, 1, NULL);

    if (ok != pdPASS) {
        Error_Handler();
    }

    SignalDisplay();
    vTaskStartScheduler();
    Error_Handler();
}

static void TaskClock(void *argument)
{
    (void)argument;
    TickType_t last_wake = xTaskGetTickCount();

    for (;;) {
        vTaskDelayUntil(&last_wake, pdMS_TO_TICKS(1000));
        Data_Lock();
        Clock_TickOneSecond(&watch_data);
        Data_Unlock();
        SignalDisplay();
    }
}

static void TaskInput(void *argument)
{
    (void)argument;
    TickType_t last_wake = xTaskGetTickCount();

    for (;;) {
        vTaskDelayUntil(&last_wake, pdMS_TO_TICKS(10));

        EncoderEvent_t event = Encoder_Poll();
        uint8_t changed = 0;

        Data_Lock();
        UpdateEncoderDiagnostics(&watch_data);
        if (event == ENCODER_EVENT_CW) {
            watch_data.current_page = (UIPage_t)((watch_data.current_page + 1U) % PAGE_MAX);
            changed = 1U;
        } else if (event == ENCODER_EVENT_CCW) {
            watch_data.current_page = (watch_data.current_page == 0U)
                                    ? (UIPage_t)(PAGE_MAX - 1U)
                                    : (UIPage_t)(watch_data.current_page - 1U);
            changed = 1U;
        } else if (event == ENCODER_EVENT_PRESS) {
            watch_data.current_page = PAGE_WATCH_FACE;
            changed = 1U;
        }
        Data_Unlock();

        if (changed != 0U) {
            SignalDisplay();
        }
    }
}

static void TaskSensor(void *argument)
{
    (void)argument;
    TickType_t last_wake = xTaskGetTickCount();
    TickType_t last_retry = 0;

    for (;;) {
        vTaskDelayUntil(&last_wake, pdMS_TO_TICKS(50));

        Data_Lock();
        uint8_t imu_online = watch_data.imu_status;
        Data_Unlock();

        if (imu_online != 0U) {
            MPU6050_Accel_t accel;
            MPU6050_Gyro_t gyro;
            MPU6050_Angle_t angle;
            float temp = 0.0f;
            int acc_ok = MPU6050_ReadAccel(&accel);
            int gyr_ok = MPU6050_ReadGyro(&gyro);
            temp = MPU6050_ReadTemp();

            Data_Lock();
            if (acc_ok != 0 || gyr_ok != 0 || temp < -100.0f) {
                watch_data.imu_status = 0;
            } else {
                MPU6050_CalcAngle(&accel, &angle);
                StepCounter_Update(&step_counter, accel.ax, accel.ay, accel.az, HAL_GetTick());
                watch_data.accel = accel;
                watch_data.gyro = gyro;
                watch_data.angle = angle;
                watch_data.steps = step_counter.steps;
                watch_data.step_filtered_g = step_counter.filtered_g;
                watch_data.step_threshold_g = step_counter.threshold_g;
                watch_data.temp_celsius = (int8_t)temp;
            }
            Data_Unlock();
            SignalDisplay();
        } else {
            TickType_t now = xTaskGetTickCount();
            if ((now - last_retry) >= pdMS_TO_TICKS(3000)) {
                uint8_t ready_68;
                uint8_t ready_69;
                uint8_t who_68;
                uint8_t who_69;
                ReadMpuDiagnostics(&ready_68, &ready_69, &who_68, &who_69);
                int init_ok = MPU6050_Init();

                Data_Lock();
                watch_data.mpu_ready_68 = ready_68;
                watch_data.mpu_ready_69 = ready_69;
                watch_data.mpu_whoami_68 = who_68;
                watch_data.mpu_whoami_69 = who_69;
                watch_data.imu_status = (init_ok == 0) ? 1U : 0U;
                Data_Unlock();

                last_retry = now;
                SignalDisplay();
            }
        }
    }
}

static void TaskDisplay(void *argument)
{
    (void)argument;
    TickType_t last_draw = 0;

    for (;;) {
        xSemaphoreTake(display_sem, pdMS_TO_TICKS(1000));

        TickType_t now = xTaskGetTickCount();
        TickType_t elapsed = now - last_draw;
        if (elapsed < pdMS_TO_TICKS(100)) {
            vTaskDelay(pdMS_TO_TICKS(100) - elapsed);
        }

        SmartWatchData_t snapshot;
        Data_Lock();
        snapshot = watch_data;
        Data_Unlock();

        UI_DrawPage(snapshot.current_page, &snapshot);
        last_draw = xTaskGetTickCount();
    }
}

static void TaskBluetooth(void *argument)
{
    (void)argument;
    TickType_t last_wake = xTaskGetTickCount();
    TickType_t last_sensor_send = 0;
    TickType_t last_status_send = 0;
    uint8_t status_pending = 0U;

    for (;;) {
        vTaskDelayUntil(&last_wake, pdMS_TO_TICKS(50));

        uint8_t changed = 0;
        uint8_t connected = 0;
        BT_ControlEvent_t event;

        Data_Lock();
        uint8_t event_flags = BT_Process(&watch_data, &event);
        if ((event_flags & BT_EVENT_RESET_STEPS) != 0U) {
            StepCounter_Reset(&step_counter);
            watch_data.steps = 0;
            watch_data.step_filtered_g = step_counter.filtered_g;
            watch_data.step_threshold_g = step_counter.threshold_g;
            changed = 1U;
        }
        if ((event_flags & (BT_EVENT_TIME_SYNC | BT_EVENT_SET_PAGE)) != 0U) {
            changed = 1U;
        }
        if ((event_flags & BT_EVENT_REQUEST_STATUS) != 0U) {
            status_pending = 1U;
        }
        connected = BT_IsConnected();
        if (watch_data.bt_connected != connected) {
            watch_data.bt_connected = connected;
            changed = 1U;
            if (connected != 0U) {
                status_pending = 1U;
            }
        }
        if (connected == 0U) {
            status_pending = 0U;
        }
        Data_Unlock();

        TickType_t now = xTaskGetTickCount();

        if (connected != 0U) {
            if (status_pending != 0U || (now - last_status_send) >= pdMS_TO_TICKS(1000)) {
                SmartWatchData_t snapshot;

                Data_Lock();
                snapshot = watch_data;
                Data_Unlock();

                if (BT_SendStatus(&snapshot) != 0U) {
                    Data_Lock();
                    watch_data.bt_frames++;
                    Data_Unlock();
                    status_pending = 0U;
                    last_status_send = now;
                    changed = 1U;
                } else {
                    status_pending = 1U;
                }
            }
        }

        if (connected != 0U && (now - last_sensor_send) >= pdMS_TO_TICKS(500)) {
            SmartWatchData_t snapshot;
            uint8_t send_frame = 0;

            Data_Lock();
            snapshot = watch_data;
            send_frame = watch_data.imu_status;
            Data_Unlock();

            if (send_frame != 0U) {
                if (BT_SendSensorData(&snapshot) != 0U) {
                    Data_Lock();
                    watch_data.bt_frames++;
                    Data_Unlock();
                    changed = 1U;
                }
            }

            last_sensor_send = now;
        }

        if (changed != 0U) {
            SignalDisplay();
        }
    }
}

void vApplicationMallocFailedHook(void)
{
    Error_Handler();
}

void vApplicationStackOverflowHook(TaskHandle_t task, char *task_name)
{
    (void)task;
    (void)task_name;
    Error_Handler();
}
