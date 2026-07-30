package io.github.ztfang.eye.domain.model

/** Sherpa-ONNX 流式 ASR 模型枚举；文件结构：encoder/decoder/joiner.onnx + tokens.txt */
enum class SherpaOnnxModel(
    val modelId: String,
    val displayName: String,
    val languageCode: String,
    val encoderFile: String,
    val decoderFile: String,
    val joinerFile: String,
    val tokensFile: String,
    val downloadUrl: String,      // tar.bz2 直链；为空走 files 多文件下载
    val sizeBytes: Long,
    val modelType: String,
    val files: List<ModelFileSpec>? = null  // 非空时优先于 downloadUrl
) {
    /**
     * X-ASR-zh-en 960ms 流式模型 int8 版（统一模型，精确/AI 模式均用）。
     * Zipformer2，自带标点+大小写，中英混说优化；int8 约 161MB（fp32 版 586MB）。
     * 来源：https://github.com/Gilgamesh-J/X-ASR, License: Apache-2.0
     */
    X_ASR_ZH_EN_960MS(
        modelId = "sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
        displayName = "中英文",
        languageCode = "zh-en",
        encoderFile = "encoder.int8.onnx",
        decoderFile = "decoder.onnx",
        joinerFile = "joiner.int8.onnx",
        tokensFile = "tokens.txt",
        // 无 tar.bz2 直链，走 files 多文件下载
        downloadUrl = "",
        sizeBytes = 161L * 1024L * 1024L,
        modelType = "zipformer2",
        files = listOf(
            // ModelScope CDN 直链（国内速度快）；大小为仓库实际值，用于进度计算
            ModelFileSpec(
                relativePath = "encoder.int8.onnx",
                url = "https://www.modelscope.cn/api/v1/models/bujidc/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/repo?Revision=master&FilePath=encoder.int8.onnx",
                sizeBytes = 155276576L   // ~148 MB (int8 量化)
            ),
            ModelFileSpec(
                relativePath = "decoder.onnx",
                url = "https://www.modelscope.cn/api/v1/models/bujidc/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/repo?Revision=master&FilePath=decoder.onnx",
                sizeBytes = 11309084L    // ~10.8 MB
            ),
            ModelFileSpec(
                relativePath = "joiner.int8.onnx",
                url = "https://www.modelscope.cn/api/v1/models/bujidc/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/repo?Revision=master&FilePath=joiner.int8.onnx",
                sizeBytes = 2581422L     // ~2.5 MB (int8 量化)
            ),
            ModelFileSpec(
                relativePath = "tokens.txt",
                url = "https://www.modelscope.cn/api/v1/models/bujidc/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/repo?Revision=master&FilePath=tokens.txt",
                sizeBytes = 58806L       // ~57 KB
            )
        )
    ),

    /**
     * Zipformer 孟加拉语流式模型（Vosk 导出版，sherpa-onnx 官方转 transducer 三件套）。
     * fp32 约 90MB；架构同 X-ASR（zipformer2），复用 SherpaOnnxAsrEngine。
     * 精度：FLEURS WER 20.6%，Respin 2025 WER 16.6%。
     * 全模式共用；未下载时静默回退 Vosk。
     * 上游：https://huggingface.co/alphacep/vosk-model-small-streaming-bn, License: Apache 2.0
     */
    BN_VOSK_2026_02_09(
        modelId = "sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09",
        displayName = "বাংলা",
        languageCode = "bn",
        encoderFile = "encoder.onnx",      // fp32 无 .int8. 后缀
        decoderFile = "decoder.onnx",
        joinerFile = "joiner.onnx",
        tokensFile = "tokens.txt",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09.tar.bz2",
        sizeBytes = 87_289_525L,   // ~83.3 MB (GitHub Release 实际大小, 2026-07-18 校验)
        modelType = "zipformer2",
        files = null   // 走 tar.bz2 下载+解压流程
    ),

    /**
     * Nemotron 3.5 ASR Streaming 0.6B 多语种 int8 版（320ms chunk），约 685MB。
     * FastConformer-Hybrid RNNT，40 locale，支持 per-stream 语种切换（setOption("language", ...)）。
     * 精度（FLEURS 320ms）：ready 19 locale 平均 WER 9.49%；broad 13 locale 平均 WER 24.11%。
     * 非中英文语种走此模型；中文仍走 X-ASR（CER ~9.59% 优于 Nemotron 20.03%）。
     * 来源：https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b, License: OpenMDW-1.1
     */
    NEMOTRON_3_5_320MS_INT8(
        modelId = "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11",
        displayName = "多语种(26)",
        languageCode = "auto",  // per-stream 动态切换
        encoderFile = "encoder.int8.onnx",
        decoderFile = "decoder.int8.onnx",
        joinerFile = "joiner.int8.onnx",
        tokensFile = "tokens.txt",
        downloadUrl = "",  // 走 files 多文件下载
        sizeBytes = 685L * 1024L * 1024L,
        // modelType 留空让 sherpa-onnx 自动检测；非法值(如 "nemo")会导致模型加载两次
        // 见 sherpa-onnx/csrc/online-model-config.cc
        modelType = "",
        files = listOf(
            // HuggingFace csukuangfj2 仓库直链（sherpa-onnx 作者导出）
            ModelFileSpec(
                relativePath = "encoder.int8.onnx",
                url = "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11/resolve/main/encoder.int8.onnx",
                sizeBytes = 658L * 1024L * 1024L   // ~658 MB
            ),
            ModelFileSpec(
                relativePath = "decoder.int8.onnx",
                url = "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11/resolve/main/decoder.int8.onnx",
                sizeBytes = 15L * 1024L * 1024L    // ~15 MB
            ),
            ModelFileSpec(
                relativePath = "joiner.int8.onnx",
                url = "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11/resolve/main/joiner.int8.onnx",
                sizeBytes = 9_961_472L    // ~9.5 MB (HuggingFace 实际大小)
            ),
            ModelFileSpec(
                relativePath = "tokens.txt",
                url = "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11/resolve/main/tokens.txt",
                sizeBytes = 131L * 1024L           // ~131 KB
            )
        )
    );

    companion object {
        /** 按模型 ID 查找 */
        fun fromModelId(modelId: String): SherpaOnnxModel? =
            entries.find { it.modelId == modelId }

        /** 获取所有支持的模型 */
        fun getAll(): List<SherpaOnnxModel> = entries.toList()

        /** 默认中文模型（X-ASR，支持中英混说） */
        val DEFAULT_ZH = X_ASR_ZH_EN_960MS

        /** 默认英文模型（X-ASR，支持中英混说） */
        val DEFAULT_EN = X_ASR_ZH_EN_960MS

        /** 默认孟加拉语模型 */
        val DEFAULT_BN = BN_VOSK_2026_02_09

        /** 默认多语种模型（Nemotron 3.5） */
        val DEFAULT_MULTILINGUAL = NEMOTRON_3_5_320MS_INT8

        /** Nemotron 支持语种；zh/en 实际走 X-ASR，故 Nemotron 实际服务 26 种 */
        val NEMOTRON_LANGUAGES: Set<String> by lazy {
            NEMOTRON_READY_LANGUAGES + NEMOTRON_BROAD_LANGUAGES
        }

        /**
         * Nemotron Transcription-ready（19 locale / 15 种语言，WER < 15%）。
         * 部分语言含多 locale（en/es/fr/pt 各 2 个）；本项目按 ISO 639-1 语言代码分流，不区分 locale。
         */
        val NEMOTRON_READY_LANGUAGES: Set<String> = setOf(
            "en", "es", "fr", "it", "pt", "de", "nl", "tr",
            "ru", "ar", "hi", "ja", "ko", "vi", "uk"
        )

        /** Nemotron Broad-coverage（13 locale，WER 17-29%）；zh 在此层但锁定走 X-ASR */
        val NEMOTRON_BROAD_LANGUAGES: Set<String> = setOf(
            "pl", "sv", "cs", "nb", "da", "bg", "fi", "hr", "sk",
            "zh", "hu", "ro", "et"
        )

        /** Nemotron Adaptation-ready：tokenizer 可识别但未训练 ASR，回退 Vosk */
        val NEMOTRON_UNSUPPORTED: Set<String> = setOf(
            "el", "lt", "lv", "mt", "sl", "he", "th", "nn"
        )
    }
}

/** Sherpa-ONNX 多文件下载规格 */
data class SherpaOnnxFileSpec(
    val relativePath: String,
    val url: String,
    val sizeBytes: Long
)