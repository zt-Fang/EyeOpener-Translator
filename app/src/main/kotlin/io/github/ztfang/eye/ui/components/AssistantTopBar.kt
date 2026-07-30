package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * 助手屏顶部栏。
 *
 * 布局：左侧 AI 头像 + 标题 + 在线状态;右侧「清空」玻璃按钮。
 * 整条 64dp 高,层级与主屏 AppBar 保持一致。
 */
@Composable
fun AssistantTopBar(
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.AssistantTopBarHeight)
            .padding(horizontal = Dimens.ScreenPaddingH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // AI 品牌头像(紫蓝渐变)
        Box(
            modifier = Modifier
                .size(Dimens.ChatAvatarSize + Dimens.SpaceXs)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF8B7FD8),
                            Color(0xFFB197FC),
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.assistant_avatar_ai),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(Dimens.SpaceSm))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.assistant_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(Dimens.SpaceXxs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)
            ) {
                // 在线小绿点
                Box(
                    modifier = Modifier
                        .size(Dimens.OnlineDotSize)
                        .clip(CircleShape)
                        .background(Color(0xFF34C759))
                )
                Text(
                    text = stringResource(R.string.assistant_online_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 右侧「清空」玻璃药丸按钮
        Row(
            modifier = Modifier
                .height(Dimens.ChatAvatarSize + Dimens.SpaceXs)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                .clickable(onClick = onClearClick)
                .padding(horizontal = Dimens.AssistantTopBarActionPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)
        ) {
            Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.assistant_clear),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.MessageMetaIcon + Dimens.ReceiptIconExtra)
            )
            Text(
                text = stringResource(R.string.assistant_clear),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
