package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import io.github.ztfang.eye.ui.theme.Dimens

/**
 * 设置项行：左侧渐变图标盒 + 标签 + (右侧值) + Chevron。
 *
 * 视觉规范（Apple HIG + M3 混合）：
 *  - 行高 60dp,符合 Material 48dp 触控目标 + 一档缓冲
 *  - 图标盒 40dp 圆角矩形(rounded 12dp)渐变,7 种 pastel 主题色
 *  - 标签 16sp Medium
 *  - 右侧值 14sp,次级色
 *
 * 带 Switch 的行请用 [SettingsToggleRow]。
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    accent: AccentTone = AccentTone.Blue,
    value: String? = null,
    valueColor: Color? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.SettingsRowHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SettingsRowPaddingV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccentIconBox(icon = icon, tone = accent)
        Spacer(modifier = Modifier.size(Dimens.SettingsRowInternalGap))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(Dimens.SettingsValueTextGap))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(Dimens.SettingsChevronSize)
        )
    }
}

/**
 * 带 Switch 的设置行。
 */
@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accent: AccentTone = AccentTone.Blue,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.SettingsRowHeight)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SettingsRowPaddingV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccentIconBox(icon = icon, tone = accent)
        Spacer(modifier = Modifier.size(Dimens.SettingsRowInternalGap))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1A73E8),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFB8C2D1),
            )
        )
    }
}

/**
 * 7 种 pastel 主题色。所有图标盒都是同一种渐变风格(亮端 → 暗端),
 * 区别仅在色相,保证视觉一致。
 */
enum class AccentTone(val start: Color, val end: Color) {
    Blue(Color(0xFF4FA3FF), Color(0xFF1A73E8)),
    Purple(Color(0xFFB197FC), Color(0xFF8B7FD8)),
    Mint(Color(0xFF7FDCC4), Color(0xFF2EB89A)),
    Amber(Color(0xFFFFD180), Color(0xFFFF8F00)),
    Pink(Color(0xFFFFB1C1), Color(0xFFE15A7A)),
    Sky(Color(0xFF8ECDF6), Color(0xFF3A8DCC)),
    Coral(Color(0xFFFFB199), Color(0xFFE0744B)),
}

@Composable
private fun AccentIconBox(icon: ImageVector, tone: AccentTone) {
    Box(
        modifier = Modifier
            .size(Dimens.SettingsIconBox)
            .clip(RoundedCornerShape(Dimens.CornerSm))
            .background(
                Brush.linearGradient(
                    colors = listOf(tone.start, tone.end)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(Dimens.SettingsIconSize)
        )
    }
}
