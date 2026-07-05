#ifndef __STEP_COUNTER_H
#define __STEP_COUNTER_H

#include <stdint.h>

typedef struct {
    uint32_t steps;
    float filtered_g;
    float threshold_g;
    float window_min_g;
    float window_max_g;
    uint8_t sample_count;
    uint8_t above_threshold;
    uint32_t last_step_tick;
} StepCounter_t;

void StepCounter_Init(StepCounter_t *counter);
void StepCounter_Reset(StepCounter_t *counter);
void StepCounter_Update(StepCounter_t *counter, float ax_ms2, float ay_ms2, float az_ms2, uint32_t now_ms);

#endif /* __STEP_COUNTER_H */
