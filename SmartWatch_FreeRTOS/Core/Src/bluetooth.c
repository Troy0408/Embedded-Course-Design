#include "bluetooth.h"
#include "usart.h"
#include <string.h>
#include <stdio.h>

/* Ring buffer for DMA RX */
static uint8_t rx_buf[BT_RX_BUF_SIZE];
static uint8_t tx_buf[BT_TX_BUF_SIZE];
static uint16_t rx_old_pos = 0;

/* Frame parser state */
static uint8_t frame_buf[BT_RX_BUF_SIZE];
static uint8_t frame_idx = 0;
static uint8_t frame_expected_len = 0;

static BT_ControlEvent_t pending_event;
static volatile uint8_t pending_flags = 0;
static volatile uint8_t pending_ack_available = 0;
static uint8_t pending_ack_cmd = 0;
static uint8_t pending_ack_status = 0;
static volatile uint8_t tx_busy = 0;

static void BT_ParseByte(uint8_t byte);

void BT_Init(void)
{
    /* Start DMA circular reception */
    HAL_UARTEx_ReceiveToIdle_DMA(&huart2, rx_buf, BT_RX_BUF_SIZE);
    __HAL_DMA_DISABLE_IT(&hdma_usart2_rx, DMA_IT_HT);
}

/* UART RX event callback (IDLE line or half-transfer) */
void HAL_UARTEx_RxEventCallback(UART_HandleTypeDef *huart, uint16_t Size)
{
    if (huart->Instance == USART2 && Size > 0)
    {
        BT_RxCpltCallback(Size);
    }
}

void HAL_UART_TxCpltCallback(UART_HandleTypeDef *huart)
{
    if (huart->Instance == USART2) {
        tx_busy = 0U;
    }
}

void BT_RxCpltCallback(uint16_t size)
{
    uint16_t new_pos = size;

    if (new_pos > BT_RX_BUF_SIZE)
    {
        new_pos = BT_RX_BUF_SIZE;
    }

    if (new_pos == rx_old_pos)
    {
        return;
    }

    if (new_pos > rx_old_pos)
    {
        for (uint16_t i = rx_old_pos; i < new_pos; i++)
        {
            BT_ParseByte(rx_buf[i]);
        }
    }
    else
    {
        for (uint16_t i = rx_old_pos; i < BT_RX_BUF_SIZE; i++)
        {
            BT_ParseByte(rx_buf[i]);
        }
        for (uint16_t i = 0; i < new_pos; i++)
        {
            BT_ParseByte(rx_buf[i]);
        }
    }

    rx_old_pos = new_pos;
}

static void BT_ResetParser(void)
{
    frame_idx = 0;
    frame_expected_len = 0;
}

static uint8_t BT_Checksum(uint8_t cmd, uint8_t len, const uint8_t *payload)
{
    uint8_t chk = cmd ^ len;
    for (uint8_t i = 0; i < len; i++) {
        chk ^= payload[i];
    }
    return chk;
}

static uint8_t BT_SendFrame(uint8_t cmd, const uint8_t *payload, uint8_t len)
{
    if (len > (BT_TX_BUF_SIZE - 5U)) {
        return 0U;
    }
    if (payload == NULL && len > 0U) {
        return 0U;
    }
    if (tx_busy != 0U || huart2.gState != HAL_UART_STATE_READY) {
        return 0U;
    }

    tx_buf[0] = BT_STX;
    tx_buf[1] = cmd;
    tx_buf[2] = len;
    if (payload != NULL && len > 0U) {
        memcpy(&tx_buf[3], payload, len);
    }
    tx_buf[3U + len] = BT_Checksum(cmd, len, payload == NULL ? &tx_buf[3] : payload);
    tx_buf[4U + len] = BT_ETX;

    tx_busy = 1U;
    if (HAL_UART_Transmit_DMA(&huart2, tx_buf, 5U + len) != HAL_OK) {
        tx_busy = 0U;
        return 0U;
    }
    return 1U;
}

static void BT_QueueAck(uint8_t acknowledged_cmd, uint8_t status)
{
    pending_ack_cmd = acknowledged_cmd;
    pending_ack_status = status;
    pending_ack_available = 1U;
}

