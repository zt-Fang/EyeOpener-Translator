package io.github.ztfang.eye.engine

import android.content.Context
import android.util.Log
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ztfang.eye.domain.model.SherpaOnnxModel
import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.engine.asr.SherpaOnnxAsrEngine
import io.github.ztfang.eye.engine.asr.VoskAsrEngine
import io.github.ztfang.eye.engine.asr.VoskLanguageMap
import io.github.ztfang.eye.engine.translation.TranslationPrepException
import io.github.ztfang.eye.engine.translation.TranslationPrepFailureKind
import io.github.ztfang.eye.engine.translation.cloud.CloudTranslationEngine
import io.github.ztfang.eye.engine.translation.llm.LLMClient
import io.github.ztfang.eye.engine.translation.mlkit.MlKitTranslationEngine
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
class ModelPreparer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val mlKitEngine: MlKitTranslationEngine,
        private val voskEngine: VoskAsrEngine,
        private val sherpaEngine: SherpaOnnxAsrEngine,
        private val llmClient: LLMClient,
        private val cloudEngine: CloudTranslationEngine,
        private val modelRepository: io.github.ztfang.eye.domain.repository.ModelRepository,
    ) {
        /**
         * 预热翻译后端。
         * - **LOCAL**：校验 Google Play Services 后预热 ML Kit 离线模型（首次需联网下载），
         *   过程中通过 [onPhase] 上报阶段，供 UI 显示非模态状态条。
         * - **CLOUD**：云端推理在服务端完成，本地不需要任何模型，**只校验 API Key**。
         * - **AI**：仅校验 LLM 配置。
         *
         * 历史 bug：CLOUD 曾与 LOCAL 合并走 `prepareMlKitPair`，导致用户选云端翻译时
         * 仍被要求下载本地离线模型，下载失败即弹"翻译模型未就绪"。已拆开。
         */
        suspend fun prepareTranslation(
            engine: TranslationEngine,
            sourceLanguage: String = "",
            targetLanguage: String = "",
            onPhase: (TranslationPrepPhase) -> Unit = {},
        ): Result<Unit> =
            when (engine) {
                TranslationEngine.LOCAL -> {
                    val play =
                        GoogleApiAvailability
                            .getInstance()
                            .isGooglePlayServicesAvailable(context)
                    when {
                        play != com.google.android.gms.common.ConnectionResult.SUCCESS -> {
                            Result.failure(
                                TranslationPrepException(
                                    kind = TranslationPrepFailureKind.GMS_UNAVAILABLE,
                                    detail = "Google Play Services 不可用（状态码 $play），本地离线翻译依赖它下载模型",
                                ),
                            )
                        }
                        sourceLanguage.isEmpty() || targetLanguage.isEmpty() -> {
                            // 语言尚未确定，跳过预热；待语言确定后由 ensureModelsLoaded 触发
                            onPhase(TranslationPrepPhase.IDLE)
                            Result.success(Unit)
                        }
                        !mlKitEngine.supportsLanguage(sourceLanguage, targetLanguage) -> {
                            Result.failure(
                                TranslationPrepException(
                                    kind = TranslationPrepFailureKind.UNSUPPORTED_LANGUAGE,
                                    detail = "ML Kit 不支持语言对 $sourceLanguage → $targetLanguage",
                                ),
                            )
                        }
                        else -> {
                            prepareMlKitPair(sourceLanguage, targetLanguage, onPhase)
                        }
                    }
                }
                TranslationEngine.CLOUD -> {
                    // 云端翻译在服务端推理，本地无需模型文件，绝不触发 ML Kit 下载
                    cloudEngine.validateConfig()
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
            val modelName =
                VoskLanguageMap.getModelName(languageCode)
                    ?: return Result.failure(
                        IllegalStateException(
                            "不支持的语种: $languageCode",
                        ),
                    )

            val modelDirPath = asrModelDir(languageCode).absolutePath

            Log.i(TAG, "prepareAsr: language=$languageCode, modelName=$modelName, path=$modelDirPath")

            val amDir = File(modelDirPath, "am")
            val confDir = File(modelDirPath, "conf")
            val graphDir = File(modelDirPath, "graph")

            if (!amDir.exists() || !confDir.exists() || !graphDir.exists()) {
                Log.e(
                    TAG,
                    "prepareAsr: 模型文件缺失, lang=$languageCode, path=$modelDirPath" +
                        ", am=${amDir.exists()}, conf=${confDir.exists()}, graph=${graphDir.exists()}",
                )
                return Result.failure(
                    IllegalStateException(
                        "语音识别模型未下载,语种: ${VoskLanguageMap.getDisplayName(languageCode)}",
                    ),
                )
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
                val aliasOrNull =
                    modelDir.listFiles()?.firstOrNull { f ->
                        val n = f.name
                        (
                            n == model.tokensFile ||
                                n == "${model.tokensFile}.part" ||
                                n == "${model.tokensFile}.part.corrupted"
                        ) &&
                            f.length() > 0L
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
                            val aliasRetry =
                                modelDir.listFiles()?.firstOrNull { f ->
                                    val n = f.name
                                    val isCandidate =
                                        n == "${model.tokensFile}.part" ||
                                            n == "${model.tokensFile}.part.corrupted" ||
                                            n == model.tokensFile
                                    isCandidate && f.length() > 0L
                                }
                            if (aliasRetry != null) {
                                aliasRetry.copyTo(tok, overwrite = true)
                            }
                        }
                        Log.i(
                            TAG,
                            "isSherpaOnnxAsrReady: [TOKENS_RECOVER] modelId=$modelId, " +
                                "${aliasOrNull.name}(${aliasOrNull.length()}) -> " +
                                "${model.tokensFile}, renameOk=$renameOk, " +
                                "finalTokExists=${tok.exists()}, " +
                                "finalTokSize=${tok.lengthOrZero()}",
                        )
                    }.onFailure { e ->
                        Log.w(
                            TAG,
                            "isSherpaOnnxAsrReady: [TOKENS_RECOVER_FAIL] modelId=$modelId: aliasOrNull=${aliasOrNull.name}(${aliasOrNull.length()}), err=${e.javaClass.simpleName}: ${e.message}",
                            e,
                        )
                    }
                }
            }

            val ready = enc.exists() && dec.exists() && join.exists() && tok.exists() && tok.length() > 0L
            if (!ready) {
                Log.e(
                    TAG,
                    "isSherpaOnnxAsrReady: modelId=$modelId, dir=${modelDir.absolutePath}" +
                        ", encoder=${enc.exists()}(size=${enc.lengthOrZero()}), decoder=${dec.exists()}(size=${dec.lengthOrZero()})" +
                        ", joiner=${join.exists()}(size=${join.lengthOrZero()}), tokens=${tok.exists()}(size=${tok.lengthOrZero()})" +
                        ", 目录文件=${modelDir.listFiles()?.map { "${it.name}(${it.length()})" }?.take(10)}",
                )
            } else {
                Log.i(TAG, "isSherpaOnnxAsrReady: modelId=$modelId OK, dir=${modelDir.absolutePath}")
            }
            return ready
        }

        private fun File.lengthOrZero(): Long = if (exists()) length() else 0L

        /** 校验 Sherpa-ONNX 模型文件并初始化引擎 */
        suspend fun prepareSherpaOnnxAsr(modelId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                val modelDir = sherpaOnnxModelDir(modelId)
                Log.i(TAG, "prepareSherpaOnnxAsr: modelId=$modelId, dir=${modelDir.absolutePath}")
                val model =
                    SherpaOnnxModel.fromModelId(modelId)
                        ?: return@withContext Result.failure(
                            IllegalStateException(
                                "不支持的 Sherpa-ONNX 模型: $modelId",
                            ),
                        )

                if (!isSherpaOnnxAsrReady(modelId)) {
                    Log.e(TAG, "[MODEL_CHECK] modelId=$modelId REASON=FILE_MISSING(真未下载/文件不全), dir=${modelDir.absolutePath}")
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Sherpa-ONNX 模型未下载: ${model.displayName}",
                        ),
                    )
                }

                Log.i(TAG, "prepareSherpaOnnxAsr: 模型文件齐全, modelId=$modelId, path=${modelDir.absolutePath}")
                val initResult = sherpaEngine.init(modelDir.absolutePath)
                if (initResult.isFailure) {
                    val errMsg = initResult.exceptionOrNull()?.message ?: "unknown"
                    Log.e(
                        TAG,
                        "[MODEL_CHECK] modelId=$modelId REASON=INIT_FAILED(文件齐全但引擎初始化失败，非下载问题), dir=${modelDir.absolutePath}, err=$errMsg",
                    )
                }
                initResult
            }

        /**
         * 获取所有支持的 Sherpa-ONNX 模型列表
         */
        fun getSherpaOnnxModels(): List<SherpaOnnxModel> = SherpaOnnxModel.getAll()

        /**
         * 预热 ML Kit 语言对模型（首次需联网下载 30-100MB）。
         *
         * 委托 [MlKitTranslationEngine.preparePair]：命中本地则秒回；未命中才下载，
         * 且下载超时后会继续轮询等待 GMS 完成，避免把"正在下载"误报为失败。
         * 不再用 `translate("hello")` 蹭下载路径——那会白跑一次推理，且错误信息不清晰。
         */
        suspend fun prepareMlKitPair(
            sourceLanguage: String,
            targetLanguage: String,
            onPhase: (TranslationPrepPhase) -> Unit = {},
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                val play =
                    GoogleApiAvailability
                        .getInstance()
                        .isGooglePlayServicesAvailable(context)
                if (play != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                    return@withContext Result.failure(
                        TranslationPrepException(
                            kind = TranslationPrepFailureKind.GMS_UNAVAILABLE,
                            detail = "Google Play Services 不可用（状态码 $play），本地离线翻译依赖它下载模型",
                        ),
                    )
                }
                if (!mlKitEngine.supportsLanguage(sourceLanguage, targetLanguage)) {
                    return@withContext Result.failure(
                        TranslationPrepException(
                            kind = TranslationPrepFailureKind.UNSUPPORTED_LANGUAGE,
                            detail = "ML Kit 不支持语言对 $sourceLanguage → $targetLanguage",
                        ),
                    )
                }
                mlKitEngine.preparePair(sourceLanguage, targetLanguage, onPhase)
            }

        private companion object {
            const val TAG = "ModelPreparer"

            /** Vosk ASR 模型根目录(相对 filesDir),按语种分目录。 */
            const val VOSK_MODEL_DIR = "models/vosk"

            /** Sherpa-ONNX ASR 模型根目录(相对 filesDir),按模型 ID 分目录。 */
            const val SHERPA_ONNX_MODEL_DIR = "models/sherpa-onnx"
        }
    }

/**
 * 翻译模型准备阶段，供 UI 呈现非模态状态条。
 * - [IDLE]：无需处理（语言尚未确定等）
 * - [CHECKING]：正在查询本地是否已有模型
 * - [DOWNLOADING]：正在下载离线模型（首次约 30-100MB，需 GMS 且网络可达 Google）
 * - [READY]：模型就绪，可离线翻译
 */
enum class TranslationPrepPhase {
    IDLE,
    CHECKING,
    DOWNLOADING,
    READY,
}
