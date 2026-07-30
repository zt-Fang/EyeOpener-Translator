package io.github.ztfang.eye.data.util

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Tar.Bz2 解压工具。
 * 用于 Sherpa-ONNX 模型下载后解压（模型以 tar.bz2 格式发布）。
 *
 * 依赖：Apache Commons Compress
 */
object TarBzipExtractor {

    private const val TAG = "TarBzipExtractor"

    /**
     * 解压 tar.bz2 文件到目标目录。
     *
     * @param tarBzFile tar.bz2 文件
     * @param destDir 目标目录（解压后文件的父目录）
     * @param stripTopLevelDir 是否去掉 tar 内的顶层目录
     * @return 成功返回 Result.success，失败返回 Result.failure
     */
    fun extract(tarBzFile: File, destDir: File, stripTopLevelDir: Boolean = true): Result<Unit> {
        return runCatching {
            if (!tarBzFile.exists()) {
                throw IOException("Tar.bz2 file not found: ${tarBzFile.absolutePath}")
            }
            destDir.mkdirs()

            FileInputStream(tarBzFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry = tarIn.nextTarEntry
                            while (entry != null) {
                                // 计算目标路径，可选去除顶层目录
                                val entryName = if (stripTopLevelDir) {
                                    val slashIdx = entry.name.indexOf('/')
                                    if (slashIdx > 0 && slashIdx < entry.name.length - 1)
                                        entry.name.substring(slashIdx + 1)
                                    else entry.name
                                } else {
                                    entry.name
                                }

                                if (entryName.isBlank() || entryName == "/") {
                                    entry = tarIn.nextTarEntry
                                    continue
                                }

                                val outFile = File(destDir, entryName)

                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        val buffer = ByteArray(64 * 1024)
                                        var len: Int
                                        while (tarIn.read(buffer).also { len = it } != -1) {
                                            fos.write(buffer, 0, len)
                                        }
                                    }
                                }
                                entry = tarIn.nextTarEntry
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Extracted ${tarBzFile.name} to ${destDir.absolutePath}")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Extract failed: ${e.message}", e)
        }
    }
}
