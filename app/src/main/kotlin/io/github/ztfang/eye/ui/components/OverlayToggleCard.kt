package io.github.ztfang.eye.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.ztfang.eye.R
import io.github.ztfang.eye.ui.theme.Dimens

/**
 * 悬浮字幕总开关卡片(主屏 Hero)。
 *
 * - 左侧:玻璃质感图标盒(科技蓝渐变)
 * - 中部:标题 + 副标题
 * - 右侧:Switch
 * - 点击整卡 = 切换状态(无障碍友好)
 */
@Composable
fun OverlayToggleCard(
    running: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = if (running) {
        stringResource(R.string.overlay_status_running)
    } else {
        stringResource(R.string.overlay_status_stopped)
    }
    val accentColor by animateColorAsState(
        targetValue = if (running) Color(0xFF1A73E8) else Color(0xFF8FA3BF),
        animationSpec = tween(durationMillis = 280),
        label = "overlay-accent"
    )

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.OverlayToggleHeight)
            .clickable { onToggle(!running) },
        cornerRadius = Dimens.CornerXl,
        contentPadding = Dimens.CardPadding
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.OverlayToggleIconBox)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                accentColor,
                                accentColor.copy(alpha = 0.65f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Subtitles,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(Dimens.OverlayToggleIcon)
                )
            }
            Spacer(modifier = Modifier.width(Dimens.SpaceMd))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.overlay_toggle_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(Dimens.SpaceXxs))
                Text(
                    text = "$statusText · ${stringResource(R.string.overlay_toggle_hint)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = running,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF1A73E8),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFB8C2D1),
                )
            )
        }
    }
}
