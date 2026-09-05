package io.github.ztfang.eye.domain.model

/**
 * ASR 路由单一事实源（Single Source of Truth）。
 *
 * 取代原本散落在 SubtitleManager / LocalModelsScreen / ModelPreparer 的多处
 * “语言 → 引擎 → 模型”硬编码 when 分支，让“中文永远走 X-ASR（不回退 Nemotron）”
 * 这类关键不变量只有唯一一个定义点，避免改一处漏一处导致中文莫名跑多语种模型。
 *
 * 路由规则（优先级从高到低）：
 *  1. zh / en        → SHERPA_ONNX          (X-ASR 中英模型，~161MB，CER 9.59%)
 *  2. bn             → SHERPA_ONNX_BN       (孟加拉语专用模型)
 *  3. Nemotron 26 语 → SHERPA_ONNX_NEMOTRON (多语种大包 ~685MB，排除 zh/en/bn)
 *  4. 其余语言        → VOSK                (按语言单独小包兜底)
 *
 * 不变量（建议补单测守门，详见 .workbuddy/artifacts/asr-routing-design.md）：
 *  - 每种语言只映射到一个 engine
 *  - zh / en 必走 X-ASR，绝不回退 Nemotron
 *  - 所有 SettingsRepository 已声明的 sourceLanguage 都有路由
 */
data class AsrRoute(
    val languageCode: String,
    val engine: AsrEngineType,
    /** Sherpa-ONNX 模型 ID；Vosk 兜底为 null */
    val modelId: String?
)

object AsrRoutingTable {

    /** X-ASR 覆盖语言（zh/en 共享同一模型） */
    private val X_ASR_LANGS = setOf("zh", "en")

    /** BN 专用语言 */
    private const val BN_LANG = "bn"

    /** 显式路由表（除 Vosk 兜底外的所有语言） */
    private val explicitRoutes: Map<String, AsrRoute> by lazy {
        buildMap {
            for (lang in X_ASR_LANGS) {
                put(
                    lang,
                    AsrRoute(lang, AsrEngineType.SHERPA_ONNX,
                        SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId)
                )
            }
            put(
                BN_LANG,
                AsrRoute(BN_LANG, AsrEngineType.SHERPA_ONNX_BN,
                    SherpaOnnxModel.BN_VOSK_2026_02_09.modelId)
            )
            for (lang in SherpaOnnxModel.NEMOTRON_LANGUAGES
                .filter { it !in X_ASR_LANGS && it != BN_LANG }) {
                put(
                    lang,
                    AsrRoute(lang, AsrEngineType.SHERPA_ONNX_NEMOTRON,
                        SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelId)
                )
            }
        }
    }

    /** 某语言的完整路由；未显式声明的语言一律回退 Vosk */
    fun forLanguage(code: String): AsrRoute =
        explicitRoutes[code] ?: AsrRoute(code, AsrEngineType.VOSK, null)

    /** 语言 → 引擎 */
    fun engineFor(code: String): AsrEngineType = forLanguage(code).engine

    /** 语言 → Sherpa 模型 ID（Vosk 兜底返回 null） */
    fun modelIdFor(code: String): String? = forLanguage(code).modelId

    /** 被 Sherpa-ONNX 覆盖的语言集合（Vosk 下载列表不再显示这些） */
    val sherpaCoveredLanguages: Set<String> get() = explicitRoutes.keys
}
