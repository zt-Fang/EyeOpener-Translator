#include "audio_processor.h"
#include <stdlib.h>
#include <memory.h>
#include <math.h>

RingBuffer* ring_buffer_create(int capacity) {
    RingBuffer* rb = (RingBuffer*)malloc(sizeof(RingBuffer));
    if (!rb) return NULL;
    rb->buffer = (int16_t*)malloc(sizeof(int16_t) * capacity);
    if (!rb->buffer) {
        free(rb);
        return NULL;
    }
    rb->capacity = capacity;
    rb->read_pos = 0;
    rb->write_pos = 0;
    rb->count = 0;
    pthread_mutex_init(&rb->lock, NULL);
    return rb;
}

void ring_buffer_destroy(RingBuffer* rb) {
    if (rb) {
        pthread_mutex_destroy(&rb->lock);
        free(rb->buffer);
        free(rb);
    }
}

int ring_buffer_write(RingBuffer* rb, const int16_t* data, int len) {
    if (!rb || !data || len <= 0) return 0;

    pthread_mutex_lock(&rb->lock);
    int written = 0;
    for (int i = 0; i < len && rb->count < rb->capacity; i++) {
        rb->buffer[rb->write_pos] = data[i];
        rb->write_pos = (rb->write_pos + 1) % rb->capacity;
        rb->count++;
        written++;
    }
    pthread_mutex_unlock(&rb->lock);
    return written;
}

int ring_buffer_read(RingBuffer* rb, int16_t* data, int len) {
    if (!rb || !data || len <= 0) return 0;

    pthread_mutex_lock(&rb->lock);
    int read = 0;
    for (int i = 0; i < len && rb->count > 0; i++) {
        data[i] = rb->buffer[rb->read_pos];
        rb->read_pos = (rb->read_pos + 1) % rb->capacity;
        rb->count--;
        read++;
    }
    pthread_mutex_unlock(&rb->lock);
    return read;
}

int ring_buffer_available(RingBuffer* rb) {
    if (!rb) return 0;
    pthread_mutex_lock(&rb->lock);
    int c = rb->count;
    pthread_mutex_unlock(&rb->lock);
    return c;
}

void ring_buffer_clear(RingBuffer* rb) {
    if (rb) {
        pthread_mutex_lock(&rb->lock);
        rb->read_pos = 0;
        rb->write_pos = 0;
        rb->count = 0;
        pthread_mutex_unlock(&rb->lock);
    }
}

AudioProcessor* audio_processor_create(int buffer_capacity, float gain_factor, float gain_threshold, float max_gain) {
    AudioProcessor* ap = (AudioProcessor*)malloc(sizeof(AudioProcessor));
    if (!ap) return NULL;
    ap->ring_buffer = ring_buffer_create(buffer_capacity);
    if (!ap->ring_buffer) {
        free(ap);
        return NULL;
    }
    ap->soft_gain_factor = gain_factor;
    ap->soft_gain_threshold = gain_threshold;
    ap->max_soft_gain = max_gain;
    return ap;
}

void audio_processor_destroy(AudioProcessor* ap) {
    if (ap) {
        ring_buffer_destroy(ap->ring_buffer);
        free(ap);
    }
}

static float compute_rms(const int16_t* data, int len) {
    if (len <= 0) return 0.0f;
    float sum = 0.0f;
    for (int i = 0; i < len; i++) {
        float sample = (float)data[i] / 32768.0f;
        sum += sample * sample;
    }
    return sqrt(sum / (float)len);
}

static int16_t apply_soft_gain(int16_t sample, float gain) {
    float result = (float)sample * gain;
    if (result > 32767.0f) result = 32767.0f;
    if (result < -32768.0f) result = -32768.0f;
    return (int16_t)result;
}

void audio_processor_feed(AudioProcessor* ap, const int16_t* data, int len) {
    if (!ap || !data || len <= 0) return;

    float rms = compute_rms(data, len);
    float gain = 1.0f;

    if (rms < ap->soft_gain_threshold) {
        gain = ap->soft_gain_factor * (ap->soft_gain_threshold / rms);
        if (gain > ap->max_soft_gain) {
            gain = ap->max_soft_gain;
        }
    }

    int16_t* processed = (int16_t*)malloc(sizeof(int16_t) * len);
    if (!processed) return;

    for (int i = 0; i < len; i++) {
        processed[i] = apply_soft_gain(data[i], gain);
    }

    ring_buffer_write(ap->ring_buffer, processed, len);
    free(processed);
}

int audio_processor_get_batch(AudioProcessor* ap, int16_t* output, int batch_size) {
    if (!ap || !output || batch_size <= 0) return 0;
    return ring_buffer_read(ap->ring_buffer, output, batch_size);
}

void audio_processor_set_gain(AudioProcessor* ap, float factor, float threshold, float max_gain) {
    if (ap) {
        ap->soft_gain_factor = factor;
        ap->soft_gain_threshold = threshold;
        ap->max_soft_gain = max_gain;
    }
}

void audio_processor_clear(AudioProcessor* ap) {
    if (ap) {
        ring_buffer_clear(ap->ring_buffer);
    }
}
