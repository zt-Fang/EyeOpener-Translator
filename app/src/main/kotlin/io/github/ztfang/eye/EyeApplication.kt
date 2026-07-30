package io.github.ztfang.eye

import android.content.Context
import android.app.Application
import io.github.ztfang.eye.util.LocaleHelper
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口类。
 * 启用 Hilt 依赖注入框架，所有 @Inject 和 @Provides 在此初始化。
 */
@HiltAndroidApp
class EyeApplication : Application() {

    override fun attachBaseContext(base: Context) {
        // 读取保存的语言偏好，默认中文
        val prefs = base.getSharedPreferences("eye_opener_settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("interface_language", "zh") ?: "zh"
        super.attachBaseContext(LocaleHelper.wrap(base, lang))
    }
}
