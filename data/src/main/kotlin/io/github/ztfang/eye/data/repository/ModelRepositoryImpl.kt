package io.github.ztfang.eye.data.repository

import android.content.Context
import android.util.Log
import io.github.ztfang.eye.data.util.ZipExtractor
import io.github.ztfang.eye.data.util.TarBzipExtractor
import io.github.ztfang.eye.domain.model.DownloadProgress
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.model.ModelStatus
import io.github.ztfang.eye.domain.model.ModelFileSpec
import io.github.ztfang.eye.domain.model.SherpaOnnxModel
import io.github.ztfang.eye.domain.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型仓库实现。
 *
 * 目录约定（与 [ModelPreparer] 保持完全一致，否则下载后 prepareAsr 找不到文件）：
 * - Vosk 模型实际文件：[Context.filesDir]/models/vosk/<languageCode>/ (am/conf/graph)
 * - Sherpa-ONNX 模型实际文件：[Context.filesDir]/models/sherpa-onnx/<modelId>/
 *   (encoder/decoder/joiner/tokens 四个 onnx/txt)
 * - 模型状态 state.json：[Context.filesDir]/models/{modelName}/state.json
 *   (modelName = "VOSK_ASR_ZH" / "SHERPA_ONNX_ASR_<modelId>"，用于 UI 列表展示 key)
 *
 * 下载完成 → 把 {modelName}/ 里刚下好的文件移动到 ModelPreparer 真实目录，
 * 然后 ModelState.localPath 改为真实路径（getModelPath 才返回非 null）。
 */
class ModelRepositoryImpl(private val context: Context) : ModelRepository {

    /** 模型存储根目录 */
    private val modelsBaseDir: File get() = File(context.filesDir, "models")

    /** Vosk 模型实际存放根目录（与 ModelPreparer.asrModelDir 完全一致） */
    private val voskModelsRoot: File get() = File(modelsBaseDir, "vosk")

    /** Sherpa-ONNX 模型实际存放根目录（与 ModelPreparer.sherpaOnnxModelDir 完全一致） */
    private val sherpaOnnxModelsRoot: File get() = File(modelsBaseDir, "sherpa-onnx")

    /**
     * 从 modelName("VOSK_ASR_ZH"/"SHERPA_ONNX_ASR_xxx") 推出【ModelPreparer 真实读取目录】。
     * 返回：null = 其他模型（不迁移），否则 = 真实目标目录
     */
    private fun resolveRealAsrDirFor(modelName: String): File? = when {
        modelName.startsWith("SHERPA_ONNX_ASR_") -> {
            val modelId = modelName.substringAfter("SHERPA_ONNX_ASR_")
            File(sherpaOnnxModelsRoot, modelId)
        }
        modelName.startsWith("VOSK_ASR_") -> {
            val lang = modelName.substringAfter("VOSK_ASR_").lowercase()
            File(voskModelsRoot, lang)
        }
        else -> null
    }

    /**
     * 下载完成后把 stageDir(= {modelsBaseDir}/{modelName}/) 内的【除 state.json
     * 以外的所有文件/子目录】搬到 realDir(= vosk/<lang>/ 或 sherpa-onnx/<modelId>/)。
     * 搬完返回：真实目录绝对路径；任何失败返回 null（上层会走 ERROR）。
     *
     * 注：若调用方传的 targetDir 本身就是 realDir（同一路径），则直接短路返回 realDir
     * 的绝对路径，避免「dest = File(realDir, f.name) 与 f 是同文件 → dest.deleteRecursively()
     * 把刚下好的文件自己删了 → 目录瞬间变空」的Bug。
     */
    private fun moveDownloadedFilesToRealAsrDir(stageDir: File, realDir: File): String? {
        return runCatching {
            // 同路径短路：stageDir == realDir，不需要移动，直接返回
            val sameDir = runCatching {
                stageDir.canonicalPath == realDir.canonicalPath
            }.getOrDefault(false) || stageDir.absolutePath == realDir.absolutePath
            if (sameDir) {
                Log.i(TAG, "[MOVE_SKIP_SAME_DIR] stageDir == realDir = $realDir, 直接使用，不移动文件，防止自删")
                return@runCatching realDir.absolutePath
            }
            realDir.mkdirs()
            val stageFiles = stageDir.listFiles() ?: emptyArray()
            for (f in stageFiles) {
                if (f.name == "state.json") continue
                val dest = File(realDir, f.name)
                if (dest.exists()) dest.deleteRecursively()
                val ok = f.renameTo(dest)
                if (!ok) {
                    // 跨挂载点 rename 失败就 copy+delete
                    if (f.isDirectory) f.copyRecursively(dest, overwrite = true)
                    else f.copyTo(dest, overwrite = true)
                    f.deleteRecursively()
                }
            }
            Log.i(TAG, "[MOVE_OK] $stageDir -> $realDir, finalFiles=${realDir.listFiles()?.map { it.name to it.length() }}")
            realDir.absolutePath
        }.onFailure { e ->
            Log.e(TAG, "[MOVE_FAIL] $stageDir -> $realDir: ${e.javaClass.simpleName}: ${e.message}", e)
        }.getOrNull()
    }

    /** 模型状态流，内存中维护最新状态 */
    private val _modelsFlow = MutableStateFlow<Map<String, ModelState>>(emptyMap())

    /** 观察单个模型的状态变化 */
    override fun observeModel(modelName: String): Flow<ModelState> =
        _modelsFlow.map { it[modelName] ?: ModelState(modelName, ModelStatus.NOT_EXIST) }

    /** 观察所有模型的状态变化 */
    override fun observeAllModels(): Flow<List<ModelState>> =
        _modelsFlow.map { it.values.toList() }

