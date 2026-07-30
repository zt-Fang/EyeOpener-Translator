package io.github.ztfang.eye.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * 多语言切换工具。
 *
 * 支持语言：
 * - "zh" : 简体中文
 * - "en" : English
 *
 * 使用方式：
 * 1. Application.attachBaseContext() 中调用 wrap()，确保全局生效
 * 2. Activity 中切换语言后调用 updateAppLocale() + recreate()
 */
object LocaleHelper {

    private const val DEFAULT_LANGUAGE = "zh"

    /** 根据语言代码构建 Locale */
    fun getLocale(languageCode: String): Locale = when (languageCode) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        else -> Locale.SIMPLIFIED_CHINESE
    }

    /**
     * 包装 Context，使其使用指定语言资源。
     * 在 Application.attachBaseContext() 中调用。
     */
    fun wrap(context: Context, languageCode: String): Context {
        val locale = getLocale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * 使用 AndroidX AppCompatDelegate 设置全局语言。
     * 调用后需 recreate() Activity 才能立即生效。
     */
    fun updateAppLocale(languageCode: String) {
        val locale = getLocale(languageCode)
        AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.create(locale))
    }

    /** 语言代码 → 显示名称 */
    fun getDisplayName(languageCode: String): String = when (languageCode) {
        "zh" -> "简体中文"
        "en" -> "English"
        else -> "简体中文"
    }
}
