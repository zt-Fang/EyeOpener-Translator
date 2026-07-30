#include <jni.h>
#include <android/log.h>
#include "audio_processor.h"

#define LOG_TAG "AudioProcessorJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_eye_engine_asr_NativeAudioProcessor_nativeCreate(
    JNIEnv* env, jobject /* thiz */, 
    jint buffer_capacity, 
    jfloat gain_factor, 
    jfloat gain_threshold, 
    jfloat max_gain) {
    
    AudioProcessor* ap = audio_processor_create(
        (int)buffer_capacity,
        (float)gain_factor,
        (float)gain_threshold,
        (float)max_gain
    );
    if (!ap) {
        LOGE("audio_processor_create failed");
        return 0;
    }
    LOGI("AudioProcessor created, handle=%p", ap);
    return reinterpret_cast<jlong>(ap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_eye_engine_asr_NativeAudioProcessor_nativeDestroy(
    JNIEnv* env, jobject /* thiz */, jlong handle) {
    
    AudioProcessor* ap = reinterpret_cast<AudioProcessor*>(handle);
    if (ap) {
        audio_processor_destroy(ap);
        LOGI("AudioProcessor destroyed, handle=%p", ap);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_eye_engine_asr_NativeAudioProcessor_nativeFeed(
    JNIEnv* env, jobject /* thiz */, jlong handle, jshortArray data) {
    
    AudioProcessor* ap = reinterpret_cast<AudioProcessor*>(handle);
    if (!ap) return;
    
    jsize len = env->GetArrayLength(data);
    jshort* elements = env->GetShortArrayElements(data, nullptr);
    
    audio_processor_feed(ap, elements, (int)len);
    
    env->ReleaseShortArrayElements(data, elements, JNI_ABORT);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_eye_engine_asr_NativeAudioProcessor_nativeGetBatch(
    JNIEnv* env, jobject /* thiz */, jlong handle, jshortArray output, jint batch_size) {
    
    AudioProcessor* ap = reinterpret_cast<AudioProcessor*>(handle);
    if (!ap) return 0;
    
    jshort* elements = env->GetShortArrayElements(output, nullptr);
    int read = audio_processor_get_batch(ap, elements, (int)batch_size);
    env->ReleaseShortArrayElements(output, elements, 0);
    
    return (jint)read;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_eye_engine_asr_NativeAudioProcessor_nativeSetGain(
    JNIEnv* env, jobject /* thiz */, jlong handle,
    jfloat factor, jfloat threshold, jfloat max_gain) {
    
    AudioProcessor* ap = reinterpret_cast<AudioProcessor*>(handle);
    if (ap) {
        audio_processor_set_gain(ap, (float)factor, (float)threshold, (float)max_gain);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_eye_engine_asr_NativeAudioProcessor_nativeClear(
    JNIEnv* env, jobject /* thiz */, jlong handle) {
    
    AudioProcessor* ap = reinterpret_cast<AudioProcessor*>(handle);
    if (ap) {
        audio_processor_clear(ap);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_eye_engine_asr_NativeAudioProcessor_nativeAvailable(
    JNIEnv* env, jobject /* thiz */, jlong handle) {
    
    AudioProcessor* ap = reinterpret_cast<AudioProcessor*>(handle);
    if (!ap) return 0;
    return (jint)audio_processor_get_batch(ap, nullptr, 0);
}