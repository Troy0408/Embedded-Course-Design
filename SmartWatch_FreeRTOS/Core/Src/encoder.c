#include "encoder.h"

static uint8_t last_ab = 0;
static uint32_t last_button_tick = 0;
static uint32_t cw_count = 0;
static uint32_t ccw_count = 0;
static uint32_t press_count = 0;
static uint32_t transition_count = 0;
static uint16_t last_timer_count = 0;
static int16_t encoder_accumulator = 0;

#define ENCODER_COUNTS_PER_EVENT 4
#define ENCODER_REVERSE_DIRECTION 0

static uint8_t Encoder_ReadAB(void)
{
    uint8_t a = (HAL_GPIO_ReadPin(ENCODER_A_GPIO_Port, ENCODER_A_Pin) == GPIO_PIN_SET) ? 1U : 0U;
    uint8_t b = (HAL_GPIO_ReadPin(ENCODER_B_GPIO_Port, ENCODER_B_Pin) == GPIO_PIN_SET) ? 1U : 0U;
    return (uint8_t)((a << 1) | b);
}

static void Encoder_TIM3_Init(void)
{
    __HAL_RCC_TIM3_CLK_ENABLE();

    TIM3->CR1 = 0U;
    TIM3->CR2 = 0U;
    TIM3->SMCR = 0U;
    TIM3->DIER = 0U;
    TIM3->CCER = 0U;
    TIM3->CCMR1 = 0U;
    TIM3->CCMR2 = 0U;

    TIM3->PSC = 0U;
    TIM3->ARR = 0xFFFFU;
    TIM3->CNT = 0U;

    /*
     * PA6/PA7 are the default TIM3_CH1/TIM3_CH2 pins on STM32F103.
     * Encoder mode lets TIM3 count quadrature transitions while OLED/I2C work
     * is running, so the main loop no longer has to catch every GPIO edge.
     */
    TIM3->CCMR1 = TIM_CCMR1_CC1S_0 |
                  TIM_CCMR1_CC2S_0 |
                  TIM_CCMR1_IC1F |
                  TIM_CCMR1_IC2F;
    TIM3->CCER = TIM_CCER_CC1E | TIM_CCER_CC2E;
    TIM3->SMCR = TIM_SMCR_SMS_0 | TIM_SMCR_SMS_1;
    TIM3->CR1 = TIM_CR1_CEN;
}

void Encoder_Init(void)
{
    last_ab = Encoder_ReadAB();
    last_button_tick = HAL_GetTick();
    cw_count = 0;
    ccw_count = 0;
    press_count = 0;
    transition_count = 0;
    encoder_accumulator = 0;
    Encoder_TIM3_Init();
    last_timer_count = (uint16_t)TIM3->CNT;
}

void Encoder_GetDebug(EncoderDebug_t *debug)
{
    uint8_t ab = Encoder_ReadAB();
    debug->a = (ab >> 1) & 0x01U;
    debug->b = ab & 0x01U;
    debug->cw_count = cw_count;
    debug->ccw_count = ccw_count;
    debug->press_count = press_count;
    debug->transition_count = transition_count;
}

EncoderEvent_t Encoder_Poll(void)
{
    uint32_t now = HAL_GetTick();
    if (HAL_GPIO_ReadPin(BUTTON_OK_GPIO_Port, BUTTON_OK_Pin) == GPIO_PIN_RESET) {
        if (now - last_button_tick > 250U) {
            last_button_tick = now;
            press_count++;
            return ENCODER_EVENT_PRESS;
        }
    }

    uint8_t ab = Encoder_ReadAB();
    if (ab != last_ab) {
        last_ab = ab;
        transition_count++;
    }

    uint16_t timer_count = (uint16_t)TIM3->CNT;
    int16_t delta = (int16_t)(timer_count - last_timer_count);
    if (ENCODER_REVERSE_DIRECTION) {
        delta = (int16_t)-delta;
    }

    if (delta != 0) {
        last_timer_count = timer_count;
        encoder_accumulator = (int16_t)(encoder_accumulator + delta);
    }

    if (encoder_accumulator >= ENCODER_COUNTS_PER_EVENT) {
        encoder_accumulator = (int16_t)(encoder_accumulator - ENCODER_COUNTS_PER_EVENT);
        cw_count++;
        return ENCODER_EVENT_CW;
    }

    if (encoder_accumulator <= -ENCODER_COUNTS_PER_EVENT) {
        encoder_accumulator = (int16_t)(encoder_accumulator + ENCODER_COUNTS_PER_EVENT);
        ccw_count++;
        return ENCODER_EVENT_CCW;
    }

    return ENCODER_EVENT_NONE;
}
