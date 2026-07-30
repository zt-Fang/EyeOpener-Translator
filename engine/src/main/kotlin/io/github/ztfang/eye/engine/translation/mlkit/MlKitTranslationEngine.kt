package io.github.ztfang.eye.engine.translation.mlkit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.ztfang.eye.domain.engine.translation.TranslationEngine
import io.github.ztfang.eye.domain.model.TranslationEngine as AppTranslationEngine
import io.github.ztfang.eye.domain.model.TranslationResult
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 翻译引擎。
 * 基于 Google ML Kit Translation API，支持 59 种语言，首次使用需联网下载语言对模型。
 * 特点：本地推理，速度最快；模型下载后可离线使用。
 */
@Singleton
class MlKitTranslationEngine @Inject constructor(
    @ApplicationContext private val context: Context
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
    private val translatorCache = object : LinkedHashMap<String, Translator>(MAX_CACHE_SIZE, 0.75f, true) {
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
    override fun supportsLanguage(source: String, target: String): Boolean =
        mlkitCode(source) != null && mlkitCode(target) != null

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
        val ok = runCatching {
            withTimeout(LOCAL_CHECK_TIMEOUT_MS) {
                RemoteModelManager.getInstance()
                    .isModelDownloaded(TranslateRemoteModel.Builder(mlkitLang).build())
                    .await()
            }
        }.getOrDefault(false)
        if (ok) downloadedLanguages.add(mlkitLang)
        return ok
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
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            val resumeOnce: (Result<T>) -> Unit = { r ->
                if (resumed.compareAndSet(false, true)) {
                    if (r.isSuccess) cont.resume(r.getOrThrow())
                    else cont.resumeWithException(r.exceptionOrNull() ?: RuntimeException("Unknown Task failure"))
                }
            }
            addOnSuccessListener(mainExecutor, object : com.google.android.gms.tasks.OnSuccessListener<T> {
                override fun onSuccess(value: T) {
                    Log.v(TAG, "Task.onSuccess: value class=${(value as? Any)?.javaClass?.simpleName}")
                    resumeOnce(Result.success(value))
                }
            })
            addOnFailureListener(mainExecutor, object : com.google.android.gms.tasks.OnFailureListener {
                override fun onFailure(e: java.lang.Exception) {
                    Log.e(TAG, "Task.onFailure: ${e.message}", e)
                    resumeOnce(Result.failure(e))
                }
            })
            cont.invokeOnCancellation {
                resumed.compareAndSet(false, true)
            }
        }

    /**
     * 翻译流程：同语种短路 → 代码转换 → LRU 取 Translator → isModelDownloaded 本地校验
     * → 缺失才 downloadModelIfNeeded（短超时防国内无 GMS 卡死）→ 翻译（带超时）。
     */
    override suspend fun translate(
        text: String, sourceLanguage: String, targetLanguage: String
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        try {
            // 源语言==目标语言：直接返回原文，完全跳过ML Kit调用
            if (sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
                Log.i(TAG, "源目标语言相同($sourceLanguage)，直接返回原文")
                return@withContext Result.success(TranslationResult(
                    sourceText = text,
                    translatedText = text,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    engine = AppTranslationEngine.LOCAL
                ))
            }

            val sourceCode = mlkitCode(sourceLanguage)
                ?: return@withContext Result.failure(IllegalArgumentException("Unsupported source: $sourceLanguage"))
            val targetCode = mlkitCode(targetLanguage)
                ?: return@withContext Result.failure(IllegalArgumentException("Unsupported target: $targetLanguage"))

            Log.i(TAG, "ML Kit翻译: source=$sourceLanguage($sourceCode) -> target=$targetLanguage($targetCode), text='$text'")

            val translator = getOrCreateTranslator(sourceCode, targetCode)
            Log.d(TAG, "ML Kit Translator获取成功")

            // 先查进程级缓存 → 再查 GMS 本地库（isModelDownloaded，纯本地SQLite不联网）
            // 任一命中就跳过 downloadModelIfNeeded，避免国内连 Google 验签超时
            var srcOk = downloadedLanguages.contains(sourceCode)
            var tgtOk = downloadedLanguages.contains(targetCode)
            if (!srcOk || !tgtOk) {
                if (!srcOk) srcOk = isModelDownloadedCached(sourceCode).also { ok ->
                    Log.d(TAG, "GMS本地库检查: src=$sourceCode=$ok")
                }
                if (!tgtOk) tgtOk = isModelDownloadedCached(targetCode).also { ok ->
                    Log.d(TAG, "GMS本地库检查: tgt=$targetCode=$ok")
                }
            }
            val localReady = srcOk && tgtOk

            if (!localReady) {
                // 同一语言对的并发下载串行化，避免10条翻译请求同时冲GMS导致全超时
                val pairKey = "$sourceCode-$targetCode"
                val mutex = synchronized(downloadMutexMap) {
                    downloadMutexMap.getOrPut(pairKey) { Mutex() }
                }
                mutex.withLock {
                    // 重新检查：可能排队期间已有其他协程下完并写入缓存
                    if (!(downloadedLanguages.contains(sourceCode) && downloadedLanguages.contains(targetCode))) {
                        Log.d(TAG, "本地未就绪($sourceCode=$srcOk, $targetCode=$tgtOk)，调用downloadModelIfNeeded(${DOWNLOAD_TIMEOUT_MS/1000}s超时，首次需VPN/GMS下载)")
                        withTimeout(DOWNLOAD_TIMEOUT_MS) {
                            ensureModelDownloaded(translator)
                        }
                        Log.i(TAG, "ML Kit 模型下载/校验完成，写入进程缓存: $sourceCode, $targetCode")
                        downloadedLanguages.add(sourceCode)
                        downloadedLanguages.add(targetCode)
                    } else {
                        Log.i(TAG, "排队后缓存已命中($sourceCode,$targetCode)，跳过下载")
                    }
                }
            } else {
                Log.i(TAG, "ML Kit 模型本地已就绪($sourceCode,$targetCode)，跳过下载校验")
            }

            Log.i(TAG, "开始翻译推理")
            val translatedText = withTimeout(TRANSLATE_TIMEOUT_MS) {
                translateAsync(translator, text)
            }
            Log.i(TAG, "ML Kit 翻译完成: '$text' -> '$translatedText'")

            Result.success(TranslationResult(
                sourceText = text,
                translatedText = translatedText,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                engine = AppTranslationEngine.LOCAL
            ))
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val reason = buildString {
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

    /** 释放所有缓存的 Translator 实例 */
    override suspend fun release() {
        synchronized(translatorCache) {
            translatorCache.values.forEach { runCatching { it.close() } }
            translatorCache.clear()
        }
    }

    /** 获取或创建语言对的 Translator 实例（线程安全） */
    private fun getOrCreateTranslator(source: String, target: String): Translator {
        val key = "$source-$target"
        synchronized(translatorCache) {
            return translatorCache.getOrPut(key) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(source).setTargetLanguage(target).build()
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
    private suspend fun translateAsync(translator: Translator, text: String): String {
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
         * 模型下载/校验超时：60s。
         * - 如果模型已在本地，isModelDownloadedCached 会直接命中，此代码路径根本不走，0s 等待；
         * - 没命中才走 downloadModelIfNeeded，经 GMS 握手+VPN下 en(~30MB)+zh(~30MB) 合计~60MB，
         *   1~2MB/s 大约 30~60s，给足60s保证下完；
         * - 下完一次写入进程缓存，后续同 APP 生命周期内完全跳过此步。
         */
        private const val DOWNLOAD_TIMEOUT_MS = 60_000L
        /** 单次翻译推理超时（模型在本地时通常1s内完成） */
        private const val TRANSLATE_TIMEOUT_MS = 5_000L

        /** ML Kit Translation 官方语言映射表，与 LanguagePicker 保持一致 */
        private val LANGUAGE_MAP = mapOf(
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
            "cy" to TranslateLanguage.WELSH
        )

        /** ML Kit 内部代码与自定义代码的转换 */
        private fun mlkitCode(lang: String): String? = when (lang.lowercase()) {
            "nb" -> TranslateLanguage.NORWEGIAN
            else -> LANGUAGE_MAP[lang.lowercase()]
        }
    }
}
