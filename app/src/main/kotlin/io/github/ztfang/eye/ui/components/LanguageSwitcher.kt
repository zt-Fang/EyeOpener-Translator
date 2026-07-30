package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.unit.Dp
import io.github.ztfang.eye.R
import io.github.ztfang.eye.ui.theme.Dimens

/**
 * 语言切换模块:左卡片 + 中央交换按钮 + 右卡片。
 *
 * - 卡片可点击唤起语言选择器
 * - 中间悬浮圆形按钮 = 交换源/目标
 * - 圆角略小于 Hero 卡片,营造层级
 */
@Composable
fun LanguageSwitcher(
    sourceLabel: String,
    sourceSubtitle: String,
    targetLabel: String,
    targetSubtitle: String,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit,
    onSwapClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
    ) {
        LanguageCard(
            label = sourceLabel,
            language = sourceSubtitle,
            onClick = onSourceClick,
            modifier = Modifier.weight(1f)
        )
        SwapButton(onClick = onSwapClick)
        LanguageCard(
            label = targetLabel,
            language = targetSubtitle,
            modifier = Modifier.weight(1f),
            onClick = onTargetClick
        )
    }
}

@Composable
private fun LanguageCard(
    label: String,
    language: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Dimens.CornerLg
) {
    GlassCard(
        modifier = modifier
            .heightIn(min = Dimens.LanguageCardMinHeight)
            .clickable(onClick = onClick),
        cornerRadius = cornerRadius,
        contentPadding = Dimens.SpaceMd
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(Dimens.LanguageCardFlagSize)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF1A73E8).copy(alpha = 0.85f),
                                        Color(0xFF4FA3FF).copy(alpha = 0.85f),
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = language.take(1),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = language,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.SpaceLg)
                )
            }
        }
    }
}

@Composable
private fun SwapButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Dimens.SwapButtonSize)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF1A73E8),
                        Color(0xFF4FA3FF),
                    )
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.SwapHoriz,
            contentDescription = stringResource(R.string.language_swap_cd),
            tint = Color.White,
            modifier = Modifier.size(Dimens.SwapButtonIcon)
        )
    }
}
