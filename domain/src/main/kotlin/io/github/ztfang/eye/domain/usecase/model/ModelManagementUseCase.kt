package io.github.ztfang.eye.domain.usecase.model

import javax.inject.Inject

import io.github.ztfang.eye.domain.model.DownloadProgress
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.model.ModelStatus
import io.github.ztfang.eye.domain.model.ModelFileSpec
import io.github.ztfang.eye.domain.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 模型管理用例。
 * 封装模型下载、删除、状态查询的业务逻辑，所有操作在 IO 线程执行。
 */
class ModelManagementUseCase @Inject constructor(
    private val modelRepository: ModelRepository
) {

    /** 观察单个模型状态 */
    fun observeModel(modelName: String): Flow<ModelState> =
        modelRepository.observeModel(modelName)

    /** 观察所有模型状态 */
    fun observeAllModels(): Flow<List<ModelState>> =
        modelRepository.observeAllModels()

    /** 下载单文件模型 */
    suspend fun startDownload(
        modelName: String,
        downloadUrl: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        modelRepository.downloadModel(modelName, downloadUrl, onProgress)
    }

    /**
     * 下载多文件模型（如 NLLB 的 5 个文件）。
     * 文件列表由调用方组装，保持 UseCase 对具体引擎的无关性。
     */
    suspend fun startDownloadFiles(
        modelName: String,
        files: List<ModelFileSpec>,
        onProgress: (DownloadProgress) -> Unit = {},
        onFileComplete: (fileName: String) -> Unit = {}
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        modelRepository.downloadModelFiles(modelName, files, onProgress, onFileComplete)
    }

    /**
     * 下载并解压 Vosk ASR 模型。
     * Vosk 模型为单 zip 文件，下载后需解压到指定目录才能使用。
     *
     * @param modelName 模型名称（如 VOSK_ASR_ZH）
     * @param zipSpec zip 文件规格
     * @param extractDir 解压目标目录
     * @param onProgress 下载进度回调
     */
    suspend fun downloadAndExtractVosk(
        modelName: String,
        zipSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        modelRepository.downloadAndExtractZip(modelName, zipSpec, extractDir, onProgress)
    }

    /**
     * 下载并解压 Sherpa-ONNX ASR 模型。
     * Sherpa-ONNX 模型为单 tar.bz2 文件，下载后需解压到指定目录才能使用。
     *
     * @param modelName 模型名称（如 SHERPA_ONNX_ASR_xxx）
     * @param tarSpec tar.bz2 文件规格
     * @param extractDir 解压目标目录
     * @param onProgress 下载进度回调
     */
    suspend fun downloadAndExtractSherpaOnnx(
        modelName: String,
        tarSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        // Sherpa-ONNX 模型为 tar.bz2 格式
        modelRepository.downloadAndExtractTarBz2(modelName, tarSpec, extractDir, onProgress)
    }

    /**
     * 下载 Sherpa-ONNX 模型的多个原始文件到指定目录（无 tar.bz2 直链时使用）。
     * 用于 X-ASR 等仅提供 git LFS 仓库的模型，逐个下载 encoder/decoder/joiner/tokens。
     *
     * @param modelName 模型名称（用于状态管理）
     * @param files 文件规格列表
     * @param targetDir 目标目录（如 models/sherpa-onnx/<modelId>/）
     * @param onProgress 下载进度回调
     */
    suspend fun downloadSherpaOnnxFiles(
        modelName: String,
        files: List<ModelFileSpec>,
        targetDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        modelRepository.downloadFilesToDir(modelName, files, targetDir, onProgress)
    }

    /** 删除模型 */
    suspend fun removeModel(modelName: String): Result<Unit> = withContext(Dispatchers.IO) {
        modelRepository.deleteModel(modelName)
    }

    /** 检查模型是否已下载 */
    suspend fun isModelAvailable(modelName: String): Boolean =
        modelRepository.getModelPath(modelName) != null
}
