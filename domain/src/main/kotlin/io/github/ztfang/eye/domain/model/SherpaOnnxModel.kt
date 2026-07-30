/**
 * Sherpa-ONNX 流式 ASR 模型配置。
 *
 * 支持中英文流式模型，ModelScope 托管，国内网络友好。
 * 模型文件结构：encoder.int8.onnx, decoder.onnx, joiner.int8.onnx, tokens.txt
 *
 * 来源：https://www.modelscope.cn/models/ZhaoChaoqun/sherpa-onnx-asr-models
 * License: Apache 2.0
 */
package io.github.ztfang.eye.domain.model

/**
 * Sherpa-ONNX 流式 ASR 模型枚举。
 * 支持中英文模型，通过 ModelScope 国内镜像下载。
 */
enum class SherpaOnnxModel(
    val modelId: String,          // 模型唯一标识
    val displayName: String,      // UI 显示名称
    val languageCode: String,     // 语言代码（zh/en/zh-en）
    val encoderFile: String,      // encoder ONNX 文件名
    val decoderFile: String,      // decoder ONNX 文件名
    val joinerFile: String,       // joiner ONNX 文件名
    val tokensFile: String,       // tokens.txt 文件名
    val downloadUrl: String,      // 下载 URL（tar.bz2，ModelScope）；为空表示走 files 多文件下载
    val sizeBytes: Long,          // 模型大小（字节）
    val modelType: String,        // 模型类型（zipformer2）
    val files: List<ModelFileSpec>? = null  // 多文件直链下载规格（非空时优先于 downloadUrl）
) {
    /**
     * X-ASR-zh-en 960ms 流式模型 int8 量化版本（统一模型）。
     * 100 万小时训练，Zipformer2 架构，自带标点+大小写，中英混说优化。
     * 精确模式（HIGH_QUALITY）和 AI 模式（中英文/中文/英文）均调用此模型。
     *
     * int8 量化版总大小约 161MB（encoder 148MB + decoder 10.8MB + joiner 2.5MB + tokens 57KB），
     * 相比 fp32 版本（586MB）体积缩小 3.6 倍，适合移动端部署。
     *
     * 文件来源：ModelScope bujidc/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05
     * 走 files 多文件下载流程，从 ModelScope 国内 CDN 直链下载。
     *
     * 来源：https://github.com/Gilgamesh-J/X-ASR
     * License: Apache-2.0
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
        sizeBytes = 161L * 1024L * 1024L,  // 实际总大小约 161MB
        modelType = "zipformer2",
        files = listOf(
            // ModelScope 文件直链，LFS 文件由 ModelScope CDN 分发（国内速度快）
            // 文件大小按仓库实际值填入，用于进度计算
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
     * Zipformer 孟加拉语流式模型（Vosk 导出版，2026-02-09）。
     *
     * Vosk small streaming bn 模型经 sherpa-onnx 官方转换为标准 transducer 三件套格式。
     * fp32 量化，体积仅 90MB，适合移动端部署。
     * 架构与 X-ASR 同构（zipformer2 transducer），可零侵入复用 SherpaOnnxAsrEngine。
     *
     * 精度：FLEURS WER 20.6%，Respin 2025 WER 16.6%（孟加拉语低资源语言可用水平）
     *
     * 设计：全模式共用（FAST/HIGH_QUALITY/AI 均走此模型，不回退 Vosk 小模型）。
     * 用户未下载时静默回退 Vosk，EngineCard 提示下载。
     *
     * 文件来源：GitHub Release asr-models（tar.bz2 单文件打包）
     * https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09.tar.bz2
     * 上游 Vosk 模型：https://huggingface.co/alphacep/vosk-model-small-streaming-bn
     * License: Apache 2.0
     */
    BN_VOSK_2026_02_09(
        modelId = "sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09",
        displayName = "বাংলা",
        languageCode = "bn",
        encoderFile = "encoder.onnx",      // fp32 无 .int8. 后缀
        decoderFile = "decoder.onnx",
        joinerFile = "joiner.onnx",
        tokensFile = "tokens.txt",
        // tar.bz2 单文件下载（GitHub Release 自动重定向到 objects.githubusercontent.com）
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09.tar.bz2",
        sizeBytes = 87_289_525L,   // ~83.3 MB (GitHub Release 实际大小, 2026-07-18 校验)
        modelType = "zipformer2",
        files = null   // 走 tar.bz2 单文件下载+解压流程
    ),

    /**
     * Nemotron 3.5 ASR Streaming 0.6B 多语种流式模型 int8 量化版（320ms chunk）。
     *
     * NVIDIA FastConformer-Hybrid RNNT 架构，40 language-locales。
     * 支持 per-stream language 动态切换（setOption("language", "ja")）。
     * int8 量化版总大小约 685MB（encoder 658MB + decoder 15MB + joiner 10MB + tokens 131KB）。
     *
     * 精度（FLEURS 320ms 锁定语种）：
     * - Transcription-ready 19 locale 平均 WER 9.49%（es 4.39 / it 4.83 / pt 5.81 / ko 7.27 / en 8.27 / de 8.83 / fr 9.79 / ru 9.87 / ja 12.22 / ar 12.55）
     * - Broad-coverage 13 locale 平均 WER 24.11%（zh-CN 20.03，但中文实际走 X-ASR 精度更高）
     *
     * 精确模式（HIGH_QUALITY）和 AI 模式（若用户已下载）的非中英文语种调用此模型。
     * 中文（zh）仍走 X-ASR（code-switching 专项优化，CER ~9.59% 优于 Nemotron 20.03%）。
     *
     * 文件来源：HuggingFace csukuangfj2 仓库（sherpa-onnx 作者亲自导出）
     * https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11
     * 原始模型：https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b
     * License: OpenMDW-1.1
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
        // modelType 留空：sherpa-onnx 官方 Nemotron-en-0.6b 系列示例均不设 modelType，
        // 让 sherpa-onnx 自动检测。设非法值(如 "nemo")会导致模型被加载两次。
        // 参考: https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/online-model-config.cc
        // "Valid values are: conformer, lstm, zipformer, zipformer2, wenet_ctc, nemo_ctc.
        //  All other values lead to loading the model twice."
        modelType = "",
        files = listOf(
            // HuggingFace csukuangfj2 仓库直链，走多文件下载流程
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

        /** 默认中文模型（统一指向 X-ASR，支持中英混说） */
        val DEFAULT_ZH = X_ASR_ZH_EN_960MS

        /** 默认英文模型（统一指向 X-ASR，支持中英混说） */
        val DEFAULT_EN = X_ASR_ZH_EN_960MS

        /** 默认孟加拉语模型 */
        val DEFAULT_BN = BN_VOSK_2026_02_09

        /** 默认多语种模型（Nemotron 3.5） */
        val DEFAULT_MULTILINGUAL = NEMOTRON_3_5_320MS_INT8

        /**
         * Nemotron 3.5 支持的语种（ready 15 + broad 13 = 28 种语言代码）。
         * 其中 zh/en 实际走 X-ASR（精度更高），Nemotron 实际服务 26 种语言。
         */
        val NEMOTRON_LANGUAGES: Set<String> by lazy {
            NEMOTRON_READY_LANGUAGES + NEMOTRON_BROAD_LANGUAGES
        }

        /**
         * Nemotron Transcription-ready (19 locale / 15 种语言)
         * WER < 15%(日韩语用 CER), 推荐使用。
         *
         * 注意: 官方说 19 locale 不是 19 种语言, 部分语言含多个 locale:
         * - English: en-US + en-GB (2 locale)
         * - Spanish: es-US + es-ES (2 locale)
         * - French:  fr-FR + fr-CA (2 locale)
         * - Portuguese: pt-BR + pt-PT (2 locale)
         * 其余语言各 1 个 locale, 总计 15 种语言 = 19 locale。
         * 本项目按语言代码(ISO 639-1) 分流, 不区分 locale 变体。
         */
        val NEMOTRON_READY_LANGUAGES: Set<String> = setOf(
            "en", "es", "fr", "it", "pt", "de", "nl", "tr",
            "ru", "ar", "hi", "ja", "ko", "vi", "uk"
        )

        /**
         * Nemotron Broad-coverage (13 locale / 13 种语言)
         * WER 17-29%, 可用但精度有限。
         * 中文 zh 在此层但项目锁定走 X-ASR (CER 9.59% 优于 Nemotron 20.03%)。
         */
        val NEMOTRON_BROAD_LANGUAGES: Set<String> = setOf(
            "pl", "sv", "cs", "nb", "da", "bg", "fi", "hr", "sk",
            "zh", "hu", "ro", "et"
        )

        /**
         * Nemotron Adaptation-ready (8 locale / 8 种语言)
         * tokenizer 可识别但未训练 ASR, 需微调才能用, 项目回退 Vosk。
         */
        val NEMOTRON_UNSUPPORTED: Set<String> = setOf(
            "el", "lt", "lv", "mt", "sl", "he", "th", "nn"
        )
    }
}

/**
 * Sherpa-ONNX 模型文件规格。
 * 用于下载管理，包含多个 ONNX 文件。
 */
data class SherpaOnnxFileSpec(
    val relativePath: String,     // 文件相对路径
    val url: String,              // 下载 URL
    val sizeBytes: Long           // 文件大小
)