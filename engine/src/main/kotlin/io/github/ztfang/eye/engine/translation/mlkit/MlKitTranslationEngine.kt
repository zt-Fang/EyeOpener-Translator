package io.github.ztfang.eye.engine.translation.mlkit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ztfang.eye.domain.engine.translation.TranslationEngine
import io.github.ztfang.eye.domain.model.TranslationResult
import io.github.ztfang.eye.engine.TranslationPrepPhase
import io.github.ztfang.eye.engine.network.GoogleReachabilityChecker
import io.github.ztfang.eye.engine.translation.TranslationPrepException
import io.github.ztfang.eye.engine.translation.TranslationPrepFailureKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.github.ztfang.eye.domain.model.TranslationEngine as AppTranslationEngine

/**
 * ML Kit 翻译引擎。
 * 基于 Google ML Kit Translation API，支持 59 种语言，首次使用需联网下载语言对模型。
 * 特点：本地推理，速度最快；模型下载后可离线使用。
 */
@Singleton
class MlKitTranslationEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val reachabilityChecker: GoogleReachabilityChecker,
    ) : TranslationEngine {
        /** 本地翻译引擎 */
        override val supportedEngine: AppTranslationEngine = AppTranslationEngine.LOCAL

        /** 主线程 Handler：ML Kit Task 回调必须在主线程 Looper 触发，否则永远不回调导致协程挂死 */
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** 主线程 Executor：Task.addOnXxxListener(executor, listener) 要求 Executor 接口 */
        private val mainExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { r -> mainHandler.post(r) }

        /**
         * Translator 实例缓存。使用 LRU 策略，最多保留 3 个语言对的 Translator。
         * 超出限制时自动移除最久未使用的条目。
         */
        private val translatorCache =
            object : LinkedHashMap<String, Translator>(MAX_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Translator>): Boolean {
                    if (size > MAX_CACHE_SIZE) {
                        runCatching { eldest.value.close() }
                        return true
                    }
                    return false
                }
            }

        /**
         * 进程级已下载模型语言缓存。
         * 一旦某语言通过 isModelDownloaded=true 或 downloadModelIfNeeded 成功，即写入此集合。
         * 后续 translate 再检查本地模型时，先命中这里直接跳过 Binder 查询，避免 GMS 偶发慢 Binder 导致误判。
         * 注意：仅在进程生命周期内有效（APP被杀后重查一次没关系）。
         */
        private val downloadedLanguages: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

        /**
         * 下载/校验互斥锁。
         * 同一语言对（en/zh等）并发进入 downloadModelIfNeeded 时串行化，避免 N 条翻译请求并发冲 GMS 导致全部超时。
         * 锁粒度是「语言对级」：en→zh 和 ja→en 可以并行，但 en→zh 的 N 次只会串行一次。
         */
        private val downloadMutexMap: MutableMap<String, Mutex> = Collections.synchronizedMap(HashMap())

        /** 检查语言对是否被 ML Kit 支持 */
        override fun supportsLanguage(
            source: String,
            target: String,
        ): Boolean = mlkitCode(source) != null && mlkitCode(target) != null

        /**
         * 检查指定语言的翻译模型是否已下载（带进程级缓存）。
         * 先命中缓存直接返回 true；否则走 GMS RemoteModelManager Binder 查询（带超时保护）。
         * 查询到 true 后写入缓存，下次跳过 Binder。
         */
        private suspend fun isModelDownloadedCached(mlkitLang: String): Boolean {
            if (downloadedLanguages.contains(mlkitLang)) {
                Log.v(TAG, "命中已下载缓存: $mlkitLang")
                return true
            }
            val ok = isModelDownloadedFresh(mlkitLang)
            if (ok) downloadedLanguages.add(mlkitLang)
            return ok
        }

        /**
         * 绕过进程级缓存，直接向 GMS 查询某语言模型是否已下载。
         * 供"下载完成确认"与"超时后轮询"使用——这两处必须拿到真实状态，
         * 不能用可能过期的内存缓存。
         */
        private suspend fun isModelDownloadedFresh(mlkitLang: String): Boolean =
            runCatching {
                withTimeout(LOCAL_CHECK_TIMEOUT_MS) {
                    RemoteModelManager
                        .getInstance()
                        .isModelDownloaded(TranslateRemoteModel.Builder(mlkitLang).build())
                        .await()
                }
            }.getOrDefault(false)

        /** 查询语言对两个方向的语言模型是否都已下载（绕过缓存） */
        private suspend fun isPairReadyFresh(
            srcCode: String,
            tgtCode: String,
        ): Boolean = isModelDownloadedFresh(srcCode) && isModelDownloadedFresh(tgtCode)

        /** 把语言写入进程级已下载缓存，后续同进程内跳过 GMS 查询 */
        private fun markDownloaded(vararg langs: String) {
            langs.forEach { downloadedLanguages.add(it) }
        }

        /**
         * 检查指定语言的翻译模型是否已下载（对外公开接口，给设置页等场景用）。
         */
        suspend fun isModelDownloaded(languageCode: String): Boolean {
            val mlkitLang = mlkitCode(languageCode) ?: return false
            return try {
                isModelDownloadedCached(mlkitLang)
            } catch (e: Exception) {
                Log.w(TAG, "isModelDownloaded 检查失败: ${e.message}")
                false
            }
        }

        /**
         * Task → suspend 的桥接。
         * 关键修复：强制 addOnSuccessListener/addOnFailureListener 绑定 mainExecutor（主线程），
         * 避免在非主线程或无 Looper 线程（如 IO Dispatcher）执行时 Task 不回调导致挂死。
         */
        private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
            suspendCancellableCoroutine { cont ->
                val resumed =
                    java.util.concurrent.atomic
                        .AtomicBoolean(false)
                val resumeOnce: (Result<T>) -> Unit = { r ->
                    if (resumed.compareAndSet(false, true)) {
                        if (r.isSuccess) {
                            cont.resume(r.getOrThrow())
                        } else {
                            cont.resumeWithException(r.exceptionOrNull() ?: RuntimeException("Unknown Task failure"))
                        }
                    }
                }
                addOnSuccessListener(
                    mainExecutor,
                    object : com.google.android.gms.tasks.OnSuccessListener<T> {
                        override fun onSuccess(value: T) {
                            Log.v(TAG, "Task.onSuccess: value class=${(value as? Any)?.javaClass?.simpleName}")
                            resumeOnce(Result.success(value))
                        }
                    },
                )
                addOnFailureListener(
                    mainExecutor,
                    object : com.google.android.gms.tasks.OnFailureListener {
                        override fun onFailure(e: java.lang.Exception) {
                            Log.e(TAG, "Task.onFailure: ${e.message}", e)
                            resumeOnce(Result.failure(e))
                        }
                    },
                )
                cont.invokeOnCancellation {
                    resumed.compareAndSet(false, true)
                }
            }

        /**
         * 翻译流程：同语种短路 → 代码转换 → LRU 取 Translator → isModelDownloaded 本地校验
         * → 缺失才 downloadModelIfNeeded（短超时防国内无 GMS 卡死）→ 翻译（带超时）。
         */
        override suspend fun translate(
            text: String,
            sourceLanguage: String,
            targetLanguage: String,
        ): Result<TranslationResult> =
            withContext(Dispatchers.IO) {
                try {
                    // 源语言==目标语言：直接返回原文，完全跳过ML Kit调用
                    if (sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
                        Log.i(TAG, "源目标语言相同($sourceLanguage)，直接返回原文")
                        return@withContext Result.success(
                            TranslationResult(
                                sourceText = text,
                                translatedText = text,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage,
                                engine = AppTranslationEngine.LOCAL,
                            ),
                        )
                    }

                    val sourceCode =
                        mlkitCode(sourceLanguage)
                            ?: return@withContext Result.failure(IllegalArgumentException("Unsupported source: $sourceLanguage"))
                    val targetCode =
                        mlkitCode(targetLanguage)
                            ?: return@withContext Result.failure(IllegalArgumentException("Unsupported target: $targetLanguage"))

                    Log.i(TAG, "ML Kit翻译: source=$sourceLanguage($sourceCode) -> target=$targetLanguage($targetCode), text='$text'")

                    val translator = getOrCreateTranslator(sourceCode, targetCode)
                    Log.d(TAG, "ML Kit Translator获取成功")

                    // 先查进程级缓存 → 再查 GMS 本地库（isModelDownloaded，纯本地SQLite不联网）
                    // 任一命中就跳过 downloadModelIfNeeded，避免国内连 Google 验签超时
                    var srcOk = downloadedLanguages.contains(sourceCode)
                    var tgtOk = downloadedLanguages.contains(targetCode)
                    if (!srcOk || !tgtOk) {
                        if (!srcOk) {
                            srcOk =
                                isModelDownloadedCached(sourceCode).also { ok ->
                                    Log.d(TAG, "GMS本地库检查: src=$sourceCode=$ok")
                                }
                        }
                        if (!tgtOk) {
                            tgtOk =
                                isModelDownloadedCached(targetCode).also { ok ->
                                    Log.d(TAG, "GMS本地库检查: tgt=$targetCode=$ok")
                                }
                        }
                    }
                    val localReady = srcOk && tgtOk

                    if (!localReady) {
                        // 同一语言对的并发下载串行化，避免多条翻译请求同时冲 GMS 导致全超时
                        val pairKey = "$sourceCode-$targetCode"
                        val mutex =
                            synchronized(downloadMutexMap) {
                                downloadMutexMap.getOrPut(pairKey) { Mutex() }
                            }
                        val downloadResult =
                            mutex.withLock {
                                downloadAndWait(sourceCode, targetCode, translator)
                            }
                        if (downloadResult.isFailure) {
                            return@withContext Result.failure(
                                downloadResult.exceptionOrNull()
                                    ?: RuntimeException("ML Kit 模型下载失败"),
                            )
                        }
                    } else {
                        Log.i(TAG, "ML Kit 模型本地已就绪($sourceCode,$targetCode)，跳过下载校验")
                    }

                    Log.i(TAG, "开始翻译推理")
                    val translatedText =
                        withTimeout(TRANSLATE_TIMEOUT_MS) {
                            translateAsync(translator, text)
                        }
                    Log.i(TAG, "ML Kit 翻译完成: '$text' -> '$translatedText'")

                    Result.success(
                        TranslationResult(
                            sourceText = text,
                            translatedText = translatedText,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                            engine = AppTranslationEngine.LOCAL,
                        ),
                    )
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    // 超时很可能只是「下载比超时慢」：GMS 的模型下载不会随协程取消而停止，
                    // 实测慢速网络下 60s 超时报失败、模型却在 2 分钟后下载完成。
                    // 因此先复查模型是否已就绪，就绪则当场重试一次翻译，避免把首次下载误报为失败。
                    val retrySrc = mlkitCode(sourceLanguage)
                    val retryTgt = mlkitCode(targetLanguage)
                    if (retrySrc != null && retryTgt != null) {
                        val nowReady =
                            runCatching {
                                isModelDownloadedCached(retrySrc) && isModelDownloadedCached(retryTgt)
                            }.getOrDefault(false)
                        if (nowReady) {
                            Log.i(TAG, "超时后复查模型已就绪，重试一次翻译: $retrySrc → $retryTgt")
                            val recovered =
                                runCatching {
                                    withTimeout(TRANSLATE_TIMEOUT_MS) {
                                        translateAsync(getOrCreateTranslator(retrySrc, retryTgt), text)
                                    }
                                }.getOrNull()
                            if (recovered != null) {
                                Log.i(TAG, "超时重试成功: '$text' -> '$recovered'")
                                return@withContext Result.success(
                                    TranslationResult(
                                        sourceText = text,
                                        translatedText = recovered,
                                        sourceLanguage = sourceLanguage,
                                        targetLanguage = targetLanguage,
                                        engine = AppTranslationEngine.LOCAL,
                                    ),
                                )
                            }
                        }
                    }
                    val reason =
                        buildString {
                            append("ML Kit 翻译模型操作超时：")
                            append(e.message ?: "unknown timeout")
                            append("。国内环境下，ML Kit 翻译模型需要：1) 手机安装Google Play服务框架(GMS) 2) VPN翻墙连Google下载语言模型。若无法满足请切换为云端/AI翻译模式（设置→翻译引擎）")
                        }
                    Log.e(TAG, reason, e)
                    Result.failure(RuntimeException(reason, e))
                } catch (e: Exception) {
                    Log.e(TAG, "ML Kit 翻译失败: ${e.message}", e)
                    Result.failure(e)
                }
            }

        /**
         * 不加锁的"下载并等待就绪"核心逻辑。调用方必须已持有该语言对的 Mutex。
         *
         * 流程：复查本地 → downloadModelIfNeeded（180s 超时）→ 失败则轮询 120s 确认。
         * 轮询的必要性：GMS 的下载不会随协程取消而停止，超时只说明"没在窗口内回调成功"，
         * 并不等于失败；直接判失败正是历史误报的根源。
         */
        private suspend fun downloadAndWait(
            srcCode: String,
            tgtCode: String,
            translator: Translator,
            onPhase: (TranslationPrepPhase) -> Unit = {},
        ): Result<Unit> {
            // 排队期间可能已被其他协程下完
            if (isPairReadyFresh(srcCode, tgtCode)) {
                markDownloaded(srcCode, tgtCode)
                onPhase(TranslationPrepPhase.READY)
                Log.i(TAG, "[downloadAndWait] 排队后复查命中，模型已就绪: $srcCode, $tgtCode")
                return Result.success(Unit)
            }

            // 下载前做一次可达性预检：把"网络到底能不能到 Google"这个最关键的问题说清楚。
            // 刻意**不阻断**下载——App 自身的网络栈与 GMS 进程受各自分流规则影响，
            // App 探测失败不代表 GMS 一定下不了，故预检结果只用于生成更准确的失败原因。
            //
            // 必须探**两个**域名（入口 dl.google.com + 真正的数据源 redirector.gvt1.com）：
            // 实测两者的分流结果可以完全相反——入口命中 DomainKeyword(google) 走了日本节点，
            // 而数据源命中 GeoIP(cn) 被放走直连。只探入口会得出"网络没问题"的错误结论，
            // 把用户引向"重试/等网速"这种无效操作。
            val reachabilities = runCatching { reachabilityChecker.checkAll() }.getOrDefault(emptyList())

            onPhase(TranslationPrepPhase.DOWNLOADING)
            Log.i(
                TAG,
                "[downloadAndWait] 开始下载: $srcCode, $tgtCode, 超时=${DOWNLOAD_TIMEOUT_MS / 1000}s, " +
                    "可达性=${
                        reachabilities.joinToString { (h, r) -> "$h=${r.javaClass.simpleName}" }
                            .ifEmpty { "unknown" }
                    }",
            )
            val downloadError =
                runCatching {
                    withTimeout(DOWNLOAD_TIMEOUT_MS) { ensureModelDownloaded(translator) }
                }.exceptionOrNull()

            if (downloadError == null) {
                markDownloaded(srcCode, tgtCode)
                onPhase(TranslationPrepPhase.READY)
                Log.i(TAG, "[downloadAndWait] 下载完成: $srcCode, $tgtCode")
                return Result.success(Unit)
            }

            Log.w(
                TAG,
                "[downloadAndWait] 未在 ${DOWNLOAD_TIMEOUT_MS / 1000}s 内返回" +
                    "(${downloadError.javaClass.simpleName}: ${downloadError.message})，" +
                    "进入轮询等待 ${RECOVERY_WAIT_MS / 1000}s",
            )
            val deadline = System.currentTimeMillis() + RECOVERY_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(RECOVERY_POLL_INTERVAL_MS)
                if (isPairReadyFresh(srcCode, tgtCode)) {
                    markDownloaded(srcCode, tgtCode)
                    onPhase(TranslationPrepPhase.READY)
                    Log.i(TAG, "[downloadAndWait] 轮询命中，模型已在后台下载完成: $srcCode, $tgtCode")
                    return Result.success(Unit)
                }
            }

            val totalSec = (DOWNLOAD_TIMEOUT_MS + RECOVERY_WAIT_MS) / 1000
            // 网络确实不通时（有响应但不是 Google，或根本连不上）单独分类，
            // 让 UI 能给出"检查代理分流规则"这种可操作的建议，而不是笼统的"下载失败"。
            // 两个探测域名**任一**不可达即算网络问题——下载需要入口与数据源都通。
            val networkBad =
                reachabilities.any { (_, r) ->
                    r !is GoogleReachabilityChecker.Reachability.Reachable
                }
            val kind =
                if (networkBad) {
                    TranslationPrepFailureKind.NETWORK_UNREACHABLE
                } else {
                    TranslationPrepFailureKind.DOWNLOAD_INCOMPLETE
                }
            // detail 只进日志与"诊断"入口：里面是域名、本地解析的 IP、证书结论、原始异常，
            // 对排障有用，但不适合直接摆在弹窗正文里。
            val detail =
                buildString {
                    append("离线翻译模型下载失败：已等待 ${totalSec}s 仍未就绪。")
                    // 每个探测域名都写一条：入口与数据源的分流结果可能相反，
                    // 只报一条会掩盖"数据源被直连"这个真正的瓶颈。
                    reachabilities.forEach { (_, r) -> append(reachabilityChecker.describe(r)) }
                    // ⚠️ 这里是**纯文本**渲染（诊断弹窗直接显示字符串），不要写 Markdown 语法——
                    // 真机上踩过：写成 `**本地 DNS**` 会原样显示出星号。
                    //
                    // 刻意保持简短：诊断是给"想知道为什么失败"的人看的补充材料，
                    // 不是教学文档。分流规则的成因分析留在代码注释与 CHANGELOG 里，
                    // 不往用户面前堆。
                    append("（注：本地解析 IP 不代表实际出口。）")
                    if (kind == TranslationPrepFailureKind.DOWNLOAD_INCOMPLETE) {
                        append("稍后重试通常可恢复；若反复失败，重点看 redirector.gvt1.com 那一条。")
                    }
                    append("（原始错误：")
                    append(downloadError.message ?: downloadError.javaClass.simpleName)
                    append("）")
                }
            Log.e(TAG, detail, downloadError)
            return Result.failure(TranslationPrepException(kind, detail, downloadError))
        }

        /**
         * 预热语言对模型（不执行翻译）：查本地 → 未就绪则下载 → 超时后轮询 → 就绪/失败。
         * 通过 [onPhase] 上报阶段，供 UI 显示非模态"下载中"状态条。
         */
        suspend fun preparePair(
            sourceLanguage: String,
            targetLanguage: String,
            onPhase: (TranslationPrepPhase) -> Unit = {},
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                val srcCode = mlkitCode(sourceLanguage)
                val tgtCode = mlkitCode(targetLanguage)
                if (srcCode == null || tgtCode == null) {
                    return@withContext Result.failure(
                        TranslationPrepException(
                            kind = TranslationPrepFailureKind.UNSUPPORTED_LANGUAGE,
                            detail = "ML Kit 不支持语言对: $sourceLanguage → $targetLanguage",
                        ),
                    )
                }
                // 源=目标：直接返回原文，无需任何模型
                if (srcCode == tgtCode) {
                    onPhase(TranslationPrepPhase.READY)
                    return@withContext Result.success(Unit)
                }

                onPhase(TranslationPrepPhase.CHECKING)
                if (isPairReadyFresh(srcCode, tgtCode)) {
                    markDownloaded(srcCode, tgtCode)
                    onPhase(TranslationPrepPhase.READY)
                    Log.i(TAG, "[preparePair] 模型本地已就绪，无需下载: $srcCode, $tgtCode")
                    return@withContext Result.success(Unit)
                }

                val translator = getOrCreateTranslator(srcCode, tgtCode)
                val pairKey = "$srcCode-$tgtCode"
                val mutex =
                    synchronized(downloadMutexMap) {
                        downloadMutexMap.getOrPut(pairKey) { Mutex() }
                    }
                mutex.withLock {
                    downloadAndWait(srcCode, tgtCode, translator, onPhase)
                }
            }

        /** 释放所有缓存的 Translator 实例 */
        override suspend fun release() {
            synchronized(translatorCache) {
                translatorCache.values.forEach { runCatching { it.close() } }
                translatorCache.clear()
            }
        }

        /** 获取或创建语言对的 Translator 实例（线程安全） */
        private fun getOrCreateTranslator(
            source: String,
            target: String,
        ): Translator {
            val key = "$source-$target"
            synchronized(translatorCache) {
                return translatorCache.getOrPut(key) {
                    val options =
                        TranslatorOptions
                            .Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(target)
                            .build()
                    Translation.getClient(options)
                }
            }
        }

        /** 确保翻译模型已下载（协程封装）。若失败或超时抛异常由上层捕获 */
        private suspend fun ensureModelDownloaded(translator: Translator) {
            Log.d(TAG, "[ensureModelDownloaded] downloadModelIfNeeded 开始，强制主Handler回调")
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            Log.d(TAG, "[ensureModelDownloaded] downloadModelIfNeeded 成功")
        }

        /** 异步执行翻译（suspend 封装，强制主Handler回调防止挂死） */
        private suspend fun translateAsync(
            translator: Translator,
            text: String,
        ): String {
            Log.d(TAG, "[translateAsync] translate 开始，len=${text.length}")
            return translator.translate(text).await()
        }

        companion object {
            private const val TAG = "MlKitTranslationEngine"

            /** 缓存最大大小：最多保留 3 个语言对的 Translator */
            private const val MAX_CACHE_SIZE = 3

            /** isModelDownloaded本地数据库检查超时（GMS Binder查询偶尔卡，首次启动GMS握手中可能慢；放宽到15s） */
            private const val LOCAL_CHECK_TIMEOUT_MS = 15_000L

            /**
             * 单次 downloadModelIfNeeded 的等待超时：180s。
             * - 模型已在本地时根本不走此路径（isModelDownloadedFresh 先命中，0s 返回）；
             * - 未命中才下载，en+zh 合计约 60MB，慢速网络/VPN 下 60s 经常不够，
             *   原 60s 会把"还在下载"误判为失败并弹窗，故放宽到 180s。
             */
            private const val DOWNLOAD_TIMEOUT_MS = 180_000L

            /**
             * 下载超时后的轮询等待窗口：120s。
             * GMS 的模型下载**不会随协程取消而停止**——实测 60s 超时报失败后，模型仍在后台下载完成。
             * 因此超时只代表"没在窗口内回调成功"，不等于失败，需再轮询确认一轮。
             */
            private const val RECOVERY_WAIT_MS = 120_000L

            /** 轮询间隔：3s。仅查询 GMS 本地库（SQLite），不产生网络请求。 */
            private const val RECOVERY_POLL_INTERVAL_MS = 3_000L

            /** 单次翻译推理超时（模型在本地时通常1s内完成） */
            private const val TRANSLATE_TIMEOUT_MS = 5_000L

            /** ML Kit Translation 官方语言映射表，与 LanguagePicker 保持一致 */
            private val LANGUAGE_MAP =
                mapOf(
                    "af" to TranslateLanguage.AFRIKAANS,
                    "sq" to TranslateLanguage.ALBANIAN,
                    "am" to "am",
                    "ar" to TranslateLanguage.ARABIC,
                    "hy" to "hy",
                    "az" to "az",
                    "eu" to "eu",
                    "be" to TranslateLanguage.BELARUSIAN,
                    "bn" to TranslateLanguage.BENGALI,
                    "bs" to "bs",
                    "bg" to TranslateLanguage.BULGARIAN,
                    "ca" to TranslateLanguage.CATALAN,
                    "zh" to TranslateLanguage.CHINESE,
                    "hr" to TranslateLanguage.CROATIAN,
                    "cs" to TranslateLanguage.CZECH,
                    "da" to TranslateLanguage.DANISH,
                    "nl" to TranslateLanguage.DUTCH,
                    "en" to TranslateLanguage.ENGLISH,
                    "et" to TranslateLanguage.ESTONIAN,
                    "fi" to TranslateLanguage.FINNISH,
                    "fr" to TranslateLanguage.FRENCH,
                    "gl" to TranslateLanguage.GALICIAN,
                    "ka" to TranslateLanguage.GEORGIAN,
                    "de" to TranslateLanguage.GERMAN,
                    "el" to TranslateLanguage.GREEK,
                    "gu" to TranslateLanguage.GUJARATI,
                    "ht" to TranslateLanguage.HAITIAN_CREOLE,
                    "he" to TranslateLanguage.HEBREW,
                    "hi" to TranslateLanguage.HINDI,
                    "hu" to TranslateLanguage.HUNGARIAN,
                    "is" to TranslateLanguage.ICELANDIC,
                    "id" to TranslateLanguage.INDONESIAN,
                    "ga" to TranslateLanguage.IRISH,
                    "it" to TranslateLanguage.ITALIAN,
                    "ja" to TranslateLanguage.JAPANESE,
                    "kn" to TranslateLanguage.KANNADA,
                    "kk" to "kk",
                    "ko" to TranslateLanguage.KOREAN,
                    "ky" to "ky",
                    "lv" to TranslateLanguage.LATVIAN,
                    "lt" to TranslateLanguage.LITHUANIAN,
                    "mk" to TranslateLanguage.MACEDONIAN,
                    "ms" to TranslateLanguage.MALAY,
                    "ml" to "ml",
                    "mt" to TranslateLanguage.MALTESE,
                    "mr" to TranslateLanguage.MARATHI,
                    "mn" to "mn",
                    "no" to TranslateLanguage.NORWEGIAN,
                    "fa" to TranslateLanguage.PERSIAN,
                    "pl" to TranslateLanguage.POLISH,
                    "pt" to TranslateLanguage.PORTUGUESE,
                    "pa" to "pa",
                    "ro" to TranslateLanguage.ROMANIAN,
                    "ru" to TranslateLanguage.RUSSIAN,
                    "sr" to "sr",
                    "sk" to TranslateLanguage.SLOVAK,
                    "sl" to TranslateLanguage.SLOVENIAN,
                    "sw" to TranslateLanguage.SWAHILI,
                    "sv" to TranslateLanguage.SWEDISH,
                    "ta" to TranslateLanguage.TAMIL,
                    "te" to TranslateLanguage.TELUGU,
                    "th" to TranslateLanguage.THAI,
                    "tr" to TranslateLanguage.TURKISH,
                    "uk" to TranslateLanguage.UKRAINIAN,
                    "ur" to TranslateLanguage.URDU,
                    "vi" to TranslateLanguage.VIETNAMESE,
                    "cy" to TranslateLanguage.WELSH,
                )

            /** ML Kit 内部代码与自定义代码的转换 */
            private fun mlkitCode(lang: String): String? =
                when (lang.lowercase()) {
                    "nb" -> TranslateLanguage.NORWEGIAN
                    else -> LANGUAGE_MAP[lang.lowercase()]
                }
        }
    }