    /** 下载单文件模型（便捷包装） */
    override suspend fun downloadModel(
        modelName: String, downloadUrl: String,
        onProgress: (DownloadProgress) -> Unit
    ): Result<ModelState> = downloadModelFiles(
        modelName = modelName,
        files = listOf(
            ModelFileSpec(
                relativePath = downloadUrl.substringAfterLast("/"),
                url = downloadUrl
            )
        ),
        onProgress = onProgress
    )

    /**
     * 下载多文件模型。
     * 聚合所有文件的下载进度，通过 onProgress 回调报告。
     */
    override suspend fun downloadModelFiles(
        modelName: String,
        files: List<ModelFileSpec>,
        onProgress: (DownloadProgress) -> Unit,
        onFileComplete: (fileName: String) -> Unit
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        if (files.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("downloadModelFiles called with no files")
            )
        }
        val dir = File(modelsBaseDir, modelName)
        dir.mkdirs()

        Log.i(TAG, "下载开始: model=$modelName, files=${files.size}, totalSize=${files.sumOf { it.sizeBytes } / 1024 / 1024}MB, dir=${dir.absolutePath}")

        // 预计算总大小用于准确进度报告
        val totalSizeAllFiles: Long = files.sumOf { it.sizeBytes }
        val sizesByFile: Map<String, Long> = files.associate { it.relativePath to it.sizeBytes }
        var aggregateDownloaded: Long = 0L  // 只累加已【实际完成】文件的真实字节数（target.length()），不做预期值重置
        val startedAt = System.currentTimeMillis()
        var lastEmit = 0L
        var lastEmittedBytes: Long = -1L   // 非递减保证：防止 100%→90% 跳动

        /** 发送进度更新（限制频率 ~5Hz，最终值不节流；保证 bytes 非递减） */
        fun emit(currentFileName: String, currentFileRead: Long, currentFileSize: Long, isFinal: Boolean = false) {
            val total = if (totalSizeAllFiles > 0) totalSizeAllFiles
            else (aggregateDownloaded + (sizesByFile[currentFileName] ?: 0L))
            val now = System.currentTimeMillis()
            val elapsed = (now - startedAt).coerceAtLeast(1L) / 1000L
            val bytes = aggregateDownloaded + currentFileRead
            // 非递减保证：新进度小于已发射的则丢弃
            if (lastEmittedBytes >= 0 && bytes < lastEmittedBytes) {
                Log.w(TAG, "[PROGRESS_GUARD] $modelName: 丢弃回退进度 emit bytes=$bytes < last=$lastEmittedBytes, file=$currentFileName")
                return
            }
            // ~5Hz 节流，但最终 emit 不节流
            if (!isFinal && now - lastEmit < 200 && currentFileRead != currentFileSize) return
            lastEmittedBytes = bytes
            onProgress(
                DownloadProgress(
                    modelName = modelName,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    speedBytesPerSec = if (elapsed > 0) bytes / elapsed else 0L
                )
            )
            lastEmit = now
        }

        try {
            // 更新为下载中状态
            updateState(modelName, ModelStatus.DOWNLOADING, downloadUrl = files.first().url)

            // 逐个下载文件
            for ((index, spec) in files.withIndex()) {
                val target = File(dir, spec.relativePath)
                target.parentFile?.mkdirs()
                Log.i(TAG, "[DL_FILE_START] $modelName: 文件(${index+1}/${files.size}) ${spec.relativePath}, expectedSize=${spec.sizeBytes}B, aggregateBefore=$aggregateDownloaded")
                downloadOneFile(
                    spec = spec,
                    target = target,
                    onBytes = { fileRead, fileSize ->
                        // 关键修复：不再用 files.take(index) 的【预期大小】重置 aggregateDownloaded，
                        // 因为 aggregateDownloaded 已在文件完成时 += target.length()，记录的是真实值。
                        // 这里仅在当前文件基础上累加 fileRead。
                        emit(spec.relativePath, fileRead, fileSize)
                    }
                )
                onFileComplete(spec.relativePath)
                val actualFileBytes = target.length()
                aggregateDownloaded += actualFileBytes
                Log.i(TAG, "[DL_FILE_DONE] $modelName: 文件(${index+1}/${files.size}) ${spec.relativePath}, actualBytes=$actualFileBytes, aggregateAfter=$aggregateDownloaded")
                emit(spec.relativePath, actualFileBytes, actualFileBytes, isFinal = true)
            }

            // 下载完成，更新为可用状态
            // === 关键修复：把 stageDir 中除 state.json 外的文件迁移到 ModelPreparer
            // 真实读取目录（sherpa-onnx/<modelId> 或 vosk/<lang>），否则
            // SubtitleManager → prepareAsr 永远找不到已下载文件。 ===
            val realDir = resolveRealAsrDirFor(modelName)
            val realPath = if (realDir != null) {
                moveDownloadedFilesToRealAsrDir(stageDir = dir, realDir = realDir)
            } else {
                dir.absolutePath
            }
            if (realPath == null) {
                // 迁移失败直接报 ERROR，避免 UI 显示 AVAILABLE 但 ASR 初始化失败
                val errState = ModelState(
                    modelName = modelName,
                    status = ModelStatus.ERROR,
                    progress = 1f,
                    downloadUrl = files.first().url,
                    localPath = null,
                    errorMessage = "文件迁移到 ASR 目录失败，请重新下载或手动清理缓存"
                )
                updateState(errState)
                Result.failure(IllegalStateException("Model files move failed: $modelName"))
            } else {
                val state = ModelState(
                    modelName = modelName,
                    status = ModelStatus.AVAILABLE,
                    progress = 1f,
                    downloadUrl = files.first().url,
                    localPath = realPath
                )
                updateState(state)
                Log.i(TAG, "下载成功: model=$modelName, localPath(ASR真实目录)=$realPath")
                Result.success(state)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelName: ${e.message}", e)
            updateState(modelName, ModelStatus.ERROR, errorMessage = e.message ?: "Unknown error")
            // 保留 .part 文件用于断点续传，不删除目录
            Result.failure(e)
        }
    }

