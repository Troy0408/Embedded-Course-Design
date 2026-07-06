#ifndef __SMARTWATCH_UI_H
#define __SMARTWATCH_UI_H

#include "main.h"
#include "mpu6050.h"
#include <stdint.h>

/* UI Pages */
typedef enum {
    PAGE_WATCH_FACE = 0,
    PAGE_IMU,
    PAGE_STEPS,
    PAGE_BLUETOOTH,
    PAGE_DEVICE_INFO,
    PAGE_MAX
} UIPage_t;

/* Device data (only real hardware: MPU6050) */
typedef struct {
    uint8_t  hour;
    uint8_t  minute;
    uint8_t  second;
    uint16_t year;
    uint8_t  month;
    uint8_t  day;
    uint8_t  weekday;       /* 0=Sun, 1=Mon ... 6=Sat */
    uint8_t  battery_pct;
    int8_t   temp_celsius;  /* from MPU6050 */
    UIPage_t current_page;
    uint32_t uptime_seconds;
    uint32_t steps;
    uint32_t bt_frames;
    float    step_filtered_g;
    float    step_threshold_g;
    uint8_t  mpu_ready_68;
    uint8_t  mpu_ready_69;
    uint8_t  mpu_whoami_68;
    uint8_t  mpu_whoami_69;
    uint8_t  enc_a;
    uint8_t  enc_b;
    uint32_t enc_cw;
    uint32_t enc_ccw;
    uint32_t enc_press;
    uint32_t enc_transitions;
    /* MPU6050 sensor data */
    uint8_t  imu_status;        /* 1 = detected, 0 = not found */
    uint8_t  bt_connected;      /* 1 = phone connected */
    MPU6050_Accel_t accel;
    MPU6050_Gyro_t  gyro;
    MPU6050_Angle_t angle;
} SmartWatchData_t;

/* ==================== UI Framework API ==================== */

void UI_InitData(SmartWatchData_t *data);
void UI_DrawPage(UIPage_t page, SmartWatchData_t *data);
void UI_DrawStatusBar(SmartWatchData_t *data);
void UI_DrawPageIndicator(uint8_t current, uint8_t total);

#endif /* __SMARTWATCH_UI_H */
