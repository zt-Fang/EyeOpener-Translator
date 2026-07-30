package io.github.ztfang.eye.domain.model

/** 单个模型文件的下载路径、URL 和大小 */
data class ModelFileSpec(
    val relativePath: String,
    val url: String,
    val sizeBytes: Long = 0L     // 用于进度计算
)
