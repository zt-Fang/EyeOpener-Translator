package io.github.ztfang.eye.domain.model

/**
 * 模型目录。
 * 定义所有可下载模型的文件规格、下载 URL 和大小信息。
 * 仓库层会验证这些 URL 是否在白名单中。
 * 文件大小为粗略估算值，仅用于进度条显示。
 */
object ModelCatalog {

    /**
     * Vosk 多语种 small 模型。
     * 从 [VoskLanguage] 枚举动态生成，避免重复维护两份列表。
     * 每个语种对应一个 zip 文件，下载后解压到 models/vosk/<lang>/ 目录。
     *
     * Source: alphacephei.com/vosk/models
     * License: Apache 2.0
     */
    val VOSK_MODELS: Map<String, ModelFileSpec> = VoskLanguage.getAll().associate { lang ->
        lang.code to ModelFileSpec(
            relativePath = "vosk/${lang.modelName}.zip",
            url = lang.modelUrl,
            sizeBytes = lang.sizeBytes
        )
    }

    /**
     * Sherpa-ONNX 流式 Zipformer 模型。
     * 从 [SherpaOnnxModel] 枚举动态生成。
     * 每个模型对应一个 tar.bz2 文件，下载后解压到 models/sherpa-onnx/<modelId>/ 目录。
     *
     * Source: github.com/k2-fsa/sherpa-onnx/releases
     * License: Apache 2.0
     */
    val SHERPA_ONNX_MODELS: Map<String, ModelFileSpec> = SherpaOnnxModel.getAll().associate { model ->
        model.modelId to ModelFileSpec(
            relativePath = "sherpa-onnx/${model.modelId}.tar.bz2",
            url = model.downloadUrl,
            sizeBytes = model.sizeBytes
        )
    }

    /** Quick lookup of aggregate size for a model name. */
    fun totalSizeBytes(name: String): Long = when {
        name.startsWith("VOSK_ASR_") -> {
            val lang = name.substringAfter("VOSK_ASR_").lowercase()
            VOSK_MODELS[lang]?.sizeBytes ?: 0L
        }
        name.startsWith("SHERPA_ONNX_ASR_") -> {
            val modelId = name.substringAfter("SHERPA_ONNX_ASR_")
            SHERPA_ONNX_MODELS[modelId]?.sizeBytes ?: 0L
        }
        else -> 0L
    }

    /** Bundle list for a given stable model name. */
    fun filesFor(name: String): List<ModelFileSpec> = when {
        name.startsWith("VOSK_ASR_") -> {
            val lang = name.substringAfter("VOSK_ASR_").lowercase()
            VOSK_MODELS[lang]?.let { listOf(it) } ?: emptyList()
        }
        name.startsWith("SHERPA_ONNX_ASR_") -> {
            val modelId = name.substringAfter("SHERPA_ONNX_ASR_")
            SHERPA_ONNX_MODELS[modelId]?.let { listOf(it) } ?: emptyList()
        }
        else -> emptyList()
    }

    /**
     * Stable model name identifiers used as keys in the repository.
     * Keep these aligned with [io.github.ztfang.eye.engine.ModelPreparer].
     */
    const val MODEL_MLKIT = "MLKIT_TRANSLATION"
    const val MODEL_VAD = "WEBRTC_VAD"

    /** Vosk ASR 模型名生成器 */
    fun voskModelName(languageCode: String): String = "VOSK_ASR_${languageCode.uppercase()}"

    /** Sherpa-ONNX ASR 模型名生成器 */
    fun sherpaOnnxModelName(modelId: String): String = "SHERPA_ONNX_ASR_$modelId"

    /** 获取指定语种的 Vosk zip 模型规格 */
    fun voskZipSpec(languageCode: String): ModelFileSpec? =
        VOSK_MODELS[languageCode.lowercase()]

    /** 获取指定模型 ID 的 Sherpa-ONNX tar.bz2 模型规格 */
    fun sherpaOnnxTarSpec(modelId: String): ModelFileSpec? =
        SHERPA_ONNX_MODELS[modelId]

    /**
     * 获取指定模型 ID 的 Sherpa-ONNX 多文件直链规格。
     * 仅当模型声明了 [SherpaOnnxModel.files] 时返回（如 X-ASR 无 tar.bz2 直链）。
     * 返回 null 表示该模型应走 tar.bz2 下载流程。
     */
    fun sherpaOnnxFileSpecs(modelId: String): List<ModelFileSpec>? =
        SherpaOnnxModel.fromModelId(modelId)?.files
}
