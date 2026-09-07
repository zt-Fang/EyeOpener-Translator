package io.github.ztfang.eye.data.repository

import android.content.Context
import android.util.Log
import io.github.ztfang.eye.data.util.TarBzipExtractor
import io.github.ztfang.eye.data.util.ZipExtractor
import io.github.ztfang.eye.domain.model.DownloadProgress
import io.github.ztfang.eye.domain.model.ModelFileSpec
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.model.ModelStatus
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模型仓库实现。目录约定（须与 [ModelPreparer] 一致，否则下载后找不到文件）：
 * - Vosk：filesDir/models/vosk/<lang>/（am/conf/graph）
 * - Sherpa-ONNX：filesDir/models/sherpa-onnx/<modelId>/（encoder/decoder/joiner/tokens）
 * - 状态：filesDir/models/{modelName}/state.json
 * 下载完成后把 {modelName}/ 暂存目录迁移到上述真实目录，localPath 指向真实路径。
 */

class ModelRepositoryImpl(
    private val context: Context,
) : ModelRepository {
    private val modelsBaseDir: File get() = File(context.filesDir, "models")

    /** 与 ModelPreparer.asrModelDir 一致 */
    private val voskModelsRoot: File get() = File(modelsBaseDir, "vosk")

    /** 与 ModelPreparer.sherpaOnnxModelDir 一致 */
    private val sherpaOnnxModelsRoot: File get() = File(modelsBaseDir, "sherpa-onnx")

    /** modelName → ModelPreparer 真实读取目录；null = 其他模型（不迁移） */
    private fun resolveRealAsrDirFor(modelName: String): File? =
        when {
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
     * 把 stageDir 内除 state.json 外的文件搬到 realDir，返回真实目录路径；失败返回 null。
     * stageDir == realDir 时短路返回：否则 dest 与源同文件，deleteRecursively 会自删。
     */
    private fun moveDownloadedFilesToRealAsrDir(
        stageDir: File,
        realDir: File,
    ): String? {
        return runCatching {
            val sameDir =
                runCatching {
                    stageDir.canonicalPath == realDir.canonicalPath
                }.getOrDefault(false) ||
                    stageDir.absolutePath == realDir.absolutePath
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
                    if (f.isDirectory) {
                        f.copyRecursively(dest, overwrite = true)
                    } else {
                        f.copyTo(dest, overwrite = true)
                    }
                    f.deleteRecursively()
                }
            }
            Log.i(TAG, "[MOVE_OK] $stageDir -> $realDir, finalFiles=${realDir.listFiles()?.map { it.name to it.length() }}")
            realDir.absolutePath
        }.onFailure { e ->
            Log.e(TAG, "[MOVE_FAIL] $stageDir -> $realDir: ${e.javaClass.simpleName}: ${e.message}", e)
        }.getOrNull()
    }

    /** 内存中维护最新模型状态 */
    private val _modelsFlow = MutableStateFlow<Map<String, ModelState>>(emptyMap())

    /** 下载取消标志：modelName → 是否请求取消（协作式：downloadOneFile 每写一块检查一次） */
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    override fun observeModel(modelName: String): Flow<ModelState> =
        _modelsFlow.map { it[modelName] ?: ModelState(modelName, ModelStatus.NOT_EXIST) }

    override fun observeAllModels(): Flow<List<ModelState>> = _modelsFlow.map { it.values.toList() }

    override suspend fun downloadModel(
        modelName: String,
        downloadUrl: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<ModelState> =
        downloadModelFiles(
            modelName = modelName,
            files =
                listOf(
                    ModelFileSpec(
                        relativePath = downloadUrl.substringAfterLast("/"),
                        url = downloadUrl,
                    ),
                ),
            onProgress = onProgress,
        )

    /** 下载多文件模型，聚合进度经 onProgress 上报 */
    override suspend fun downloadModelFiles(
        modelName: String,
        files: List<ModelFileSpec>,
        onProgress: (DownloadProgress) -> Unit,
        onFileComplete: (fileName: String) -> Unit,
    ): Result<ModelState> =
        withContext(Dispatchers.IO) {
            if (files.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("downloadModelFiles called with no files"),
                )
            }
            val dir = File(modelsBaseDir, modelName)
            dir.mkdirs()

            // 预计算总大小用于准确进度报告
            val totalSizeAllFiles: Long = files.sumOf { it.sizeBytes }

            // 磁盘空间预检：开写前一次性判断，避免下到一半 ENOSPC 留下截断文件
            if (totalSizeAllFiles > 0) {
                val usable = dir.usableSpace
                if (usable < totalSizeAllFiles + MIN_FREE_SPACE_BYTES) {
                    val msg =
                        "存储空间不足：需要 ${totalSizeAllFiles / 1024 / 1024}MB，" +
                            "可用 ${usable / 1024 / 1024}MB"
                    Log.e(TAG, "[DL_SPACE] $modelName: $msg")
                    updateState(modelName, ModelStatus.ERROR, errorMessage = msg)
                    return@withContext Result.failure(IOException(msg))
                }
            }

            Log.i(
                TAG,
                "下载开始: model=$modelName, files=${files.size}, totalSize=${totalSizeAllFiles / 1024 / 1024}MB, dir=${dir.absolutePath}",
            )
            val sizesByFile: Map<String, Long> = files.associate { it.relativePath to it.sizeBytes }
            var aggregateDownloaded: Long = 0L // 只累加已完成文件的真实字节数（target.length()）
            val startedAt = System.currentTimeMillis()
            var lastEmit = 0L
            var lastEmittedBytes: Long = -1L // 进度非递减：防止 100%→90% 跳动

            /** 进度上报（~5Hz 节流，最终值不节流；bytes 非递减） */
            fun emit(
                currentFileName: String,
                currentFileRead: Long,
                currentFileSize: Long,
                isFinal: Boolean = false,
            ) {
                val total =
                    if (totalSizeAllFiles > 0) {
                        totalSizeAllFiles
                    } else {
                        (aggregateDownloaded + (sizesByFile[currentFileName] ?: 0L))
                    }
                val now = System.currentTimeMillis()
                val elapsed = (now - startedAt).coerceAtLeast(1L) / 1000L
                val bytes = aggregateDownloaded + currentFileRead
                if (lastEmittedBytes >= 0 && bytes < lastEmittedBytes) {
                    Log.w(TAG, "[PROGRESS_GUARD] $modelName: 丢弃回退进度 emit bytes=$bytes < last=$lastEmittedBytes, file=$currentFileName")
                    return
                }
                if (!isFinal && now - lastEmit < 200 && currentFileRead != currentFileSize) return
                lastEmittedBytes = bytes
                onProgress(
                    DownloadProgress(
                        modelName = modelName,
                        bytesDownloaded = bytes,
                        totalBytes = total,
                        speedBytesPerSec = if (elapsed > 0) bytes / elapsed else 0L,
                    ),
                )
                lastEmit = now
            }

            try {
                updateState(modelName, ModelStatus.DOWNLOADING, downloadUrl = files.first().url)

                for ((index, spec) in files.withIndex()) {
                    val target = File(dir, spec.relativePath)
                    target.parentFile?.mkdirs()
                    Log.i(
                        TAG,
                        "[DL_FILE_START] $modelName: 文件(${index + 1}/${files.size}) ${spec.relativePath}, expectedSize=${spec.sizeBytes}B, aggregateBefore=$aggregateDownloaded",
                    )
                    downloadOneFile(
                        spec = spec,
                        target = target,
                        onBytes = { fileRead, fileSize ->
                            emit(spec.relativePath, fileRead, fileSize)
                        },
                        modelName = modelName,
                    )
                    onFileComplete(spec.relativePath)
                    val actualFileBytes = target.length()
                    // 先 emit 再累加：避免 bytes = aggregate(已含 actualFileBytes) + actualFileBytes 重复计算
                    emit(spec.relativePath, actualFileBytes, actualFileBytes, isFinal = true)
                    aggregateDownloaded += actualFileBytes
                    Log.i(
                        TAG,
                        "[DL_FILE_DONE] $modelName: 文件(${index + 1}/${files.size}) ${spec.relativePath}, actualBytes=$actualFileBytes, aggregateAfter=$aggregateDownloaded",
                    )
                }

                // 迁移暂存目录 → ModelPreparer 真实读取目录，否则 prepareAsr 找不到文件
                val realDir = resolveRealAsrDirFor(modelName)
                val realPath =
                    if (realDir != null) {
                        moveDownloadedFilesToRealAsrDir(stageDir = dir, realDir = realDir)
                    } else {
                        dir.absolutePath
                    }
                if (realPath == null) {
                    // 迁移失败直接报 ERROR，避免 UI 显示 AVAILABLE 但 ASR 初始化失败
                    val errState =
                        ModelState(
                            modelName = modelName,
                            status = ModelStatus.ERROR,
                            progress = 1f,
                            downloadUrl = files.first().url,
                            localPath = null,
                            errorMessage = "文件迁移到 ASR 目录失败，请重新下载或手动清理缓存",
                        )
                    updateState(errState)
                    Result.failure(IllegalStateException("Model files move failed: $modelName"))
                } else {
                    val state =
                        ModelState(
                            modelName = modelName,
                            status = ModelStatus.AVAILABLE,
                            progress = 1f,
                            downloadUrl = files.first().url,
                            localPath = realPath,
                        )
                    updateState(state)
                    Log.i(TAG, "下载成功: model=$modelName, localPath(ASR真实目录)=$realPath")
                    Result.success(state)
                }
            } catch (e: DownloadCancelledException) {
                Log.w(TAG, "[CANCEL] 下载被用户取消: $modelName, 清理 .part 并重置状态为 NOT_EXIST")
                cleanupPartialFiles(dir)
                cancelFlags.remove(modelName)
                updateState(modelName, ModelStatus.NOT_EXIST)
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $modelName: ${e.message}", e)
                updateState(modelName, ModelStatus.ERROR, errorMessage = e.message ?: "Unknown error")
                // 保留 .part 文件用于断点续传
                Result.failure(e)
            }
        }

    /** 下载多文件到 targetDir（不解压）；进度逻辑同 downloadModelFiles */
    override suspend fun downloadFilesToDir(
        modelName: String,
        files: List<ModelFileSpec>,
        targetDir: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<ModelState> =
        withContext(Dispatchers.IO) {
            if (files.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("downloadFilesToDir called with no files"),
                )
            }
            val dir = File(targetDir)
            dir.mkdirs()

            Log.i(
                TAG,
                "多文件下载开始: model=$modelName, files=${files.size}, totalSize=${files.sumOf {
                    it.sizeBytes
                } / 1024 / 1024}MB, dir=${dir.absolutePath}",
            )

            val totalSizeAllFiles: Long = files.sumOf { it.sizeBytes }
            val sizesByFile: Map<String, Long> = files.associate { it.relativePath to it.sizeBytes }
            var aggregateDownloaded: Long = 0L
            val startedAt = System.currentTimeMillis()
            var lastEmit = 0L
            var lastEmittedBytes: Long = -1L

            /** 进度上报（~5Hz 节流，最终值不节流；bytes 非递减） */
            fun emit(
                currentFileName: String,
                currentFileRead: Long,
                currentFileSize: Long,
                isFinal: Boolean = false,
            ) {
                val total =
                    if (totalSizeAllFiles > 0) {
                        totalSizeAllFiles
                    } else {
                        (aggregateDownloaded + (sizesByFile[currentFileName] ?: 0L))
                    }
                val now = System.currentTimeMillis()
                val elapsed = (now - startedAt).coerceAtLeast(1L) / 1000L
                val bytes = aggregateDownloaded + currentFileRead
                if (lastEmittedBytes >= 0 && bytes < lastEmittedBytes) {
                    Log.w(TAG, "[PROGRESS_GUARD] $modelName: 丢弃回退进度 emit bytes=$bytes < last=$lastEmittedBytes, file=$currentFileName")
                    return
                }
                if (!isFinal && now - lastEmit < 200 && currentFileRead != currentFileSize) return
                lastEmittedBytes = bytes
                onProgress(
                    DownloadProgress(
                        modelName = modelName,
                        bytesDownloaded = bytes,
                        totalBytes = total,
                        speedBytesPerSec = if (elapsed > 0) bytes / elapsed else 0L,
                    ),
                )
                lastEmit = now
            }

            try {
                updateState(modelName, ModelStatus.DOWNLOADING, downloadUrl = files.first().url)

                for ((index, spec) in files.withIndex()) {
                    val target = File(dir, spec.relativePath)
                    target.parentFile?.mkdirs()
                    val urlHead = spec.url.take(80)
                    Log.i(
                        TAG,
                        "[DL_FILE_START] $modelName: 文件(${index + 1}/${files.size}) " +
                            "${spec.relativePath}, expectedSize=${spec.sizeBytes}B, " +
                            "url=$urlHead..., aggregateBefore=$aggregateDownloaded",
                    )
                    downloadOneFile(
                        spec = spec,
                        target = target,
                        onBytes = { fileRead, fileSize ->
                            emit(spec.relativePath, fileRead, fileSize)
                        },
                        modelName = modelName,
                    )
                    val actualFileBytes = target.length()
                    // 先 emit 再累加：避免 bytes = aggregate(已含 actualFileBytes) + actualFileBytes 重复计算
                    Log.i(
                        TAG,
                        "[DL_FILE_DONE] $modelName: 文件(${index + 1}/${files.size}) ${spec.relativePath}, actualBytes=$actualFileBytes, aggregateBefore=$aggregateDownloaded",
                    )
                    emit(spec.relativePath, actualFileBytes, actualFileBytes, isFinal = true)
                    aggregateDownloaded += actualFileBytes
                }

                // 全部下完后做完整性校验
                val allOK = isSherpaOnnxModelComplete(dir)
                if (!allOK) {
                    Log.w(
                        TAG,
                        "[DL_INTEGRITY_FAIL] $modelName: 所有文件下载完成但完整性校验失败！dir=${dir.absolutePath}, files=${dir.listFiles()?.map {
                            it.name to it.length()
                        }}",
                    )
                    val errState =
                        ModelState(
                            modelName = modelName,
                            status = ModelStatus.ERROR,
                            progress = 1f,
                            downloadUrl = files.first().url,
                            localPath = null,
                            errorMessage = "文件完整性校验失败，请重新下载",
                        )
                    updateState(errState)
                    Result.failure(IllegalStateException("Integrity check failed: $modelName"))
                } else {
                    // 迁移暂存目录 → ASR 真实目录
                    val realDir = resolveRealAsrDirFor(modelName)
                    val realPath =
                        if (realDir != null) {
                            moveDownloadedFilesToRealAsrDir(stageDir = dir, realDir = realDir)
                        } else {
                            dir.absolutePath
                        }
                    if (realPath == null) {
                        val errState =
                            ModelState(
                                modelName = modelName,
                                status = ModelStatus.ERROR,
                                progress = 1f,
                                downloadUrl = files.first().url,
                                localPath = null,
                                errorMessage = "文件迁移到 ASR 目录失败，请重新下载或手动清理缓存",
                            )
                        updateState(errState)
                        Result.failure(IllegalStateException("Model files move failed: $modelName"))
                    } else {
                        val state =
                            ModelState(
                                modelName = modelName,
                                status = ModelStatus.AVAILABLE,
                                progress = 1f,
                                downloadUrl = files.first().url,
                                localPath = realPath,
                            )
                        updateState(state)
                        Log.i(TAG, "多文件下载结束: model=$modelName, status=AVAILABLE, integrityOK=true, localPath(ASR真实目录)=$realPath")
                        Result.success(state)
                    }
                }
            } catch (e: DownloadCancelledException) {
                Log.w(TAG, "[CANCEL] 多文件下载被用户取消: $modelName, 清理 .part 并重置状态为 NOT_EXIST")
                cleanupPartialFiles(dir)
                cancelFlags.remove(modelName)
                updateState(modelName, ModelStatus.NOT_EXIST)
                Result.failure(e)
            } catch (e: Exception) {
                val failingFile =
                    runCatching {
                        files
                            .getOrNull(
                                files.indexOfFirst { !File(dir, it.relativePath).exists() },
                            )?.relativePath
                    }.getOrNull()
                Log.e(
                    TAG,
                    "[DL_FAIL] $modelName: 多文件下载失败: ${e.javaClass.simpleName}: " +
                        "${e.message}; 累计bytes=$aggregateDownloaded/$totalSizeAllFiles; " +
                        "正在尝试的文件=$failingFile",
                    e,
                )
                updateState(modelName, ModelStatus.ERROR, errorMessage = e.message ?: "Unknown error")
                Result.failure(e)
            }
        }

    /**
     * 带重试的下载入口：对网络类 IOException 自动重试，.part 保留可续传。
     * 不重试的情况：用户取消、host 不在白名单、存储空间不足（重试也必然失败）。
     *
     * 内部走 .part 临时文件 + Range 断点续传。
     *
     * @param allowResume 是否允许基于已有 .part 续传。HTTP 416 重试时置 false——
     *        此时 .part 已比服务端文件还长，必须先删掉再从头下，否则该模型将永久下载失败。
     */
    private fun downloadOneFile(
        spec: ModelFileSpec,
        target: File,
        onBytes: (bytesRead: Long, totalBytes: Long) -> Unit,
        allowResume: Boolean = true,
        modelName: String,
    ) {
        var lastError: IOException? = null
        repeat(MAX_DOWNLOAD_RETRIES) { attempt ->
            try {
                downloadOneFileOnce(spec, target, onBytes, allowResume, modelName)
                return
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                val msg = e.message.orEmpty()
                val noRetry =
                    msg.contains("存储空间不足") ||
                        msg.contains("non-whitelisted") ||
                        msg.contains("size mismatch")
                if (noRetry) throw e
                if (attempt < MAX_DOWNLOAD_RETRIES - 1) {
                    val waitMs = RETRY_BACKOFF_MS * (attempt + 1)
                    Log.w(
                        TAG,
                        "[DL_RETRY] ${spec.relativePath}: 第${attempt + 1}/${MAX_DOWNLOAD_RETRIES} 次失败 " +
                            "(${e.javaClass.simpleName}: ${e.message})，${waitMs}ms 后重试，.part 保留续传",
                    )
                    Thread.sleep(waitMs)
                }
            }
        }
        throw lastError ?: IOException("Download failed: ${spec.relativePath}")
    }

    private fun downloadOneFileOnce(
        spec: ModelFileSpec,
        target: File,
        onBytes: (bytesRead: Long, totalBytes: Long) -> Unit,
        allowResume: Boolean = true,
        modelName: String,
    ) {
        val host = runCatching { URL(spec.url).host }.getOrNull()
        if (host == null || host !in ALLOWED_DOWNLOAD_HOSTS) {
            throw SecurityException("Refusing to download from non-whitelisted host: $host")
        }

        // 已完整下载过（大小与声明完全一致）→ 跳过，避免重试时重复下载 657MB 的 encoder
        if (target.exists() && spec.sizeBytes > 0 && target.length() == spec.sizeBytes) {
            Log.i(TAG, "[DL_SKIP] ${spec.relativePath}: 已存在且大小匹配(${target.length()}B)，跳过重复下载")
            onBytes(target.length(), target.length())
            return
        }

        val partFile = File(target.parentFile, "${target.name}.part")
        // 416 重试：.part 长度已超过远端文件，无法续传，删除后从头下载
        if (!allowResume && partFile.exists()) partFile.delete()
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val conn =
            (URL(spec.url).openConnection() as HttpURLConnection).apply {
                // 读超时仅指"连续这么久一个字节都没到"，不限下载总时长/速度。
                // 60s 在代理网络下会把短暂卡顿误杀成断线（触发重试、极端时丢进度），
                // 放宽到 5 分钟：正常下载永远碰不到，只有连接真死透才会中断重连。
                // 设 0 = 完全不限，但死连接会永久冻结下载且无任何报错，故保留兜底。
                connectTimeout = 20_000
                readTimeout = 300_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }
        conn.connect()

        val responseCode = conn.responseCode
        // 416 = Range 起点超出远端文件长度（.part 比远端文件还长，通常因远端文件已更新）。
        // 旧实现直接抛错但保留 .part，导致之后每次重试都带同样的 Range → 该模型永久下不动。
        // 现改为：删掉 .part 从头重下一次；仍失败才抛错。
        if (responseCode == 416) {
            conn.disconnect()
            if (allowResume) {
                Log.w(TAG, "[DL_416] ${spec.relativePath}: .part 无法续传，删除后重新下载")
                downloadOneFileOnce(spec, target, onBytes, allowResume = false, modelName = modelName)
                return
            }
            throw IOException("HTTP 416 for ${spec.url}")
        }
        // 206 = 断点续传成功，200 = 服务器不支持 Range 或全新下载
        val isResume = responseCode == 206
        if (responseCode !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $responseCode for ${spec.url}")
        }

        // 完整文件大小：206 时从 Content-Range 获取，否则用 contentLength
        val fullSize =
            if (isResume) {
                parseContentRangeTotal(conn.getHeaderField("Content-Range")) ?: (existingBytes + conn.contentLengthLong)
            } else {
                conn.contentLengthLong
            }
        val totalBytes =
            when {
                fullSize > 0 -> fullSize
                spec.sizeBytes > 0 -> spec.sizeBytes
                else -> -1L
            }

        // 单文件空间预检（用服务端返回的真实长度，比 spec.sizeBytes 可靠）
        if (totalBytes > 0) {
            val usable = target.parentFile?.usableSpace ?: Long.MAX_VALUE
            val remaining = totalBytes - if (isResume) existingBytes else 0L
            if (usable < remaining + MIN_FREE_SPACE_BYTES) {
                conn.disconnect()
                throw IOException(
                    "存储空间不足：还需 ${remaining / 1024 / 1024}MB，可用 ${usable / 1024 / 1024}MB",
                )
            }
        }

        // 非续传时清空 .part 从头下载
        if (!isResume && existingBytes > 0) {
            partFile.delete()
        }

        val input = conn.inputStream
        try {
            val appendMode = isResume
            FileOutputStream(partFile, appendMode).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var total = if (isResume) existingBytes else 0L
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    total += read
                    onBytes(total, totalBytes)
                    // 协作式取消：用户点「取消」后下一文件块即中断（最快一个 64KB chunk 内响应）
                    if (cancelFlags[modelName]?.get() == true) {
                        Log.w(TAG, "[CANCEL] downloadOneFile 检测到取消: model=$modelName, file=${spec.relativePath}, 已下=$total/$totalBytes")
                        throw DownloadCancelledException("Download cancelled: $modelName")
                    }
                }
                output.flush()
            }
        } finally {
            runCatching { input.close() }
            conn.disconnect()
        }

        // 严格校验字节数：必须与服务端声明长度【完全相等】才允许转正。
        // 旧实现只要求 ≥95%，685MB 的 Nemotron 实际 626MB 就能通过，截断的 ONNX 会被
        // 重命名为正式文件并标记 AVAILABLE，UI 显示"已下载"但引擎 init() 必失败，用户无从察觉。
        // 仍不做魔数校验——曾反复误判（tokens.txt 首字符'<'、zip/tar 头等）并触发自愈误删完整模型。
        // 注：若某模型报 size mismatch，说明 SherpaOnnxModel 里声明的 sizeBytes 与远端实际不符，需核对常量。
        val verifyTotal = if (fullSize > 0) fullSize else spec.sizeBytes
        if (verifyTotal > 0 && partFile.length() != verifyTotal) {
            partFile.delete()
            throw IOException("File size mismatch for ${spec.relativePath}: got ${partFile.length()} of $verifyTotal")
        }

        if (target.exists()) target.delete()
        if (!partFile.renameTo(target)) {
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
        }
    }

    /** 解析 Content-Range 中的总大小（bytes 0-1023/2048 → 2048） */
    private fun parseContentRangeTotal(contentRange: String?): Long? {
        if (contentRange == null) return null
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex < 0 || slashIndex == contentRange.length - 1) return null
        return contentRange.substring(slashIndex + 1).toLongOrNull()
    }

    override suspend fun deleteModel(modelName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.i(TAG, "deleteModel: 开始删除 $modelName")
                updateState(modelName, ModelStatus.DELETING)
                val dir = File(modelsBaseDir, modelName)
                Log.i(TAG, "deleteModel: state.json 目录=${dir.absolutePath}, exists=${dir.exists()}")
                if (dir.exists()) dir.deleteRecursively()
                // 同时删除实际模型文件 models/vosk/<lang>/
                if (modelName.startsWith("VOSK_ASR_")) {
                    val langCode = modelName.substringAfter("VOSK_ASR_").lowercase()
                    val voskLangDir = File(modelsBaseDir, "vosk/$langCode")
                    Log.i(TAG, "deleteModel: Vosk 模型目录=${voskLangDir.absolutePath}, exists=${voskLangDir.exists()}")
                    if (voskLangDir.exists()) voskLangDir.deleteRecursively()
                }
                // 同时删除实际模型文件 models/sherpa-onnx/<modelId>/
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

    override fun getModelPath(modelName: String): String? {
        if (modelName.startsWith("SHERPA_ONNX_ASR_")) {
            val modelId = modelName.substringAfter("SHERPA_ONNX_ASR_")
            val sherpaDir = File(modelsBaseDir, "sherpa-onnx/$modelId")
            val exists = sherpaDir.exists() && sherpaDir.listFiles()?.isNotEmpty() == true
            Log.d(TAG, "getModelPath: $modelName, dir=${sherpaDir.absolutePath}, available=$exists")
            return if (exists) sherpaDir.absolutePath else null
        }
        if (modelName.startsWith("VOSK_ASR_")) {
            val langCode = modelName.substringAfter("VOSK_ASR_").lowercase()
            val voskDir = File(modelsBaseDir, "vosk/$langCode")
            val exists = voskDir.exists() && voskDir.listFiles()?.isNotEmpty() == true
            Log.d(TAG, "getModelPath: $modelName, dir=${voskDir.absolutePath}, available=$exists")
            return if (exists) voskDir.absolutePath else null
        }
        val dir = File(modelsBaseDir, modelName)
        val exists = dir.exists() && dir.listFiles()?.isNotEmpty() == true
        Log.d(TAG, "getModelPath: $modelName, dir=${dir.absolutePath}, available=$exists")
        return if (exists) dir.absolutePath else null
    }

    override fun cancelDownload(modelName: String) {
        cancelFlags.getOrPut(modelName) { AtomicBoolean(false) }.set(true)
        Log.w(TAG, "[CANCEL] cancelDownload 请求: $modelName（下一文件块将中断并清理 .part）")
    }

    /** 删除目录下所有 .part 暂存文件（取消下载后清理，避免残留半截文件被误判完整） */
    private fun cleanupPartialFiles(dir: File) {
        runCatching {
            dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { f ->
                val ok = f.delete()
                Log.i(TAG, "[CANCEL] 删除暂存: ${f.absolutePath}, ok=$ok")
            }
        }
    }

    /** 下载 zip → 解压到 extractDir → 更新状态 */
    override suspend fun downloadAndExtractZip(
        modelName: String,
        zipSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<ModelState> =
        withContext(Dispatchers.IO) {
            val destDir = File(extractDir)
            val zipFile = File(modelsBaseDir, "${modelName}_tmp.zip")
            val startedAt = System.currentTimeMillis()
            var lastEmittedBytes: Long = -1L

            runCatching {
                updateState(modelName, ModelStatus.DOWNLOADING, downloadUrl = zipSpec.url)

                downloadOneFile(
                    spec = zipSpec,
                    target = zipFile,
                    onBytes = { read, total ->
                        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L) / 1000L
                        val clampedRead = read.coerceAtMost(if (total > 0) total else Long.MAX_VALUE)
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
                                speedBytesPerSec = if (elapsed > 0) clampedRead / elapsed else 0L,
                            ),
                        )
                    },
                    modelName = modelName,
                )
                Log.i(TAG, "Zip download complete: ${zipFile.absolutePath}")

                // 最终进度（确保 100%）
                val finalBytes = zipSpec.sizeBytes.coerceAtLeast(lastEmittedBytes)
                lastEmittedBytes = finalBytes
                onProgress(
                    DownloadProgress(
                        modelName = modelName,
                        bytesDownloaded = finalBytes,
                        totalBytes = zipSpec.sizeBytes,
                        speedBytesPerSec = 0L,
                    ),
                )

                destDir.parentFile?.mkdirs()
                if (destDir.exists()) destDir.deleteRecursively()
                destDir.mkdirs()

                ZipExtractor.extract(zipFile, destDir, stripTopLevelDir = true).getOrThrow()
                Log.i(TAG, "Zip extracted to: ${destDir.absolutePath}")

                zipFile.delete()

                val state =
                    ModelState(
                        modelName = modelName,
                        status = ModelStatus.AVAILABLE,
                        progress = 1f,
                        downloadUrl = zipSpec.url,
                        localPath = destDir.absolutePath,
                    )
                updateState(state)
                state
            }.onFailure { e ->
                if (e is DownloadCancelledException) {
                    Log.w(TAG, "[CANCEL] zip 下载被用户取消: $modelName, 清理并重置 NOT_EXIST")
                    cleanupPartialFiles(zipFile.parentFile ?: modelsBaseDir)
                    cancelFlags.remove(modelName)
                    updateState(modelName, ModelStatus.NOT_EXIST)
                } else {
                    Log.e(TAG, "downloadAndExtractZip failed for $modelName: ${e.message}", e)
                    updateState(modelName, ModelStatus.ERROR, errorMessage = e.message ?: "Unknown error")
                    // 清理残留文件
                    zipFile.delete()
                    if (destDir.exists()) destDir.deleteRecursively()
                }
            }
        }

    /** 下载 tar.bz2 → 解压到 extractDir → 更新状态 */
    override suspend fun downloadAndExtractTarBz2(
        modelName: String,
        tarSpec: ModelFileSpec,
        extractDir: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<ModelState> =
        withContext(Dispatchers.IO) {
            val destDir = File(extractDir)
            val tarFile = File(modelsBaseDir, "${modelName}_tmp.tar.bz2")
            val startedAt = System.currentTimeMillis()
            var lastEmittedBytes: Long = -1L

            runCatching {
                updateState(modelName, ModelStatus.DOWNLOADING, downloadUrl = tarSpec.url)

                downloadOneFile(
                    spec = tarSpec,
                    target = tarFile,
                    onBytes = { read, total ->
                        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L) / 1000L
                        val clampedRead = read.coerceAtMost(if (total > 0) total else Long.MAX_VALUE)
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
                                speedBytesPerSec = if (elapsed > 0) clampedRead / elapsed else 0L,
                            ),
                        )
                    },
                    modelName = modelName,
                )
                Log.i(TAG, "Tar.bz2 download complete: ${tarFile.absolutePath}")

                // 最终进度（确保 100%）
                val finalBytes = tarSpec.sizeBytes.coerceAtLeast(lastEmittedBytes)
                lastEmittedBytes = finalBytes
                onProgress(
                    DownloadProgress(
                        modelName = modelName,
                        bytesDownloaded = finalBytes,
                        totalBytes = tarSpec.sizeBytes,
                        speedBytesPerSec = 0L,
                    ),
                )

                destDir.parentFile?.mkdirs()
                if (destDir.exists()) destDir.deleteRecursively()
                destDir.mkdirs()

                TarBzipExtractor.extract(tarFile, destDir, stripTopLevelDir = true).getOrThrow()
                Log.i(TAG, "Tar.bz2 extracted to: ${destDir.absolutePath}")

                tarFile.delete()

                val state =
                    ModelState(
                        modelName = modelName,
                        status = ModelStatus.AVAILABLE,
                        progress = 1f,
                        downloadUrl = tarSpec.url,
                        localPath = destDir.absolutePath,
                    )
                updateState(state)
                state
            }.onFailure { e ->
                if (e is DownloadCancelledException) {
                    Log.w(TAG, "[CANCEL] tar.bz2 下载被用户取消: $modelName, 清理并重置 NOT_EXIST")
                    cleanupPartialFiles(tarFile.parentFile ?: modelsBaseDir)
                    cancelFlags.remove(modelName)
                    updateState(modelName, ModelStatus.NOT_EXIST)
                } else {
                    Log.e(TAG, "downloadAndExtractTarBz2 failed for $modelName: ${e.message}", e)
                    updateState(modelName, ModelStatus.ERROR, errorMessage = e.message ?: "Unknown error")
                    tarFile.delete()
                    if (destDir.exists()) destDir.deleteRecursively()
                }
            }
        }

    private fun updateState(
        name: String,
        status: ModelStatus,
        progress: Float = 0f,
        downloadUrl: String = "",
        localPath: String? = null,
        errorMessage: String? = null,
    ) {
        updateState(ModelState(name, status, progress, downloadUrl, localPath ?: getModelPath(name), errorMessage))
    }

    /** 更新状态到内存和 state.json */
    private fun updateState(state: ModelState) {
        val prev = _modelsFlow.value[state.modelName]?.status
        Log.w(
            TAG,
            "[STATE_CHANGE] ${state.modelName}: ${prev?.name} -> ${state.status.name}" +
                "  progress=${state.progress}  localPath=${state.localPath}  err=${state.errorMessage}",
        )
        _modelsFlow.value = _modelsFlow.value + (state.modelName to state)
        writeStateFile(state)
    }

    /** 将状态写入 state.json 文件 */
    private fun writeStateFile(state: ModelState) {
        val dir = File(modelsBaseDir, state.modelName)
        dir.mkdirs()
        val json = JSONObject()
        json.put("modelName", state.modelName)
        json.put("status", state.status.name)
        json.put("progress", state.progress.toDouble())
        json.put("downloadUrl", state.downloadUrl)
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
                if (sf.exists()) {
                    try {
                        val j = JSONObject(sf.readText())
                        val statusStr = j.getString("status")
                        val modelNameFromJson = j.getString("modelName")
                        val localPathStr = j.optString("localPath").ifEmpty { null }
                        val st = ModelStatus.valueOf(statusStr)
                        var fixedStatus = st
                        var fixedProgress = j.getDouble("progress").toFloat()
                        var fixedLocalPath = localPathStr
                        // 修复残留状态：localPath 存在时用完整性检查辅助修正
                        val localPathFile = if (!localPathStr.isNullOrBlank()) File(localPathStr) else null
                        val localPathExists = localPathFile != null && localPathFile.exists()
                        val isSherpaModel =
                            modelNameFromJson.startsWith("SHERPA_ONNX_ASR_") ||
                                modelDir.name.startsWith("SHERPA_ONNX_ASR_") ||
                                (localPathFile != null && localPathFile.parentFile?.name == "sherpa-onnx")
                        val isVoskModel =
                            modelNameFromJson.startsWith("VOSK_ASR_") ||
                                modelDir.name.startsWith("VOSK_ASR_") ||
                                (localPathFile != null && localPathFile.parentFile?.name == "vosk")
                        val completeNow =
                            if (localPathExists && localPathFile != null) {
                                when {
                                    isSherpaModel -> isSherpaOnnxModelComplete(localPathFile)
                                    isVoskModel -> isVoskModelComplete(localPathFile)
                                    else -> localPathFile.isDirectory && (localPathFile.list()?.size ?: 0) > 0
                                }
                            } else {
                                false
                            }

                        if (st != ModelStatus.AVAILABLE) {
                            // 情况A：非 AVAILABLE 但文件完整 → 修正 AVAILABLE（如写 state.json 前被杀）
                            if (localPathExists && completeNow) {
                                Log.w(TAG, "[INIT_STATE_FIX] ${modelDir.name}: 残留 $statusStr 但 localPath 完整=$completeNow，修正为 AVAILABLE")
                                fixedStatus = ModelStatus.AVAILABLE
                                fixedProgress = 1f
                                fixedLocalPath = localPathStr
                            } else if (st == ModelStatus.DOWNLOADING && !localPathExists) {
                                // 情况B：DOWNLOADING 且 localPath 不存在 → 重置 NOT_EXIST（不删文件）
                                Log.w(TAG, "[INIT_STATE_FIX] ${modelDir.name}: 残留 DOWNLOADING 且localPath不存在，重置为 NOT_EXIST（不删文件）")
                                fixedStatus = ModelStatus.NOT_EXIST
                                fixedProgress = 0f
                                runCatching { sf.delete() }
                            } else if (st == ModelStatus.ERROR) {
                                // 情况C：ERROR 且 localPath 不完整 → 只改状态 NOT_EXIST，
                                // 不删文件（曾误判删除 161MB 完整模型）
                                if (localPathExists && !completeNow) {
                                    Log.w(TAG, "[INIT_STATE_FIX] ${modelDir.name}: ERROR+localPath不完整 → 只改状态NOT_EXIST（不删已下载的大文件）")
                                    fixedStatus = ModelStatus.NOT_EXIST
                                    fixedProgress = 0f
                                    // fixedLocalPath 保留：自愈完整后自动变 AVAILABLE
                                } else if (!localPathExists) {
                                    Log.w(TAG, "[INIT_STATE_FIX] ${modelDir.name}: ERROR且localPath不存在，重置为 NOT_EXIST")
                                    fixedStatus = ModelStatus.NOT_EXIST
                                    fixedProgress = 0f
                                    runCatching { sf.delete() }
                                }
                                // ERROR 但 localPath 完整 → 已走情况A
                            }
                        } else if (st == ModelStatus.AVAILABLE && localPathExists && !completeNow) {
                            // 情况D：AVAILABLE 但磁盘文件不完整（如 tokens.txt 被外部清理/损坏丢失）
                            // 原逻辑只对 !AVAILABLE 做自愈，AVAILABLE 永不校验，导致 UI 假下载但运行时 init 失败。
                            // 改为：降级为 ERROR，保留文件（可断点续传），UI 触发"重新下载"提示。
                            val listedFiles =
                                localPathFile.listFiles()?.map { it.name to it.length() }
                            Log.w(
                                TAG,
                                "[INIT_STATE_FIX] ${modelDir.name}: AVAILABLE 但磁盘不完整" +
                                    "（missing files），降级为 ERROR 保留文件供续传, files=$listedFiles",
                            )
                            fixedStatus = ModelStatus.ERROR
                            fixedProgress = 0f
                            // fixedLocalPath 保留：UI 仍能定位目录并发起重新下载
                        }
                        result[modelDir.name] =
                            ModelState(
                                modelNameFromJson,
                                fixedStatus,
                                fixedProgress,
                                j.optString("downloadUrl", ""),
                                fixedLocalPath,
                                j.optString("errorMessage").ifEmpty { null },
                            )
                        Log.i(
                            TAG,
                            "[INIT_LOAD_STATE] ${modelDir.name}: status=${fixedStatus.name}(原$statusStr), localPath=$fixedLocalPath, progress=$fixedProgress, 完整=$completeNow",
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "[INIT_STATE_ERROR] ${modelDir.name}: ${e.message}", e)
                    }
                }
            }
        }
        // 扫描 Vosk 目录，补全/修正状态
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
                            result[modelName] =
                                ModelState(
                                    modelName = modelName,
                                    status = ModelStatus.AVAILABLE,
                                    progress = 1f,
                                    localPath = langDir.absolutePath,
                                )
                            Log.w(TAG, "[INIT_VOSK_FIX] $modelName: 修正为 AVAILABLE")
                        }
                    }
                    // 文件不完整 + 残留 DOWNLOADING/AVAILABLE → 重置 NOT_EXIST，不删文件（曾误删部分下载）
                    existing != null && existing.status != ModelStatus.NOT_EXIST -> {
                        result[modelName] = existing.copy(status = ModelStatus.NOT_EXIST, progress = 0f)
                        Log.w(
                            TAG,
                            "[INIT_VOSK_FIX] $modelName: 文件不完整(完整=$isComplete)且状态=${existing.status.name}，重置为 NOT_EXIST（保留文件，允许用户手动删或重新下载覆盖）",
                        )
                    }
                }
            }
        }
        // 扫描 Sherpa-ONNX 目录，按文件完整性修正残留状态（下载中断/进程被杀）
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
                    // 文件完整则强制 AVAILABLE
                    isComplete -> {
                        if (existing == null || existing.status != ModelStatus.AVAILABLE) {
                            result[modelName] =
                                ModelState(
                                    modelName = modelName,
                                    status = ModelStatus.AVAILABLE,
                                    progress = 1f,
                                    localPath = modelDir.absolutePath,
                                )
                            Log.w(TAG, "[INIT_SHERPA_FIX] $modelName: 修正为 AVAILABLE (文件完整)")
                        }
                    }
                    // 文件不完整 + 残留 DOWNLOADING/AVAILABLE → 只改状态，保留文件（曾误判删除 161MB ONNX）
                    existing != null && existing.status != ModelStatus.NOT_EXIST -> {
                        result[modelName] = existing.copy(status = ModelStatus.NOT_EXIST, progress = 0f)
                        Log.w(
                            TAG,
                            "[INIT_SHERPA_FIX] $modelName: 文件不完整(enc/dec/join/tokens=$isComplete)且状态=${existing.status.name}，重置为 NOT_EXIST（保留已下载大文件，自愈完整后自动变AVAILABLE）",
                        )
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
        // tokens 识别：也接受 .part / .part.corrupted 历史残留（>0 字节即可）
        val tokenFile =
            modelDir.listFiles()?.firstOrNull { f ->
                val n = f.name
                (n == "tokens.txt" || n == "tokens.txt.part" || n == "tokens.txt.part.corrupted") && f.length() > 0
            }
        val hasTok = tokenFile != null
        // 历史残留的 .part.corrupted 重命名为正式 tokens.txt，避免已下载文件被误判"未下载"
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
        val topFiles = files.take(10)
        Log.d(
            TAG,
            "[CHECK_SHERPA] ${modelDir.absolutePath}: enc=$hasEnc, dec=$hasDec, " +
                "join=$hasJoin, tokens=$hasTok(tokenFile=${tokenFile?.name ?: "null"}), " +
                "files(Top10)=$topFiles",
        )
        return hasEnc &&
            hasDec &&
            hasJoin &&
            (
                hasTok ||
                    run {
                        File(modelDir, "tokens.txt").let { it.exists() && it.length() > 0 }
                    }
            )
    }

    private companion object {
        const val TAG = "ModelRepository"

        /** 下载/解压时要求保留的最小剩余空间（128MB），低于此值直接拒绝，避免产生截断文件 */
        const val MIN_FREE_SPACE_BYTES = 128L * 1024 * 1024

        /**
         * 单文件下载最大尝试次数（含首次）。
         * 685MB 的 Nemotron 在国内网络下极易中途 read timeout，
         * 旧实现一次超时即整体失败，用户只能手动重下。
         */
        const val MAX_DOWNLOAD_RETRIES = 4

        /** 重试退避基数：第 n 次失败等待 RETRY_BACKOFF_MS * n */
        const val RETRY_BACKOFF_MS = 3_000L

        /** 下载 host 白名单，新增源须人工审核 */
        val ALLOWED_DOWNLOAD_HOSTS =
            setOf(
                "huggingface.co",
                "cdn-lfs.huggingface.co",
                "github.com",
                "objects.githubusercontent.com",
                "raw.githubusercontent.com",
                "storage.googleapis.com",
                "alphacephei.com",
                // ModelScope 国内镜像（.ai 为新域名，X-ASR 等模型仓库）
                "modelscope.cn",
                "www.modelscope.cn",
                "modelscope.ai",
                "www.modelscope.ai",
            )
    }
}

/** 用户主动取消下载时抛出，区别于网络/IO 错误（不会误报为 ERROR，状态回滚 NOT_EXIST）。 */
private class DownloadCancelledException(
    message: String,
) : Exception(message)