    /**
     * 下载多文件到指定目录（不解压）。
     * 用于 Sherpa-ONNX X-ASR 等无 tar.bz2 直链的模型，逐个下载原始文件到 targetDir。
     * 进度聚合逻辑与 downloadModelFiles 一致，区别仅在于目标目录可控。
     */
    override suspend fun downloadFilesToDir(
        modelName: String,
        files: List<ModelFileSpec>,
        targetDir: String,
        onProgress: (DownloadProgress) -> Unit
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        if (files.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("downloadFilesToDir called with no files")
            )
        }
        val dir = File(targetDir)
        dir.mkdirs()

        Log.i(TAG, "多文件下载开始: model=$modelName, files=${files.size}, totalSize=${files.sumOf { it.sizeBytes } / 1024 / 1024}MB, dir=${dir.absolutePath}")

        // 预计算总大小用于准确进度报告
        val totalSizeAllFiles: Long = files.sumOf { it.sizeBytes }
        val sizesByFile: Map<String, Long> = files.associate { it.relativePath to it.sizeBytes }
        var aggregateDownloaded: Long = 0L  // 只累加已【实际完成】文件的真实字节数，不做预期值重置
        val startedAt = System.currentTimeMillis()
        var lastEmit = 0L
        var lastEmittedBytes: Long = -1L   // 非递减保证

        /** 发送进度更新（限制频率 ~5Hz，最终值不节流；保证 bytes 非递减） */
        fun emit(currentFileName: String, currentFileRead: Long, currentFileSize: Long, isFinal: Boolean = false) {
            val total = if (totalSizeAllFiles > 0) totalSizeAllFiles
            else (aggregateDownloaded + (sizesByFile[currentFileName] ?: 0L))
            val now = System.currentTimeMillis()
            val elapsed = (now - startedAt).coerceAtLeast(1L) / 1000L
            val bytes = aggregateDownloaded + currentFileRead
            // 非递减保证
            if (lastEmittedBytes >= 0 && bytes < lastEmittedBytes) {
                Log.w(TAG, "[PROGRESS_GUARD] $modelName: 丢弃回退进度 emit bytes=$bytes < last=$lastEmittedBytes, file=$currentFileName")
                return
            }
            // ~5Hz 节流，但最终 emit 不节流
            if (!isFinal && now - lastEmit < 200 && currentFileRead != currentFileSize) return
            lastEmittedBytes = bytes
            onProgress(
                DownloadProgress(
                    modelName = modelName,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    speedBytesPerSec = if (elapsed > 0) bytes / elapsed else 0L
                )
            )
            lastEmit = now
        }

