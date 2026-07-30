package io.github.ztfang.eye.domain.model

/** 可下载模型目录：文件规格、URL、大小（估算值，仅用于进度条） */
object ModelCatalog {

    /**
     * Vosk small 模型，由 [VoskLanguage] 枚举生成；每语种一个 zip，解压到 models/vosk/<lang>/
     * Source: alphacephei.com/vosk/models, License: Apache 2.0
     */
    val VOSK_MODELS: Map<String, ModelFileSpec> = VoskLanguage.getAll().associate { lang ->
        lang.code to ModelFileSpec(
            relativePath = "vosk/${lang.modelName}.zip",
            url = lang.modelUrl,
            sizeBytes = lang.sizeBytes
        )
    }

    /**
     * Sherpa-ONNX 流式 Zipformer 模型，由 [SherpaOnnxModel] 枚举生成；每模型一个 tar.bz2，解压到 models/sherpa-onnx/<modelId>/
     * Source: github.com/k2-fsa/sherpa-onnx/releases, License: Apache 2.0
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

    /** 多文件直链规格；null 表示走 tar.bz2 下载流程 */
    fun sherpaOnnxFileSpecs(modelId: String): List<ModelFileSpec>? =
        SherpaOnnxModel.fromModelId(modelId)?.files
}
