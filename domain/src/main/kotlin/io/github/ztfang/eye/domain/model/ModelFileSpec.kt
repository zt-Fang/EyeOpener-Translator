package io.github.ztfang.eye.domain.model

/**
 * 模型文件规格描述。
 * 定义单个模型文件的下载路径、URL 和大小。
 */
data class ModelFileSpec(
    val relativePath: String,    // 本地相对路径
    val url: String,             // 下载 URL
    val sizeBytes: Long = 0L     // 文件大小（字节），用于进度计算
)
