#include "step_counter.h"
#include <math.h>

void StepCounter_Init(StepCounter_t *counter)
{
    counter->steps = 0;
    counter->filtered_g = 1.0f;
    counter->threshold_g = 1.12f;
    counter->window_min_g = 2.0f;
    counter->window_max_g = 0.0f;
    counter->sample_count = 0;
    counter->above_threshold = 0;
    counter->last_step_tick = 0;
}

void StepCounter_Reset(StepCounter_t *counter)
{
    StepCounter_Init(counter);
}

void StepCounter_Update(StepCounter_t *counter, float ax_ms2, float ay_ms2, float az_ms2, uint32_t now_ms)
{
    float mag_g = sqrtf(ax_ms2 * ax_ms2 + ay_ms2 * ay_ms2 + az_ms2 * az_ms2) / 9.81f;
    counter->filtered_g = 0.15f * mag_g + 0.85f * counter->filtered_g;

    if (counter->filtered_g < counter->window_min_g) counter->window_min_g = counter->filtered_g;
    if (counter->filtered_g > counter->window_max_g) counter->window_max_g = counter->filtered_g;

    counter->sample_count++;
    if (counter->sample_count >= 50U) {
        float dynamic = (counter->window_min_g + counter->window_max_g) * 0.5f;
        counter->threshold_g = dynamic > 1.08f ? dynamic : 1.08f;
        counter->window_min_g = 2.0f;
        counter->window_max_g = 0.0f;
        counter->sample_count = 0;
    }

    if (!counter->above_threshold && counter->filtered_g > counter->threshold_g) {
        if (now_ms - counter->last_step_tick > 250U) {
            counter->steps++;
            counter->last_step_tick = now_ms;
        }
        counter->above_threshold = 1;
    } else if (counter->filtered_g < counter->threshold_g - 0.05f) {
        counter->above_threshold = 0;
    }
}
