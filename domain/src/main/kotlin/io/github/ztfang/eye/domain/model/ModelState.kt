package io.github.ztfang.eye.domain.model

/** 模型状态枚举 */
enum class ModelStatus {
    NOT_EXIST,      // 不存在
    DOWNLOADING,    // 下载中
    AVAILABLE,      // 已下载
    DELETING,       // 删除中
    ERROR           // 错误
}

/** 模型状态数据类 */
data class ModelState(
    val modelName: String,
    val status: ModelStatus,
    val progress: Float = 0f,           // 下载进度 0..1，仅 DOWNLOADING 状态有效
    val downloadUrl: String = "",       // 下载地址
    val localPath: String? = null,      // 本地路径
    val errorMessage: String? = null    // 错误信息
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
