package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ztfang.eye.ui.theme.Dimens

/**
 * 单个引擎选项卡(三列之一)。
 *
 * 视觉:圆形图标盒 + 标题 + 副标题 + 选中态(蓝/橙/紫边框 + 对勾 + 图标盒主色填充)。
 */
@Composable
fun EngineCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    accent: EngineAccent = EngineAccent.Blue,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Dimens.CornerLg
) {
    val colors = accentColors(accent)

    val iconBg = if (selected) {
        Brush.linearGradient(listOf(colors.primary, colors.secondary))
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            )
        )
    }
    val iconTint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor = if (selected) colors.primary else MaterialTheme.colorScheme.onBackground
    val borderColor = if (selected) colors.primary.copy(alpha = 0.7f) else Color.Transparent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.EngineCardMinHeight)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                if (selected) Color.White.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.surface.copy(alpha = Dimens.GlassSurfaceAlpha)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(cornerRadius)
            )
            .clickable(onClick = onClick)
            .padding(Dimens.SpaceMd)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.EngineCardIconBox)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(Dimens.EngineCardIcon)
                )
            }
            Spacer(modifier = Modifier.height(Dimens.SpaceXxs))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.primary.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                minLines = 2
            )
        }
        // 选中右上角对勾
        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .align(Alignment.TopEnd),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

enum class EngineAccent { Blue, Orange, Purple }

private data class EngineColors(val primary: Color, val secondary: Color)

private fun accentColors(accent: EngineAccent): EngineColors = when (accent) {
    EngineAccent.Blue -> EngineColors(
        primary = Color(0xFF1A73E8),
        secondary = Color(0xFF4FA3FF)
    )
    EngineAccent.Orange -> EngineColors(
        primary = Color(0xFFFF8F00),
        secondary = Color(0xFFFFD180)
    )
    EngineAccent.Purple -> EngineColors(
        primary = Color(0xFF8B7FD8),
        secondary = Color(0xFFB197FC)
    )
}
