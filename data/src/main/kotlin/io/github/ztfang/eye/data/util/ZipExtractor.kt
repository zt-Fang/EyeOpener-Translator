package io.github.ztfang.eye.data.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Zip 解压工具。
 * 用于 Vosk 模型下载后解压到目标目录。
 */
object ZipExtractor {

    private const val TAG = "ZipExtractor"

    /**
     * 解压 zip 文件到目标目录。
     *
     * @param zipFile zip 文件
     * @param destDir 目标目录（解压后文件的父目录）
     * @param stripTopLevelDir 是否去掉 zip 内的顶层目录（Vosk 模型 zip 通常包一层目录）
     * @return 成功返回 true，失败返回 false
     */
    fun extract(zipFile: File, destDir: File, stripTopLevelDir: Boolean = true): Result<Unit> {
        return runCatching {
            if (!zipFile.exists()) {
                throw IOException("Zip file not found: ${zipFile.absolutePath}")
            }
            destDir.mkdirs()

            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        var entryName = entry.name

                        // 去掉顶层目录（vosk-model-small-cn-0.22/am/... -> am/...）
                        if (stripTopLevelDir) {
                            val firstSlash = entryName.indexOf('/')
                            if (firstSlash > 0) {
                                entryName = entryName.substring(firstSlash + 1)
                            }
                        }

                        val targetFile = File(destDir, entryName)

                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { os ->
                                zis.copyTo(os)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "Extracted ${zipFile.name} to ${destDir.absolutePath}")
            Unit // runCatching 需要显式 Unit 确保返回 Result<Unit>
        }.onFailure {
            Log.e(TAG, "Extract failed: ${it.message}", it)
        }
    }
}
