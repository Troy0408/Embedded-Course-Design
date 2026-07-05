#ifndef __BLUETOOTH_H
#define __BLUETOOTH_H

#include "main.h"
#include "smartwatch_ui.h"
#include <stdint.h>

/* HC-05 frame constants */
#define BT_STX          0xAA
#define BT_ETX          0x55
#define BT_RX_BUF_SIZE  256
#define BT_TX_BUF_SIZE  256

/* HC-05 STATE pin (PB0): HIGH = connected, LOW = disconnected */
#define BT_STATE_PORT   GPIOB
#define BT_STATE_PIN    GPIO_PIN_0

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

/* Time sync data structure */
typedef struct {
    uint8_t  hour;
    uint8_t  minute;
    uint8_t  second;
    uint16_t year;
    uint8_t  month;
    uint8_t  day;
    uint8_t  weekday;
} BT_TimeSync_t;

typedef struct {
    uint8_t flags;
    BT_TimeSync_t time;
    UIPage_t page;
} BT_ControlEvent_t;

void BT_Init(void);
uint8_t BT_SendSensorData(const SmartWatchData_t *data);
uint8_t BT_SendStatus(const SmartWatchData_t *data);
uint8_t BT_SendAck(uint8_t acknowledged_cmd, uint8_t status);
uint8_t BT_Process(SmartWatchData_t *data, BT_ControlEvent_t *event);
void BT_RxCpltCallback(uint16_t size);
uint8_t BT_IsConnected(void);

#endif /* __BLUETOOTH_H */
