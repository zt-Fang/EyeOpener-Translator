package io.github.ztfang.eye.engine

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asset 文件拷贝工具。
 * 将 APK 内置的模型文件从 assets 目录拷贝到 [Context.filesDir]，
 * 以便 native 和 ONNX 运行时可以通过真实文件路径进行 mmap 加载。
 *
 * 原因：JNI / ONNX Runtime 无法直接从 APK 读取文件：
 *   - assets 存储在 ZipFileInputStream 中，缺少 mmap 语义
 *   - [android.content.res.AssetManager.openFd] 返回的文件描述符在某些设备上不是真正的文件
 *
 * 拷贝规则：
 *   - 目标文件不存在或大小与源文件不同时才拷贝（使用大小而非哈希，保持拷贝开销低）
 *   - 目标文件父目录不存在时自动创建
 *   - 使用 64KB 缓冲区流式拷贝，降低峰值内存
 */
@Singleton
class AssetCopy @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 将 assets 中的文件拷贝到磁盘指定位置。
     * 返回目标文件，调用方可将其 [File.absolutePath] 传给 native API。
     *
     * @param assetPath assets 中的相对路径
     * @param destFile 目标文件
     * @throws IllegalStateException 当 asset 缺失或拷贝失败时（如磁盘空间不足）
     */
    fun materialize(assetPath: String, destFile: File): File {
        if (destFile.exists() && destFile.length() > 0) {
            // 快速路径：已存在则校验大小，避免部分拷贝的文件静默保留
            val expected = try {
                context.assets.openFd(assetPath).use { fd -> fd.length }
            } catch (e: Exception) {
                -1L
            }
            if (expected < 0L || destFile.length() == expected) {
                return destFile
            }
            Log.w(TAG, "$destFile 大小不匹配 (${destFile.length()} vs $expected); 重新拷贝")
        }

        destFile.parentFile?.mkdirs()
        val start = System.currentTimeMillis()
        context.assets.open(assetPath).use { input ->
            destFile.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
        }
        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "拷贝完成 $assetPath → ${destFile.absolutePath} (${destFile.length()} B, ${elapsed} ms)")
        return destFile
    }

    /**
     * 根据相对路径解析目标文件位置（相对于 filesDir）。
     * 返回的路径在进程重启后保持稳定，与 [ModelCatalog] 中的模型配置一致。
     */
    fun destinationFor(filesDirRelative: String): File {
        val cleaned = filesDirRelative.trimStart('/')
        val out = File(context.filesDir, cleaned)
        out.parentFile?.mkdirs()
        return out
    }

    private companion object {
        const val TAG = "AssetCopy"
        const val BUFFER_SIZE = 64 * 1024 // 64KB 缓冲区
    }
}
