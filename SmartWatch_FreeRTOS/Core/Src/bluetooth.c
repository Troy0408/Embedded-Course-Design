#include "bluetooth.h"
#include "usart.h"
#include <string.h>

/* Ring buffer for DMA RX */
static uint8_t rx_buf[BT_RX_BUF_SIZE];
static uint8_t tx_buf[BT_TX_BUF_SIZE];
static uint16_t rx_old_pos = 0;

#define BT_ACK_QUEUE_SIZE 8U

typedef struct {
    uint8_t cmd;
    uint8_t status;
    uint32_t seq;
} BT_Ack_t;

/* Frame parser state */
static uint8_t frame_buf[BT_RX_BUF_SIZE];
static uint8_t frame_idx = 0;
static uint8_t frame_expected_len = 0;

static BT_ControlEvent_t pending_event;
static volatile uint8_t pending_flags = 0;
static BT_Ack_t ack_queue[BT_ACK_QUEUE_SIZE];
static volatile uint8_t ack_head = 0;
static volatile uint8_t ack_tail = 0;
static volatile uint8_t ack_count = 0;
static volatile uint32_t ack_next_seq = 0;
static volatile uint32_t ack_overflow_count = 0;
static volatile uint8_t tx_busy = 0;
static volatile uint8_t rx_restart_requested = 0;

static void BT_ParseByte(uint8_t byte);

static uint32_t BT_EnterCritical(void)
{
    uint32_t primask = __get_PRIMASK();
    __disable_irq();
    return primask;
}

static void BT_ExitCritical(uint32_t primask)
{
    __set_PRIMASK(primask);
}

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

void HAL_UART_ErrorCallback(UART_HandleTypeDef *huart)
{
    if (huart->Instance == USART2) {
        tx_busy = 0U;
        rx_restart_requested = 1U;
    }
}