static void BT_HandleFrame(uint8_t cmd, const uint8_t *payload, uint8_t len)
{
    if (cmd == BT_CMD_TIME_SYNC) {
        if (len != 7U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        pending_event.time.hour = payload[0];
        pending_event.time.minute = payload[1];
        pending_event.time.second = payload[2];
        pending_event.time.year = (uint16_t)(2000U + payload[3]);
        pending_event.time.month = payload[4];
        pending_event.time.day = payload[5];
        pending_event.time.weekday = payload[6];
        pending_flags |= BT_EVENT_TIME_SYNC;
        BT_QueueAck(cmd, BT_ACK_OK);
        return;
    }

    if (cmd == BT_CMD_SET_PAGE) {
        if (len != 1U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        if (payload[0] >= PAGE_MAX) {
            BT_QueueAck(cmd, BT_ACK_INVALID_VALUE);
            return;
        }
        pending_event.page = (UIPage_t)payload[0];
        pending_flags |= BT_EVENT_SET_PAGE;
        BT_QueueAck(cmd, BT_ACK_OK);
        return;
    }

    if (cmd == BT_CMD_RESET_STEPS) {
        if (len != 0U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        pending_flags |= BT_EVENT_RESET_STEPS;
        BT_QueueAck(cmd, BT_ACK_OK);
        return;
    }

    if (cmd == BT_CMD_REQUEST_STATUS) {
        if (len != 0U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        pending_flags |= BT_EVENT_REQUEST_STATUS;
        BT_QueueAck(cmd, BT_ACK_OK);
        return;
    }

    BT_QueueAck(cmd, BT_ACK_UNSUPPORTED);
}

static void BT_ParseByte(uint8_t byte)
{
    if (frame_idx == 0)
    {
        if (byte == BT_STX)
        {
            frame_buf[frame_idx++] = byte;
        }
        return;
    }

    if (frame_idx == 1)
    {
        frame_buf[frame_idx++] = byte; /* CMD */
        return;
    }

    if (frame_idx == 2)
    {
        frame_expected_len = byte;
        if (frame_expected_len > (BT_RX_BUF_SIZE - 5U))
        {
            BT_ResetParser();
            return;
        }
        frame_buf[frame_idx++] = byte; /* LEN */
        return;
    }

    if (frame_idx < 3 + frame_expected_len)
    {
        frame_buf[frame_idx++] = byte; /* DATA */
        return;
    }

    if (frame_idx == 3 + frame_expected_len)
    {
        frame_buf[frame_idx++] = byte; /* CHK */
        return;
    }

    if (frame_idx == 4 + frame_expected_len)
    {
        frame_buf[frame_idx] = byte; /* ETX */
        if (byte == BT_ETX)
        {
            uint8_t cmd = frame_buf[1];
            uint8_t len = frame_buf[2];
            uint8_t chk = BT_Checksum(cmd, len, &frame_buf[3]);
            if (chk == frame_buf[3U + len])
            {
                BT_HandleFrame(cmd, &frame_buf[3], len);
            }
        }
        BT_ResetParser();
    }
}

/* Send sensor data frame via BT */
uint8_t BT_SendSensorData(const SmartWatchData_t *data)
{
    if (data == NULL) {
        return 0U;
    }

    uint8_t payload[24];
    uint8_t *p = payload;
    memcpy(p, &data->accel.ax, 4); p += 4;
    memcpy(p, &data->accel.ay, 4); p += 4;
    memcpy(p, &data->accel.az, 4); p += 4;
    memcpy(p, &data->gyro.gx,  4); p += 4;
    memcpy(p, &data->gyro.gy,  4); p += 4;
    memcpy(p, &data->gyro.gz,  4);
    return BT_SendFrame(BT_CMD_SENSOR_DATA, payload, sizeof(payload));
}

uint8_t BT_SendStatus(const SmartWatchData_t *data)
{
    if (data == NULL) {
        return 0U;
    }

    uint8_t payload[14];
    uint16_t steps = (data->steps > 0xFFFFU) ? 0xFFFFU : (uint16_t)data->steps;
    uint16_t frames = (data->bt_frames > 0xFFFFU) ? 0xFFFFU : (uint16_t)data->bt_frames;

    payload[0] = data->hour;
    payload[1] = data->minute;
    payload[2] = data->second;
    payload[3] = (data->year >= 2000U) ? (uint8_t)(data->year - 2000U) : 0U;
    payload[4] = data->month;
    payload[5] = data->day;
    payload[6] = data->weekday;
    payload[7] = (uint8_t)data->current_page;
    payload[8] = (uint8_t)(steps & 0xFFU);
    payload[9] = (uint8_t)(steps >> 8);
    payload[10] = (uint8_t)(frames & 0xFFU);
    payload[11] = (uint8_t)(frames >> 8);
    payload[12] = data->imu_status;
    payload[13] = data->bt_connected;

    return BT_SendFrame(BT_CMD_STATUS, payload, sizeof(payload));
}

uint8_t BT_SendAck(uint8_t acknowledged_cmd, uint8_t status)
{
    uint8_t payload[2] = { acknowledged_cmd, status };
    return BT_SendFrame(BT_CMD_ACK, payload, sizeof(payload));
}

uint8_t BT_Process(SmartWatchData_t *data, BT_ControlEvent_t *event)
{
    if (pending_ack_available != 0U) {
        if (BT_SendAck(pending_ack_cmd, pending_ack_status) != 0U) {
            pending_ack_available = 0U;
        }
    }

    __disable_irq();
    uint8_t flags = pending_flags;
    BT_ControlEvent_t local_event = pending_event;
    pending_flags = 0U;
    __enable_irq();

    if (flags == 0U) {
        if (event != NULL) {
            memset(event, 0, sizeof(*event));
        }
        return 0U;
    }

    if (event != NULL) {
        *event = local_event;
        event->flags = flags;
    }

    if (data != NULL && (flags & BT_EVENT_TIME_SYNC) != 0U) {
        data->hour = local_event.time.hour;
        data->minute = local_event.time.minute;
        data->second = local_event.time.second;
        data->year = local_event.time.year;
        data->month = local_event.time.month;
        data->day = local_event.time.day;
        data->weekday = local_event.time.weekday;
    }

    if (data != NULL && (flags & BT_EVENT_SET_PAGE) != 0U) {
        data->current_page = local_event.page;
    }

    return flags;
}

/* Read HC-05 STATE pin: returns 1 if connected, 0 if disconnected */
uint8_t BT_IsConnected(void)
{
    return (HAL_GPIO_ReadPin(BT_STATE_PORT, BT_STATE_PIN) == GPIO_PIN_SET) ? 1 : 0;
}
