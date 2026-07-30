package io.github.ztfang.eye.domain.repository

import io.github.ztfang.eye.domain.model.DownloadProgress
import io.github.ztfang.eye.domain.model.ModelFileSpec
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.model.ModelStatus
import kotlinx.coroutines.flow.Flow

/** 模型仓库：下载、删除、状态查询 */
interface ModelRepository {

    /** 观察单个模型的状态变化 */
    fun observeModel(modelName: String): Flow<ModelState>

    /** 观察所有模型的状态变化 */
    fun observeAllModels(): Flow<List<ModelState>>

    /** 下载单文件模型（downloadModelFiles 的便捷包装） */
    suspend fun downloadModel(
        modelName: String,
        downloadUrl: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState>

    /** 下载多文件模型到 modelsBaseDir/<modelName>/；onFileComplete 通知单文件完成 */
    suspend fun downloadModelFiles(
        modelName: String,
        files: List<ModelFileSpec>,
        onProgress: (DownloadProgress) -> Unit = {},
        onFileComplete: (fileName: String) -> Unit = {}
    ): Result<ModelState>

    /** 下载多文件到 targetDir（不解压），用于无 tar.bz2 直链、仅有 git LFS 仓库的模型 */
    suspend fun downloadFilesToDir(
        modelName: String,
        files: List<ModelFileSpec>,
        targetDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState>

    /** 删除已下载的模型 */
    suspend fun deleteModel(modelName: String): Result<Unit>

    /** 获取模型的本地路径 */
    fun getModelPath(modelName: String): String?

    /** 更新模型状态 */
    suspend fun updateModelStatus(modelName: String, status: ModelStatus): Result<Unit>

    /** 下载 zip 并解压到 extractDir（Vosk 等 zip 发布的模型） */
    suspend fun downloadAndExtractZip(
        modelName: String,
        zipSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState>

    /** 下载 tar.bz2 并解压到 extractDir（Sherpa-ONNX 等 tar.bz2 发布的模型） */
    suspend fun downloadAndExtractTarBz2(
        modelName: String,
        tarSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState>
}


