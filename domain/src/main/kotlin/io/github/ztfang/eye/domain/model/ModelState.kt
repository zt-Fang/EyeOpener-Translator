package io.github.ztfang.eye.domain.model

/** 模型状态枚举 */
enum class ModelStatus {
    NOT_EXIST,
    DOWNLOADING,
    AVAILABLE,
    DELETING,
    ERROR
}

/** 模型状态数据类 */
data class ModelState(
    val modelName: String,
    val status: ModelStatus,
    val progress: Float = 0f,           // 下载进度 0..1，仅 DOWNLOADING 状态有效
    val downloadUrl: String = "",
    val localPath: String? = null,
    val errorMessage: String? = null
)

/** 下载进度数据类 */
data class DownloadProgress(
    val modelName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
