package io.github.ztfang.eye.engine

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 assets 中的模型文件拷贝到 [Context.filesDir]，供 native/ONNX 以真实路径 mmap 加载。
 * （assets 缺少 mmap 语义，openFd 的 fd 在部分设备上也不是真文件。）
 * 仅在目标不存在或大小不匹配时拷贝（比大小而非哈希，开销低）。
 */
@Singleton
class AssetCopy @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 拷贝 assetPath 到 destFile，返回 destFile（取 [File.absolutePath] 传给 native API） */
    fun materialize(assetPath: String, destFile: File): File {
        if (destFile.exists() && destFile.length() > 0) {
            // 已存在则校验大小，避免残留部分拷贝的文件
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

    /** 解析 filesDir 相对路径为目标文件（与 [ModelCatalog] 配置一致） */
    fun destinationFor(filesDirRelative: String): File {
        val cleaned = filesDirRelative.trimStart('/')
        val out = File(context.filesDir, cleaned)
        out.parentFile?.mkdirs()
        return out
    }

    private companion object {
        const val TAG = "AssetCopy"
        const val BUFFER_SIZE = 64 * 1024
    }
}
