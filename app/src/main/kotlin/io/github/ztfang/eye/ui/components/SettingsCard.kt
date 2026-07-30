package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
 * 设置分组卡片：容纳一组相关 SettingsRow 的浮动玻璃容器。
 *
 * 设计要点：
 *  - 圆角 20dp(比 Hero 卡略紧,营造层级)
 *  - 内部 4dp 内边距,行自带 16dp 水平内边距,避免双重 padding
 *  - 顶部白色高光描边 + 科技蓝柔和阴影
 *  - 行间不加分割线,依靠行间空气感划分
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Dimens.SettingsCardCorner,
    contentPadding: Dp = Dimens.SettingsCardPadding,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = Dimens.GlassShadowElevation,
                shape = shape,
                ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f),
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(
                BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = Dimens.GlassHighlightAlpha),
                            Color.White.copy(alpha = 0.15f),
                        )
                    )
                ),
                shape = shape
            )
            .padding(contentPadding)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}
