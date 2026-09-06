package io.github.ztfang.eye.engine.asr

import io.github.ztfang.eye.domain.model.VoskLanguage

object VoskLanguageMap {
    private val languageMap = VoskLanguage.getAll().associateBy { it.code }

    fun getByCode(code: String): VoskLanguage? = languageMap[code.lowercase()]

    fun getAllSupported(): List<VoskLanguage> = VoskLanguage.getAll()

    fun isMlKitSupported(code: String): Boolean = getByCode(code)?.mlkitSupported ?: false

    fun getModelName(code: String): String? = getByCode(code)?.modelName

    fun getModelUrl(code: String): String? = getByCode(code)?.modelUrl

    fun getSizeBytes(code: String): Long = getByCode(code)?.sizeBytes ?: 0L

    fun getDisplayName(code: String): String = getByCode(code)?.displayName ?: code
}
