package io.github.ztfang.eye.domain.repository

import io.github.ztfang.eye.domain.model.DownloadProgress
import io.github.ztfang.eye.domain.model.ModelFileSpec
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.model.ModelStatus
import kotlinx.coroutines.flow.Flow

/**
 * 模型仓库接口。
 * 定义模型下载、删除、状态查询等能力。
 */
interface ModelRepository {

    /** 观察单个模型的状态变化 */
    fun observeModel(modelName: String): Flow<ModelState>

    /** 观察所有模型的状态变化 */
    fun observeAllModels(): Flow<List<ModelState>>

    /**
     * 下载单文件模型。
     * 便捷包装，底层调用 downloadModelFiles。
     */
    suspend fun downloadModel(
        modelName: String,
        downloadUrl: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState>

    /**
     * 下载多文件模型（如 NLLB 的 5 个文件）。
     * onProgress 报告总进度，onFileComplete 通知单个文件完成。
     * 文件下载到 modelsBaseDir/<modelName>/ 目录。
     */
    suspend fun downloadModelFiles(
        modelName: String,
        files: List<ModelFileSpec>,
        onProgress: (DownloadProgress) -> Unit = {},
        onFileComplete: (fileName: String) -> Unit = {}
    ): Result<ModelState>

    /**
     * 下载多文件到指定目录（不解压）。
     * 用于 Sherpa-ONNX 等模型：官方仅提供 git LFS 仓库无 tar.bz2 直链，
     * 需逐个下载原始文件到 extractDir（如 models/sherpa-onnx/<modelId>/）。
     *
     * @param modelName 模型名称（用于状态管理）
     * @param files 文件规格列表
     * @param targetDir 目标目录绝对路径
     * @param onProgress 下载进度回调
     */
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

    /**
     * 下载 zip 模型并解压到指定目录。
     * 用于 Vosk 等以 zip 形式发布的模型。
     *
     * @param modelName 模型名称
     * @param zipSpec zip 文件规格
     * @param extractDir 解压目标目录（绝对路径）
     * @param onProgress 下载进度回调
     */
    suspend fun downloadAndExtractZip(
        modelName: String,
        zipSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState>

    /**
     * 下载 tar.bz2 模型并解压到指定目录。
     * Sherpa-ONNX 等模型以 tar.bz2 形式发布。
     */
    suspend fun downloadAndExtractTarBz2(
        modelName: String,
        tarSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState>
}


