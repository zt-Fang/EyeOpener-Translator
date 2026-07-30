package io.github.ztfang.eye.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Eye 主题（Material 3）。
 *
 * 设计目标：
 *  1. 与 res/values/themes.xml 的 Theme.Material3.DayNight.NoActionBar 保持一致的日夜模式语义
 *  2. 优先使用 Android 12+ 的 Dynamic Color（Material You），低版本回退到内置品牌色
 *  3. 所有色彩走 MaterialTheme.colorScheme，禁止在 Composable 中写死十六进制色值
 *  4. 遵循 AGENTS.md 规范：UI 仅负责展示，主题切换不涉及业务逻辑
 *
 * 深色模式优先级：
 *   [darkTheme] 参数（非 null）> 系统设置（isSystemInDarkTheme）
 *   调用方可将 DataStore 的 isDarkMode 转为非 null 传入以覆盖系统。
 */

// ---------- 品牌色（低版本 / 关闭动态色时使用） ----------
private val EyeBlue80 = Color(0xFFA8C7FA)
private val EyeBlue40 = Color(0xFF1A73E8)
private val EyeTeal80 = Color(0xFF7FDCC4)
private val EyeTeal40 = Color(0xFF00897B)
private val EyeAmber80 = Color(0xFFFFD180)
private val EyeAmber40 = Color(0xFFFF8F00)
private val NeutralDark = Color(0xFF1C1B1F)
private val NeutralLight = Color(0xFFFFFBFE)
private val NeutralVariantDark = Color(0xFF49454F)
private val NeutralVariantLight = Color(0xFFE7E0EC)

// ---------- M3 ColorScheme ----------
private val DarkColors = darkColorScheme(
    primary = EyeBlue80,
    onPrimary = NeutralDark,
    primaryContainer = EyeBlue40,
    onPrimaryContainer = NeutralLight,
    secondary = EyeTeal80,
    onSecondary = NeutralDark,
    tertiary = EyeAmber80,
    onTertiary = NeutralDark,
    background = NeutralDark,
    onBackground = NeutralLight,
    surface = NeutralDark,
    onSurface = NeutralLight,
    surfaceVariant = NeutralVariantDark,
    onSurfaceVariant = NeutralLight
)

private val LightColors = lightColorScheme(
    primary = EyeBlue40,
    onPrimary = NeutralLight,
    primaryContainer = EyeBlue80,
    onPrimaryContainer = NeutralDark,
    secondary = EyeTeal40,
    onSecondary = NeutralLight,
    tertiary = EyeAmber40,
    onTertiary = NeutralLight,
    background = NeutralLight,
    onBackground = NeutralDark,
    surface = NeutralLight,
    onSurface = NeutralDark,
    surfaceVariant = NeutralVariantLight,
    onSurfaceVariant = NeutralDark
)

/**
 * App 全局主题入口。
 *
 * @param darkTheme 强制深色模式；为 null 时跟随系统（isSystemInDarkTheme）
 * @param dynamicColor 是否启用 Material You 动态取色（默认 true，仅 Android 12+ 生效）
 */
@Composable
fun EyeTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val resolvedDark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (resolvedDark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        resolvedDark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
