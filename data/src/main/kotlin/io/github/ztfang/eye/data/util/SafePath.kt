package io.github.ztfang.eye.data.util

import java.io.File
import java.io.IOException

/**
 * 解压时的路径安全校验（防 Zip Slip / Tar Slip）。
 *
 * 压缩包内条目名可包含 `../` 或绝对路径，直接 `File(destDir, entryName)` 会把文件写到
 * destDir 之外，甚至覆盖应用私有目录以外的位置。这里统一做 canonical 归一化校验：
 * 归一化后的目标路径必须严格位于 destDir 之内。
 *
 * @throws IOException 条目越界时抛出，由调用方的 `runCatching` 统一兜住
 */
internal fun resolveEntryFile(
    destDir: File,
    entryName: String,
): File {
    val destRoot = destDir.canonicalFile
    val target = File(destRoot, entryName).canonicalFile
    // 允许 entryName 归一化后正好等于根目录（目录条目），否则必须是根的子孙
    val insideRoot =
        target.path == destRoot.path ||
            target.path.startsWith(destRoot.path + File.separator)
    if (!insideRoot) {
        throw IOException("压缩包条目越界（疑似 Zip Slip）: $entryName")
    }
    return target
}
