package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ztfang.eye.ui.theme.Dimens

/**
 * 玻璃拟态卡片容器。
 *
 * 设计目标(AGENTS.md 风格：Minimal Tech + Glassmorphism Lite)：
 *  - 半透明白色面 + 顶部高光线性描边,模拟玻璃边缘光
 *  - 柔和科技蓝阴影,避免厚重 Material 默认阴影
 *  - 圆角默认 24dp,可在参数中调整
 *  - 内容由调用方提供,本组件不耦合任何业务字段
 *
 * 为什么不直接用 RenderEffect 真实模糊:
 *  - minSdk = 24,RenderEffect 仅 API 31+ 可用
 *  - 真实模糊对 GPU 压力大,逐卡 blur 影响滚动性能
 *  - 浅色渐变背景上,半透明面 + 描边已经能呈现"frosted"质感
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Dimens.CornerXl,
    contentPadding: Dp = Dimens.CardPadding,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = Dimens.GlassSurfaceAlpha),
    elevation: Dp = Dimens.GlassShadowElevation,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    // 顶部高光 → 底部隐去的线性渐变描边,营造玻璃边缘光
    val borderBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = Dimens.GlassHighlightAlpha),
            Color.White.copy(alpha = 0.15f),
        )
    )
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f),
            )
            .clip(shape)
            .background(containerColor)
            .border(
                border = BorderStroke(width = 1.dp, brush = borderBrush),
                shape = shape
            )
            .padding(contentPadding)
    ) {
        content()
    }
}
