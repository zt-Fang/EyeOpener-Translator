package io.github.ztfang.eye.data.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** zip 解压（Vosk 模型包） */
object ZipExtractor {

    private const val TAG = "ZipExtractor"

    /** 解压 zip 到 destDir；stripTopLevelDir=true 时去掉 zip 内顶层目录（Vosk zip 通常包一层） */
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
            Unit
        }.onFailure {
            Log.e(TAG, "Extract failed: ${it.message}", it)
        }
    }
}
