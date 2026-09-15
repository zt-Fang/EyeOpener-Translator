package io.github.ztfang.eye.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 品牌色：低版本 / 关闭动态色时使用
private val EyeBlue80 = Color(0xFFA8C7FA)
private val EyeBlue40 = Color(0xFF1A73E8)
private val EyeTeal40 = Color(0xFF00897B)
private val EyeAmber40 = Color(0xFFFF8F00)
private val NeutralDark = Color(0xFF1C1B1F)
private val NeutralLight = Color(0xFFFFFBFE)
private val NeutralVariantLight = Color(0xFFE7E0EC)

// ---------- M3 ColorScheme ----------
private val LightColors =
    lightColorScheme(
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
        onSurfaceVariant = NeutralDark,
    )

/**
 * App 全局主题入口（Material 3）—— **仅浅色主题**。
 *
 * 设计目标：
 *  1. 与 res/values/themes.xml 的 Theme.Material3.Light.NoActionBar 保持一致的语义（都不跟随系统深色）
 *  2. 优先使用 Android 12+ 的 Dynamic Color（Material You），低版本回退到内置品牌色
 *  3. 所有色彩走 MaterialTheme.colorScheme，禁止在 Composable 中写死十六进制色值
 *
 * 为什么不再支持深色（2026-09-14 定）：
 *  - 产品视觉基调是「极简科技风浅色」，但全项目大量组件（GradientBackground / ChatBubble /
 *    GlassCard / AssistantTopBar 等，58 处 Color.White）硬编码浅色底与白色，与深色
 *    colorScheme 组合会产生「浅色文字压浅色底」的不可读界面。真机复现：系统深色下冷启动，
 *    `EyeOpener` 标题、`实时翻译·看见世界` 副标题、`翻译引擎` 章节名、`温馨提示` 几乎不可见。
 *  - 应用内从未提供任何深色开关：设置页 5 个分组（字体颜色/背景透明度/字体大小/结果显示/
 *    音频输入源）均与主题无关，也无对应的 DataStore 键。深色此前**仅由系统深色模式被动触发**。
 *  - 与其维护一套必然显示异常的深色皮肤，不如确定性地钉死浅色。
 *
 * 若要重新支持深色，前置条件是：先把上述硬编码色全部改走 colorScheme，再恢复此处的深色分支
 * 与 values-night 资源，并在真机上验证深色下的逐页可读性。
 *
 * @param dynamicColor 是否启用 Material You 动态取色（默认 true，仅 Android 12+ 生效）
 */
@Composable
fun EyeTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicLightColorScheme(LocalContext.current)
        } else {
            LightColors
        }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
