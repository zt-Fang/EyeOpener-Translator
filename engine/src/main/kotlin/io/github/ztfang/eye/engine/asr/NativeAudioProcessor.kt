package io.github.ztfang.eye.engine.asr

import android.util.Log

class NativeAudioProcessor(
    bufferCapacity: Int = 32768,
    gainFactor: Float = 0.8f,
    gainThreshold: Float = 0.01f,
    maxGain: Float = 10.0f
) {

    private var handle: Long = 0L
    private val TAG = "NativeAudioProcessor"

    init {
        handle = nativeCreate(bufferCapacity, gainFactor, gainThreshold, maxGain)
        if (handle == 0L) {
            Log.e(TAG, "NativeAudioProcessor init failed")
        }
    }

    fun feed(data: ShortArray) {
        if (handle != 0L) {
            nativeFeed(handle, data)
        }
    }

    fun getBatch(batchSize: Int): ShortArray? {
        if (handle == 0L) return null
        val output = ShortArray(batchSize)
        val read = nativeGetBatch(handle, output, batchSize)
        return if (read > 0) {
            output.copyOf(read)
        } else {
            null
        }
    }

    fun setGain(factor: Float, threshold: Float, maxGain: Float) {
        if (handle != 0L) {
            nativeSetGain(handle, factor, threshold, maxGain)
        }
    }

    fun clear() {
        if (handle != 0L) {
            nativeClear(handle)
        }
    }

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(
        bufferCapacity: Int,
        gainFactor: Float,
        gainThreshold: Float,
        maxGain: Float
    ): Long

    private external fun nativeDestroy(handle: Long)

    private external fun nativeFeed(handle: Long, data: ShortArray)

    private external fun nativeGetBatch(handle: Long, output: ShortArray, batchSize: Int): Int

    private external fun nativeSetGain(handle: Long, factor: Float, threshold: Float, maxGain: Float)

    private external fun nativeClear(handle: Long)

    companion object {
        init {
            System.loadLibrary("eye_native")
        }
    }
}