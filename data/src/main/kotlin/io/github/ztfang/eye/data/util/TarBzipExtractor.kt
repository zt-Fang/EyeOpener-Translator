package io.github.ztfang.eye.data.util

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** tar.bz2 解压（Sherpa-ONNX 模型包），依赖 Apache Commons Compress */
object TarBzipExtractor {

    private const val TAG = "TarBzipExtractor"

    /** 解压 tar.bz2 到 destDir；stripTopLevelDir=true 时去掉 tar 内顶层目录 */
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