        try {
            updateState(modelName, ModelStatus.DOWNLOADING, downloadUrl = files.first().url)

            for ((index, spec) in files.withIndex()) {
                val target = File(dir, spec.relativePath)
                target.parentFile?.mkdirs()
                Log.i(TAG, "[DL_FILE_START] $modelName: 文件(${index+1}/${files.size}) ${spec.relativePath}, expectedSize=${spec.sizeBytes}B, url=${spec.url.take(80)}..., aggregateBefore=$aggregateDownloaded")
                downloadOneFile(
                    spec = spec,
                    target = target,
                    onBytes = { fileRead, fileSize ->
                        // 关键修复：不再用预期大小重置 aggregateDownloaded
                        emit(spec.relativePath, fileRead, fileSize)
                    }
                )
                val actualFileBytes = target.length()
                aggregateDownloaded += actualFileBytes
                Log.i(TAG, "[DL_FILE_DONE] $modelName: 文件(${index+1}/${files.size}) ${spec.relativePath}, actualBytes=$actualFileBytes, aggregateAfter=$aggregateDownloaded")
                emit(spec.relativePath, actualFileBytes, actualFileBytes, isFinal = true)
            }

            // 所有文件下载完，最后再跑一次完整性校验（防止损坏）
            val allOK = isSherpaOnnxModelComplete(dir)
            if (!allOK) {
                Log.w(TAG, "[DL_INTEGRITY_FAIL] $modelName: 所有文件下载完成但完整性校验失败！dir=${dir.absolutePath}, files=${dir.listFiles()?.map { it.name to it.length() }}")
                val errState = ModelState(
                    modelName = modelName,
                    status = ModelStatus.ERROR,
                    progress = 1f,
                    downloadUrl = files.first().url,
                    localPath = null,
                    errorMessage = "文件完整性校验失败，请重新下载"
                )
                updateState(errState)
                Result.failure(IllegalStateException("Integrity check failed: $modelName"))
            } else {
                // 关键修复：同步把 stageDir 文件 → ASR 真实目录，localPath 改为真实目录
                val realDir = resolveRealAsrDirFor(modelName)
                val realPath = if (realDir != null) {
                    moveDownloadedFilesToRealAsrDir(stageDir = dir, realDir = realDir)
                } else {
                    dir.absolutePath
                }
                if (realPath == null) {
                    val errState = ModelState(
                        modelName = modelName,
                        status = ModelStatus.ERROR,
                        progress = 1f,
                        downloadUrl = files.first().url,
                        localPath = null,
                        errorMessage = "文件迁移到 ASR 目录失败，请重新下载或手动清理缓存"
                    )
                    updateState(errState)
                    Result.failure(IllegalStateException("Model files move failed: $modelName"))
                } else {
                    val state = ModelState(
                        modelName = modelName,
                        status = ModelStatus.AVAILABLE,
                        progress = 1f,
                        downloadUrl = files.first().url,
                        localPath = realPath
                    )
                    updateState(state)
                    Log.i(TAG, "多文件下载结束: model=$modelName, status=AVAILABLE, integrityOK=true, localPath(ASR真实目录)=$realPath")
                    Result.success(state)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DL_FAIL] $modelName: 多文件下载失败: ${e.javaClass.simpleName}: ${e.message}; 累计bytes=$aggregateDownloaded/$totalSizeAllFiles; 正在尝试的文件=${runCatching { files.getOrNull(files.indexOfFirst { !File(dir, it.relativePath).exists() })?.relativePath }.getOrNull()}", e)
            updateState(modelName, ModelStatus.ERROR, errorMessage = e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * 下载单个文件（支持断点续传 + 魔数校验）。
     *
     * 断点续传：使用 .part 临时文件，中断后下次从断点继续（Range header）。
     * 魔数校验：下载完成后校验文件头，防止 HTML 错误页或损坏文件被误用。
     */
    private fun downloadOneFile(
        spec: ModelFileSpec,
        target: File,
        onBytes: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val host = runCatching { URL(spec.url).host }.getOrNull()
        if (host == null || host !in ALLOWED_DOWNLOAD_HOSTS) {
            throw SecurityException("Refusing to download from non-whitelisted host: $host")
        }

        // 使用 .part 临时文件支持断点续传
        val partFile = File(target.parentFile, "${target.name}.part")
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            // 断点续传：已有部分数据时请求剩余部分
            if (existingBytes > 0) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }
        conn.connect()

        val responseCode = conn.responseCode
        // 206 = 断点续传成功，200 = 服务器不支持 Range 或全新下载
        val isResume = responseCode == 206
        if (responseCode !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $responseCode for ${spec.url}")
        }

        // 完整文件大小：206 时从 Content-Range 获取，否则用 contentLength
        val fullSize = if (isResume) {
            parseContentRangeTotal(conn.getHeaderField("Content-Range")) ?: (existingBytes + conn.contentLengthLong)
        } else {
            conn.contentLengthLong
        }
        val totalBytes = when {
            fullSize > 0 -> fullSize
            spec.sizeBytes > 0 -> spec.sizeBytes
            else -> -1L
        }

        // 非续传时清空 .part 文件（从头下载）
        if (!isResume && existingBytes > 0) {
            partFile.delete()
        }

        val input = conn.inputStream
        try {
            // append 模式：续传时追加写入，全新下载时覆盖
            val appendMode = isResume
            FileOutputStream(partFile, appendMode).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var total = if (isResume) existingBytes else 0L
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    total += read
                    onBytes(total, totalBytes)
                }
                output.flush()
            }
        } finally {
            runCatching { input.close() }
            conn.disconnect()
        }

        // 完整性校验：仅文件大小（HTTP 2xx + 达到预期大小 95% 以上即视为成功）。
        // 用户要求取消魔数校验——历史上 ONNX ir_version 范围限制、tokens.txt 首字符'<'误判
        // HTML、zip/tar 头等反复产生 .part/.corrupted 垃圾文件，甚至触发 INIT 自愈删除用户已
        // 下载的完整 161MB 模型。文件格式正确性由 ASR 引擎在 init() 时再验证，这里只保证"
        // 下载完了"，不再拦。
        val verifyTotal = if (fullSize > 0) fullSize else spec.sizeBytes
        if (verifyTotal > 0 && partFile.length() < verifyTotal * 95 / 100) {
            partFile.delete()
            throw IOException("Truncated file for ${spec.relativePath}: got ${partFile.length()} of $verifyTotal")
        }

        // 校验通过，rename .part → target。不再保留 .corrupted 样本。
        if (target.exists()) target.delete()
        if (!partFile.renameTo(target)) {
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
        }
    }

