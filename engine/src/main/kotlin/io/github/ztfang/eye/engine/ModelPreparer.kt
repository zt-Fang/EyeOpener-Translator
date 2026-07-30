/**
 * 模块说明: ModelPreparer —— 模型预加载与下载管理
 *
 * 职责:
 * 1. 统一管理三类推理后端的就绪状态: ASR(Vosk 多语种/Sherpa-ONNX 中文)、ML Kit 翻译、LLM
 * 2. 按 [io.github.ztfang.eye.domain.model.TranslationEngine] 选择对应后端进行预加载
 *    - LOCAL  → ML Kit 翻译（ASR 由源语言决定，与翻译引擎解耦）
 *    - CLOUD  → 云端翻译引擎（Phase 3 接入，暂复用 ML Kit 占位）
 *    - AI     → LLM API 翻译
 * 3. ASR 支持两种引擎：
 *    - Vosk 多语种流式识别（en/zh/ja/ko/ru/fr/de/es 等）
 *    - Sherpa-ONNX 流式 Zipformer（中文优化，准确率更高）
 * 4. 处理模型文件的下载路径解析、assets 内置模型回退拷贝、ML Kit 语言对预热
 *
 * 调用时机:
 * - 用户在主屏切换引擎或切换源语种时由字幕 ViewModel 触发
 * - 悬浮窗 Overlay 启动前预热,避免录制时延迟
 *
 * 返回 [Result] 便于调用方将错误信息透传给 UI。
 */
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
 * 模型预加载器。
 *
 * 为给定的 [TranslationEngine] 及 ASR 加载对应的推理后端。
 *
 * 调用场景:
 *   1. 用户在主屏切换引擎或切换源语种时,由字幕 ViewModel 调用。
 *   2. 悬浮窗 Overlay 启动时调用,用于录制前预热。
 *
 * 通过 [Result] 返回,调用方可将错误信息清晰地呈现给用户。
 *
 * @property mlKitEngine     ML Kit 翻译引擎（LOCAL 模式）
 * @property voskEngine      Vosk 多语种流式 ASR 引擎
 * @property sherpaEngine    Sherpa-ONNX 流式 ASR 引擎（中文优化）
 * @property llmClient       LLM 远程客户端（AI 模式）
 * @property modelRepository 模型仓库,负责查询已下载模型实际路径
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
     * 按引擎准备翻译模型,确保对应后端就绪。
     *
     * 分支逻辑:
     * - [TranslationEngine.LOCAL]: 校验 Google Play Services 可用,
     *                              再通过 [prepareMlKitPair] 预热 ML Kit(模型按需下载)。
     * - [TranslationEngine.CLOUD]: 暂复用 ML Kit 占位（Phase 3 接入云端引擎后替换）。
     * - [TranslationEngine.AI]:    校验 LLM 的 URL/Key/Model 配置;网络连通性在调用时检查。
     *
     * @param engine          翻译引擎
     * @param sourceLanguage 源语言代码,空串表示暂不指定(LOCAL 模式下会跳过预热)
     * @param targetLanguage 目标语言代码
     * @return 成功返回 [Result.success],失败返回带原因的 [Result.failure]
     */
    suspend fun prepareTranslation(
        engine: TranslationEngine,
        sourceLanguage: String = "",
        targetLanguage: String = ""
    ): Result<Unit> = when (engine) {
        TranslationEngine.LOCAL, TranslationEngine.CLOUD -> {
            // LOCAL: ML Kit 离线翻译；CLOUD: Phase 3 接入前暂复用 ML Kit 占位
            // 1. 校验 Google Play Services
            val play = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
            if (play != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                return Result.failure(IllegalStateException(
                    "Google Play Services 不可用,需要它来下载翻译模型"
                ))
            }
            // 2. 仅当源/目标语言都给出时才预热
            if (sourceLanguage.isNotEmpty() && targetLanguage.isNotEmpty()) {
                if (!mlKitEngine.supportsLanguage(sourceLanguage, targetLanguage)) {
                    // 引擎不支持该语言对时静默返回失败（按用户指示：不响应即可）
                    return Result.failure(IllegalStateException(
                        "ML Kit 不支持语言对 $sourceLanguage → $targetLanguage"
                    ))
                }
                // 主动预热模型,首次下载需要联网
                return prepareMlKitPair(sourceLanguage, targetLanguage)
            }
            Result.success(Unit)
        }
        TranslationEngine.AI -> {
            // LLM 仅校验配置,不预下载
            llmClient.validateConfig()
        }
    }

    /**
     * 准备 ASR 后端: 按语种加载 Vosk small 模型。
     *
     * 流程:
     * 1. 根据 [languageCode] 查询对应的 Vosk 模型名称。
     * 2. 直接使用 [asrModelDir] 拼接模型路径（Vosk 解压目录固定为 filesDir/models/vosk/<lang>/）。
     *    注意：不能使用 [modelRepository.getModelPath]，因为该方法返回的是 state.json 所在目录
     *    （filesDir/models/VOSK_ASR_<LANG>/），那里只有状态文件，没有 am/conf/graph 模型文件，
     *    会导致误判"模型未下载"。
     * 3. 校验模型目录下核心文件(am/conf/graph)是否存在。
     * 4. 若下载未完成或缺失,返回失败,提示去模型管理下载。
     *
     * @param languageCode 源语言代码(en/zh/ja/ko/ru/fr/de/es)
     * @return 成功返回 [Result.success];失败返回 [Result.failure],携带缺失路径或初始化异常。
     */
    suspend fun prepareAsr(languageCode: String): Result<Unit> {
        val modelName = VoskLanguageMap.getModelName(languageCode)
            ?: return Result.failure(IllegalStateException(
                "不支持的语种: $languageCode"
            ))

        // Vosk 模型解压目录固定为 filesDir/models/vosk/<lang>/，不查 modelRepository
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

    /**
     * 获取指定语种的 Vosk 模型文件位置,并确保父目录存在。
     *
     * 供下载流程使用。
     *
     * @param languageCode 语言代码(en/zh/ja/ko/ru/fr/de/es)
     * @return 模型根目录 [File],已通过 [File.mkdirs] 创建。
     */
    fun asrModelDir(languageCode: String): File {
        val dir = File(context.filesDir, "${VOSK_MODEL_DIR}/$languageCode")
        dir.mkdirs()
        return dir
    }

    /**
     * 获取 Sherpa-ONNX ASR 模型文件位置,并确保父目录存在。
     *
     * 供下载流程使用。
     *
     * @param modelId 模型 ID（如 sherpa-onnx-streaming-zipformer-zh-xlarge-int8-2025-06-30）
     * @return 模型根目录 [File],已通过 [File.mkdirs] 创建。
     */
    fun sherpaOnnxModelDir(modelId: String): File {
        val dir = File(context.filesDir, "${SHERPA_ONNX_MODEL_DIR}/$modelId")
        dir.mkdirs()
        return dir
    }

    /**
     * 检查 Sherpa-ONNX ASR 模型是否就绪。
     * 需要目录下存在 encoder/decoder/joiner/tokens 四个文件。
     *
     * 对 tokens.txt 做【自愈检查】：历史版本曾把首字符为'<'的 tokens.txt 误判为 HTML，
     * 留下 tokens.txt.part / tokens.txt.part.corrupted 的遗留文件，只要其大小>0就认为有效，
     * 并立即重命名为官方 tokensFile(通常=tokens.txt)，确保后续 ASR init 正常。
     *
     * @param modelId 模型 ID
     * @return 就绪返回 true,否则 false
     */
    fun isSherpaOnnxAsrReady(modelId: String): Boolean {
        val modelDir = sherpaOnnxModelDir(modelId)
        val model = SherpaOnnxModel.fromModelId(modelId) ?: return false
        val enc = File(modelDir, model.encoderFile)
        val dec = File(modelDir, model.decoderFile)
        val join = File(modelDir, model.joinerFile)
        val tok = File(modelDir, model.tokensFile)

        // tokens.txt 自愈：若官方名不存在，但存在 .part / .part.corrupted >0 字节就改名回去
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
                    // copyTo 成功后再强制检查一遍 tok 是否存在（防止 rename 失败但未抛异常等边缘情况）
                    if (!tok.exists() || tok.length() <= 0L) {
                        // 再尝试一遍：aliasOrNull 重新 list 再找，强制 copy
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

    /**
     * 准备 Sherpa-ONNX ASR 后端。
     *
     * @param modelId 模型 ID
     * @return 成功返回 [Result.success],失败返回 [Result.failure]
     */
    suspend fun prepareSherpaOnnxAsr(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val modelDir = sherpaOnnxModelDir(modelId)
        Log.i(TAG, "prepareSherpaOnnxAsr: modelId=$modelId, dir=${modelDir.absolutePath}")
        val model = SherpaOnnxModel.fromModelId(modelId)
            ?: return@withContext Result.failure(IllegalStateException(
                "不支持的 Sherpa-ONNX 模型: $modelId"
            ))

        // 检查模型文件完整性
        if (!isSherpaOnnxAsrReady(modelId)) {
            Log.e(TAG, "prepareSherpaOnnxAsr: 模型文件不完整, modelId=$modelId")
            return@withContext Result.failure(IllegalStateException(
                "Sherpa-ONNX 模型未下载: ${model.displayName}"
            ))
        }

        Log.i(TAG, "prepareSherpaOnnxAsr: 模型文件齐全, modelId=$modelId, path=${modelDir.absolutePath}")
        // 调用引擎初始化（加载模型到内存，创建 Recognizer）
        val initResult = sherpaEngine.init(modelDir.absolutePath)
        if (initResult.isFailure) {
            Log.e(TAG, "prepareSherpaOnnxAsr: 引擎初始化失败: ${initResult.exceptionOrNull()?.message}")
        }
        initResult
    }

    /**
     * 检查指定语言代码对应的 ASR 模型是否完整就绪（文件系统级检查）。
     *
     * 引擎映射（与 SubtitleManager.resolveAsrEngine 保持完全一致，保证三方判断同源）：
     * - zh/en → SHERPA_ONNX (X-ASR)
     * - bn → SHERPA_ONNX_BN
     * - Nemotron 26 语 → SHERPA_ONNX_NEMOTRON (多语种大包共享)
     * - 其余 → Vosk (小语种小包，按语言单独目录)
     *
     * 本函数无副作用，可在非挂起上下文调用，被 SettingsViewModel / UI / SubtitleManager 共享。
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
     * 主动下载/预热 ML Kit 某语言对的翻译模型。
     *
     * 首次使用某 src→tgt 对时需要联网下载 30-100MB 模型文件,之后可离线使用。
     * 供 UI 层"首次切换语言"弹确认后调用。
     *
     * 实现方式: 调用 [mlKitEngine.translate] 翻译一段占位文本("hello"),
     * 其内部会触发 `downloadModelIfNeeded`,从而完成模型下载与预热。
     *
     * @param sourceLanguage 源语言代码
     * @param targetLanguage 目标语言代码
     * @return 成功返回 [Result.success];失败返回 [Result.failure] 并说明 GPS/语言对问题。
     */
    suspend fun prepareMlKitPair(
        sourceLanguage: String,
        targetLanguage: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // 校验 Google Play Services
        val play = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (play != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            return@withContext Result.failure(IllegalStateException(
                "Google Play Services 不可用,本地翻译需要它来下载模型"
            ))
        }
        // 校验语言对是否被 ML Kit 支持
        if (!mlKitEngine.supportsLanguage(sourceLanguage, targetLanguage)) {
            return@withContext Result.failure(IllegalStateException(
                "ML Kit 不支持语言对 $sourceLanguage → $targetLanguage"
            ))
        }
        // translate() 内部会触发 downloadModelIfNeeded,用一段空文本预热即可
        mlKitEngine.translate("hello", sourceLanguage, targetLanguage)
            .map { Unit }
    }

    /**
     * 从 assets 拷贝单个文件到目标位置。
     *
     * 若目标文件已存在且非空,视为已就绪直接返回 true;
     * 否则尝试打开 [assetName] 并拷贝,拷贝成功返回 true,失败(如 asset 缺失)返回 false。
     *
     * @param assetName assets 内的相对路径
     * @param dest      目标文件
     * @return 文件最终存在于磁盘上则 true,否则 false
     */
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
