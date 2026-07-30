package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ztfang.eye.R
import io.github.ztfang.eye.ui.theme.Dimens

/**
 * 聊天气泡。
 *
 * - AI 消息：左侧头像在上方 + 白色玻璃卡 + 时间戳
 * - 用户消息：右侧头像在上方 + 紫渐变气泡 + 已读 ✓✓ 标记
 *
 * 头像位于气泡上方（Column 布局），而非同排。
 */
@Composable
fun ChatBubble(
    text: String,
    timestamp: String,
    isFromAi: Boolean,
    isRead: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPaddingH),
        horizontalArrangement = if (isFromAi) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = if (isFromAi) Alignment.Start else Alignment.End,
            modifier = Modifier.widthIn(max = Dimens.ChatBubbleMaxWidth)
        ) {
            // 头像在上方
            if (isFromAi) {
                AiAvatar()
            } else {
                UserAvatar()
            }
            Spacer(modifier = Modifier.height(Dimens.SpaceXs))
            // 气泡本体
            if (isFromAi) {
                AiBubble(text = text)
            } else {
                UserBubble(text = text)
            }
            // 元数据(时间 + 已读)
            Spacer(modifier = Modifier.height(Dimens.MessageMetaSpacing))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXxs)
            ) {
                if (!isFromAi && isRead) {
                    Icon(
                        imageVector = Icons.Filled.DoneAll,
                        contentDescription = stringResource(R.string.assistant_read_receipt_cd),
                        tint = Color(0xFF8B7FD8),
                        modifier = Modifier.size(Dimens.MessageMetaIcon)
                    )
                }
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AiBubble(text: String) {
    val shape = RoundedCornerShape(
        topStart = Dimens.ChatBubbleCornerSm,
        topEnd = Dimens.ChatBubbleCornerLg,
        bottomEnd = Dimens.ChatBubbleCornerLg,
        bottomStart = Dimens.ChatBubbleCornerLg,
    )
    Box(
        modifier = Modifier
            .shadow(
                elevation = Dimens.SpaceXxs,
                shape = shape,
                ambientColor = Color(0xFF1A73E8).copy(alpha = 0.06f),
                spotColor = Color(0xFF1A73E8).copy(alpha = 0.08f),
            )
            .clip(shape)
            .background(Color.White.copy(alpha = 0.85f))
            .border(
                border = BorderStroke(width = 1.dp, brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.95f),
                        Color.White.copy(alpha = 0.25f),
                    )
                )),
                shape = shape
            )
            .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm + Dimens.SpaceXxs)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    val shape = RoundedCornerShape(
        topStart = Dimens.ChatBubbleCornerLg,
        topEnd = Dimens.ChatBubbleCornerSm,
        bottomEnd = Dimens.ChatBubbleCornerLg,
        bottomStart = Dimens.ChatBubbleCornerLg,
    )
    Box(
        modifier = Modifier
            .shadow(
                elevation = Dimens.SpaceXs,
                shape = shape,
                ambientColor = Color(0xFF8B7FD8).copy(alpha = 0.18f),
                spotColor = Color(0xFF8B7FD8).copy(alpha = 0.22f),
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFB197FC),
                        Color(0xFF8B7FD8),
                    )
                )
            )
            .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm + Dimens.SpaceXxs)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

@Composable
private fun AiAvatar() {
    Box(
        modifier = Modifier
            .size(Dimens.ChatAvatarSize)
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
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun UserAvatar() {
    Box(
        modifier = Modifier
            .size(Dimens.ChatAvatarSize)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF4F8AFF),
                        Color(0xFF8B7FD8),
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.assistant_avatar_user),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