void BT_RxCpltCallback(uint16_t size)
{
    uint32_t primask = BT_EnterCritical();
    uint16_t new_pos = size;

    if (new_pos > BT_RX_BUF_SIZE)
    {
        new_pos = BT_RX_BUF_SIZE;
    }

    if (new_pos == rx_old_pos)
    {
        BT_ExitCritical(primask);
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

    rx_old_pos = (new_pos == BT_RX_BUF_SIZE) ? 0U : new_pos;
    BT_ExitCritical(primask);
}

static uint8_t BT_NextAckIndex(uint8_t index)
{
    index++;
    return (index >= BT_ACK_QUEUE_SIZE) ? 0U : index;
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
    uint32_t primask;

    if (len > (BT_TX_BUF_SIZE - 5U)) {
        return 0U;
    }
    if (payload == NULL && len > 0U) {
        return 0U;
    }

    primask = BT_EnterCritical();
    if (tx_busy != 0U || huart2.gState != HAL_UART_STATE_READY) {
        BT_ExitCritical(primask);
        return 0U;
    }
    tx_busy = 1U;
    BT_ExitCritical(primask);

    tx_buf[0] = BT_STX;
    tx_buf[1] = cmd;
    tx_buf[2] = len;
    if (payload != NULL && len > 0U) {
        memcpy(&tx_buf[3], payload, len);
    }
    tx_buf[3U + len] = BT_Checksum(cmd, len, payload == NULL ? &tx_buf[3] : payload);
    tx_buf[4U + len] = BT_ETX;

    if (HAL_UART_Transmit_DMA(&huart2, tx_buf, 5U + len) != HAL_OK) {
        primask = BT_EnterCritical();
        tx_busy = 0U;
        BT_ExitCritical(primask);
        return 0U;
    }
    return 1U;
}

static void BT_QueueAck(uint8_t acknowledged_cmd, uint8_t status)
{
    uint32_t primask = BT_EnterCritical();

    if (ack_count == BT_ACK_QUEUE_SIZE) {
        /* Keep the newest command response; drop the oldest queued ACK when full. */
        ack_head = BT_NextAckIndex(ack_head);
        ack_count--;
        ack_overflow_count++;
    }

    ack_queue[ack_tail].cmd = acknowledged_cmd;
    ack_queue[ack_tail].status = status;
    ack_queue[ack_tail].seq = ++ack_next_seq;
    ack_tail = BT_NextAckIndex(ack_tail);
    ack_count++;

    BT_ExitCritical(primask);
}

static uint8_t BT_PeekAck(BT_Ack_t *ack)
{
    uint8_t available = 0U;
    uint32_t primask = BT_EnterCritical();

    if (ack_count > 0U) {
        *ack = ack_queue[ack_head];
        available = 1U;
    }

    BT_ExitCritical(primask);
    return available;
}

static uint8_t BT_PopAckIfHeadMatches(const BT_Ack_t *ack)
{
    uint8_t popped = 0U;
    uint32_t primask = BT_EnterCritical();

    if (ack != NULL && ack_count > 0U && ack_queue[ack_head].seq == ack->seq) {
        ack_head = BT_NextAckIndex(ack_head);
        ack_count--;
        popped = 1U;
    }

    BT_ExitCritical(primask);
    return popped;
}

static void BT_ProcessQueuedAck(void)
{
    BT_Ack_t ack;

    if (BT_PeekAck(&ack) == 0U) {
        return;
    }

    if (BT_SendAck(ack.cmd, ack.status) != 0U) {
        (void)BT_PopAckIfHeadMatches(&ack);
    }
}

static void BT_ServiceRxRestart(void)
{
    uint8_t restart;
    uint32_t primask = BT_EnterCritical();

    restart = rx_restart_requested;
    rx_restart_requested = 0U;

    if (restart != 0U) {
        rx_old_pos = 0U;
        BT_ResetParser();
    }

    BT_ExitCritical(primask);

    if (restart != 0U) {
        HAL_UARTEx_ReceiveToIdle_DMA(&huart2, rx_buf, BT_RX_BUF_SIZE);
        __HAL_DMA_DISABLE_IT(&hdma_usart2_rx, DMA_IT_HT);
    }
}

static uint8_t BT_IsLeapYear(uint16_t year)
{
    if ((year % 400U) == 0U) {
        return 1U;
    }
    if ((year % 100U) == 0U) {
        return 0U;
    }
    return ((year % 4U) == 0U) ? 1U : 0U;
}

static uint8_t BT_DaysInMonth(uint16_t year, uint8_t month)
{
    switch (month) {
    case 1U:
    case 3U:
    case 5U:
    case 7U:
    case 8U:
    case 10U:
    case 12U:
        return 31U;
    case 4U:
    case 6U:
    case 9U:
    case 11U:
        return 30U;
    case 2U:
        return (BT_IsLeapYear(year) != 0U) ? 29U : 28U;
    default:
        return 0U;
    }
}

static uint8_t BT_IsValidTimeSyncPayload(const uint8_t *payload)
{
    uint16_t year = (uint16_t)(2000U + payload[3]);
    uint8_t days_in_month;

    if (payload[0] > 23U) {
        return 0U;
    }
    if (payload[1] > 59U || payload[2] > 59U) {
        return 0U;
    }
    if (payload[4] < 1U || payload[4] > 12U) {
        return 0U;
    }
    days_in_month = BT_DaysInMonth(year, payload[4]);
    if (payload[5] < 1U || payload[5] > days_in_month) {
        return 0U;
    }
    if (payload[6] > 6U) {
        return 0U;
    }
    return 1U;
}

static void BT_HandleFrame(uint8_t cmd, const uint8_t *payload, uint8_t len)
{
    if (cmd == BT_CMD_TIME_SYNC) {
        if (len != 7U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_LENGTH);
            return;
        }
        if (BT_IsValidTimeSyncPayload(payload) == 0U) {
            BT_QueueAck(cmd, BT_ACK_INVALID_VALUE);
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
        if (byte == BT_STX)
        {
            BT_ResetParser();
            frame_buf[frame_idx++] = byte;
        }
        else
        {
            BT_ResetParser();
        }
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
    uint32_t primask;
    uint8_t flags;
    BT_ControlEvent_t local_event;

    BT_ServiceRxRestart();

    primask = BT_EnterCritical();
    flags = pending_flags;
    local_event = pending_event;
    pending_flags = 0U;
    BT_ExitCritical(primask);

    if ((flags & BT_EVENT_REQUEST_STATUS) == 0U) {
        BT_ProcessQueuedAck();
    }

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
