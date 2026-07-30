#ifndef AUDIO_PROCESSOR_H
#define AUDIO_PROCESSOR_H

#include <stdint.h>
#include <pthread.h>

#ifdef __cplusplus
extern "C" {
#endif

// 环形缓冲区（线程安全，pthread_mutex 保护读写）
typedef struct {
    int16_t* buffer;
    int capacity;
    int read_pos;
    int write_pos;
    int count;
    pthread_mutex_t lock;  // 互斥锁：防止 feed(写) 和 getBatch(读) 并发错乱
} RingBuffer;

RingBuffer* ring_buffer_create(int capacity);
void ring_buffer_destroy(RingBuffer* rb);
int ring_buffer_write(RingBuffer* rb, const int16_t* data, int len);
int ring_buffer_read(RingBuffer* rb, int16_t* data, int len);
int ring_buffer_available(RingBuffer* rb);
void ring_buffer_clear(RingBuffer* rb);

typedef struct {
    RingBuffer* ring_buffer;
    float soft_gain_factor;
    float soft_gain_threshold;
    float max_soft_gain;
} AudioProcessor;

AudioProcessor* audio_processor_create(int buffer_capacity, float gain_factor, float gain_threshold, float max_gain);
void audio_processor_destroy(AudioProcessor* ap);
void audio_processor_feed(AudioProcessor* ap, const int16_t* data, int len);
int audio_processor_get_batch(AudioProcessor* ap, int16_t* output, int batch_size);
void audio_processor_set_gain(AudioProcessor* ap, float factor, float threshold, float max_gain);
void audio_processor_clear(AudioProcessor* ap);

#ifdef __cplusplus
}
#endif

#endif