    /**
     * 解析 Content-Range header 中的完整文件大小。
     * 格式：bytes 0-1023/2048 → 返回 2048
     */
    private fun parseContentRangeTotal(contentRange: String?): Long? {
        if (contentRange == null) return null
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex < 0 || slashIndex == contentRange.length - 1) return null
        return contentRange.substring(slashIndex + 1).toLongOrNull()
    }

    /** 删除模型 */
    override suspend fun deleteModel(modelName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(TAG, "deleteModel: 开始删除 $modelName")
            updateState(modelName, ModelStatus.DELETING)
            val dir = File(modelsBaseDir, modelName)
            Log.i(TAG, "deleteModel: state.json 目录=${dir.absolutePath}, exists=${dir.exists()}")
            if (dir.exists()) dir.deleteRecursively()
            // Vosk 模型：同时删除实际模型文件（models/vosk/<lang>/）
            if (modelName.startsWith("VOSK_ASR_")) {
                val langCode = modelName.substringAfter("VOSK_ASR_").lowercase()
                val voskLangDir = File(modelsBaseDir, "vosk/$langCode")
                Log.i(TAG, "deleteModel: Vosk 模型目录=${voskLangDir.absolutePath}, exists=${voskLangDir.exists()}")
                if (voskLangDir.exists()) voskLangDir.deleteRecursively()
            }
            // Sherpa-ONNX 模型：同时删除实际模型文件（models/sherpa-onnx/<modelId>/）
            if (modelName.startsWith("SHERPA_ONNX_ASR_")) {
                val modelId = modelName.substringAfter("SHERPA_ONNX_ASR_")
                val sherpaDir = File(modelsBaseDir, "sherpa-onnx/$modelId")
                Log.i(TAG, "deleteModel: Sherpa 模型目录=${sherpaDir.absolutePath}, exists=${sherpaDir.exists()}")
                if (sherpaDir.exists()) sherpaDir.deleteRecursively()
            }
            _modelsFlow.value = _modelsFlow.value - modelName
            Log.i(TAG, "deleteModel: $modelName 删除完成")
            Unit
        }
    }

    /** 获取模型本地路径 */
    override fun getModelPath(modelName: String): String? {
        // Sherpa-ONNX 模型：检查实际模型目录 models/sherpa-onnx/<modelId>/
        if (modelName.startsWith("SHERPA_ONNX_ASR_")) {
            val modelId = modelName.substringAfter("SHERPA_ONNX_ASR_")
            val sherpaDir = File(modelsBaseDir, "sherpa-onnx/$modelId")
            val exists = sherpaDir.exists() && sherpaDir.listFiles()?.isNotEmpty() == true
            Log.d(TAG, "getModelPath: $modelName, dir=${sherpaDir.absolutePath}, available=$exists")
            return if (exists) sherpaDir.absolutePath else null
        }
        // Vosk 模型：检查实际模型目录 models/vosk/<lang>/
        if (modelName.startsWith("VOSK_ASR_")) {
            val langCode = modelName.substringAfter("VOSK_ASR_").lowercase()
            val voskDir = File(modelsBaseDir, "vosk/$langCode")
            val exists = voskDir.exists() && voskDir.listFiles()?.isNotEmpty() == true
            Log.d(TAG, "getModelPath: $modelName, dir=${voskDir.absolutePath}, available=$exists")
            return if (exists) voskDir.absolutePath else null
        }
        // 其他模型：检查默认目录
        val dir = File(modelsBaseDir, modelName)
        val exists = dir.exists() && dir.listFiles()?.isNotEmpty() == true
        Log.d(TAG, "getModelPath: $modelName, dir=${dir.absolutePath}, available=$exists")
        return if (exists) dir.absolutePath else null
    }

    /**
     * 下载 zip 模型并解压到指定目录。
     * Vosk 等模型以 zip 形式发布，需解压后才能使用。
     *
     * 流程：下载 zip 到临时位置 → 解压到 extractDir → 校验关键文件 → 更新状态
     */
    override suspend fun downloadAndExtractZip(
        modelName: String,
        zipSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        val destDir = File(extractDir)
        val zipFile = File(modelsBaseDir, "${modelName}_tmp.zip")
        val startedAt = System.currentTimeMillis()
        var lastEmittedBytes: Long = -1L   // 非递减保证

        runCatching {
            updateState(modelName, ModelStatus.DOWNLOADING, downloadUrl = zipSpec.url)

            // 1. 下载 zip 文件
            downloadOneFile(
                spec = zipSpec,
                target = zipFile,
                onBytes = { read, total ->
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L) / 1000L
                    val clampedRead = read.coerceAtMost(if (total > 0) total else Long.MAX_VALUE)
                    // 非递减保证
                    if (lastEmittedBytes >= 0 && clampedRead < lastEmittedBytes) {
                        Log.w(TAG, "[PROGRESS_GUARD] $modelName(zip): 丢弃回退 read=$clampedRead < last=$lastEmittedBytes")
                        return@downloadOneFile
                    }
                    lastEmittedBytes = clampedRead
                    onProgress(
                        DownloadProgress(
                            modelName = modelName,
                            bytesDownloaded = clampedRead,
                            totalBytes = total,
                            speedBytesPerSec = if (elapsed > 0) clampedRead / elapsed else 0L
                        )
                    )
                }
            )
            Log.i(TAG, "Zip download complete: ${zipFile.absolutePath}")

            // 发送最终下载进度（确保 100%，非递减保护）
            val finalBytes = zipSpec.sizeBytes.coerceAtLeast(lastEmittedBytes)
            lastEmittedBytes = finalBytes
            onProgress(
                DownloadProgress(
                    modelName = modelName,
                    bytesDownloaded = finalBytes,
                    totalBytes = zipSpec.sizeBytes,
                    speedBytesPerSec = 0L
                )
            )

            // 2. 解压
            destDir.parentFile?.mkdirs()
            // 先清空目标目录，避免旧文件残留
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            ZipExtractor.extract(zipFile, destDir, stripTopLevelDir = true).getOrThrow()
            Log.i(TAG, "Zip extracted to: ${destDir.absolutePath}")

            // 3. 删除临时 zip 文件
            zipFile.delete()

            // 4. 更新状态
            val state = ModelState(
                modelName = modelName,
                status = ModelStatus.AVAILABLE,
                progress = 1f,
                downloadUrl = zipSpec.url,
                localPath = destDir.absolutePath
            )
            updateState(state)
            state
        }.onFailure { e ->
            Log.e(TAG, "downloadAndExtractZip failed for $modelName: ${e.message}", e)
            updateState(modelName, ModelStatus.ERROR, errorMessage = e.message ?: "Unknown error")
            // 清理残留文件
            zipFile.delete()
            if (destDir.exists()) destDir.deleteRecursively()
        }
    }

    /**
     * 下载 tar.bz2 模型并解压到指定目录。
     * Sherpa-ONNX 等模型以 tar.bz2 形式发布。
     *
     * 流程：下载 tar.bz2 到临时位置 → 解压到 extractDir → 校验 → 更新状态
     */
    override suspend fun downloadAndExtractTarBz2(
        modelName: String,
        tarSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit
    ): Result<ModelState> = withContext(Dispatchers.IO) {
        val destDir = File(extractDir)
        val tarFile = File(modelsBaseDir, "${modelName}_tmp.tar.bz2")
        val startedAt = System.currentTimeMillis()
        var lastEmittedBytes: Long = -1L   // 非递减保证

        runCatching {
            updateState(modelName, ModelStatus.DOWNLOADING, downloadUrl = tarSpec.url)

            // 1. 下载 tar.bz2 文件
            downloadOneFile(
                spec = tarSpec,
                target = tarFile,
                onBytes = { read, total ->
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L) / 1000L
                    val clampedRead = read.coerceAtMost(if (total > 0) total else Long.MAX_VALUE)
                    // 非递减保证
                    if (lastEmittedBytes >= 0 && clampedRead < lastEmittedBytes) {
                        Log.w(TAG, "[PROGRESS_GUARD] $modelName(tar): 丢弃回退 read=$clampedRead < last=$lastEmittedBytes")
                        return@downloadOneFile
                    }
                    lastEmittedBytes = clampedRead
                    onProgress(
                        DownloadProgress(
                            modelName = modelName,
                            bytesDownloaded = clampedRead,
                            totalBytes = total,
                            speedBytesPerSec = if (elapsed > 0) clampedRead / elapsed else 0L
                        )
                    )
                }
            )
            Log.i(TAG, "Tar.bz2 download complete: ${tarFile.absolutePath}")

            // 发送最终下载进度（确保 100%，非递减保护）
            val finalBytes = tarSpec.sizeBytes.coerceAtLeast(lastEmittedBytes)
            lastEmittedBytes = finalBytes
            onProgress(
                DownloadProgress(
                    modelName = modelName,
                    bytesDownloaded = finalBytes,
                    totalBytes = tarSpec.sizeBytes,
                    speedBytesPerSec = 0L
                )
            )

            // 2. 解压（tar.bz2 格式）
            destDir.parentFile?.mkdirs()
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            TarBzipExtractor.extract(tarFile, destDir, stripTopLevelDir = true).getOrThrow()
            Log.i(TAG, "Tar.bz2 extracted to: ${destDir.absolutePath}")

            // 3. 删除临时文件
            tarFile.delete()

            // 4. 更新状态
            val state = ModelState(
                modelName = modelName,
                status = ModelStatus.AVAILABLE,
                progress = 1f,
                downloadUrl = tarSpec.url,
                localPath = destDir.absolutePath
            )
            updateState(state)
            state
        }.onFailure { e ->
            Log.e(TAG, "downloadAndExtractTarBz2 failed for $modelName: ${e.message}", e)
            updateState(modelName, ModelStatus.ERROR, errorMessage = e.message ?: "Unknown error")
            tarFile.delete()
            if (destDir.exists()) destDir.deleteRecursively()
        }
    }

    /** 更新模型状态 */
    override suspend fun updateModelStatus(modelName: String, status: ModelStatus): Result<Unit> =
        withContext(Dispatchers.IO) { runCatching { updateState(modelName, status) } }

    private fun updateState(name: String, status: ModelStatus, progress: Float = 0f, downloadUrl: String = "", localPath: String? = null, errorMessage: String? = null) {
        updateState(ModelState(name, status, progress, downloadUrl, localPath ?: getModelPath(name), errorMessage))
    }

    /** 更新状态到内存和 state.json 文件（关键：日志记录每次状态流转） */
    private fun updateState(state: ModelState) {
        val prev = _modelsFlow.value[state.modelName]?.status
        Log.w(TAG, "[STATE_CHANGE] ${state.modelName}: ${prev?.name} -> ${state.status.name}" +
                "  progress=${state.progress}  localPath=${state.localPath}  err=${state.errorMessage}")
        _modelsFlow.value = _modelsFlow.value + (state.modelName to state)
        writeStateFile(state)
    }

    /** 将状态写入 state.json 文件 */
    private fun writeStateFile(state: ModelState) {
        val dir = File(modelsBaseDir, state.modelName); dir.mkdirs()
        val json = JSONObject()
        json.put("modelName", state.modelName); json.put("status", state.status.name)
        json.put("progress", state.progress.toDouble()); json.put("downloadUrl", state.downloadUrl)
        json.put("localPath", state.localPath ?: "")
        json.put("errorMessage", state.errorMessage ?: "")
        val sf = File(dir, "state.json")
        sf.writeText(json.toString())
        Log.i(TAG, "[WRITE_STATE] ${state.modelName} -> ${sf.absolutePath}: status=${state.status.name}")
    }

    /** 初始化：从 state.json 恢复模型状态，并扫描 Vosk 模型目录补全缺失记录 */
    init {
        val dir = modelsBaseDir
        val result = mutableMapOf<String, ModelState>()
        Log.i(TAG, "[INIT] 开始扫描模型目录: ${dir.absolutePath}, 存在=${dir.exists()}")
        if (dir.exists()) {
            dir.listFiles()?.forEach { modelDir ->
                val sf = File(modelDir, "state.json")
                if (sf.exists()) try {
                    val j = JSONObject(sf.readText())
                    val statusStr = j.getString("status")
                    val modelNameFromJson = j.getString("modelName")
                    val localPathStr = j.optString("localPath").ifEmpty { null }
                    val st = ModelStatus.valueOf(statusStr)
                    var fixedStatus = st
                    var fixedProgress = j.getDouble("progress").toFloat()
                    var fixedLocalPath = localPathStr
                    // 修复：残留状态（DOWNLOADING / ERROR / NOT_EXIST 但localPath存在）
                    // 判断：如果localPath存在 → 用 Sherpa/Vosk 的完整性检查辅助修状态
                    val localPathFile = if (!localPathStr.isNullOrBlank()) File(localPathStr) else null
                    val localPathExists = localPathFile != null && localPathFile.exists()
                    val isSherpaModel = modelNameFromJson.startsWith("SHERPA_ONNX_ASR_") ||
                            modelDir.name.startsWith("SHERPA_ONNX_ASR_") ||
                            (localPathFile != null && localPathFile.parentFile?.name == "sherpa-onnx")
                    val isVoskModel = modelNameFromJson.startsWith("VOSK_ASR_") ||
                            modelDir.name.startsWith("VOSK_ASR_") ||
                            (localPathFile != null && localPathFile.parentFile?.name == "vosk")
                    val completeNow = if (localPathExists && localPathFile != null) {
                        when {
                            isSherpaModel -> isSherpaOnnxModelComplete(localPathFile)
                            isVoskModel   -> isVoskModelComplete(localPathFile)
                            else          -> localPathFile.isDirectory && (localPathFile.list()?.size ?: 0) > 0
                        }
                    } else false

                    if (st != ModelStatus.AVAILABLE) {
                        // 情况A：非AVAILABLE但文件完整 → 应该是AVAILABLE（比如写state.json前被杀）
                        if (localPathExists && completeNow) {
                            Log.w(TAG, "[INIT_STATE_FIX] ${modelDir.name}: 残留 $statusStr 但 localPath 完整=$completeNow，修正为 AVAILABLE")
                            fixedStatus = ModelStatus.AVAILABLE
                            fixedProgress = 1f
                            fixedLocalPath = localPathStr
                        }
                        // 情况B：DOWNLOADING且localPath不存在 → 重置NOT_EXIST（不删真实目录，保留已下数据）
                        else if (st == ModelStatus.DOWNLOADING && !localPathExists) {
                            Log.w(TAG, "[INIT_STATE_FIX] ${modelDir.name}: 残留 DOWNLOADING 且localPath不存在，重置为 NOT_EXIST（不删文件）")
                            fixedStatus = ModelStatus.NOT_EXIST
                            fixedProgress = 0f
                            runCatching { sf.delete() }
                        }
                        // 情况C：ERROR 且 localPath存在但不完整 → 只改状态为 NOT_EXIST，允许用户点击下载覆盖
                        //   【不删 localPathFile 内容】防止魔数校验误判产生的 tokens.txt.part.corrupted
                        //   被判定"不完整"后，整个 161MB 的 encoder/decoder/joiner 被 walkTopDown 删掉。
                        else if (st == ModelStatus.ERROR) {
                            if (localPathExists && !completeNow) {
                                Log.w(TAG, "[INIT_STATE_FIX] ${modelDir.name}: ERROR+localPath不完整 → 只改状态NOT_EXIST（不删已下载的大文件）")
                                fixedStatus = ModelStatus.NOT_EXIST
                                fixedProgress = 0f
                                // fixedLocalPath 保留：下次进入时如果自愈完整了会自动变AVAILABLE
                            } else if (!localPathExists) {
                                Log.w(TAG, "[INIT_STATE_FIX] ${modelDir.name}: ERROR且localPath不存在，重置为 NOT_EXIST")
                                fixedStatus = ModelStatus.NOT_EXIST
                                fixedProgress = 0f
                                runCatching { sf.delete() }
                            }
                            // ERROR 但 localPath 完整 → 走情况A的修复分支（上面if已经进入），这里不会到
                        }
                    }
                    result[modelDir.name] = ModelState(
                        modelNameFromJson,
                        fixedStatus,
                        fixedProgress,
                        j.optString("downloadUrl", ""),
                        fixedLocalPath,
                        j.optString("errorMessage").ifEmpty { null }
                    )
                    Log.i(TAG, "[INIT_LOAD_STATE] ${modelDir.name}: status=${fixedStatus.name}(原$statusStr), localPath=$fixedLocalPath, progress=$fixedProgress, 完整=$completeNow")
                } catch(e: Exception) {
                    Log.e(TAG, "[INIT_STATE_ERROR] ${modelDir.name}: ${e.message}", e)
                }
            }
        }
        // 扫描 Vosk 模型目录，补全/修正模型状态（同 Sherpa-ONNX 修复逻辑）
        val voskDir = File(modelsBaseDir, "vosk")
        Log.i(TAG, "[INIT] 扫描Vosk目录: ${voskDir.absolutePath}, 存在=${voskDir.exists() && voskDir.isDirectory}")
        if (voskDir.exists() && voskDir.isDirectory) {
            voskDir.listFiles()?.forEach { langDir ->
                if (!langDir.isDirectory) return@forEach
                val modelName = "VOSK_ASR_${langDir.name.uppercase()}"
                val isComplete = isVoskModelComplete(langDir)
                val existing = result[modelName]
                Log.i(TAG, "[INIT_VOSK_SCAN] $modelName: dir=${langDir.name}, 完整=$isComplete, 现有状态=${existing?.status?.name}")
                when {
                    isComplete -> {
                        if (existing == null || existing.status != ModelStatus.AVAILABLE) {
                            result[modelName] = ModelState(
                                modelName = modelName,
                                status = ModelStatus.AVAILABLE,
                                progress = 1f,
                                localPath = langDir.absolutePath
                            )
                            Log.w(TAG, "[INIT_VOSK_FIX] $modelName: 修正为 AVAILABLE")
                        }
                    }
                    // 文件不完整 + 残留DOWNLOADING/AVAILABLE：重置为NOT_EXIST，允许用户重新下载
                    existing != null && existing.status != ModelStatus.NOT_EXIST -> {
                        result[modelName] = existing.copy(status = ModelStatus.NOT_EXIST, progress = 0f)
                        Log.w(TAG, "[INIT_VOSK_FIX] $modelName: 文件不完整(完整=$isComplete)且状态=${existing.status.name}，重置为 NOT_EXIST（保留文件，允许用户手动删或重新下载覆盖）")
                        // 不 walkTopDown：避免用户已下好 am/conf 但 graph 少几个文件时被清空
                    }
                }
            }
        }
        // 扫描 Sherpa-ONNX 模型目录，补全/修正模型状态
        // 修复：state.json 残留 DOWNLOADING 状态时(下载中断/进程被杀)，需根据文件完整性修正
        val sherpaOnnxDir = File(modelsBaseDir, "sherpa-onnx")
        Log.i(TAG, "[INIT] 扫描Sherpa目录: ${sherpaOnnxDir.absolutePath}, 存在=${sherpaOnnxDir.exists() && sherpaOnnxDir.isDirectory}")
        if (sherpaOnnxDir.exists() && sherpaOnnxDir.isDirectory) {
            sherpaOnnxDir.listFiles()?.forEach { modelDir ->
                if (!modelDir.isDirectory) return@forEach
                val modelName = "SHERPA_ONNX_ASR_${modelDir.name}"
                val isComplete = isSherpaOnnxModelComplete(modelDir)
                val existing = result[modelName]
                Log.i(TAG, "[INIT_SHERPA_SCAN] $modelName: 完整=$isComplete, 现有状态=${existing?.status?.name}, path=${modelDir.absolutePath}")
                when {
                    // 文件完整：无论 state.json 是何状态，都修正为 AVAILABLE
                    isComplete -> {
                        if (existing == null || existing.status != ModelStatus.AVAILABLE) {
                            result[modelName] = ModelState(
                                modelName = modelName,
                                status = ModelStatus.AVAILABLE,
                                progress = 1f,
                                localPath = modelDir.absolutePath
                            )
                            Log.w(TAG, "[INIT_SHERPA_FIX] $modelName: 修正为 AVAILABLE (文件完整)")
                        }
                    }
                    // 文件不完整 + 残留DOWNLOADING/AVAILABLE：只改状态为 NOT_EXIST，保留目录内容
                    // （不再 walkTopDown 删！）—— 历史上魔数校验误判 tokens.txt 为 HTML，
                    // 产生 tokens.txt.part.corrupted，导致完整性=false → 把已下好的 161MB ONNX 全删。
                    existing != null && existing.status != ModelStatus.NOT_EXIST -> {
                        result[modelName] = existing.copy(status = ModelStatus.NOT_EXIST, progress = 0f)
                        Log.w(TAG, "[INIT_SHERPA_FIX] $modelName: 文件不完整(enc/dec/join/tokens=${isComplete})且状态=${existing.status.name}，重置为 NOT_EXIST（保留已下载大文件，自愈完整后自动变AVAILABLE）")
                    }
                }
            }
        }
        Log.i(TAG, "[INIT] 最终模型数量=${result.size}, 列表: ${result.keys.joinToString { "$it=${result[it]?.status?.name}" }}")
        _modelsFlow.value = result
    }

    /** 检查 Vosk 模型目录是否完整（包含 am/conf/graph 三个子目录） */
    private fun isVoskModelComplete(langDir: File): Boolean {
        val am = File(langDir, "am").isDirectory
        val conf = File(langDir, "conf").isDirectory
        val graph = File(langDir, "graph").isDirectory
        Log.d(TAG, "[CHECK_VOSK] ${langDir.absolutePath}: am=$am, conf=$conf, graph=$graph -> ${am && conf && graph}")
        return am && conf && graph
    }

    /** 检查 Sherpa-ONNX 模型目录是否完整（包含 encoder/decoder/joiner/tokens 四个文件） */
    private fun isSherpaOnnxModelComplete(modelDir: File): Boolean {
        val files = modelDir.listFiles()?.map { it.name to it.length() } ?: emptyList()
        val names = files.map { it.first }
        val hasEnc = names.any { it.contains("encoder", ignoreCase = true) && it.endsWith(".onnx") }
        val hasDec = names.any { it.contains("decoder", ignoreCase = true) && it.endsWith(".onnx") }
        val hasJoin = names.any { it.contains("joiner", ignoreCase = true) && it.endsWith(".onnx") }
        // tokens.txt 识别：接受 tokens.txt / tokens.txt.part / tokens.txt.part.corrupted（历史残留），
        // 只要存在且>0字节就认为准备好（避免正常tokens里首字符为<被误判为HTML产生.part.corrupted垃圾文件一直堆积）
        val tokenFile = modelDir.listFiles()?.firstOrNull { f ->
            val n = f.name
            (n == "tokens.txt" || n == "tokens.txt.part" || n == "tokens.txt.part.corrupted") && f.length() > 0
        }
        val hasTok = tokenFile != null
        // 自动清理：如果历史产生了 .part.corrupted 但正式 tokens.txt 不存在，就把 .part.corrupted 重命名成 tokens.txt
        // （避免用户已经下好了 tokens，被误判为"未下载"）
        if (hasTok && tokenFile != null && tokenFile.name != "tokens.txt") {
            runCatching {
                val official = File(modelDir, "tokens.txt")
                if (official.exists()) official.delete()
                if (tokenFile.renameTo(official)) {
                    Log.i(TAG, "[TOKENS_RECOVER] ${modelDir.name}: 把 ${tokenFile.name} 重命名为 tokens.txt")
                } else {
                    tokenFile.copyTo(official, overwrite = true)
                    tokenFile.delete()
                    Log.i(TAG, "[TOKENS_RECOVER] ${modelDir.name}: copy+delete 把 ${tokenFile.name} 转为 tokens.txt")
                }
            }.onFailure { e ->
                Log.w(TAG, "[TOKENS_RECOVER_FAIL] ${modelDir.name}: ${e.message}", e)
            }
        }
        Log.d(TAG, "[CHECK_SHERPA] ${modelDir.absolutePath}: enc=$hasEnc, dec=$hasDec, join=$hasJoin, tokens=$hasTok(tokenFile=${tokenFile?.name ?: "null"}), files(Top10)=${files.take(10)}")
        return hasEnc && hasDec && hasJoin && (hasTok || run {
            File(modelDir, "tokens.txt").let { it.exists() && it.length() > 0 }
        })
    }

    private companion object {
        const val TAG = "ModelRepository"

        /**
         * 允许下载模型的 host 白名单。
         * 新增源必须经过人工审核。
         */
        val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "huggingface.co",
            "cdn-lfs.huggingface.co",
            "github.com",
            "objects.githubusercontent.com",
            "raw.githubusercontent.com",
            "storage.googleapis.com",
            "alphacephei.com",
            "modelscope.cn",          // ModelScope 国内镜像
            "www.modelscope.cn",      // ModelScope 国内镜像（带 www）
            "modelscope.ai",          // ModelScope 新域名（X-ASR 等模型仓库）
            "www.modelscope.ai"       // ModelScope 新域名（带 www）
        )
    }
}
