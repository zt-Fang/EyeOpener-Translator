package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 极简科技风渐变背景。
 *
 * 浅白 + 浅蓝渐变(科技蓝 + 浅白灰色,符合 AGENTS.md 风格定义)。
 * 三个色标形成柔和的纵向渐变,作为玻璃拟态卡片底色使用。
 */
@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color(0xFFE8F1FF),  // 顶部:浅蓝
            0.5f to Color(0xFFF4F8FF),  // 中段:过渡
            1.0f to Color(0xFFFAFCFF),  // 底部:近白
        )
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradient)
    ) {
        content()
    }
}
