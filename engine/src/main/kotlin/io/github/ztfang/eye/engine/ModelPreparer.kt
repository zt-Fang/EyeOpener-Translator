package io.github.ztfang.eye.engine

import android.content.Context
import android.util.Log
import io.github.ztfang.eye.domain.model.AsrEngineType
import io.github.ztfang.eye.domain.model.SherpaOnnxModel
import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.domain.model.VoskLanguage
import io.github.ztfang.eye.engine.asr.SherpaOnnxAsrEngine
import io.github.ztfang.eye.engine.asr.VoskAsrEngine
import io.github.ztfang.eye.engine.asr.VoskLanguageMap
import io.github.ztfang.eye.engine.translation.llm.LLMClient
import io.github.ztfang.eye.engine.translation.mlkit.MlKitTranslationEngine
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型预加载器：按 [TranslationEngine] 及源语种预热推理后端（ML Kit / 云端 / LLM / Vosk / Sherpa-ONNX）。
 * 主屏切换引擎/语种、悬浮窗启动前调用，避免录制时延迟。
 */
@Singleton
class ModelPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mlKitEngine: MlKitTranslationEngine,
    private val voskEngine: VoskAsrEngine,
    private val sherpaEngine: SherpaOnnxAsrEngine,
    private val llmClient: LLMClient,
    private val modelRepository: io.github.ztfang.eye.domain.repository.ModelRepository
) {

    /**
     * 预热翻译后端：LOCAL/CLOUD 校验 Google Play Services 后预热 ML Kit；AI 仅校验 LLM 配置。
     * sourceLanguage 为空时跳过预热。
     */
    suspend fun prepareTranslation(
        engine: TranslationEngine,
        sourceLanguage: String = "",
        targetLanguage: String = ""
    ): Result<Unit> = when (engine) {
        TranslationEngine.LOCAL, TranslationEngine.CLOUD -> {
            // CLOUD 暂复用 ML Kit
            val play = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
            if (play != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                return Result.failure(IllegalStateException(
                    "Google Play Services 不可用,需要它来下载翻译模型"
                ))
            }
            if (sourceLanguage.isNotEmpty() && targetLanguage.isNotEmpty()) {
                if (!mlKitEngine.supportsLanguage(sourceLanguage, targetLanguage)) {
                    return Result.failure(IllegalStateException(
                        "ML Kit 不支持语言对 $sourceLanguage → $targetLanguage"
                    ))
                }
                // 首次下载需联网
                return prepareMlKitPair(sourceLanguage, targetLanguage)
            }
            Result.success(Unit)
        }
        TranslationEngine.AI -> {
            // 仅校验配置，不预下载
            llmClient.validateConfig()
        }
    }

    /**
     * 按语种加载 Vosk small 模型并初始化引擎。
     * 路径直接用 [asrModelDir] 拼接；不能用 [modelRepository.getModelPath]——它返回
     * state.json 所在目录，没有 am/conf/graph，会误判"模型未下载"。
     */
    suspend fun prepareAsr(languageCode: String): Result<Unit> {
        val modelName = VoskLanguageMap.getModelName(languageCode)
            ?: return Result.failure(IllegalStateException(
                "不支持的语种: $languageCode"
            ))

        val modelDirPath = asrModelDir(languageCode).absolutePath

        Log.i(TAG, "prepareAsr: language=$languageCode, modelName=$modelName, path=$modelDirPath")

        val amDir = File(modelDirPath, "am")
        val confDir = File(modelDirPath, "conf")
        val graphDir = File(modelDirPath, "graph")

        if (!amDir.exists() || !confDir.exists() || !graphDir.exists()) {
            Log.e(TAG, "prepareAsr: 模型文件缺失, lang=$languageCode, path=$modelDirPath" +
                    ", am=${amDir.exists()}, conf=${confDir.exists()}, graph=${graphDir.exists()}")
            return Result.failure(IllegalStateException(
                "语音识别模型未下载,语种: ${VoskLanguageMap.getDisplayName(languageCode)}"
            ))
        }
        Log.i(TAG, "prepareAsr: 模型文件齐全, lang=$languageCode, path=$modelDirPath")
        return voskEngine.init(modelDirPath)
    }

    /** Vosk 模型目录 filesDir/models/vosk/<lang>/（自动创建） */
    fun asrModelDir(languageCode: String): File {
        val dir = File(context.filesDir, "${VOSK_MODEL_DIR}/$languageCode")
        dir.mkdirs()
        return dir
    }

    /** Sherpa-ONNX 模型目录 filesDir/models/sherpa-onnx/<modelId>/（自动创建） */
    fun sherpaOnnxModelDir(modelId: String): File {
        val dir = File(context.filesDir, "${SHERPA_ONNX_MODEL_DIR}/$modelId")
        dir.mkdirs()
        return dir
    }

    /**
     * 检查 encoder/decoder/joiner/tokens 是否齐全。
     * tokens.txt 自愈：历史误判产生的 .part/.part.corrupted 残留（>0 字节）重命名回正式名。
     */
    fun isSherpaOnnxAsrReady(modelId: String): Boolean {
        val modelDir = sherpaOnnxModelDir(modelId)
        val model = SherpaOnnxModel.fromModelId(modelId) ?: return false
        val enc = File(modelDir, model.encoderFile)
        val dec = File(modelDir, model.decoderFile)
        val join = File(modelDir, model.joinerFile)
        val tok = File(modelDir, model.tokensFile)

        if (!tok.exists() || tok.length() <= 0L) {
            val aliasOrNull = modelDir.listFiles()?.firstOrNull { f ->
                val n = f.name
                (n == model.tokensFile ||
                        n == "${model.tokensFile}.part" ||
                        n == "${model.tokensFile}.part.corrupted") && f.length() > 0L
            }
            if (aliasOrNull != null && aliasOrNull.name != model.tokensFile) {
                runCatching {
                    if (tok.exists()) tok.delete()
                    val renameOk = runCatching { aliasOrNull.renameTo(tok) }.getOrDefault(false)
                    if (!renameOk) {
                        aliasOrNull.copyTo(tok, overwrite = true)
                        runCatching { aliasOrNull.delete() }
                    }
                    // rename 可能静默失败，复查一次不行再强制 copy
                    if (!tok.exists() || tok.length() <= 0L) {
                        val aliasRetry = modelDir.listFiles()?.firstOrNull { f ->
                            val n = f.name
                            (n == "${model.tokensFile}.part" || n == "${model.tokensFile}.part.corrupted" || n == model.tokensFile) && f.length() > 0L
                        }
                        if (aliasRetry != null) {
                            aliasRetry.copyTo(tok, overwrite = true)
                        }
                    }
                    Log.i(TAG, "isSherpaOnnxAsrReady: [TOKENS_RECOVER] modelId=$modelId, ${aliasOrNull.name}(${aliasOrNull.length()}) -> ${model.tokensFile}, renameOk=$renameOk, finalTokExists=${tok.exists()}, finalTokSize=${tok.lengthOrZero()}")
                }.onFailure { e ->
                    Log.w(TAG, "isSherpaOnnxAsrReady: [TOKENS_RECOVER_FAIL] modelId=$modelId: aliasOrNull=${aliasOrNull.name}(${aliasOrNull.length()}), err=${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }

        val ready = enc.exists() && dec.exists() && join.exists() && tok.exists() && tok.length() > 0L
        if (!ready) {
            Log.e(TAG, "isSherpaOnnxAsrReady: modelId=$modelId, dir=${modelDir.absolutePath}" +
                    ", encoder=${enc.exists()}(size=${enc.lengthOrZero()}), decoder=${dec.exists()}(size=${dec.lengthOrZero()})" +
                    ", joiner=${join.exists()}(size=${join.lengthOrZero()}), tokens=${tok.exists()}(size=${tok.lengthOrZero()})" +
                    ", 目录文件=${modelDir.listFiles()?.map { "${it.name}(${it.length()})" }?.take(10)}")
        } else {
            Log.i(TAG, "isSherpaOnnxAsrReady: modelId=$modelId OK, dir=${modelDir.absolutePath}")
        }
        return ready
    }

    private fun File.lengthOrZero(): Long = if (exists()) length() else 0L

    /** 校验 Sherpa-ONNX 模型文件并初始化引擎 */
    suspend fun prepareSherpaOnnxAsr(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val modelDir = sherpaOnnxModelDir(modelId)
        Log.i(TAG, "prepareSherpaOnnxAsr: modelId=$modelId, dir=${modelDir.absolutePath}")
        val model = SherpaOnnxModel.fromModelId(modelId)
            ?: return@withContext Result.failure(IllegalStateException(
                "不支持的 Sherpa-ONNX 模型: $modelId"
            ))

        if (!isSherpaOnnxAsrReady(modelId)) {
            Log.e(TAG, "prepareSherpaOnnxAsr: 模型文件不完整, modelId=$modelId")
            return@withContext Result.failure(IllegalStateException(
                "Sherpa-ONNX 模型未下载: ${model.displayName}"
            ))
        }

        Log.i(TAG, "prepareSherpaOnnxAsr: 模型文件齐全, modelId=$modelId, path=${modelDir.absolutePath}")
        val initResult = sherpaEngine.init(modelDir.absolutePath)
        if (initResult.isFailure) {
            Log.e(TAG, "prepareSherpaOnnxAsr: 引擎初始化失败: ${initResult.exceptionOrNull()?.message}")
        }
        initResult
    }

    /**
     * 文件系统级检查 ASR 模型是否就绪；无副作用，被 SettingsViewModel / UI / SubtitleManager 共享。
     * 引擎映射与 SubtitleManager.resolveAsrEngine 保持一致：
     * zh/en → X-ASR；bn → BN；Nemotron 26 语 → NEMOTRON；其余 → Vosk。
     */
    fun isAsrModelReadyFor(languageCode: String): Boolean = when {
        languageCode == "zh" || languageCode == "en" ->
            isSherpaOnnxAsrReady(SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId)
        languageCode == "bn" ->
            isSherpaOnnxAsrReady(SherpaOnnxModel.BN_VOSK_2026_02_09.modelId)
        SherpaOnnxModel.NEMOTRON_LANGUAGES.contains(languageCode) ->
            isSherpaOnnxAsrReady(SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelId)
        else -> {
            val dir = asrModelDir(languageCode)
            val am = File(dir, "am").isDirectory
            val conf = File(dir, "conf").isDirectory
            val graph = File(dir, "graph").isDirectory
            am && conf && graph
        }
    }

    /** 返回某语言对应的 ASR 引擎类型（与 SubtitleManager 共用，避免判断不一致） */
    fun asrEngineTypeFor(languageCode: String): AsrEngineType = when {
        languageCode == "zh" || languageCode == "en" -> AsrEngineType.SHERPA_ONNX
        languageCode == "bn" -> AsrEngineType.SHERPA_ONNX_BN
        SherpaOnnxModel.NEMOTRON_LANGUAGES.contains(languageCode) -> AsrEngineType.SHERPA_ONNX_NEMOTRON
        else -> AsrEngineType.VOSK
    }

    /** 获取所有支持的 Sherpa-ONNX 模型列表 */
    fun getSherpaOnnxModels(): List<SherpaOnnxModel> = SherpaOnnxModel.getAll()

    /** 获取所有支持的 Vosk 语种列表 */
    fun getVoskLanguages(): List<VoskLanguage> = VoskLanguage.getAll()

    /**
     * 预热 ML Kit 语言对模型（首次需联网下载 30-100MB）。
     * 通过翻译占位文本触发内部 downloadModelIfNeeded 完成下载。
     */
    suspend fun prepareMlKitPair(
        sourceLanguage: String,
        targetLanguage: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val play = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (play != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            return@withContext Result.failure(IllegalStateException(
                "Google Play Services 不可用,本地翻译需要它来下载模型"
            ))
        }
        if (!mlKitEngine.supportsLanguage(sourceLanguage, targetLanguage)) {
            return@withContext Result.failure(IllegalStateException(
                "ML Kit 不支持语言对 $sourceLanguage → $targetLanguage"
            ))
        }
        // translate() 内部会触发 downloadModelIfNeeded,用一段空文本预热即可
        mlKitEngine.translate("hello", sourceLanguage, targetLanguage)
            .map { Unit }
    }

    /** 从 assets 拷贝文件；已存在非空则跳过，失败返回 false */
    private fun copyFromAssetsIfPresent(assetName: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0) return true
        return try {
            context.assets.open(assetName).use { input ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Asset $assetName missing: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "ModelPreparer"

        /** Vosk ASR 模型根目录(相对 filesDir),按语种分目录。 */
        const val VOSK_MODEL_DIR = "models/vosk"

        /** Sherpa-ONNX ASR 模型根目录(相对 filesDir),按模型 ID 分目录。 */
        const val SHERPA_ONNX_MODEL_DIR = "models/sherpa-onnx"
    }
}
