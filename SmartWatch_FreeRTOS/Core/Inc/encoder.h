#ifndef __ENCODER_H
#define __ENCODER_H

#include "main.h"
#include <stdint.h>

typedef enum {
    ENCODER_EVENT_NONE = 0,
    ENCODER_EVENT_CW,
    ENCODER_EVENT_CCW,
    ENCODER_EVENT_PRESS
} EncoderEvent_t;

typedef struct {
    uint8_t a;
    uint8_t b;
    uint32_t cw_count;
    uint32_t ccw_count;
    uint32_t press_count;
    uint32_t transition_count;
} EncoderDebug_t;

void Encoder_Init(void);
EncoderEvent_t Encoder_Poll(void);
void Encoder_GetDebug(EncoderDebug_t *debug);

#endif /* __ENCODER_H */
