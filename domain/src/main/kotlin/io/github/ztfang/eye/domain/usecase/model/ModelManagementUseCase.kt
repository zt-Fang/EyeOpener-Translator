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

/** 模型管理用例：下载、删除、状态查询，均在 IO 线程执行 */
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

    /** 下载多文件模型；文件列表由调用方组装，UseCase 与具体引擎解耦 */
    suspend fun startDownloadFiles(
        modelName: String,
        files: List<ModelFileSpec>,
        onProgress: (DownloadProgress) -> Unit = {},
        onFileComplete: (fileName: String) -> Unit = {}
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        modelRepository.downloadModelFiles(modelName, files, onProgress, onFileComplete)
    }

    /** 下载并解压 Vosk 模型（单 zip，如 VOSK_ASR_ZH） */
    suspend fun downloadAndExtractVosk(
        modelName: String,
        zipSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        modelRepository.downloadAndExtractZip(modelName, zipSpec, extractDir, onProgress)
    }

    /** 下载并解压 Sherpa-ONNX 模型（单 tar.bz2，如 SHERPA_ONNX_ASR_xxx） */
    suspend fun downloadAndExtractSherpaOnnx(
        modelName: String,
        tarSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        modelRepository.downloadAndExtractTarBz2(modelName, tarSpec, extractDir, onProgress)
    }

    /** 逐个下载 Sherpa-ONNX 原始文件到 targetDir（无 tar.bz2 直链时用，如 models/sherpa-onnx/<modelId>/） */
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
